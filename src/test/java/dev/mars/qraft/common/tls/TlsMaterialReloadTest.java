/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.mars.qraft.common.tls;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests reloading TLS material (certificate reloading list, Phase 1, decision 1) with real handshakes on one loopback
 * listener that stays open throughout: after a reload its next handshake presents the new certificate and trusts the
 * new CA alone, and a reload of files that do not match is refused and leaves the material in use.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class TlsMaterialReloadTest {
    @TempDir
    Path directory;

    private Path ca;
    private Path cert;
    private Path key;

    private void place(String caFixture, String certFixture, String keyFixture) throws IOException {
        ca = directory.resolve("ca.pem");
        cert = directory.resolve("cert.pem");
        key = directory.resolve("cert.key");
        Files.copy(TlsMaterialTest.fixture(caFixture), ca, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(TlsMaterialTest.fixture(certFixture), cert, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(TlsMaterialTest.fixture(keyFixture), key, StandardCopyOption.REPLACE_EXISTING);
    }

    private TlsMaterial load() {
        return TlsMaterial.load(new TlsSettings("server.tls", ca, Optional.of(cert), Optional.of(key),
                List.of("TLSv1.3", "TLSv1.2")));
    }

    /** A new client context each time, so that no TLS session is resumed and every handshake is a full one. */
    private static TlsMaterial client(String name) {
        return TlsMaterialTest.material("ca.pem", name + ".pem", name + ".key");
    }

    @Test
    void anOpenListenerPresentsTheReloadedCertificateAndRefusesAMismatchedOne() throws Exception {
        place("ca.pem", "server.pem", "server.key");
        TlsMaterial server = load();
        try (Listener listener = new Listener(server, false)) {
            assertEquals("RSA", listener.serverKeyAlgorithm(client("client")));

            place("ca.pem", "server-ec.pem", "server-ec.key");
            server.reload();
            assertEquals("EC", listener.serverKeyAlgorithm(client("client")),
                    "the next handshake presents the new certificate");
            assertEquals("EC", server.certificate().orElseThrow().getPublicKey().getAlgorithm());

            Files.copy(TlsMaterialTest.fixture("server.pem"), cert, StandardCopyOption.REPLACE_EXISTING);
            IllegalStateException refused = assertThrows(IllegalStateException.class, server::reload);
            assertTrue(refused.getMessage().contains("is not the key of"), refused.getMessage());
            assertEquals("EC", listener.serverKeyAlgorithm(client("client")),
                    "a refused reload keeps the material in use");
        }
    }

    @Test
    void anOpenListenerTrustsTheReloadedCaAlone() throws Exception {
        place("ca.pem", "server.pem", "server.key");
        TlsMaterial server = load();
        try (Listener listener = new Listener(server, true)) {
            assertEquals("RSA", listener.serverKeyAlgorithm(client("client")));
            assertThrows(IOException.class, () -> listener.serverKeyAlgorithm(client("other-client")));

            Files.copy(TlsMaterialTest.fixture("other-ca.pem"), ca, StandardCopyOption.REPLACE_EXISTING);
            server.reload();
            assertEquals("RSA", listener.serverKeyAlgorithm(client("other-client")),
                    "the other CA's client is trusted now");
            assertThrows(IOException.class, () -> listener.serverKeyAlgorithm(client("client")),
                    "and the first CA's no longer");
        }
    }

    /** One TLS listener on loopback, opened once, answering one connection per exchange. */
    private static final class Listener implements AutoCloseable {
        private final SSLServerSocket socket;

        Listener(TlsMaterial server, boolean requireClientCertificate) throws IOException {
            socket = (SSLServerSocket) server.serverContext().getServerSocketFactory()
                    .createServerSocket(0, 1, InetAddress.getLoopbackAddress());
            socket.setSSLParameters(server.serverParameters(requireClientCertificate));
        }

        /** The key algorithm of the certificate the listener presents to {@code client}, over a new connection. */
        String serverKeyAlgorithm(TlsMaterial client) throws Exception {
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
                try (SSLSocket accepted = (SSLSocket) socket.accept()) {
                    accepted.setSoTimeout(5_000);
                    if (accepted.getInputStream().read() == 'p') {
                        accepted.getOutputStream().write("pong".getBytes());
                        accepted.getOutputStream().flush();
                    }
                } catch (IOException refusedHere) {
                    // The server side of a refused handshake; the client's exception is what the test checks.
                }
            });
            try (SSLSocket caller = (SSLSocket) client.clientContext().getSocketFactory().createSocket()) {
                caller.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), socket.getLocalPort()), 5_000);
                caller.setSoTimeout(5_000);
                SSLParameters parameters = client.clientParameters(true);
                parameters.setServerNames(List.of(new javax.net.ssl.SNIHostName("localhost")));
                caller.setSSLParameters(parameters);
                caller.getOutputStream().write('p');
                caller.getOutputStream().flush();
                byte[] answer = caller.getInputStream().readNBytes(4);
                if (answer.length < 4) throw new IOException("the server closed the connection");
                return ((X509Certificate) caller.getSession().getPeerCertificates()[0]).getPublicKey().getAlgorithm();
            } finally {
                served.get(10, TimeUnit.SECONDS);
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}

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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests reading PEM TLS material and the contexts built from it (transport security list, Phase 1), with real
 * handshakes on loopback: a server that requires a client certificate answers a client with one from its CA, and
 * refuses a client with none or with one from another CA; a client checks the server's certificate against its CA
 * and the server's name.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class TlsMaterialTest {
    @TempDir
    Path directory;

    static Path fixture(String name) {
        try {
            return Path.of(TlsMaterialTest.class.getResource("/tls/" + name).toURI());
        } catch (Exception e) {
            throw new IllegalStateException("test TLS fixture " + name + " is missing", e);
        }
    }

    static TlsMaterial material(String ca, String cert, String key) {
        return TlsMaterial.load(new TlsSettings("test.tls", fixture(ca),
                Optional.ofNullable(cert).map(TlsMaterialTest::fixture),
                Optional.ofNullable(key).map(TlsMaterialTest::fixture), List.of("TLSv1.3", "TLSv1.2")));
    }

    @Test
    void aClientWithACertificateFromTheCaIsAnswered() throws Exception {
        TlsMaterial server = material("ca.pem", "server.pem", "server.key");
        TlsMaterial client = material("ca.pem", "client.pem", "client.key");

        assertEquals("pong", exchange(server, true, client, "localhost"));
    }

    @Test
    void anEcKeyIsReadAsWellAsAnRsaKey() throws Exception {
        TlsMaterial server = material("ca.pem", "server-ec.pem", "server-ec.key");
        TlsMaterial client = material("ca.pem", "client.pem", "client.key");

        assertEquals("pong", exchange(server, true, client, "localhost"));
    }

    @Test
    void aClientWithoutACertificateIsRefusedWhenOneIsRequired() throws Exception {
        TlsMaterial server = material("ca.pem", "server.pem", "server.key");
        TlsMaterial client = material("ca.pem", null, null);

        assertThrows(IOException.class, () -> exchange(server, true, client, "localhost"));
        assertEquals("pong", exchange(server, false, client, "localhost"), "and answered when none is required");
    }

    @Test
    void aClientWithACertificateFromAnotherCaIsRefused() throws Exception {
        TlsMaterial server = material("ca.pem", "server.pem", "server.key");
        TlsMaterial client = material("ca.pem", "other-client.pem", "other-client.key");

        assertThrows(IOException.class, () -> exchange(server, true, client, "localhost"));
    }

    @Test
    void aClientRefusesAServerItsCaDidNotSign() throws Exception {
        TlsMaterial server = material("ca.pem", "server.pem", "server.key");
        TlsMaterial client = material("other-ca.pem", "client.pem", "client.key");

        assertThrows(SSLException.class, () -> exchange(server, false, client, "localhost"));
    }

    @Test
    void aClientRefusesAServerWhoseCertificateNamesAnotherHost() throws Exception {
        TlsMaterial server = material("ca.pem", "wrong-host.pem", "wrong-host.key");
        TlsMaterial client = material("ca.pem", "client.pem", "client.key");

        assertThrows(SSLException.class, () -> exchange(server, false, client, "localhost"));
    }

    @Test
    void theProtocolsAreThoseTheSettingsAllow() {
        TlsMaterial only13 = TlsMaterial.load(new TlsSettings("test.tls", fixture("ca.pem"),
                Optional.of(fixture("server.pem")), Optional.of(fixture("server.key")), List.of("TLSv1.3")));

        assertArrayEquals(new String[] {"TLSv1.3"}, only13.serverParameters(true).getProtocols());
        assertTrue(only13.serverParameters(true).getNeedClientAuth());
        assertFalse(only13.serverParameters(false).getNeedClientAuth());
        assertArrayEquals(new String[] {"TLSv1.3"}, only13.clientParameters(true).getProtocols());
        assertEquals("HTTPS", only13.clientParameters(true).getEndpointIdentificationAlgorithm());
        assertEquals(null, only13.clientParameters(false).getEndpointIdentificationAlgorithm());
    }

    @Test
    void aServerContextNeedsACertificate() {
        TlsMaterial client = material("ca.pem", null, null);

        IllegalStateException refused = assertThrows(IllegalStateException.class, client::serverContext);
        assertEquals("test.tls.certFile and test.tls.keyFile are required to serve TLS", refused.getMessage());
    }

    @Test
    void aMissingFileIsNamed() {
        Path missing = directory.resolve("absent.pem");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> TlsMaterial.load(
                new TlsSettings("test.tls", missing, Optional.empty(), Optional.empty(), List.of("TLSv1.2"))));

        assertEquals("test.tls.caFile cannot be read: " + missing, refused.getMessage());
    }

    @Test
    void aFileWithoutACertificateIsRefused() throws Exception {
        Path empty = Files.writeString(directory.resolve("empty.pem"), "not a certificate\n");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> TlsMaterial.load(
                new TlsSettings("test.tls", empty, Optional.empty(), Optional.empty(), List.of("TLSv1.2"))));

        assertEquals("test.tls.caFile holds no PEM certificate: " + empty, refused.getMessage());
    }

    @Test
    void aBadKeyIsRefusedWithoutItsContent() throws Exception {
        String secret = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCsecretsecret";
        Path key = Files.writeString(directory.resolve("bad.key"),
                "-----BEGIN PRIVATE KEY-----\n" + secret + "\n-----END PRIVATE KEY-----\n");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> TlsMaterial.load(
                new TlsSettings("test.tls", fixture("ca.pem"), Optional.of(fixture("server.pem")), Optional.of(key),
                        List.of("TLSv1.2"))));

        assertEquals("test.tls.keyFile holds no PKCS #8 RSA or EC private key: " + key, refused.getMessage());
        assertFalse(String.valueOf(refused.getCause()).contains(secret), "the key's content is never in the refusal");
    }

    @Test
    void aKeyInAnotherFormatIsRefusedWithTheFormatNamed() throws Exception {
        Path key = Files.writeString(directory.resolve("rsa.key"),
                "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> TlsMaterial.load(
                new TlsSettings("test.tls", fixture("ca.pem"), Optional.of(fixture("server.pem")), Optional.of(key),
                        List.of("TLSv1.2"))));

        assertEquals("test.tls.keyFile must hold a PKCS #8 key (BEGIN PRIVATE KEY), not BEGIN RSA PRIVATE KEY: " + key,
                refused.getMessage());
    }

    @Test
    void aKeyThatDoesNotMatchItsCertificateIsRefused() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> TlsMaterial.load(
                new TlsSettings("test.tls", fixture("ca.pem"), Optional.of(fixture("server.pem")),
                        Optional.of(fixture("client.key")), List.of("TLSv1.2"))));

        assertEquals("test.tls.keyFile is not the key of test.tls.certFile: " + fixture("client.key"),
                refused.getMessage());
    }

    /** One request and answer over a fresh loopback TLS connection; the client checks the server's name. */
    private static String exchange(TlsMaterial server, boolean requireClientCertificate, TlsMaterial client,
                                   String serverName) throws Exception {
        SSLContext serverContext = server.serverContext();
        try (SSLServerSocket listener = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            listener.setSSLParameters(server.serverParameters(requireClientCertificate));
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
                try (SSLSocket accepted = (SSLSocket) listener.accept()) {
                    accepted.setSoTimeout(5_000);
                    if (accepted.getInputStream().read() == 'p') {
                        accepted.getOutputStream().write("pong".getBytes());
                        accepted.getOutputStream().flush();
                    }
                } catch (IOException refusedHere) {
                    // The server side of a refused handshake; the client's exception is what the test checks.
                }
            });
            try (SSLSocket socket = (SSLSocket) client.clientContext().getSocketFactory().createSocket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()), 5_000);
                socket.setSoTimeout(5_000);
                SSLParameters parameters = client.clientParameters(true);
                parameters.setServerNames(List.of(new javax.net.ssl.SNIHostName(serverName)));
                socket.setSSLParameters(parameters);
                socket.getOutputStream().write('p');
                socket.getOutputStream().flush();
                byte[] answer = socket.getInputStream().readNBytes(4);
                if (answer.length < 4) throw new IOException("the server closed the connection");
                return new String(answer);
            } finally {
                served.get(10, TimeUnit.SECONDS);
            }
        }
    }
}

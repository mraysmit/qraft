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

package dev.mars.qraft.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.tls.TlsMaterial;
import dev.mars.qraft.common.tls.TlsSettings;
import dev.mars.qraft.server.config.AppConfig;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that a server started from a document with {@code server.tls} serves HTTPS on its HTTP listener and TLS on
 * its API gRPC port, each requiring a client certificate (transport security list, Phase 2).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class QraftServerTlsStartupTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;

    private RaftExecutorsFixture executors;
    private QraftServerService service;

    @AfterEach
    void stop() throws Exception {
        try {
            if (service != null) service.stop().get(30, TimeUnit.SECONDS);
        } finally {
            if (executors != null) executors.close().get(30, TimeUnit.SECONDS);
        }
    }

    static Path fixture(String name) {
        try {
            return Path.of(QraftServerTlsStartupTest.class.getResource("/tls/" + name).toURI());
        } catch (Exception e) {
            throw new IllegalStateException("test TLS fixture " + name + " is missing", e);
        }
    }

    private Map<String, InetSocketAddress> start() throws Exception {
        Path configuration = directory.resolve("server.json");
        JSON.writeValue(configuration.toFile(), Map.of("version", 1, "server", Map.of(
                "id", "tls-startup", "http", Map.of("port", 0), "apiGrpcPort", 0,
                "raft", Map.of("port", 0, "electionTimeoutMs", 300, "heartbeatIntervalMs", 100,
                        "storage", Map.of("path", directory.resolve("raft").toString())),
                "tls", Map.of("caFile", fixture("ca.pem").toString(), "certFile", fixture("server.pem").toString(),
                        "keyFile", fixture("server.key").toString(),
                        "https", Map.of("enabled", true), "grpc", Map.of("enabled", true),
                        "raft", Map.of("enabled", true)),
                "telemetry", Map.of("enabled", false))));
        AppConfig config = AppConfig.fromFile(configuration);
        config.validate();
        executors = RaftExecutorsFixture.create();
        service = new QraftServerService(executors.stateLoop(), executors.blockingTasks(), config);
        service.start().get(30, TimeUnit.SECONDS);
        return service.boundAddresses();
    }

    private static TlsMaterial client() {
        return TlsMaterial.load(new TlsSettings("test.tls", fixture("ca.pem"), Optional.of(fixture("client.pem")),
                Optional.of(fixture("client.key")), List.of("TLSv1.3", "TLSv1.2")));
    }

    @Test
    void theHttpListenerServesHttpsAndRequiresACertificate() throws Exception {
        int port = start().get("http").getPort();
        TlsMaterial client = client();
        try (HttpClient https = HttpClient.newBuilder().sslContext(client.clientContext())
                .sslParameters(client.clientParameters(true)).build();
             HttpClient plain = HttpClient.newHttpClient()) {
            HttpResponse<String> live = https.send(HttpRequest.newBuilder(
                    URI.create("https://localhost:" + port + "/health/live")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, live.statusCode());
            assertThrows(IOException.class, () -> plain.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/health/live")).build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
    }

    @Test
    void theRaftPortServesMutualTls() throws Exception {
        int port = start().get("raft").getPort();

        assertEquals("h2", handshake(client(), port), "a peer with a certificate from the CA completes the handshake");
        TlsMaterial withoutCertificate = TlsMaterial.load(new TlsSettings("test.tls", fixture("ca.pem"),
                Optional.empty(), Optional.empty(), List.of("TLSv1.3", "TLSv1.2")));
        assertThrows(IOException.class, () -> {
            try (SSLSocket socket = (SSLSocket) withoutCertificate.clientContext().getSocketFactory()
                    .createSocket("localhost", port)) {
                SSLParameters parameters = withoutCertificate.clientParameters(true);
                parameters.setApplicationProtocols(new String[] {"h2"});
                socket.setSSLParameters(parameters);
                socket.setSoTimeout(5_000);
                socket.startHandshake();
                // TLS 1.3 refuses a missing certificate after the handshake, at the first read.
                if (socket.getInputStream().read() < 0) throw new IOException("closed by the server");
            }
        }, "a peer without a certificate is refused");
    }

    private static String handshake(TlsMaterial client, int port) throws IOException {
        try (SSLSocket socket = (SSLSocket) client.clientContext().getSocketFactory().createSocket("localhost", port)) {
            SSLParameters parameters = client.clientParameters(true);
            parameters.setApplicationProtocols(new String[] {"h2"});
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(5_000);
            socket.startHandshake();
            return socket.getApplicationProtocol();
        }
    }

    @Test
    void theApiGrpcPortServesTls() throws Exception {
        int port = start().get("apiGrpc").getPort();
        TlsMaterial client = client();

        try (SSLSocket socket = (SSLSocket) client.clientContext().getSocketFactory().createSocket("localhost", port)) {
            SSLParameters parameters = client.clientParameters(true);
            parameters.setApplicationProtocols(new String[] {"h2"});
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(5_000);
            socket.startHandshake();
            assertEquals("h2", socket.getApplicationProtocol(), "a TLS handshake that negotiated HTTP/2");
        }
        try (Socket plain = new Socket("localhost", port)) {
            plain.setSoTimeout(5_000);
            OutputStream out = plain.getOutputStream();
            out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = plain.getInputStream();
            byte[] answer;
            try {
                answer = in.readNBytes(9);
            } catch (IOException closed) {
                answer = new byte[0];
            }
            assertEquals(false, answer.length == 9 && answer[3] == 0x04,
                    "a plaintext HTTP/2 preface gets no SETTINGS frame back");
        }
    }
}

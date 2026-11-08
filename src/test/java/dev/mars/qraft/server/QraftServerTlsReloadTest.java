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
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.common.tls.TlsMaterial;
import dev.mars.qraft.common.tls.TlsSettings;
import dev.mars.qraft.server.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a server takes up a replaced certificate without a restart (certificate reloading list, Phase 2, decision
 * 2): after its {@code server.tls} files are replaced, new handshakes on its HTTPS listener and its Raft port present
 * the new certificate; and stopping the server stops the files' checks.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class QraftServerTlsReloadTest {
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

    private static TlsMaterial client() {
        // A new context for each handshake, so that no TLS session is resumed.
        return TlsMaterial.load(new TlsSettings("test.tls", QraftServerTlsStartupTest.fixture("ca.pem"),
                Optional.of(QraftServerTlsStartupTest.fixture("client.pem")),
                Optional.of(QraftServerTlsStartupTest.fixture("client.key")), List.of("TLSv1.3", "TLSv1.2")));
    }

    /** The key algorithm of the certificate the port presents to a new connection. */
    private static String presented(int port, String protocol) throws IOException {
        TlsMaterial client = client();
        try (SSLSocket socket = (SSLSocket) client.clientContext().getSocketFactory().createSocket("localhost", port)) {
            SSLParameters parameters = client.clientParameters(true);
            parameters.setApplicationProtocols(new String[] {protocol});
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(5_000);
            socket.startHandshake();
            return ((X509Certificate) socket.getSession().getPeerCertificates()[0]).getPublicKey().getAlgorithm();
        }
    }

    private static boolean reloaderRunning() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().equals("qraft-tls-reloader"));
    }

    @Test
    void aReplacedCertificateIsPresentedByNewHandshakesWithoutARestart() throws Exception {
        Path ca = directory.resolve("ca.pem");
        Path cert = directory.resolve("server.pem");
        Path key = directory.resolve("server.key");
        Files.copy(QraftServerTlsStartupTest.fixture("ca.pem"), ca);
        Files.copy(QraftServerTlsStartupTest.fixture("server.pem"), cert);
        Files.copy(QraftServerTlsStartupTest.fixture("server.key"), key);
        Path configuration = directory.resolve("server.json");
        JSON.writeValue(configuration.toFile(), Map.of("version", 1, "server", Map.of(
                "id", "tls-reload", "http", Map.of("port", 0), "apiGrpcPort", 0,
                "raft", Map.of("port", 0, "electionTimeoutMs", 300, "heartbeatIntervalMs", 100,
                        "storage", Map.of("path", directory.resolve("raft").toString())),
                "tls", Map.of("caFile", ca.toString(), "certFile", cert.toString(), "keyFile", key.toString(),
                        "https", Map.of("enabled", true), "raft", Map.of("enabled", true)),
                "telemetry", Map.of("enabled", false))));
        AppConfig config = AppConfig.fromFile(configuration);
        config.validate();
        executors = RaftExecutorsFixture.create();
        service = new QraftServerService(executors.stateLoop(), executors.blockingTasks(), config);
        service.useTlsReloadInterval(Duration.ofMillis(200));
        service.start().get(30, TimeUnit.SECONDS);
        int https = service.boundAddresses().get("http").getPort();
        int raft = service.boundAddresses().get("raft").getPort();
        assertEquals("RSA", presented(https, "http/1.1"));
        assertEquals("RSA", presented(raft, "h2"));
        assertTrue(reloaderRunning(), "the server checks its files");

        Files.copy(QraftServerTlsStartupTest.fixture("server-ec.key"), key, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(QraftServerTlsStartupTest.fixture("server-ec.pem"), cert, StandardCopyOption.REPLACE_EXISTING);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!presented(https, "http/1.1").equals("EC") && System.nanoTime() < deadline) Thread.sleep(100);
        assertEquals("EC", presented(https, "http/1.1"), "the HTTPS listener presents the new certificate");
        assertEquals("EC", presented(raft, "h2"), "and so does the Raft port");

        service.stop().get(30, TimeUnit.SECONDS);
        service = null;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (reloaderRunning() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(!reloaderRunning(), "stopping the server stops the checks");
    }
}

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

package dev.mars.qraft.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.common.tls.TlsMaterial;
import dev.mars.qraft.common.tls.TlsSettings;
import dev.mars.qraft.server.QraftServerService;
import dev.mars.qraft.server.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of certificate rotation (certificate reloading list, Phase 3): three servers with HTTPS and mutual
 * TLS on the Raft port elect a leader; each server's certificate is replaced in turn while the cluster runs, after which
 * a new connection to each is presented the new certificate, and the cluster still elects and commits a write.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
@Tag("e2e")
class CertificateRotationEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    private final List<RaftExecutorsFixture> executors = new ArrayList<>();
    private final List<QraftServerService> servers = new ArrayList<>();
    private final List<Integer> httpPorts = new ArrayList<>();
    private final List<Path> tlsDirectories = new ArrayList<>();
    private HttpClient https;

    @AfterEach
    void stop() throws Exception {
        CleanupHelper cleanup = new CleanupHelper();
        for (QraftServerService server : servers.reversed()) cleanup.run(() -> server.stop().get(30, TimeUnit.SECONDS));
        for (RaftExecutorsFixture executor : executors) cleanup.run(() -> executor.close().get(30, TimeUnit.SECONDS));
        if (https != null) cleanup.run(https::close);
        cleanup.rethrow();
    }

    private static TlsMaterial caller() {
        return TlsMaterial.load(new TlsSettings("client.tls", TlsEndToEndTest.fixture("ca.pem"),
                Optional.of(TlsEndToEndTest.fixture("client.pem")), Optional.of(TlsEndToEndTest.fixture("client.key")),
                List.of("TLSv1.3", "TLSv1.2")));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void eachServersCertificateIsReplacedWhileTheClusterRunsAndItStillCommits() throws Exception {
        startCluster();
        TlsMaterial caller = caller();
        https = HttpClient.newBuilder().sslContext(caller.clientContext()).sslParameters(caller.clientParameters(true))
                .connectTimeout(Duration.ofSeconds(5)).build();
        waitUntil(() -> httpPorts.stream().allMatch(port -> status(port, "GET", "/health/ready") == 200),
                "every server is ready: a leader was elected over mutual TLS");
        for (int port : httpPorts) assertEquals("RSA", presented(port));

        for (Path tls : tlsDirectories) {
            Files.copy(TlsEndToEndTest.fixture("server-ec.key"), tls.resolve("server.key"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(TlsEndToEndTest.fixture("server-ec.pem"), tls.resolve("server.pem"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        for (int port : httpPorts) {
            waitUntil(() -> "EC".equals(presentedOrNull(port)), "server on " + port + " presents its new certificate");
        }

        waitUntil(() -> httpPorts.stream().anyMatch(port -> status(port, "PUT", "/v1/kv/rotated") == 200),
                "the cluster still commits a write after every certificate was replaced");
        waitUntil(() -> httpPorts.stream().allMatch(port -> status(port, "GET", "/health/ready") == 200),
                "and every server is still ready");
    }

    private void startCluster() throws Exception {
        Map<String, Integer> raftPorts = new LinkedHashMap<>();
        Map<String, String> nodes = new LinkedHashMap<>();
        for (String id : List.of("server1", "server2", "server3")) {
            int raft = freePort();
            raftPorts.put(id, raft);
            nodes.put(id, "localhost:" + raft);
        }
        for (String id : nodes.keySet()) {
            int http = freePort();
            httpPorts.add(http);
            Path tls = Files.createDirectories(root.resolve(id + "-tls"));
            tlsDirectories.add(tls);
            Files.copy(TlsEndToEndTest.fixture("ca.pem"), tls.resolve("ca.pem"));
            Files.copy(TlsEndToEndTest.fixture("server.pem"), tls.resolve("server.pem"));
            Files.copy(TlsEndToEndTest.fixture("server.key"), tls.resolve("server.key"));
            Path configuration = root.resolve(id + ".json");
            JSON.writeValue(configuration.toFile(), Map.of("version", 1, "server", Map.of(
                    "id", id, "http", Map.of("port", http), "apiGrpcPort", freePort(),
                    "raft", Map.of("port", raftPorts.get(id), "nodes", nodes, "electionTimeoutMs", 600,
                            "heartbeatIntervalMs", 150, "storage", Map.of("path", root.resolve(id).toString())),
                    "tls", Map.of("caFile", tls.resolve("ca.pem").toString(),
                            "certFile", tls.resolve("server.pem").toString(),
                            "keyFile", tls.resolve("server.key").toString(), "https", Map.of("enabled", true),
                            "raft", Map.of("enabled", true)),
                    "telemetry", Map.of("enabled", false))));
            AppConfig config = AppConfig.fromFile(configuration);
            config.validate();
            RaftExecutorsFixture executor = RaftExecutorsFixture.create();
            executors.add(executor);
            QraftServerService server = new QraftServerService(executor.stateLoop(), executor.blockingTasks(), config);
            server.useTlsReloadInterval(Duration.ofMillis(200));
            servers.add(server);
        }
        for (QraftServerService server : servers) server.start().get(30, TimeUnit.SECONDS);
    }

    /** The key algorithm of the certificate the HTTPS listener presents to a new connection with a new context. */
    private static String presented(int port) throws IOException {
        TlsMaterial client = caller();
        try (SSLSocket socket = (SSLSocket) client.clientContext().getSocketFactory().createSocket("localhost", port)) {
            SSLParameters parameters = client.clientParameters(true);
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(5_000);
            socket.startHandshake();
            return ((X509Certificate) socket.getSession().getPeerCertificates()[0]).getPublicKey().getAlgorithm();
        }
    }

    private static String presentedOrNull(int port) {
        try {
            return presented(port);
        } catch (IOException unanswered) {
            return null;
        }
    }

    private int status(int port, String method, String path) {
        try {
            return https.send(HttpRequest.newBuilder(URI.create("https://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(5)).method(method, method.equals("PUT")
                            ? HttpRequest.BodyPublishers.ofString("v") : HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException unanswered) {
            return -1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static void waitUntil(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(), description);
    }
}

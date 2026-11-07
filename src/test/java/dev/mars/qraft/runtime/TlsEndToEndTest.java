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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.client.QraftClient;
import dev.mars.qraft.client.acl.ClientAclSettings;
import dev.mars.qraft.client.acl.DownPolicy;
import dev.mars.qraft.client.acl.TokenKind;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.common.tls.TlsMaterial;
import dev.mars.qraft.common.tls.TlsSettings;
import dev.mars.qraft.server.QraftServerService;
import dev.mars.qraft.server.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of transport security (transport security list, Phase 6): three servers with HTTPS, TLS on the
 * API gRPC port and mutual TLS on the Raft port, each requiring a client certificate, and ACLs under {@code deny}.
 * They elect a leader over mutual TLS and install the management token; a client on one host registers a service
 * over HTTPS, and a client on another discovers it. Plain HTTP reaches none of them.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
@Tag("e2e")
class TlsEndToEndTest {
    private static final String MANAGEMENT = "8d0f3c3e-9a51-4c2e-b7d4-2f6a1e9c0b57";
    private static final String ORDERS = """
            {"id":"orders-1","name":"orders","port":9000}""";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    private final List<RaftExecutorsFixture> executors = new ArrayList<>();
    private final List<QraftServerService> servers = new ArrayList<>();
    private final List<QraftClient> clients = new ArrayList<>();
    private final List<Integer> httpPorts = new ArrayList<>();
    private HttpClient https;

    @AfterEach
    void stop() throws Exception {
        CleanupHelper cleanup = new CleanupHelper();
        for (QraftClient client : clients.reversed()) cleanup.run(() -> client.shutdown().get(10, TimeUnit.SECONDS));
        for (QraftServerService server : servers.reversed()) cleanup.run(() -> server.stop().get(30, TimeUnit.SECONDS));
        for (RaftExecutorsFixture executor : executors) cleanup.run(() -> executor.close().get(30, TimeUnit.SECONDS));
        if (https != null) cleanup.run(https::close);
        cleanup.rethrow();
    }

    static Path fixture(String name) {
        try {
            return Path.of(TlsEndToEndTest.class.getResource("/tls/" + name).toURI());
        } catch (Exception e) {
            throw new IllegalStateException("test TLS fixture " + name + " is missing", e);
        }
    }

    private static TlsSettings clientTls() {
        return new TlsSettings("client.tls", fixture("ca.pem"), Optional.of(fixture("client.pem")),
                Optional.of(fixture("client.key")), List.of("TLSv1.3", "TLSv1.2"));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void aClusterServingOnlyTlsElectsInstallsItsTokenAndCarriesARegistrationToDiscovery() throws Exception {
        startCluster();
        TlsMaterial caller = TlsMaterial.load(clientTls());
        https = HttpClient.newBuilder().sslContext(caller.clientContext()).sslParameters(caller.clientParameters(true))
                .connectTimeout(Duration.ofSeconds(5)).build();
        waitUntil(() -> httpPorts.stream().allMatch(port -> status(port, "/health/ready", null) == 200),
                "every server is ready: a leader was elected over mutual TLS");
        waitUntil(() -> httpPorts.stream().anyMatch(port -> status(port, "/v1/acl/token/self", MANAGEMENT) == 200),
                "the leader installed the management token");

        // A client finds the leader by trying its servers in turn, so each lists all three, host-b from server 3.
        QraftClient hostA = startClient("host-a", List.of(httpPorts.get(0), httpPorts.get(1), httpPorts.get(2)));
        QraftClient hostB = startClient("host-b", List.of(httpPorts.get(2), httpPorts.get(1), httpPorts.get(0)));
        waitUntil(() -> hostA.localApi().isReady() && hostB.localApi().isReady(), "both clients registered over HTTPS");

        HttpResponse<String> registered = local(hostA, "PUT", "/v1/client/service/register", ORDERS);
        assertEquals(200, registered.statusCode(), registered.body());
        waitUntil(() -> discovered(hostB).contains("orders-1"),
                "host-b discovers what host-a registered");

        try (HttpClient plain = HttpClient.newHttpClient()) {
            for (int port : httpPorts) {
                assertThrows(IOException.class, () -> plain.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/health/live")).build(),
                        HttpResponse.BodyHandlers.ofString()), "plain HTTP reaches no server");
            }
        }
    }

    private void startCluster() throws Exception {
        Path managementFile = Files.writeString(root.resolve("management-token"), MANAGEMENT);
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
            Path configuration = root.resolve(id + ".json");
            JSON.writeValue(configuration.toFile(), Map.of("version", 1, "server", Map.of(
                    "id", id, "http", Map.of("port", http), "apiGrpcPort", freePort(),
                    "raft", Map.of("port", raftPorts.get(id), "nodes", nodes, "electionTimeoutMs", 600,
                            "heartbeatIntervalMs", 150, "storage", Map.of("path", root.resolve(id).toString())),
                    "acl", Map.of("enabled", true, "defaultPolicy", "deny",
                            "initialManagementTokenFile", managementFile.toString()),
                    "tls", Map.of("caFile", fixture("ca.pem").toString(), "certFile", fixture("server.pem").toString(),
                            "keyFile", fixture("server.key").toString(), "https", Map.of("enabled", true),
                            "grpc", Map.of("enabled", true), "raft", Map.of("enabled", true)),
                    "telemetry", Map.of("enabled", false))));
            AppConfig config = AppConfig.fromFile(configuration);
            config.validate();
            RaftExecutorsFixture executor = RaftExecutorsFixture.create();
            executors.add(executor);
            QraftServerService server = new QraftServerService(executor.stateLoop(), executor.blockingTasks(), config);
            servers.add(server);
        }
        for (QraftServerService server : servers) server.start().get(30, TimeUnit.SECONDS);
    }

    private QraftClient startClient(String nodeName, List<Integer> serverPorts) throws Exception {
        Path data = Files.createDirectories(root.resolve(nodeName));
        Path tokenFile = Files.writeString(data.resolve("client-token"), MANAGEMENT);
        QraftClient client = new QraftClient(ClientConfiguration.builder()
                .nodeName(nodeName).dataDirectory(data).address("127.0.0.1").clientPort(0)
                .serverUrls(serverPorts.stream().map(port -> URI.create("https://localhost:" + port)).toList())
                .heartbeatInterval(100).requestTimeoutMs(5_000).registrationRetryMinMs(50)
                .registrationRetryMaxMs(250).contactFreshnessMs(5_000).shutdownTimeoutMs(3_000)
                .acl(new ClientAclSettings(true, AclDefaultPolicy.DENY, DownPolicy.EXTEND_CACHE,
                        ClientAclSettings.DEFAULT_TTL, ClientAclSettings.DEFAULT_TTL, ClientAclSettings.DEFAULT_TTL,
                        false, Map.of(TokenKind.CLIENT, tokenFile, TokenKind.DEFAULT, tokenFile)))
                .tls(clientTls())
                .build());
        clients.add(client);
        client.start().get(15, TimeUnit.SECONDS);
        return client;
    }

    private int status(int port, String path, String token) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(5));
            if (token != null) request.header("X-Qraft-Token", token);
            return https.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException unanswered) {
            return -1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static HttpResponse<String> local(QraftClient client, String method, String path, String body)
            throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + client.localApi().port() + path))
                            .header("X-Qraft-Token", MANAGEMENT)
                            .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private static List<String> discovered(QraftClient client) {
        try {
            HttpResponse<String> answer = local(client, "GET", "/v1/catalog/service/orders", null);
            List<String> ids = new ArrayList<>();
            if (answer.statusCode() != 200) return ids;
            for (JsonNode entry : JSON.readTree(answer.body())) ids.add(entry.path("serviceId").textValue());
            return ids;
        } catch (Exception unanswered) {
            return List.of();
        }
    }

    private static void waitUntil(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(condition.getAsBoolean(), description);
    }
}

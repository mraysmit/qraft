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
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.common.ServiceDefinition;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of observability (observability list, Phase 4): three servers in one JVM and a client. The leader
 * answers autopilot health with all three servers healthy and a failure tolerance of one, and the followers send the
 * caller on; every server reports its storage's size in {@code /raft/status}; and the client's metrics show it ready
 * after a clean reconciliation pass.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
@Tag("e2e")
class ObservabilityEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    private final List<RaftExecutorsFixture> executors = new ArrayList<>();
    private final List<QraftServerService> servers = new ArrayList<>();
    private final List<QraftClient> clients = new ArrayList<>();
    private final List<Integer> httpPorts = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() throws Exception {
        CleanupHelper cleanup = new CleanupHelper();
        for (QraftClient client : clients.reversed()) cleanup.run(() -> client.shutdown().get(10, TimeUnit.SECONDS));
        for (QraftServerService server : servers.reversed()) cleanup.run(() -> server.stop().get(30, TimeUnit.SECONDS));
        for (RaftExecutorsFixture executor : executors) cleanup.run(() -> executor.close().get(30, TimeUnit.SECONDS));
        cleanup.run(http::close);
        cleanup.rethrow();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void theLeaderReportsEveryServerHealthyAndTheClientReportsItsPasses() throws Exception {
        startCluster();
        waitUntil(() -> {
            JsonNode health = leaderHealth();
            return health != null && health.path("Healthy").asBoolean() && health.path("Servers").size() == 3;
        }, "the leader reports three healthy servers");
        JsonNode health = leaderHealth();
        assertEquals(1, health.path("FailureTolerance").asInt());
        long leaders = 0;
        for (JsonNode server : health.path("Servers")) if (server.path("Leader").asBoolean()) leaders++;
        assertEquals(1, leaders);
        long unavailable = httpPorts.stream().filter(port -> status(port, "/v1/operator/autopilot/health") == 503)
                .count();
        assertEquals(2, unavailable, "the followers send the caller on to the leader");
        for (int port : httpPorts) {
            JsonNode status = JSON.readTree(get(port, "/raft/status").body());
            assertTrue(status.path("storageBytes").asLong() > 0, "each server's storage holds its log: " + status);
        }

        Path data = Files.createDirectories(root.resolve("client"));
        QraftClient client = new QraftClient(ClientConfiguration.builder()
                .nodeName("observed").dataDirectory(data).address("127.0.0.1").clientPort(0)
                .serverUrls(httpPorts.stream().map(port -> URI.create("http://localhost:" + port)).toList())
                .heartbeatInterval(100).requestTimeoutMs(5_000).registrationRetryMinMs(50)
                .registrationRetryMaxMs(250).contactFreshnessMs(5_000).shutdownTimeoutMs(3_000)
                .services(List.of(new ServiceDefinition("web-1", "web", "127.0.0.1", 9090, List.of(), Map.of(), true)))
                .build());
        clients.add(client);
        client.start().get(15, TimeUnit.SECONDS);
        waitUntil(() -> client.localApi().isReady(), "the client registered");
        waitUntil(() -> clientMetric(client, "qraft_client_reconciliations_total{result=\"clean\"}") > 0,
                "a clean pass is counted");
        assertEquals(1, clientMetric(client, "qraft_client_ready"));
        assertTrue(clientMetric(client, "qraft_client_last_contact_age_seconds") >= 0);
    }

    private JsonNode leaderHealth() {
        for (int port : httpPorts) {
            try {
                HttpResponse<String> answer = get(port, "/v1/operator/autopilot/health");
                if (answer.statusCode() == 200) return JSON.readTree(answer.body());
            } catch (Exception unavailable) {
                // The next server is asked.
            }
        }
        return null;
    }

    private long clientMetric(QraftClient client, String series) {
        try {
            String text = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + client.localApi().port()
                    + "/v1/client/metrics?format=prometheus")).build(), HttpResponse.BodyHandlers.ofString()).body();
            for (String line : text.split("\n")) {
                if (line.startsWith(series + " ")) return Long.parseLong(line.substring(series.length() + 1).trim());
            }
            return -2;
        } catch (IOException | InterruptedException unavailable) {
            return -2;
        }
    }

    private int status(int port, String path) {
        try {
            return get(port, path).statusCode();
        } catch (Exception unavailable) {
            return -1;
        }
    }

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
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
            int port = freePort();
            httpPorts.add(port);
            Path configuration = root.resolve(id + ".json");
            JSON.writeValue(configuration.toFile(), Map.of("version", 1, "server", Map.of(
                    "id", id, "http", Map.of("port", port), "apiGrpcPort", freePort(),
                    "raft", Map.of("port", raftPorts.get(id), "nodes", nodes, "electionTimeoutMs", 600,
                            "heartbeatIntervalMs", 150, "storage", Map.of("path", root.resolve(id).toString())),
                    "telemetry", Map.of("enabled", false))));
            AppConfig config = AppConfig.fromFile(configuration);
            config.validate();
            RaftExecutorsFixture executor = RaftExecutorsFixture.create();
            executors.add(executor);
            servers.add(new QraftServerService(executor.stateLoop(), executor.blockingTasks(), config));
        }
        for (QraftServerService server : servers) server.start().get(30, TimeUnit.SECONDS);
    }

    private static void waitUntil(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(), description);
    }
}

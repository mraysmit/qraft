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
import dev.mars.qraft.client.QraftClient;
import dev.mars.qraft.client.config.ClientConfiguration;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of consistency (consistency list, Phase 5): three servers in one JVM and two clients. A consistent
 * read is answered by the leader alone, and through a client, which finds it; a blocking key/value read through one
 * client wakes when another client writes.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
@Tag("e2e")
class ConsistencyEndToEndTest {
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
    void aConsistentReadIsTheLeadersAndABlockingReadWakesOnAnotherClientsWrite() throws Exception {
        startCluster();
        waitUntil(() -> consistentAnswers() == 1, "exactly one server, the leader, answers a consistent read");

        QraftClient hostA = startClient("host-a", List.of(0, 1, 2));
        QraftClient hostB = startClient("host-b", List.of(2, 1, 0));
        waitUntil(() -> hostA.localApi().isReady() && hostB.localApi().isReady(), "both clients registered");
        assertEquals("true", text(local(hostA, "PUT", "/v1/kv/flag", "off")));
        waitUntil(() -> local(hostB, "GET", "/v1/kv/flag?consistent", null).statusCode() == 200,
                "a consistent read through a client finds the leader");

        long index = Long.parseLong(local(hostB, "GET", "/v1/kv/flag?consistent", null).headers()
                .firstValue("X-Qraft-Index").orElseThrow());
        CompletableFuture<HttpResponse<byte[]>> blocked = CompletableFuture.supplyAsync(
                () -> local(hostB, "GET", "/v1/kv/flag?index=" + index + "&wait=30s", null));
        Thread.sleep(300);
        assertFalse(blocked.isDone(), "nothing has changed");

        assertEquals("true", text(local(hostA, "PUT", "/v1/kv/flag", "on")));

        HttpResponse<byte[]> woken = blocked.get(20, TimeUnit.SECONDS);
        assertEquals(200, woken.statusCode());
        assertTrue(Long.parseLong(woken.headers().firstValue("X-Qraft-Index").orElseThrow()) > index);
        assertTrue(text(woken).contains("\"Value\":\"b24=\""), text(woken));
    }

    /** How many servers answer a consistent read: the leader alone, once it is elected and confirmed. */
    private int consistentAnswers() {
        int answered = 0;
        for (int port : httpPorts) {
            try {
                HttpResponse<String> read = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/v1/catalog/nodes?consistent")).build(), HttpResponse.BodyHandlers.ofString());
                if (read.statusCode() == 200) answered++;
                else if (read.statusCode() != 503 || !JSON.readTree(read.body()).path("code").asText()
                        .equals("leader_unavailable")) return -1;
            } catch (Exception unanswered) {
                return -1;
            }
        }
        return answered;
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

    private QraftClient startClient(String nodeName, List<Integer> serverOrder) throws Exception {
        Path data = Files.createDirectories(root.resolve(nodeName));
        QraftClient client = new QraftClient(ClientConfiguration.builder()
                .nodeName(nodeName).dataDirectory(data).address("127.0.0.1").clientPort(0)
                .serverUrls(serverOrder.stream().map(i -> URI.create("http://localhost:" + httpPorts.get(i))).toList())
                .heartbeatInterval(100).requestTimeoutMs(5_000).registrationRetryMinMs(50)
                .registrationRetryMaxMs(250).contactFreshnessMs(5_000).shutdownTimeoutMs(3_000)
                .build());
        clients.add(client);
        client.start().get(15, TimeUnit.SECONDS);
        return client;
    }

    private HttpResponse<byte[]> local(QraftClient client, String method, String path, String body) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + client.localApi().port() + path))
                            .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("the local API did not answer", e);
        }
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static void waitUntil(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(condition.getAsBoolean(), description);
    }
}

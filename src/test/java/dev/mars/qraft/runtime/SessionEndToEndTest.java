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
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.server.QraftServerService;
import dev.mars.qraft.server.config.AppConfig;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
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
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of sessions and locks (sessions list, Phase 4): three servers in one JVM, and two clients on two
 * hosts contending for one lock through their local APIs. Each client's session is bound to its own node; the holder's
 * lock refuses the other; a renewal reaches the leader through either client; and when the holder stops renewing, the
 * leader destroys its session at twice its TTL, the key is released, and the other client acquires it once the lock
 * delay has passed, with a new {@code LockIndex}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
@Tag("e2e")
class SessionEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOCK = "/v1/kv/service/leader";

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
        // Each server is stopped on purpose while the others may still send to it: a leader that sends a heartbeat
        // to one already stopped logs it as unreachable (AuthEndToEndTest, 2026-11-13).
        for (int i = servers.size() - 1; i >= 0; i--) {
            QraftServerService server = servers.get(i);
            IntentionalErrorsHelper.expectPeerShutdownDuringCleanup("server" + (i + 1));
            cleanup.run(() -> server.stop().get(30, TimeUnit.SECONDS));
        }
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
    void whenTheHoldersSessionLapsesTheOtherClientAcquiresTheLockAfterItsDelay() throws Exception {
        startCluster();
        QraftClient hostA = startClient("host-a", List.of(0, 1, 2));
        QraftClient hostB = startClient("host-b", List.of(2, 1, 0));
        waitUntil(() -> hostA.localApi().isReady() && hostB.localApi().isReady(), "both clients registered");

        String holder = ok(local(hostA, "PUT", "/v1/session/create",
                "{\"Name\":\"leader\",\"TTL\":\"10s\",\"LockDelay\":\"2s\"}")).path("ID").asText();
        String waiter = ok(local(hostB, "PUT", "/v1/session/create", "{\"Name\":\"leader\"}")).path("ID").asText();
        assertEquals("host-a", ok(local(hostB, "GET", "/v1/session/info/" + holder, null)).get(0).path("Node").asText(),
                "each session is bound to its own client's node");

        assertEquals("true", text(local(hostA, "PUT", LOCK + "?acquire=" + holder, "host-a")));
        assertEquals("false", text(local(hostB, "PUT", LOCK + "?acquire=" + waiter, "host-b")), "the lock is held");
        assertEquals(holder, ok(local(hostA, "PUT", "/v1/session/renew/" + holder, null)).get(0).path("ID").asText());
        assertEquals(holder, ok(local(hostB, "PUT", "/v1/session/renew/" + holder, null)).get(0).path("ID").asText(),
                "a renewal reaches the leader through either client");
        JsonNode held = ok(local(hostB, "GET", LOCK, null)).get(0);
        assertEquals(1, held.path("LockIndex").asLong());

        // host-a stops renewing: its session lapses at twice its TTL, and its key is released.
        waitUntil(() -> sessionGone(hostB, holder), 40, "the leader destroyed the lapsed session");
        JsonNode released = ok(local(hostB, "GET", LOCK, null)).get(0);
        assertFalse(released.has("Session"), "the release behaviour freed the key");
        assertEquals("host-a", new String(java.util.Base64.getDecoder().decode(released.path("Value").asText()),
                StandardCharsets.UTF_8), "a release keeps the value");

        waitUntil(() -> "true".equals(text(local(hostB, "PUT", LOCK + "?acquire=" + waiter, "host-b"))), 10,
                "host-b acquires once the lock delay has passed");
        JsonNode acquired = ok(local(hostA, "GET", LOCK, null)).get(0);
        assertEquals(waiter, acquired.path("Session").asText());
        assertEquals(2, acquired.path("LockIndex").asLong(), "a new holder increments the lock index");
        HttpResponse<byte[]> lapsed = local(hostA, "PUT", LOCK + "?acquire=" + holder, "host-a");
        assertEquals(400, lapsed.statusCode(), text(lapsed));
        assertEquals("invalid_session", JSON.readTree(lapsed.body()).path("code").asText(),
                "the lapsed session acquires nothing");
    }

    private boolean sessionGone(QraftClient client, String id) {
        HttpResponse<byte[]> answer = local(client, "GET", "/v1/session/info/" + id, null);
        try {
            return answer.statusCode() == 200 && JSON.readTree(answer.body()).isEmpty();
        } catch (IOException notJson) {
            throw new IllegalStateException("the session info is not JSON: " + text(answer), notJson);
        }
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

    private static JsonNode ok(HttpResponse<byte[]> response) throws Exception {
        assertEquals(200, response.statusCode(), text(response));
        return JSON.readTree(response.body());
    }

    private static void waitUntil(BooleanSupplier condition, String description) throws Exception {
        waitUntil(condition, 30, description);
    }

    private static void waitUntil(BooleanSupplier condition, int seconds, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(), description);
    }
}

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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the event watch (event query and watch list, Phase 4): three servers in one JVM. A watch on one
 * server sees the key/value writes made through another, each as that server's own event of the replicated fact, which
 * carries the same replicated identity as the writing server's event of it (decision 4).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
@Tag("e2e")
class EventWatchEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> KEYS = List.of("app/a", "app/b", "app/c");

    @TempDir
    Path root;

    private final List<RaftExecutorsFixture> executors = new ArrayList<>();
    private final List<QraftServerService> servers = new ArrayList<>();
    private final List<Integer> httpPorts = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() throws Exception {
        CleanupHelper cleanup = new CleanupHelper();
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
    void aWatchOnOneServerSeesTheWritesMadeThroughAnother() throws Exception {
        startCluster();
        waitUntil(() -> leader() >= 0, "one server, the leader, answers a consistent read");
        int watched = (leader() + 1) % httpPorts.size();

        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        HttpResponse<Stream<String>> watch = http.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                + httpPorts.get(watched) + "/v1/operator/events/watch?type=key.set")).build(),
                HttpResponse.BodyHandlers.ofLines());
        assertEquals(200, watch.statusCode());
        Thread.ofVirtual().start(() -> watch.body().forEach(lines::add));

        int writer = -1;
        for (String key : KEYS) writer = put(watched, key);

        Map<String, JsonNode> seen = new LinkedHashMap<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (seen.size() < KEYS.size() && System.nanoTime() < deadline) {
            String line = lines.poll(100, TimeUnit.MILLISECONDS);
            if (line == null || !line.startsWith("data: ")) continue;
            JsonNode event = JSON.readTree(line.substring(6));
            seen.putIfAbsent(event.path("event").path("key").asText(), event);
        }
        assertEquals(KEYS, List.copyOf(seen.keySet()), "the watched server saw each write, in order");
        assertEquals("server" + (watched + 1), seen.get(KEYS.getFirst()).path("node").asText());

        Map<String, String> written = new LinkedHashMap<>();
        HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                + httpPorts.get(writer) + "/v1/operator/events?type=key.set")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode(), page.body());
        JSON.readTree(page.body()).path("events").forEach(event -> written.putIfAbsent(
                event.path("event").path("key").asText(), event.path("replicatedId").asText()));
        for (String key : KEYS) {
            assertEquals(written.get(key), seen.get(key).path("replicatedId").asText(), "the same replicated fact");
            assertNotEquals(seen.get(key).path("cursor").asText(), "", key);
        }
        assertTrue(writer != watched, "the writes went through another server");
    }

    /** Writes {@code key} through the first server other than {@code watched} that accepts it; that server's index. */
    private int put(int watched, String key) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < httpPorts.size(); i++) {
                if (i == watched) continue;
                HttpResponse<String> answer = http.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                        + httpPorts.get(i) + "/v1/kv/" + key)).PUT(HttpRequest.BodyPublishers.ofString("v")).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (answer.statusCode() == 200 && answer.body().equals("true")) return i;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no server other than " + watched + " accepted the write of " + key);
    }

    /** The index of the one server answering a consistent read, or -1 while there is not exactly one. */
    private int leader() {
        int leader = -1;
        for (int i = 0; i < httpPorts.size(); i++) {
            try {
                HttpResponse<String> read = http.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                        + httpPorts.get(i) + "/v1/catalog/nodes?consistent")).build(), HttpResponse.BodyHandlers.ofString());
                if (read.statusCode() == 200) {
                    if (leader >= 0) return -1;
                    leader = i;
                }
            } catch (IOException | InterruptedException unanswered) {
                return -1;
            }
        }
        return leader;
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
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(condition.getAsBoolean(), description);
    }
}

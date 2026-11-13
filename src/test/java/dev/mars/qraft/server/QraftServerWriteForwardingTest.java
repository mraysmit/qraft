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
import dev.mars.qraft.server.config.AppConfig;
import dev.mars.qraft.server.http.HttpApiServer;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests forwarding writes to the leader (write forwarding list, Phase 2, decisions 1 to 3) on three servers in one JVM:
 * a write to a follower is answered with the leader's answer, which names the leader, and is applied; a session
 * renewal, which only the leader can make, is answered by a follower; a write already forwarded once is refused by a
 * follower, and so is a write to a follower that is draining; and a follower whose leader is gone answers 503.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class QraftServerWriteForwardingTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    private final List<RaftExecutorsFixture> executors = new ArrayList<>();
    private final List<QraftServerService> servers = new ArrayList<>();
    private final List<Integer> httpPorts = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() throws Exception {
        try {
            for (QraftServerService server : servers.reversed()) {
                if (server != null) server.stop().get(30, TimeUnit.SECONDS);
            }
        } finally {
            for (RaftExecutorsFixture executor : executors) executor.close().get(30, TimeUnit.SECONDS);
            http.close();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private HttpResponse<String> send(int server, String method, String path, String body, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + httpPorts.get(server)
                + path)).method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The one server answering a consistent read, or -1. */
    private int leader() {
        int leader = -1;
        for (int i = 0; i < httpPorts.size(); i++) {
            if (servers.get(i) == null) continue;
            try {
                if (send(i, "GET", "/v1/catalog/nodes?consistent", null).statusCode() == 200) {
                    if (leader >= 0) return -1;
                    leader = i;
                }
            } catch (Exception unanswered) {
                return -1;
            }
        }
        return leader;
    }

    @Test
    void aFollowerAnswersAWriteWithTheLeadersAnswer() throws Exception {
        startCluster();
        waitUntil(() -> leader() >= 0, "a leader was elected");
        int leader = leader();
        int follower = (leader + 1) % 3;
        waitUntil(() -> {
            try {
                return send(follower, "GET", "/raft/status", null).body().contains("\"leaderId\":\"server"
                        + (leader + 1) + "\"");
            } catch (Exception unanswered) {
                return false;
            }
        }, "the follower knows the leader");

        HttpResponse<String> written = send(follower, "PUT", "/v1/kv/app/config?flags=7", "forwarded");
        assertEquals(200, written.statusCode(), written.body());
        assertEquals("true", written.body());
        assertEquals("server" + (leader + 1), written.headers().firstValue("X-Qraft-Forwarded-To").orElseThrow());
        long writtenAt = Long.parseLong(written.headers().firstValue("X-Qraft-Index").orElseThrow());
        long applied = Long.parseLong(send(follower, "GET", "/raft/status", null).headers()
                .firstValue("X-Qraft-Index").orElseThrow());
        assertTrue(applied >= writtenAt, "the follower answers once it has applied the write itself, so its own reads "
                + "see it: applied " + applied + ", written at " + writtenAt);
        HttpResponse<String> read = send(leader, "GET", "/v1/kv/app/config", null);
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(7, JSON.readTree(read.body()).get(0).path("Flags").asInt(), "the query came with the write");

        assertEquals(200, send(follower, "PUT", "/v1/catalog/register", "{\"node\":{\"address\":\"10.0.0.1\"}}",
                "X-Qraft-Node", "host-a").statusCode(), "the identity header is carried to the leader");
        HttpResponse<String> created = send(follower, "PUT", "/v1/session/create", "{\"Node\":\"host-a\",\"TTL\":\"10s\"}");
        assertEquals(200, created.statusCode(), created.body());
        String session = JSON.readTree(created.body()).path("ID").asText();
        assertEquals(200, send(follower, "PUT", "/v1/session/renew/" + session, null).statusCode(),
                "a renewal, which only the leader makes, through a follower");

        HttpResponse<String> twice = send(follower, "PUT", "/v1/kv/app/again", "x", "X-Qraft-Forwarded-By", "server9");
        assertEquals(503, twice.statusCode(), twice.body());
        assertEquals("leader_unavailable", JSON.readTree(twice.body()).path("code").asText());

        HttpApiServer followerApi = servers.get(follower).httpApi().orElseThrow();
        followerApi.useWriteForwarding((to, request) -> CompletableFuture.failedFuture(
                new dev.mars.qraft.raft.ForwardNotSentException("refused by the test")));
        HttpResponse<String> notSent = send(follower, "PUT", "/v1/kv/app/not-sent", "x");
        assertEquals(503, notSent.statusCode(), notSent.body());
        assertEquals("leader_unavailable", JSON.readTree(notSent.body()).path("code").asText(),
                "a write that never reached the leader was not applied, so the caller may send it elsewhere");
        followerApi.useWriteForwarding((to, request) -> CompletableFuture.failedFuture(
                new IllegalStateException("lost after it was sent")));
        HttpResponse<String> lost = send(follower, "PUT", "/v1/kv/app/lost", "x");
        assertEquals(503, lost.statusCode(), lost.body());
        assertEquals("outcome_unknown", JSON.readTree(lost.body()).path("code").asText(),
                "a write lost after it was sent may have been applied");
        followerApi.useWriteForwarding(servers.get(follower).forwardingTransport());

        int draining = (leader + 2) % 3;
        servers.get(draining).httpApi().orElseThrow().enterDrainMode().get(10, TimeUnit.SECONDS);
        HttpResponse<String> drained = send(draining, "PUT", "/v1/kv/app/draining", "x");
        assertEquals(503, drained.statusCode(), drained.body());
        assertEquals("draining", JSON.readTree(drained.body()).path("code").asText(),
                "a draining server takes no new write, not even to forward it");

        // The two servers are stopped on purpose; the survivor may log them as unreachable peers.
        IntentionalErrorsHelper.expectPeerShutdownDuringCleanup("server" + (leader + 1));
        servers.get(leader).stop().get(30, TimeUnit.SECONDS);
        servers.set(leader, null);
        int other = (leader + 2) % 3;
        IntentionalErrorsHelper.expectPeerShutdownDuringCleanup("server" + (other + 1));
        servers.get(other).stop().get(30, TimeUnit.SECONDS);
        servers.set(other, null);
        long started = System.nanoTime();
        HttpResponse<String> alone = send(follower, "PUT", "/v1/kv/app/alone", "x");
        assertEquals(503, alone.statusCode(), alone.body());
        assertEquals("leader_unavailable", JSON.readTree(alone.body()).path("code").asText());
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(4),
                "a follower whose leader has gone answers promptly, so its caller can try another server");
    }

    @Test
    void aWriteForwardedToALeaderThisServerStopsFollowingIsAnsweredAtOnceAsPerhapsApplied() throws Exception {
        startCluster();
        waitUntil(() -> leader() >= 0, "a leader was elected");
        int leader = leader();
        int follower = (leader + 1) % 3;
        waitUntil(() -> {
            try {
                return send(follower, "GET", "/raft/status", null).body().contains("\"leaderId\":\"server"
                        + (leader + 1) + "\"");
            } catch (Exception unanswered) {
                return false;
            }
        }, "the follower knows the leader");

        // A leader cut off by the network takes a forwarded write on a connection that still looks ready and never
        // answers it (DockerClientTopologyTest, 2026-11-13); this forward never answers either.
        CompletableFuture<Long> forwardedAt = new CompletableFuture<>();
        servers.get(follower).httpApi().orElseThrow().useWriteForwarding((to, request) -> {
            forwardedAt.complete(System.nanoTime());
            return new CompletableFuture<>();
        });
        CompletableFuture<Long> answeredAt = new CompletableFuture<>();
        CompletableFuture<HttpResponse<String>> answer = http.sendAsync(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + httpPorts.get(follower) + "/v1/kv/app/held"))
                .PUT(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, failure) -> answeredAt.complete(System.nanoTime()));
        long sent = forwardedAt.get(10, TimeUnit.SECONDS);

        // The leader is stopped on purpose; the others may log it as an unreachable peer.
        IntentionalErrorsHelper.expectPeerShutdownDuringCleanup("server" + (leader + 1));
        servers.get(leader).stop().get(30, TimeUnit.SECONDS);
        servers.set(leader, null);
        long stopped = System.nanoTime();

        HttpResponse<String> held = answer.get(30, TimeUnit.SECONDS);
        assertEquals(503, held.statusCode(), held.body());
        assertEquals("outcome_unknown", JSON.readTree(held.body()).path("code").asText(),
                "the write was sent, so it may have been applied");
        long answered = answeredAt.get();
        assertTrue(answered - stopped < TimeUnit.SECONDS.toNanos(3), "answered within "
                + TimeUnit.NANOSECONDS.toMillis(answered - stopped) + " ms of the leader stopping: a follower answers "
                + "once it stops following that leader, so its caller can try another server while the write's check "
                + "still has time");
        assertTrue(answered - sent < HttpApiServer.DEFAULT_RAFT_TIMEOUT.multipliedBy(2).toNanos(), "answered before "
                + "the follower's own wait for the leader's answer ended");
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

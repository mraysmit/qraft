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

package dev.mars.qraft.controller.http;

import dev.mars.qraft.controller.raft.InMemoryTransportSimulator;
import dev.mars.qraft.controller.raft.RaftNode;
import dev.mars.qraft.controller.raft.RaftNodeMode;
import dev.mars.qraft.controller.raft.RaftTransport;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LeaderGuardHandler}.
 *
 * <p>Validates that:
 * <ul>
 *   <li>Write requests on the leader node pass through</li>
 *   <li>Read requests always pass through regardless of leadership</li>
 *   <li>Write requests on follower nodes are rejected with NOT_LEADER error</li>
 *   <li>Non-API paths (health, metrics) bypass the guard</li>
 * </ul>
 *
 * <p>Uses a real 3-node Raft cluster to obtain both leader and follower nodes.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-02-19
 */
@ExtendWith(VertxExtension.class)
@DisplayName("LeaderGuardHandler Tests")
class LeaderGuardHandlerTest {

        private static final int NODE1_PORT = 18098;
        private static final int NODE2_PORT = 18099;
        private static final int NODE3_PORT = 18100;

    private static Vertx vertx;
        private static RaftNode node1;
        private static RaftNode node2;
        private static RaftNode node3;
        private static HttpApiServer server1;
        private static HttpApiServer server2;
        private static HttpApiServer server3;
        private static final Map<RaftNode, Integer> nodePorts = new LinkedHashMap<>();
    private static WebClient webClient;

    @BeforeAll
    static void setUp() throws Exception {
        vertx = Vertx.vertx();

        Set<String> clusterNodes = Set.of("guard-node-1", "guard-node-2", "guard-node-3");

        RaftTransport transport1 = new InMemoryTransportSimulator("guard-node-1");
        RaftTransport transport2 = new InMemoryTransportSimulator("guard-node-2");
        RaftTransport transport3 = new InMemoryTransportSimulator("guard-node-3");

        QraftStateStore sm1 = new QraftStateStore();
        QraftStateStore sm2 = new QraftStateStore();
        QraftStateStore sm3 = new QraftStateStore();

        node1 = RaftNode.builder().vertx(vertx).nodeId("guard-node-1").clusterNodes(clusterNodes).transport(transport1).stateMachine(sm1).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(1500).heartbeatInterval(100).build();
        node2 = RaftNode.builder().vertx(vertx).nodeId("guard-node-2").clusterNodes(clusterNodes).transport(transport2).stateMachine(sm2).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(1500).heartbeatInterval(100).build();
        node3 = RaftNode.builder().vertx(vertx).nodeId("guard-node-3").clusterNodes(clusterNodes).transport(transport3).stateMachine(sm3).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(1500).heartbeatInterval(100).build();

        node1.start();
        node2.start();
        node3.start();

        // Wait for leader election 
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> node1.isLeader() || node2.isLeader() || node3.isLeader());

                // Start HTTP servers on all nodes so tests can target current leader/follower dynamically.
                server1 = new HttpApiServer(vertx, NODE1_PORT, node1, sm1);
                server1.start().toCompletionStage().toCompletableFuture()
                .get(5, java.util.concurrent.TimeUnit.SECONDS);

                server2 = new HttpApiServer(vertx, NODE2_PORT, node2, sm2);
                server2.start().toCompletionStage().toCompletableFuture()
                                .get(5, java.util.concurrent.TimeUnit.SECONDS);

                server3 = new HttpApiServer(vertx, NODE3_PORT, node3, sm3);
                server3.start().toCompletionStage().toCompletableFuture()
                .get(5, java.util.concurrent.TimeUnit.SECONDS);

                nodePorts.clear();
                nodePorts.put(node1, NODE1_PORT);
                nodePorts.put(node2, NODE2_PORT);
                nodePorts.put(node3, NODE3_PORT);

        webClient = WebClient.create(vertx);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (webClient != null) webClient.close();
        if (server1 != null) server1.stop().toCompletionStage().toCompletableFuture()
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        if (server2 != null) server2.stop().toCompletionStage().toCompletableFuture()
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        if (server3 != null) server3.stop().toCompletionStage().toCompletableFuture()
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture()
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        InMemoryTransportSimulator.clearAllTransports();
    }

    private static int currentLeaderPort() {
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> node1.isLeader() || node2.isLeader() || node3.isLeader());

        return nodePorts.entrySet().stream()
                .filter(entry -> entry.getKey().isLeader())
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No leader port available"));
    }

    private static int currentFollowerPort() {
        return nodePorts.entrySet().stream()
                .filter(entry -> !entry.getKey().isLeader())
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No follower port available"));
    }

    // ==================== Leader: writes pass through ====================

    @Nested
    @DisplayName("Leader node -- writes allowed")
    class LeaderWrites {

        @Test
        @DisplayName("PUT to leader succeeds (200)")
        void testWriteOnLeader(VertxTestContext ctx) {
                        int leaderPort = currentLeaderPort();
            JsonObject body = new JsonObject()
                    .put("value", "guard-leader-value");

                        webClient.put(leaderPort, "localhost", "/api/v1/state/guard-leader-key")
                    .sendJsonObject(body)
                    .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
                        assertEquals(200, response.statusCode(),
                                "Write on leader should succeed");
                        ctx.completeNow();
                    })));
        }
    }

    // ==================== Follower: writes rejected ====================

    @Nested
    @DisplayName("Follower node -- writes rejected")
    class FollowerWrites {

        @Test
        @DisplayName("PUT to follower returns NOT_LEADER error")
        void testWriteOnFollower(VertxTestContext ctx) {
                        int followerPort = currentFollowerPort();
            JsonObject body = new JsonObject()
                    .put("value", "guard-follower-value");

                        webClient.put(followerPort, "localhost", "/api/v1/state/guard-follower-key")
                    .sendJsonObject(body)
                    .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
                        assertEquals(503, response.statusCode(),
                                "Write on follower should be rejected");
                        JsonObject json = response.bodyAsJsonObject();
                        JsonObject error = json.getJsonObject("error");
                        assertNotNull(error, "Error envelope expected");
                        String code = error.getString("code");
                        assertTrue("NOT_LEADER".equals(code) || "NO_LEADER".equals(code),
                                "Error code should be NOT_LEADER or NO_LEADER, got: " + code);
                        ctx.completeNow();
                    })));
        }

        @Test
        @DisplayName("DELETE to follower returns NOT_LEADER error")
        void testDeleteOnFollower(VertxTestContext ctx) {
                        int followerPort = currentFollowerPort();
                        webClient.delete(followerPort, "localhost", "/api/v1/state/any-id")
                    .send()
                    .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
                        assertEquals(503, response.statusCode(),
                                "Delete on follower should be rejected");
                        ctx.completeNow();
                    })));
        }
    }

    // ==================== Reads always pass through ====================

    @Nested
    @DisplayName("Reads pass through on any node")
    class ReadPassthrough {

        @Test
        @DisplayName("GET on follower succeeds (200)")
        void testReadOnFollower(VertxTestContext ctx) {
                        int followerPort = currentFollowerPort();
                        webClient.get(followerPort, "localhost", "/api/v1/state")
                    .send()
                    .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
                        assertEquals(200, response.statusCode(),
                                "Read on follower should succeed");
                        ctx.completeNow();
                    })));
        }

        @Test
        @DisplayName("GET on leader succeeds (200)")
        void testReadOnLeader(VertxTestContext ctx) {
                        int leaderPort = currentLeaderPort();
                        webClient.get(leaderPort, "localhost", "/api/v1/state")
                    .send()
                    .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
                        assertEquals(200, response.statusCode(),
                                "Read on leader should succeed");
                        ctx.completeNow();
                    })));
        }
    }

    // ==================== Non-API paths bypass guard ====================

    @Nested
    @DisplayName("Non-API paths bypass guard")
    class NonApiPaths {

        @Test
        @DisplayName("GET /health/live on follower always succeeds")
        void testHealthOnFollower(VertxTestContext ctx) {
                        int followerPort = currentFollowerPort();
                        webClient.get(followerPort, "localhost", "/health/live")
                    .send()
                    .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
                        assertEquals(200, response.statusCode(),
                                "Health probe on follower should succeed");
                        ctx.completeNow();
                    })));
        }

        @Test
        @DisplayName("GET /raft/status on follower always succeeds")
        void testRaftStatusOnFollower(VertxTestContext ctx) {
                        int followerPort = currentFollowerPort();
                        webClient.get(followerPort, "localhost", "/raft/status")
                    .send()
                    .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
                        assertEquals(200, response.statusCode(),
                                "Raft status on follower should succeed");
                        ctx.completeNow();
                    })));
        }
    }
}



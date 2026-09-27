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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.controller.raft.InMemoryTransportSimulator;
import dev.mars.qraft.controller.raft.RaftNode;
import dev.mars.qraft.controller.raft.RaftNodeMode;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a server reports ready only when it can safely serve its API: it has finished recovery, is not
 * fenced or draining, and knows a current leader. A 503 names every failed condition; liveness is never
 * affected. A server cut off from the majority becomes unready and is ready again once the partition heals.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
class HttpApiServerReadinessTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, RaftNode> nodes = new LinkedHashMap<>();
    private final List<HttpApiServer> servers = new ArrayList<>();
    private JavaRuntime runtime;

    @AfterEach
    void stop() throws Exception {
        servers.forEach(HttpApiServer::close);
        for (RaftNode node : nodes.values()) node.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (runtime != null) runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        InMemoryTransportSimulator.clearAllTransports();
        http.close();
    }

    @Test
    void aLeaderIsReady() throws Exception {
        HttpApiServer server = serve(startCluster(Set.of("a"), Set.of("a")).get("a"));
        await(() -> nodes.get("a").isLeader());

        HttpResponse<String> ready = get(server, "/health/ready");

        assertEquals(200, ready.statusCode(), ready.body());
        assertTrue(ready.body().contains("ready"));
    }

    @Test
    void aFollowerOfAKnownLeaderIsReady() throws Exception {
        startCluster(Set.of("a", "b", "c"), Set.of("a", "b", "c"));
        await(() -> leader() != null);
        String follower = nodes.keySet().stream().filter(id -> !id.equals(leader())).findFirst().orElseThrow();
        HttpApiServer server = serve(nodes.get(follower));

        await(() -> get(server, "/health/ready").statusCode() == 200);
    }

    @Test
    void aNodeThatKnowsNoLeaderIsNotReadyButIsLive() throws Exception {
        // Only one of three members runs, so it can never learn of a leader.
        HttpApiServer server = serve(startCluster(Set.of("a", "b", "c"), Set.of("a")).get("a"));

        HttpResponse<String> ready = get(server, "/health/ready");

        assertEquals(503, ready.statusCode());
        assertEquals(Set.of("no_leader"), conditions(ready));
        assertEquals(200, get(server, "/health/live").statusCode());
    }

    @Test
    void aNodeThatHasNotFinishedRecoveryIsNotReady() throws Exception {
        runtime = JavaRuntime.create();
        QraftStateStore store = new QraftStateStore();
        RaftNode unstarted = node("a", Set.of("a"), store);
        HttpApiServer server = new HttpApiServer(0, unstarted, store);
        servers.add(server);
        server.start().get(10, TimeUnit.SECONDS);

        HttpResponse<String> ready = get(server, "/health/ready");

        assertEquals(503, ready.statusCode());
        assertTrue(conditions(ready).contains("recovering"), ready.body());
    }

    @Test
    void aDrainingServerIsNotReadyButIsLive() throws Exception {
        HttpApiServer server = serve(startCluster(Set.of("a"), Set.of("a")).get("a"));
        await(() -> nodes.get("a").isLeader());

        server.enterDrainMode().get(10, TimeUnit.SECONDS);
        HttpResponse<String> ready = get(server, "/health/ready");

        assertEquals(503, ready.statusCode());
        assertEquals(Set.of("draining"), conditions(ready));
        assertEquals(200, get(server, "/health/live").statusCode());
    }

    @Test
    void aServerCutOffFromTheMajorityBecomesUnreadyAndIsReadyAgainWhenThePartitionHeals() throws Exception {
        startCluster(Set.of("a", "b", "c"), Set.of("a", "b", "c"));
        await(() -> leader() != null);
        String isolated = nodes.keySet().stream().filter(id -> !id.equals(leader())).findFirst().orElseThrow();
        HttpApiServer server = serve(nodes.get(isolated));
        await(() -> get(server, "/health/ready").statusCode() == 200);

        Set<String> majority = new LinkedHashSet<>(nodes.keySet());
        majority.remove(isolated);
        InMemoryTransportSimulator.createPartition(Set.of(isolated), majority);
        await(() -> {
            HttpResponse<String> ready = get(server, "/health/ready");
            return ready.statusCode() == 503 && conditions(ready).contains("no_leader");
        });

        InMemoryTransportSimulator.healPartitions();
        await(() -> get(server, "/health/ready").statusCode() == 200);
    }

    private Map<String, RaftNode> startCluster(Set<String> members, Set<String> started) throws Exception {
        InMemoryTransportSimulator.clearAllTransports();
        runtime = JavaRuntime.create();
        for (String id : members.stream().sorted().toList()) {
            if (!started.contains(id)) continue;
            RaftNode node = node(id, members, new QraftStateStore());
            nodes.put(id, node);
        }
        for (RaftNode node : nodes.values()) node.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        return nodes;
    }

    private RaftNode node(String id, Set<String> members, QraftStateStore store) {
        return RaftNode.builder().runtime(runtime).nodeId(id).clusterNodes(members)
                .transport(new InMemoryTransportSimulator(id)).stateMachine(store)
                .commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(150).heartbeatInterval(30).build();
    }

    private HttpApiServer serve(RaftNode node) throws Exception {
        HttpApiServer server = new HttpApiServer(0, node, new QraftStateStore());
        servers.add(server);
        server.start().get(10, TimeUnit.SECONDS);
        return server;
    }

    private String leader() {
        return nodes.entrySet().stream().filter(entry -> entry.getValue().isLeader()).map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    private static Set<String> conditions(HttpResponse<String> response) throws Exception {
        JsonNode body = JSON.readTree(response.body());
        Set<String> conditions = new LinkedHashSet<>();
        body.path("conditions").forEach(condition -> conditions.add(condition.asText()));
        return conditions;
    }

    private HttpResponse<String> get(HttpApiServer server, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.met() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.met(), "condition was not met before the deadline");
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean met() throws Exception;
    }
}

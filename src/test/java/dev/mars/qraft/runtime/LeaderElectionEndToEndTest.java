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
import dev.mars.qraft.client.api.KvEntry;
import dev.mars.qraft.client.api.LeaderElection;
import dev.mars.qraft.client.api.QraftApi;
import dev.mars.qraft.client.api.QueryOptions;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the client library (client library list, Phase 4): three servers in one JVM and two clients, and
 * an application beside each that elects a leader through its own client's local API with {@link LeaderElection}. One
 * leads, its session bound to its client's node; when it stops, the other takes over.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
@Tag("e2e")
class LeaderElectionEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "service/billing/leader";

    @TempDir
    Path root;

    private final List<RaftExecutorsFixture> executors = new ArrayList<>();
    private final List<QraftServerService> servers = new ArrayList<>();
    private final List<QraftClient> clients = new ArrayList<>();
    private final List<Integer> httpPorts = new ArrayList<>();
    private final List<AutoCloseable> applications = new ArrayList<>();

    @AfterEach
    void stop() throws Exception {
        CleanupHelper cleanup = new CleanupHelper();
        for (AutoCloseable application : applications.reversed()) cleanup.run(application::close);
        for (QraftClient client : clients.reversed()) cleanup.run(() -> client.shutdown().get(10, TimeUnit.SECONDS));
        // Each server is stopped on purpose while the others may still send to it: a leader that sends a heartbeat
        // to one already stopped logs it as unreachable (AuthEndToEndTest, 2026-11-13).
        for (int i = servers.size() - 1; i >= 0; i--) {
            QraftServerService server = servers.get(i);
            IntentionalErrorsHelper.expectPeerShutdownDuringCleanup("server" + (i + 1));
            cleanup.run(() -> server.stop().get(30, TimeUnit.SECONDS));
        }
        for (RaftExecutorsFixture executor : executors) cleanup.run(() -> executor.close().get(30, TimeUnit.SECONDS));
        cleanup.rethrow();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void anApplicationBesideEachClientElectsALeaderAndTheOtherTakesOver() throws Exception {
        startCluster();
        QraftClient hostA = startClient("host-a", List.of(0, 1, 2));
        QraftClient hostB = startClient("host-b", List.of(2, 1, 0));
        waitUntil(() -> hostA.localApi().isReady() && hostB.localApi().isReady(), "both clients registered");

        QraftApi apiA = api(hostA);
        QraftApi apiB = api(hostB);
        LeaderElection a = elect(apiA, "a.example:8443");
        waitUntil(a::isLeader, "the first application leads");
        LeaderElection b = elect(apiB, "b.example:8443");
        waitUntil(() -> b.session().isPresent(), "the second application campaigns");
        assertFalse(b.isLeader());

        // Default reads are the answering server's own state, so the checks read consistently.
        KvEntry held = apiB.keyValue().get(KEY, QueryOptions.consistent()).value().orElseThrow();
        assertEquals(a.session().orElseThrow(), held.session(), "read through the other client");
        assertArrayEquals("a.example:8443".getBytes(StandardCharsets.UTF_8), held.value());
        assertEquals("host-a", apiA.sessions().info(held.session()).orElseThrow().node(),
                "the local API bound the session to its own node");

        a.close();
        waitUntil(b::isLeader, "the second application took over");
        assertArrayEquals("b.example:8443".getBytes(StandardCharsets.UTF_8),
                apiA.keyValue().get(KEY, QueryOptions.consistent()).value().orElseThrow().value());
        assertEquals("host-b", apiB.sessions().info(b.session().orElseThrow()).orElseThrow().node());
    }

    private QraftApi api(QraftClient client) {
        QraftApi api = QraftApi.builder().address(URI.create("http://127.0.0.1:" + client.localApi().port())).build();
        applications.add(api);
        return api;
    }

    private LeaderElection elect(QraftApi api, String address) {
        LeaderElection election = LeaderElection.builder(api, KEY).value(address.getBytes(StandardCharsets.UTF_8))
                .ttl(Duration.ofSeconds(10)).lockDelay(Duration.ZERO).retryWait(Duration.ofSeconds(2)).start();
        applications.add(election);
        return election;
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

    private static void waitUntil(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(condition.getAsBoolean(), description);
    }
}

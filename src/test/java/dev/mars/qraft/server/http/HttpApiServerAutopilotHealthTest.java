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

package dev.mars.qraft.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.raft.StorageStatus;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code GET /v1/operator/autopilot/health} and the storage fields of {@code /raft/status} (observability list,
 * Phase 3, decisions 5 and 6): the leader answers each server in Consul's form, and a follower answers 503
 * {@code leader_unavailable}; and a server that knows its storage reports its size and its snapshot's age.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class HttpApiServerAutopilotHealthTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path storage;

    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private HttpApiServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() throws Exception {
        try {
            if (server != null) server.close();
            if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        } finally {
            if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
            http.close();
        }
    }

    private void start(Set<String> members) throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("health-node").clusterNodes(members).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (members.size() == 1 && !node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null);
        server.start().get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theLeaderAnswersEachServerInConsulsForm() throws Exception {
        start(Set.of("health-node"));

        HttpResponse<String> answer = get("/v1/operator/autopilot/health");
        assertEquals(200, answer.statusCode(), answer.body());
        JsonNode health = JSON.readTree(answer.body());
        assertTrue(health.path("Healthy").asBoolean());
        assertEquals(0, health.path("FailureTolerance").asInt(), "one voter survives no loss");
        JsonNode self = health.path("Servers").get(0);
        assertEquals("health-node", self.path("Name").asText());
        assertTrue(self.path("Leader").asBoolean());
        assertTrue(self.path("Voter").asBoolean());
        assertTrue(self.path("Healthy").asBoolean());
        assertEquals("0s", self.path("LastContact").asText());
        assertTrue(self.path("LastIndex").asLong() > 0);
        assertFalse(self.path("ID").asText().isEmpty());
        assertEquals(400, get("/v1/operator/autopilot/health?stale=x&other").statusCode());
    }

    @Test
    void aFollowerAnswersThatOnlyTheLeaderKnows() throws Exception {
        start(Set.of("health-node", "absent-peer"));

        HttpResponse<String> answer = get("/v1/operator/autopilot/health");
        assertEquals(503, answer.statusCode(), answer.body());
        assertEquals("leader_unavailable", JSON.readTree(answer.body()).path("code").asText());
    }

    @Test
    void theStatusReportsTheStorageSizeAndTheSnapshotsAgeWhenTheServerKnowsItsStorage() throws Exception {
        start(Set.of("health-node"));
        assertFalse(JSON.readTree(get("/raft/status").body()).has("storageBytes"), "a volatile server has none");

        Files.write(storage.resolve("wal-0001.log"), new byte[100]);
        server.useStorageStatus(new StorageStatus(storage, Clock.systemUTC()));
        JsonNode status = JSON.readTree(get("/raft/status").body());
        assertEquals(100, status.path("storageBytes").asLong());
        assertEquals(-1, status.path("snapshotAgeSeconds").asLong(), "no snapshot yet");
    }
}

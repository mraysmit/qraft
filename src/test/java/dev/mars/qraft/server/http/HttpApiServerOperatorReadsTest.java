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
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.InMemoryTransportSimulatorFixture;
import dev.mars.qraft.raft.ManualRaftClusterFixture;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftLogApplicator;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.raft.storage.RaftStorageFactory;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import dev.mars.qraft.testing.fault.IntentionalErrorFixture;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the operator reads the administrative interface's Operate views take (UI-3 task list, Phase 1):
 * {@code GET /v1/operator/raft/members}, the leader's view of each server's progress and the quorum, or a follower's
 * labelled partial view; and {@code GET /v1/operator/raft/storage}, the storage and snapshot state.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-14
 * @version 1.0
 */
class HttpApiServerOperatorReadsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> MEMBERS = Set.of("a", "b", "c");
    private static final String SNAPSHOT_REFUSAL = "the test's state machine refuses to take a snapshot";

    @TempDir
    Path directory;

    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private RaftNode single;
    private final Map<String, RaftNode> nodes = new HashMap<>();
    private final Map<String, QraftStateStore> stores = new HashMap<>();
    private final List<HttpApiServer> servers = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() throws Exception {
        try {
            for (HttpApiServer server : servers) server.close();
            if (cluster != null) cluster.close();
            if (single != null) single.stop().get(10, TimeUnit.SECONDS);
        } finally {
            if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
            http.close();
        }
    }

    // ---- Membership ----

    @Test
    void theLeaderAnswersEachServersProgressAndTheQuorum() throws Exception {
        RaftNode leader = startCluster();
        HttpApiServer onLeader = serve(leader, "a", AclHttpSettings.disabled());

        JsonNode members = ok(get(onLeader, "/v1/operator/raft/members", Map.of()));

        assertEquals("leader", members.path("view").asText());
        assertEquals("a", members.path("answeredBy").asText());
        assertEquals("a", members.path("leaderId").asText());
        assertTrue(members.path("term").asLong() >= 1);
        long last = members.path("lastLogIndex").asLong();
        assertTrue(last >= 2, members.toString());
        JsonNode quorum = members.path("quorum");
        assertEquals(3, quorum.path("voters").asInt());
        assertEquals(2, quorum.path("quorumSize").asInt());
        assertEquals(3, quorum.path("reachableVoters").asInt());
        assertEquals(1, quorum.path("failureTolerance").asInt());
        assertTrue(quorum.path("healthy").asBoolean());
        List<String> names = new ArrayList<>();
        for (JsonNode server : members.path("servers")) {
            names.add(server.path("name").asText());
            assertTrue(server.path("voter").asBoolean());
            assertFalse(server.path("serverId").asText().isEmpty());
            assertEquals(last, server.path("matchIndex").asLong(), server.toString());
            assertEquals(0, server.path("lag").asLong(), server.toString());
            assertTrue(server.path("reachable").asBoolean(), server.toString());
            assertTrue(server.path("healthy").asBoolean(), server.toString());
        }
        assertEquals(List.of("a", "b", "c"), names, "in name order");
        JsonNode self = members.path("servers").get(0);
        assertTrue(self.path("leader").asBoolean() && self.path("self").asBoolean());
        assertEquals(0, self.path("lastContactMillis").asLong(), "the leader is in contact with itself");
        JsonNode follower = members.path("servers").get(1);
        assertFalse(follower.path("leader").asBoolean() || follower.path("self").asBoolean());
        assertEquals(last + 1, follower.path("nextIndex").asLong());
        assertTrue(follower.path("lastContactMillis").asLong() >= 0);
    }

    @Test
    void aServerThatStopsAnsweringFallsBehindAndIsUnreachableAfterAnElectionTimeoutOfRounds() throws Exception {
        RaftNode leader = startCluster();
        HttpApiServer onLeader = serve(leader, "a", AclHttpSettings.disabled());
        nodes.get("c").stop().get(10, TimeUnit.SECONDS);
        // The leader's own check-quorum test: an answer within an election timeout of heartbeat rounds.
        IntentionalErrorsHelper.expectPeerShutdownDuringCleanup("c");
        submit(leader, "after-c-stopped");
        // One settled round at a time: the heartbeat timer drops a fire while the previous round is still pending.
        cluster.heartbeatUntil(leader, () -> !reachable(leader, "c"), "c found unreachable after its rounds pass");

        JsonNode members = ok(get(onLeader, "/v1/operator/raft/members", Map.of()));

        JsonNode c = members.path("servers").get(2);
        assertEquals("c", c.path("name").asText());
        assertFalse(c.path("reachable").asBoolean(), c.toString());
        assertFalse(c.path("healthy").asBoolean(), c.toString());
        assertTrue(c.path("lag").asLong() >= 1, c.toString());
        assertTrue(members.path("servers").get(1).path("reachable").asBoolean(), "b still answers");
        assertEquals(2, members.path("quorum").path("reachableVoters").asInt());
        assertEquals(0, members.path("quorum").path("failureTolerance").asInt());
        assertFalse(members.path("quorum").path("healthy").asBoolean());
    }

    private static boolean reachable(RaftNode leader, String name) {
        try {
            return leader.membership().get(10, TimeUnit.SECONDS).servers().stream()
                    .filter(member -> member.name().equals(name))
                    .anyMatch(member -> Boolean.TRUE.equals(member.reachable()));
        } catch (Exception unanswered) {
            throw new AssertionError("the leader did not answer its membership", unanswered);
        }
    }

    @Test
    void aFollowerAnswersItsOwnPartialViewLabelledWithTheLeaderItKnows() throws Exception {
        // UI-3 task list, decision 5: only the leader knows each server's progress.
        startCluster();
        HttpApiServer onFollower = serve(nodes.get("b"), "b", AclHttpSettings.disabled());

        JsonNode members = ok(get(onFollower, "/v1/operator/raft/members", Map.of()));

        assertEquals("follower", members.path("view").asText());
        assertEquals("b", members.path("answeredBy").asText());
        assertEquals("a", members.path("leaderId").asText());
        assertTrue(members.path("quorum").isNull(), members.toString());
        assertEquals(3, members.path("servers").size());
        for (JsonNode server : members.path("servers")) {
            for (String progress : List.of("matchIndex", "nextIndex", "lag", "lastContactMillis", "reachable", "healthy")) {
                assertTrue(server.path(progress).isNull(), progress + " in " + server);
            }
        }
        assertTrue(members.path("servers").get(0).path("leader").asBoolean(), "a is the leader b knows");
        assertTrue(members.path("servers").get(1).path("self").asBoolean());
    }

    @Test
    void bothReadsNeedOperatorReadAndRefuseAnyParameter() throws Exception {
        RaftNode leader = startCluster();
        HttpApiServer onLeader = serve(leader, "a", new AclHttpSettings(true, directory, AclDefaultPolicy.DENY));

        for (String path : List.of("/v1/operator/raft/members", "/v1/operator/raft/storage")) {
            HttpResponse<String> refused = get(onLeader, path, Map.of());
            assertEquals(403, refused.statusCode(), path + " " + refused.body());
            assertEquals("permission_denied", JSON.readTree(refused.body()).path("code").asText(), path);
        }
        HttpApiServer open = serve(nodes.get("b"), "b", AclHttpSettings.disabled());
        assertEquals(400, get(open, "/v1/operator/raft/members?wan", Map.of()).statusCode());
        assertEquals(400, get(open, "/v1/operator/raft/storage?x=1", Map.of()).statusCode());
    }

    // ---- Storage ----

    @Test
    void theStorageReadAnswersTheLogAndSnapshotState() throws Exception {
        RaftNode leader = startCluster();
        HttpApiServer onLeader = serve(leader, "a", AclHttpSettings.disabled());

        JsonNode storage = ok(get(onLeader, "/v1/operator/raft/storage", Map.of()));

        assertEquals("a", storage.path("answeredBy").asText());
        assertEquals(0, storage.path("snapshotLastIndex").asLong());
        assertEquals(0, storage.path("snapshotLastTerm").asLong());
        assertEquals(1, storage.path("firstLogIndex").asLong(), "every entry is still held");
        long last = storage.path("lastLogIndex").asLong();
        assertTrue(last >= 2, storage.toString());
        assertEquals(last, storage.path("commitIndex").asLong());
        assertEquals(last, storage.path("lastApplied").asLong());
        assertFalse(storage.path("fenced").asBoolean());
        assertTrue(storage.path("fenceReason").isNull());
        assertEquals(0, storage.path("transitionQueueDepth").asInt());
        assertTrue(storage.path("lastSnapshotFailure").isNull());
        assertTrue(storage.path("storageBytes").isNull(), "a volatile server has no storage directory");
        assertTrue(storage.path("snapshotAgeSeconds").isNull());
    }

    @Test
    void aSnapshotMovesTheBoundaryAndAFailedOneIsRecorded() throws Exception {
        AtomicBoolean refuse = new AtomicBoolean();
        QraftStateStore store = new QraftStateStore();
        single = durableNode(new RefusingSnapshots(store, refuse));
        single.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!single.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        submit(single, "one");
        submit(single, "two");
        HttpApiServer server = new HttpApiServer(0, single, store, Clock.systemUTC(), AdminUiConfig.disabled(), null);
        server.useStorageStatus(new dev.mars.qraft.raft.StorageStatus(directory, Clock.systemUTC()));
        server.start().get(10, TimeUnit.SECONDS);
        servers.add(server);

        single.takeSnapshot().get(10, TimeUnit.SECONDS);
        JsonNode taken = ok(get(server, "/v1/operator/raft/storage", Map.of()));
        long index = taken.path("snapshotLastIndex").asLong();
        assertTrue(index >= 3, taken.toString());
        assertEquals(single.getCurrentTerm(), taken.path("snapshotLastTerm").asLong());
        assertEquals(index + 1, taken.path("firstLogIndex").asLong(), "the entries the snapshot holds are compacted");
        assertTrue(taken.path("storageBytes").asLong() > 0);
        assertTrue(taken.path("snapshotAgeSeconds").asLong() >= 0);
        assertTrue(taken.path("lastSnapshotFailure").isNull());

        submit(single, "three");
        refuse.set(true);
        IntentionalErrorsHelper.expect(IntentionalErrorFixture.RAFT_SNAPSHOT_REFUSED_BY_TEST);
        assertTrue(single.takeSnapshot().handle((ok, failure) -> failure != null).get(10, TimeUnit.SECONDS));

        JsonNode failed = ok(get(server, "/v1/operator/raft/storage", Map.of()));
        assertEquals(index, failed.path("snapshotLastIndex").asLong(), "the boundary stays where it was");
        assertEquals(SNAPSHOT_REFUSAL, failed.path("lastSnapshotFailure").path("message").asText(), failed.toString());
        assertTrue(failed.path("lastSnapshotFailure").path("at").asText().matches("\\d{4}-\\d{2}-\\d{2}T.+Z"));
        assertFalse(failed.path("fenced").asBoolean(), "a snapshot that fails before publication fences nothing");
    }

    /** A state machine that refuses to take a snapshot while {@code refuse} is set, and is otherwise {@code store}. */
    private record RefusingSnapshots(QraftStateStore store, AtomicBoolean refuse) implements RaftLogApplicator {
        @Override public RaftCommandResult<?> apply(RaftCommand command) { return store.apply(command); }
        @Override public byte[] takeSnapshot() {
            if (refuse.get()) throw new IllegalStateException(SNAPSHOT_REFUSAL);
            return store.takeSnapshot();
        }
        @Override public void restoreSnapshot(byte[] snapshot) { store.restoreSnapshot(snapshot); }
        @Override public long getLastAppliedIndex() { return store.getLastAppliedIndex(); }
        @Override public void setLastAppliedIndex(long index) { store.setLastAppliedIndex(index); }
        @Override public void reset() { store.reset(); }
    }

    private RaftNode durableNode(RaftLogApplicator stateMachine) throws Exception {
        RaftStorageFactory.DurableStorage durable = ManualRaftClusterFixture.await(RaftStorageFactory.createDurable(directory, true));
        runtime = RaftExecutorsFixture.create();
        return RaftNode.builder()
                .stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("solo").serverId(ManualRaftClusterFixture.serverIdOf("solo"))
                .clusterNodes(Set.of("solo")).transport(new PeerlessTransportFixture())
                .stateMachine(stateMachine).commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(durable.wal(), durable.snapshots()))
                .snapshotEnabled(false).electionTimeout(25).heartbeatInterval(10_000)
                .build();
    }

    private RaftNode startCluster() throws Exception {
        runtime = RaftExecutorsFixture.create();
        cluster = new ManualRaftClusterFixture(runtime);
        InMemoryTransportSimulatorFixture.clearAllTransports();
        for (String name : MEMBERS) {
            stores.put(name, new QraftStateStore());
            nodes.put(name, cluster.add(cluster.builder(name, MEMBERS, new InMemoryTransportSimulatorFixture(name),
                    stores.get(name), RaftNodeMode.volatileMode())));
        }
        ManualRaftClusterFixture.startAll(nodes.values().toArray(RaftNode[]::new));
        RaftNode leader = cluster.elect(nodes.get("a"));
        submit(leader, "k");
        cluster.heartbeatUntil(leader, () -> nodes.values().stream()
                .allMatch(node -> node.getCommitIndex() == leader.getCommitIndex()), "every server holds the commit");
        return leader;
    }

    private static void submit(RaftNode leader, String key) throws Exception {
        assertTrue(leader.submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put(key, "v")))
                .get(10, TimeUnit.SECONDS) instanceof RaftCommandResult.Success<?>);
    }

    private HttpApiServer serve(RaftNode node, String name, AclHttpSettings acl) throws Exception {
        HttpApiServer server = new HttpApiServer(0, node, stores.get(name), Clock.systemUTC(), AdminUiConfig.disabled(),
                null, HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, acl);
        server.start().get(10, TimeUnit.SECONDS);
        servers.add(server);
        return server;
    }

    private HttpResponse<String> get(HttpApiServer server, String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET();
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.request().uri() + " " + response.body());
        return JSON.readTree(response.body());
    }
}

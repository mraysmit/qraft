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

package dev.mars.qraft.controller.raft;

import com.google.protobuf.ByteString;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.testing.fault.IntentionalErrors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static dev.mars.qraft.controller.raft.ManualRaftCluster.await;
import static dev.mars.qraft.controller.raft.ManualRaftCluster.startAll;
import static dev.mars.qraft.testing.fault.IntentionalError.RAFT_PEER_UNREACHABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a {@link RaftNode} carries its server ID: it reports the ID it was built with, or its own UUID when
 * given none, and every Raft request it sends and every response it returns names it as the sender, on refusals
 * as well as acceptances. Step 2 of the membership task list counts votes and acknowledgements by this ID.
 *
 * <p>Nodes run on manual timers, so each exchange happens only when the test fires it.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
class RaftNodeServerIdentityTest {
    private static final String LEADER_ID = ManualRaftCluster.serverIdOf("node1");
    private static final Map<String, String> SERVER_IDS = Map.of(
            "node1", LEADER_ID,
            "node2", ManualRaftCluster.serverIdOf("node2"),
            "node3", ManualRaftCluster.serverIdOf("node3"));
    private static final long SNAPSHOT_THRESHOLD = 5;
    private static final long SNAPSHOT_CHECK_MS = 300;

    private JavaRuntime runtime;
    private ManualRaftCluster cluster;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        cluster = new ManualRaftCluster(runtime);
        InMemoryTransportSimulator.clearAllTransports();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            cluster.close();
        } finally {
            runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulator.clearAllTransports();
        }
    }

    @Test
    void aNodeReportsTheServerIdItWasBuiltWith() throws Exception {
        RaftNode node = cluster.add(cluster.unconfiguredBuilder("node1", Set.of("node1"),
                new InMemoryTransportSimulator("node1"), new QraftStateStore(), RaftNodeMode.volatileMode())
                .serverId(LEADER_ID));
        await(node.start());

        assertEquals(LEADER_ID, node.getServerId());
        assertEquals(LEADER_ID, await(node.status()).serverId());
    }

    @Test
    void aNodeBuiltWithoutAServerIdGetsItsOwnUuid() {
        RaftNode first = cluster.add(cluster.unconfiguredBuilder("node1", Set.of("node1"),
                new InMemoryTransportSimulator("node1"), new QraftStateStore(), RaftNodeMode.volatileMode()));
        RaftNode second = cluster.add(cluster.unconfiguredBuilder("node2", Set.of("node2"),
                new InMemoryTransportSimulator("node2"), new QraftStateStore(), RaftNodeMode.volatileMode()));

        assertEquals(first.getServerId(), UUID.fromString(first.getServerId()).toString());
        assertNotEquals(first.getServerId(), second.getServerId());
    }

    @Test
    void everyRequestAndResponseNamesItsSendersServerId() throws Exception {
        IntentionalErrors.expect(RAFT_PEER_UNREACHABLE);
        Set<String> members = Set.of("node1", "node2", "node3");
        RecordingTransport leaderTransport = new RecordingTransport(new InMemoryTransportSimulator("node1"));
        RaftNode leader = snapshottingNode("node1", members, leaderTransport);
        RaftNode node2 = snapshottingNode("node2", members, new InMemoryTransportSimulator("node2"));
        RaftNode node3 = snapshottingNode("node3", members, new InMemoryTransportSimulator("node3"));
        // node3 misses the entries the leader compacts, so it can only catch up by installing the snapshot.
        InMemoryTransportSimulator.createPartition(Set.of("node1", "node2"), Set.of("node3"));
        startAll(leader, node2, node3);
        cluster.elect(leader);
        for (int i = 0; i < 8; i++) {
            await(leader.submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put("k" + i, "v"))));
        }
        cluster.timers(leader).firePeriodic(SNAPSHOT_CHECK_MS);
        awaitTrue(() -> leader.getSnapshotLastIndex() >= SNAPSHOT_THRESHOLD, "the leader compacts its log");
        InMemoryTransportSimulator.healPartitions();
        cluster.heartbeatUntil(leader, () -> node3.getSnapshotLastIndex() > 0, "node3 installs the snapshot");

        // node3 answers after installing, so its reply may reach the leader just after the condition above held.
        awaitTrue(() -> leaderTransport.answered.stream()
                .anyMatch(exchange -> exchange.message() instanceof InstallSnapshotResponse),
                "the leader received node3's answer to the snapshot install");

        for (Class<?> kind : List.of(VoteRequest.class, AppendEntriesRequest.class, InstallSnapshotRequest.class)) {
            assertTrue(leaderTransport.sent.stream().anyMatch(exchange -> kind.isInstance(exchange.message())),
                    "the leader sent a " + kind.getSimpleName());
        }
        for (Exchange sent : leaderTransport.sent) {
            assertEquals(LEADER_ID, senderOf(sent.message()), sent.message().getClass().getSimpleName());
        }
        for (Exchange answer : leaderTransport.answered) {
            assertEquals(SERVER_IDS.get(answer.peer()), senderOf(answer.message()),
                    answer.message().getClass().getSimpleName() + " from " + answer.peer());
        }
        for (Exchange sent : leaderTransport.sent) {
            switch (sent.message()) {
                case AppendEntriesRequest request -> assertEquals(SERVER_IDS.get(sent.peer()),
                        request.getTargetServerId(), "an append names the server it is meant for");
                case InstallSnapshotRequest request -> assertEquals(SERVER_IDS.get(sent.peer()),
                        request.getTargetServerId(), "a snapshot install names the server it is meant for");
                default -> { }
            }
        }
    }

    @Test
    void aRequestMeantForAnotherServerIdIsRefusedWithoutChangingAnything() throws Exception {
        RaftNode follower = cluster.add(cluster.builder("node2", Set.of("node1", "node2"),
                new InMemoryTransportSimulator("node2"), new QraftStateStore(), RaftNodeMode.volatileMode()));
        await(follower.start());
        long term = follower.getCurrentTerm();
        long lastIndex = follower.getLastLogIndex();

        AppendEntriesResponse append = await(follower.handleAppendEntriesRequest(
                append(term + 3, 0).toBuilder().setTargetServerId("another-server").build()));
        InstallSnapshotResponse snapshot = await(follower.handleInstallSnapshot(InstallSnapshotRequest.newBuilder()
                .setTerm(term + 3).setLeaderId("node1").setLeaderServerId(LEADER_ID)
                .setTargetServerId("another-server").setLastIncludedIndex(3).setLastIncludedTerm(1)
                .setChunkIndex(0).setTotalChunks(1)
                .setData(ByteString.copyFrom(ManualRaftCluster.snapshotOf(Set.of("node1", "node2"), new byte[0])))
                .setDone(true).build()));

        assertFalse(append.getSuccess());
        assertFalse(snapshot.getSuccess());
        assertEquals(term, follower.getCurrentTerm(), "a request for another server does not move the term");
        assertEquals(lastIndex, follower.getLastLogIndex());
        assertEquals(0, follower.getSnapshotLastIndex());
        assertNull(follower.getLeaderId(), "nor does its sender become this server's leader");

        assertTrue(await(follower.handleAppendEntriesRequest(append(term + 3, 0).toBuilder()
                .setTargetServerId(follower.getServerId()).build())).getSuccess(),
                "the same request under this server's own ID is accepted");
    }

    @Test
    void refusalsAlsoNameTheRespondersServerId() throws Exception {
        String followerId = SERVER_IDS.get("node2");
        RaftNode follower = cluster.add(cluster.builder("node2", Set.of("node1", "node2"),
                new InMemoryTransportSimulator("node2"), new QraftStateStore(), RaftNodeMode.volatileMode()));
        await(follower.start());
        assertTrue(await(follower.handleAppendEntriesRequest(append(2, 0))).getSuccess(), "node2 follows in term 2");

        VoteResponse staleVote = await(follower.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(1).setCandidateId("node1").setCandidateServerId(LEADER_ID).build()));
        AppendEntriesResponse staleAppend = await(follower.handleAppendEntriesRequest(append(1, 0)));
        AppendEntriesResponse missingPrevious = await(follower.handleAppendEntriesRequest(append(2, 5)));
        InstallSnapshotResponse staleSnapshot = await(follower.handleInstallSnapshot(InstallSnapshotRequest.newBuilder()
                .setTerm(1).setLeaderId("node1").setLeaderServerId(LEADER_ID).setLastIncludedIndex(3)
                .setLastIncludedTerm(1).setChunkIndex(0).setTotalChunks(1).setData(ByteString.copyFromUtf8("x"))
                .setDone(true).build()));

        assertFalse(staleVote.getVoteGranted());
        assertFalse(staleAppend.getSuccess());
        assertFalse(missingPrevious.getSuccess());
        assertFalse(staleSnapshot.getSuccess());
        assertEquals(List.of(followerId, followerId, followerId, followerId), List.of(staleVote.getVoterServerId(),
                staleAppend.getFollowerServerId(), missingPrevious.getFollowerServerId(),
                staleSnapshot.getFollowerServerId()));
    }

    private static AppendEntriesRequest append(long term, long prevLogIndex) {
        return AppendEntriesRequest.newBuilder().setTerm(term).setLeaderId("node1").setLeaderServerId(LEADER_ID)
                .setPrevLogIndex(prevLogIndex).setPrevLogTerm(prevLogIndex == 0 ? 0 : 1).setLeaderCommit(0).build();
    }

    private RaftNode snapshottingNode(String nodeId, Set<String> members, RaftTransport transport) throws Exception {
        TestRaftStorage storage = new TestRaftStorage();
        storage.open(null).get(10, TimeUnit.SECONDS);
        return cluster.add(cluster.builder(nodeId, members, transport, new QraftStateStore(),
                        RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(true).snapshotThreshold(SNAPSHOT_THRESHOLD).snapshotCheckInterval(SNAPSHOT_CHECK_MS));
    }

    private static String senderOf(Object message) {
        return switch (message) {
            case VoteRequest request -> request.getCandidateServerId();
            case VoteResponse response -> response.getVoterServerId();
            case AppendEntriesRequest request -> request.getLeaderServerId();
            case AppendEntriesResponse response -> response.getFollowerServerId();
            case InstallSnapshotRequest request -> request.getLeaderServerId();
            case InstallSnapshotResponse response -> response.getFollowerServerId();
            default -> throw new AssertionError("not a Raft message: " + message);
        };
    }

    /** Bounds a wait for in-memory round trips; the bound only diagnoses a hang. */
    private static void awaitTrue(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), description);
    }

    /** A message the recorded node sent to {@code peer}, or received from it. */
    private record Exchange(String peer, Object message) { }

    /** Delegates to another transport, recording each request as it is sent and each response as it arrives. */
    private static final class RecordingTransport implements RaftTransport {
        private final RaftTransport delegate;
        private final List<Exchange> sent = new CopyOnWriteArrayList<>();
        private final List<Exchange> answered = new CopyOnWriteArrayList<>();

        private RecordingTransport(RaftTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public void start(Consumer<RaftMessage> messageHandler) {
            delegate.start(messageHandler);
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return record(targetId, request, delegate::sendVoteRequest);
        }

        @Override
        public Future<AppendEntriesResponse> sendAppendEntries(String targetId, AppendEntriesRequest request) {
            return record(targetId, request, delegate::sendAppendEntries);
        }

        @Override
        public Future<InstallSnapshotResponse> sendInstallSnapshot(String targetId, InstallSnapshotRequest request) {
            return record(targetId, request, delegate::sendInstallSnapshot);
        }

        private <Q, R> Future<R> record(String targetId, Q request,
                                        BiFunction<String, Q, Future<R>> send) {
            sent.add(new Exchange(targetId, request));
            Future<R> response = send.apply(targetId, request);
            response.onSuccess(result -> answered.add(new Exchange(targetId, result)));
            return response;
        }
    }
}

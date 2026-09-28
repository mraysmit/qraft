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

import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstance;
import dev.mars.qraft.catalog.ServiceKey;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.raft.storage.RaftStorageFactory;
import dev.mars.qraft.controller.raft.storage.snapshot.FileSnapshotStore;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.CatalogCommand;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.controller.state.RaftCommand;
import dev.mars.qraft.controller.state.RaftCommandResult;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.distributedstate.DistributedStateCommandCodec;
import dev.mars.raftlog.storage.RaftStorage;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link RaftNode} with real components and the in-memory network: start and stop, elections,
 * replication of catalog and key/value commands, a partitioned leader's lost write, snapshot and WAL
 * recovery, vote decisions, fencing after uncertain metadata persistence, refusals when a higher term
 * cannot be persisted, rejected vote requests, and timers cancelled on stop.
 *
 * <p>Elections happen only when a test fires a chosen node's election timeout through
 * {@link ManualRaftTimers}, and heartbeats only when it fires the leader's heartbeat, so who leads and
 * when followers learn a commit are decided by the test. Every node a test builds is tracked and
 * stopped afterwards, which also releases its storage, even when an assertion fails. Every wait is
 * bounded.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 2.3
 * @since 2025-08-20
 */
class RaftNodeTest {
    private static final Logger LOG = LoggerFactory.getLogger(RaftNodeTest.class);

    @TempDir
    Path tempDir;

    private JavaRuntime runtime;
    private ManualRaftCluster cluster;
    private RaftNode node1;
    private RaftNode node2;
    private RaftNode node3;
    private InMemoryTransportSimulator transport1;
    private QraftStateStore stateMachine1;
    private QraftStateStore stateMachine2;
    private QraftStateStore stateMachine3;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        cluster = new ManualRaftCluster(runtime);
        InMemoryTransportSimulator.clearAllTransports();
        Set<String> clusterNodes = Set.of("node1", "node2", "node3");
        transport1 = new InMemoryTransportSimulator("node1");
        stateMachine1 = new QraftStateStore();
        stateMachine2 = new QraftStateStore();
        stateMachine3 = new QraftStateStore();
        node1 = node("node1", clusterNodes, transport1, stateMachine1, RaftNodeMode.volatileMode());
        node2 = node("node2", clusterNodes, new InMemoryTransportSimulator("node2"), stateMachine2,
                RaftNodeMode.volatileMode());
        node3 = node("node3", clusterNodes, new InMemoryTransportSimulator("node3"), stateMachine3,
                RaftNodeMode.volatileMode());
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
    void aNewlyBuiltNodeIsAFollowerInTermZero() {
        assertEquals("node1", node1.getNodeId());
        assertEquals(RaftNode.State.FOLLOWER, node1.getState());
        assertEquals(0, node1.getCurrentTerm());
        assertFalse(node1.isLeader());
    }

    @Test
    void aStartedNodeFollowsWithItsTransportRunningAndStoppingItStopsTheTransport() throws Exception {
        assertFalse(transport1.isRunning());

        await(node1.start());
        assertTrue(transport1.isRunning());
        assertEquals(RaftNode.State.FOLLOWER, node1.getState());

        await(node1.stop());
        assertFalse(transport1.isRunning());
    }

    @Test
    void aSoleMemberElectsItselfWhenItsElectionTimeoutFires() throws Exception {
        RaftNode sole = node("sole", Set.of("sole"), new InMemoryTransportSimulator("sole"), new QraftStateStore(),
                RaftNodeMode.volatileMode());
        await(sole.start());
        assertEquals(RaftNode.State.FOLLOWER, sole.getState(), "a started node follows until its election timeout fires");
        assertNull(sole.getLeaderId(), "no leader is known before an election");

        elect(sole);

        assertTrue(sole.isLeader());
        assertEquals("sole", sole.getLeaderId());
        assertEquals(1, sole.getCurrentTerm());
    }

    @Test
    void aThreeMemberClusterElectsTheCandidateAndItsPeersFollowIt() throws Exception {
        startAll(node1, node2, node3);

        elect(node1);

        heartbeatUntil(node1, () -> "node1".equals(node2.getLeaderId()) && "node1".equals(node3.getLeaderId()));
        assertEquals(List.of(true, false, false), List.of(node1.isLeader(), node2.isLeader(), node3.isLeader()));
        assertEquals(1, node2.getCurrentTerm());
        assertEquals(1, node3.getCurrentTerm());
    }

    @Test
    void replicatesCatalogRegistrationAndDeregistrationAcrossThreeNodes() throws Exception {
        startAll(node1, node2, node3);
        elect(node1);
        ServiceInstance instance = new ServiceInstance("catalog-1", "catalog", "node-1",
                "127.0.0.1", 8080, List.of("v1"), Map.of(), ServiceHealth.PASSING);

        assertInstanceOf(RaftCommandResult.Success.class, await(node1.submitCommand(CatalogCommand.register(instance))));
        heartbeatUntil(node1, () -> List.of(stateMachine1, stateMachine2, stateMachine3).stream()
                .allMatch(store -> store.getServiceCatalog().instances(ServiceKey.inDefaultScope("catalog"))
                        .equals(List.of(instance))));

        assertInstanceOf(RaftCommandResult.Success.class, await(node1.submitCommand(CatalogCommand.deregister("catalog-1"))));
        heartbeatUntil(node1, () -> List.of(stateMachine1, stateMachine2, stateMachine3).stream()
                .allMatch(store -> store.getServiceCatalog().instances(ServiceKey.inDefaultScope("catalog")).isEmpty()));
    }

    @Test
    void majorityCatalogValueWinsWhenAnIsolatedLeaderHasAnUncommittedReplacement() throws Exception {
        startAll(node1, node2, node3);
        elect(node1);
        // The followers must be in node1's term before the partition, so node2's election is for term 2.
        heartbeatUntil(node1, () -> "node1".equals(node2.getLeaderId()) && "node1".equals(node3.getLeaderId()));
        InMemoryTransportSimulator.createPartition(Set.of("node1"), Set.of("node2", "node3"));
        Future<RaftCommandResult<?>> uncertainWrite = node1.submitCommand(
                CatalogCommand.register(serviceInstance("partitioned-1", 8080)));

        elect(node2);
        assertEquals(2, node2.getCurrentTerm());
        ServiceInstance winningValue = serviceInstance("partitioned-1", 9090);
        assertInstanceOf(RaftCommandResult.Success.class, await(node2.submitCommand(CatalogCommand.register(winningValue))));
        InMemoryTransportSimulator.healPartitions();

        // The old leader hears the new term from node2's heartbeat, steps down, and replaces its uncommitted
        // entry with the majority's value.
        heartbeatUntil(node2, () -> List.of(stateMachine1, stateMachine2, stateMachine3).stream()
                .allMatch(store -> store.getServiceCatalog().instances(ServiceKey.inDefaultScope("catalog"))
                        .equals(List.of(winningValue))));
        ExecutionException lost = assertThrows(ExecutionException.class,
                () -> uncertainWrite.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertInstanceOf(CommandOutcomeUnknownException.class, lost.getCause(),
                "a write the old leader could not commit has an unknown outcome");
    }

    @Test
    void recoversCatalogSnapshotAndPostSnapshotCommandsFromDurableStorage() throws Exception {
        Path storageDir = tempDir.resolve("catalog-snapshot-recovery");
        RaftNode original = durableSingleNode("catalog-durable", new QraftStateStore(), awaitPersistence(storageDir));
        await(original.start());
        elect(original);
        ServiceInstance snapshotted = serviceInstance("snapshot-1", 8080);
        ServiceInstance afterSnapshot = serviceInstance("snapshot-2", 8081);
        await(original.submitCommand(CatalogCommand.register(snapshotted)));
        await(original.takeSnapshot());
        await(original.submitCommand(CatalogCommand.register(afterSnapshot)));
        await(original.stop());

        QraftStateStore recoveredStore = new QraftStateStore();
        RaftNode recovered = durableSingleNode("catalog-durable", recoveredStore, awaitPersistence(storageDir));
        await(recovered.start());

        assertEquals(List.of(snapshotted, afterSnapshot),
                recoveredStore.getServiceCatalog().instances(ServiceKey.inDefaultScope("catalog")));
    }

    @Test
    void multiNodeRestartAppliesPreviouslyCommittedWalAfterElection() throws Exception {
        Set<String> members = Set.of("node1", "node2", "node3");
        Path node1Directory = tempDir.resolve("restart-node1");
        Path node2Directory = tempDir.resolve("restart-node2");
        Path node3Directory = tempDir.resolve("restart-node3");
        List<RaftNode> originals = List.of(durableNode("node1", members, stateMachine1, node1Directory),
                durableNode("node2", members, stateMachine2, node2Directory),
                durableNode("node3", members, stateMachine3, node3Directory));
        startAll(originals.toArray(RaftNode[]::new));
        RaftNode leader = elect(originals.getFirst());
        await(leader.submitCommand(distributedPut("restart-key", "durable-value")));
        heartbeatUntil(leader, () -> List.of(stateMachine1, stateMachine2, stateMachine3).stream()
                .allMatch(store -> "durable-value".equals(store.getMetadata("restart-key"))));

        for (RaftNode original : originals) await(original.stop());
        InMemoryTransportSimulator.clearAllTransports();
        List<QraftStateStore> recoveredStores = List.of(new QraftStateStore(), new QraftStateStore(),
                new QraftStateStore());
        List<RaftNode> recovered = List.of(durableNode("node1", members, recoveredStores.get(0), node1Directory),
                durableNode("node2", members, recoveredStores.get(1), node2Directory),
                durableNode("node3", members, recoveredStores.get(2), node3Directory));
        startAll(recovered.toArray(RaftNode[]::new));
        assertTrue(recoveredStores.stream().allMatch(store -> store.getMetadata("restart-key") == null),
                "a multi-member node applies nothing before a leader establishes the commit");

        RaftNode newLeader = elect(recovered.getFirst());
        heartbeatUntil(newLeader, () -> recoveredStores.stream()
                .allMatch(store -> "durable-value".equals(store.getMetadata("restart-key"))));
    }

    @Test
    void replaysMixedLegacyJsonAndProtobufWalEntries() throws Exception {
        Path storageDir = tempDir.resolve("catalog-mixed-codec-recovery");
        RaftStorageFactory.DurableStorage writerStorage = awaitPersistence(storageDir);
        ServiceInstance instance = serviceInstance("mixed-1", 8080);
        try {
            byte[] legacy = new DistributedStateCommandCodec()
                    .serialize(DistributedStateCommand.put("legacy-key", "legacy-value"));
            byte[] protobuf = new ProtobufRaftCommandCodec().serialize(CatalogCommand.register(instance));
            writerStorage.wal().appendEntries(List.of(new RaftStorage.LogEntryData(1, 1, legacy),
                            new RaftStorage.LogEntryData(2, 1, protobuf)))
                    .thenCompose(ignored -> writerStorage.wal().sync()).get(10, TimeUnit.SECONDS);
        } finally {
            writerStorage.snapshots().close();
            writerStorage.wal().close();
        }

        QraftStateStore recoveredStore = new QraftStateStore();
        RaftNode recovered = durableSingleNode("catalog-mixed", recoveredStore, awaitPersistence(storageDir));
        await(recovered.start());

        assertEquals("legacy-value", recoveredStore.getMetadata("legacy-key"));
        assertEquals(List.of(instance), recoveredStore.getServiceCatalog().instances(ServiceKey.inDefaultScope("catalog")));
    }

    @Test
    void aFollowerRefusesToAcceptACommand() throws Exception {
        await(node1.start());

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> node1.submitCommand(distributedPut("test-key", "test-value"))
                        .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));

        assertInstanceOf(IllegalStateException.class, refused.getCause());
        assertTrue(refused.getCause().getMessage().contains("Not the leader"), refused.getCause().getMessage());
    }

    @Test
    void theInMemoryTransportDeliversAVoteRequest() throws Exception {
        transport1.start(message -> { });
        InMemoryTransportSimulator transport2 = new InMemoryTransportSimulator("node2-transport-only");
        transport2.start(message -> { });

        VoteResponse response = await(transport1.sendVoteRequest("node2-transport-only", VoteRequest.newBuilder()
                .setTerm(1).setCandidateId("node1").setLastLogIndex(0).setLastLogTerm(0).build()));

        assertEquals(1, response.getTerm());
        transport2.stop();
    }

    @Test
    void aDurableElectionPersistsTermAndSelfVote() throws Exception {
        TestRaftStorage storage = new TestRaftStorage();
        storage.open(null).get(10, TimeUnit.SECONDS);
        RaftNode sole = node("node1", Set.of("node1"), new InMemoryTransportSimulator("node1"),
                new QraftStateStore(), RaftNodeMode.durable(storage, storage));
        await(sole.start());

        elect(sole);

        assertEquals(1, storage.getCurrentTerm(), "the election term is persisted");
        assertEquals(Optional.of("node1"), storage.getVotedFor(), "the self vote is persisted");
    }

    @Test
    void aRecoveredMultiMemberFollowerKeepsButDoesNotApplyAnUncommittedLogTail() throws Exception {
        TestRaftStorage storage = new TestRaftStorage();
        storage.open(null).get(10, TimeUnit.SECONDS);
        byte[] payload = new ProtobufRaftCommandCodec().serialize(distributedPut("recovery-key", "tail-value"));
        storage.appendEntries(List.of(new LogEntryData(1L, 1L, payload))).get(10, TimeUnit.SECONDS);
        storage.updateMetadata(1L, Optional.empty()).get(10, TimeUnit.SECONDS);
        QraftStateStore recoveredState = new QraftStateStore();
        RaftNode recovered = node("node1", Set.of("node1", "node2", "node3"), new InMemoryTransportSimulator("node1"),
                recoveredState, RaftNodeMode.durable(storage, storage));

        await(recovered.start());

        // Recovery completes before start does, and no election runs, so nothing else can apply the tail.
        assertNull(recoveredState.getMetadata("recovery-key"),
                "a recovered follower must not apply an uncertain log tail before a leader commits it");
        assertEquals(1, recovered.getLastLogIndex(), "the tail is kept, only not applied");
    }

    @Test
    void aRecoveredSoleMemberAppliesItsWholeLocalLogBeforeStartCompletes() throws Exception {
        TestRaftStorage storage = new TestRaftStorage();
        storage.open(null).get(10, TimeUnit.SECONDS);
        byte[] payload = new ProtobufRaftCommandCodec().serialize(distributedPut("single-recovery-key", "single-value"));
        storage.appendEntries(List.of(new LogEntryData(1L, 1L, payload))).get(10, TimeUnit.SECONDS);
        storage.updateMetadata(1L, Optional.of("node1")).get(10, TimeUnit.SECONDS);
        QraftStateStore recoveredState = new QraftStateStore();
        RaftNode recovered = node("node1", Set.of("node1"), new InMemoryTransportSimulator("node1"),
                recoveredState, RaftNodeMode.durable(storage, storage));

        await(recovered.start());

        assertEquals("single-value", recoveredState.getMetadata("single-recovery-key"),
                "a sole member's whole log is committed, so recovery applies it before start completes");
    }

    @Test
    void durableModeRecoversFromExternalWalAndSeparateSnapshotStore() throws Exception {
        Path storageDirectory = tempDir.resolve("direct-external-storage");
        var wal = new dev.mars.raftlog.storage.FileRaftStorage(dev.mars.raftlog.storage.RaftStorageConfig.builder()
                .dataDir(storageDirectory).syncEnabled(true).build());
        var snapshots = new FileSnapshotStore();
        wal.open(storageDirectory).get(10, TimeUnit.SECONDS);
        snapshots.open(storageDirectory).get(10, TimeUnit.SECONDS);
        QraftStateStore snapshottedState = new QraftStateStore();
        snapshottedState.apply(distributedPut("before-snapshot", "preserved"));
        snapshots.saveAtomically(new dev.mars.qraft.raft.api.SnapshotStore.SnapshotData(
                snapshottedState.takeSnapshot(), 1L, 1L)).get(10, TimeUnit.SECONDS);
        byte[] postSnapshotCommand = new ProtobufRaftCommandCodec().serialize(distributedPut("after-snapshot", "replayed"));
        wal.truncatePrefix(1L).get(10, TimeUnit.SECONDS);
        wal.appendEntries(List.of(new RaftStorage.LogEntryData(2L, 1L, postSnapshotCommand))).get(10, TimeUnit.SECONDS);
        wal.sync().get(10, TimeUnit.SECONDS);
        QraftStateStore recoveredState = new QraftStateStore();
        RaftNode recovered = node("node1", Set.of("node1"), new InMemoryTransportSimulator("node1"),
                recoveredState, RaftNodeMode.durable(wal, snapshots));

        await(recovered.start());

        assertEquals("preserved", recoveredState.getMetadata("before-snapshot"));
        assertEquals("replayed", recoveredState.getMetadata("after-snapshot"));
        assertEquals(1L, recovered.getSnapshotLastIndex());
    }

    @Test
    void appendConflictPlanningUsesIndicesRelativeToTheSnapshotBoundary() throws Exception {
        TestRaftStorage storage = new TestRaftStorage();
        storage.open(tempDir.resolve("compacted-conflict")).get(10, TimeUnit.SECONDS);
        ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();
        storage.saveAtomically(new dev.mars.qraft.raft.api.SnapshotStore.SnapshotData(
                new QraftStateStore().takeSnapshot(), 5L, 2L)).get(10, TimeUnit.SECONDS);
        storage.appendEntries(List.of(
                new LogEntryData(6, 2, codec.serialize(distributedPut("six", "old"))),
                new LogEntryData(7, 2, codec.serialize(distributedPut("seven", "old"))),
                new LogEntryData(8, 3, codec.serialize(distributedPut("eight", "old-suffix"))))).get(10, TimeUnit.SECONDS);
        RaftNode follower = node("node1", Set.of("node1", "leader"), new InMemoryTransportSimulator("compacted-follower"),
                new QraftStateStore(), RaftNodeMode.durable(storage, storage));
        await(follower.start());

        AppendEntriesResponse response = await(follower.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                .setTerm(3).setLeaderId("leader")
                .setPrevLogIndex(5).setPrevLogTerm(2)
                .addEntries(grpcEntry(2, codec.serialize(distributedPut("six", "old"))))
                .addEntries(grpcEntry(3, codec.serialize(distributedPut("seven", "replacement"))))
                .addEntries(grpcEntry(3, codec.serialize(distributedPut("eight", "new"))))
                .build()));

        assertTrue(response.getSuccess());
        assertEquals(List.of(6L, 7L, 8L), storage.getLog().stream().map(LogEntryData::index).toList());
        assertEquals(List.of(2L, 3L, 3L), storage.getLog().stream().map(LogEntryData::term).toList());
    }

    @Test
    void aVoteIsRefusedToACandidateWhoseLogIsBehind() throws Exception {
        RaftNode sole = node("node1", Set.of("node1"), new InMemoryTransportSimulator("node1"), new QraftStateStore(),
                RaftNodeMode.volatileMode());
        await(sole.start());
        elect(sole);
        assertInstanceOf(RaftCommandResult.Success.class,
                await(sole.submitCommand(distributedPut("vote-log-key", "vote-log-value"))));

        VoteResponse response = await(sole.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(sole.getCurrentTerm() + 1).setCandidateId("candidate-behind")
                .setLastLogTerm(0).setLastLogIndex(0).build()));

        assertFalse(response.getVoteGranted(), "a vote is refused to a candidate whose log is behind");
    }

    @Test
    void stoppingAFollowerCancelsItsElectionTimer() throws Exception {
        await(node1.start());
        ManualRaftTimers timers = cluster.timers(node1);
        assertEquals(1, timers.oneShotCount(), "a follower waits on an election timer");

        await(node1.stop());

        assertFalse(node1.isRunning());
        assertEquals(0, timers.oneShotCount(), "no election timer is left armed");
    }

    @Test
    void stoppingALeaderCancelsItsHeartbeat() throws Exception {
        RaftNode sole = node("sole", Set.of("sole"), new InMemoryTransportSimulator("sole"), new QraftStateStore(),
                RaftNodeMode.volatileMode());
        await(sole.start());
        elect(sole);
        ManualRaftTimers timers = cluster.timers(sole);
        assertTrue(timers.hasPeriodic(ManualRaftCluster.HEARTBEAT_MS), "a leader sends heartbeats");

        await(sole.stop());

        assertFalse(sole.isRunning());
        assertFalse(timers.hasPeriodic(ManualRaftCluster.HEARTBEAT_MS), "no heartbeat is left scheduled");
    }

    @Test
    void aVoteRequestNamingNoCandidateIsRejectedWithoutSpendingTheVote() throws Exception {
        RaftNode sole = node("node1", Set.of("node1"), new InMemoryTransportSimulator("node1"), new QraftStateStore(),
                RaftNodeMode.volatileMode());
        await(sole.start());

        for (String blank : List.of("", " ")) {
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> await(sole.handleVoteRequest(
                    VoteRequest.newBuilder().setTerm(1).setCandidateId(blank).build())));
            assertInstanceOf(IllegalArgumentException.class, rejected.getCause());
            assertEquals("A vote request must name its candidate", rejected.getCause().getMessage());
        }
        assertEquals(0, sole.getCurrentTerm(), "a rejected request does not advance the term");

        VoteResponse real = await(sole.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(1).setCandidateId("candidate").setLastLogTerm(0).setLastLogIndex(0).build()));
        assertTrue(real.getVoteGranted(), "the term's vote is still free for a real candidate");
        assertEquals(1, real.getTerm());
    }

    @Test
    void aFailedVotePersistenceFencesTheNodeAgainstAnyFurtherMetadataWrite() throws Exception {
        TestRaftStorage delegate = new TestRaftStorage();
        MetadataFailureStorage flakyMetadataStorage = MetadataFailureStorage.failFirstUpdate(delegate);
        flakyMetadataStorage.open(null).get(10, TimeUnit.SECONDS);
        RaftNode durableNode = node("node1", Set.of("node1"), new InMemoryTransportSimulator("node1"),
                new QraftStateStore(), RaftNodeMode.durable(flakyMetadataStorage, delegate));
        await(durableNode.start());
        VoteRequest voteRequest = VoteRequest.newBuilder()
                .setTerm(7).setCandidateId("candidate-x").setLastLogIndex(99).setLastLogTerm(99).build();

        ExecutionException persistenceFailure = assertThrows(ExecutionException.class,
                () -> durableNode.handleVoteRequest(voteRequest).toCompletionStage().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS));

        assertEquals("Simulated one-shot metadata failure", persistenceFailure.getCause().getMessage());
        RaftStorage.PersistentMeta persistedMeta = flakyMetadataStorage.loadMetadata().get(10, TimeUnit.SECONDS);
        assertEquals(0, persistedMeta.currentTerm(),
                "a failed write must not be followed by an unsafely assumed term-only write");
        assertEquals(Optional.empty(), persistedMeta.votedFor());
        assertEquals(1, flakyMetadataStorage.updateCalls(),
                "the node must not issue a compensating metadata write after an uncertain failure");
        ExecutionException fenced = assertThrows(ExecutionException.class,
                () -> durableNode.handleVoteRequest(voteRequest.toBuilder().setCandidateId("candidate-y").build())
                        .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertInstanceOf(RaftTransitionSequencer.FencedException.class, fenced.getCause());
        assertEquals(1, flakyMetadataStorage.updateCalls(), "a fenced node must not execute a later metadata transition");
        logExpectedFailure("vote metadata persistence fenced node", persistenceFailure.getCause());
    }

    @Test
    void aHigherTermCandidateWithAStaleLogIsRefusedAndItsTermSurvivesRestart() throws Exception {
        Path storageDir = tempDir.resolve("vote-reject-higher-term");
        RaftNode durableNode = durableSingleNode("node1", new QraftStateStore(), awaitPersistence(storageDir));
        await(durableNode.start());
        elect(durableNode);
        assertInstanceOf(RaftCommandResult.Success.class,
                await(durableNode.submitCommand(distributedPut("vote-log-key", "vote-log-value"))));
        long higherTerm = durableNode.getCurrentTerm() + 5;

        VoteResponse response = await(durableNode.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(higherTerm).setCandidateId("candidate-behind").setLastLogTerm(0).setLastLogIndex(0).build()));

        assertFalse(response.getVoteGranted(), "a vote is refused to a candidate whose log is behind");
        assertEquals(higherTerm, response.getTerm(), "the node reports the higher term it observed");
        await(durableNode.stop());
        RaftNode recovered = durableSingleNode("node1", new QraftStateStore(), awaitPersistence(storageDir));
        await(recovered.start());
        assertEquals(higherTerm, recovered.getCurrentTerm(),
                "the observed higher term is durable after refusing the stale candidate, so the term never regresses");
    }

    @Test
    void aHigherTermCandidateWithAStaleLogIsRefusedAndItsTermIsPersistedWithNoVote() throws Exception {
        Path storageDir = tempDir.resolve("vote-reject-higher-term-empty-vote");
        RaftNode durableNode = durableSingleNode("node1", new QraftStateStore(), awaitPersistence(storageDir));
        await(durableNode.start());
        elect(durableNode);
        assertInstanceOf(RaftCommandResult.Success.class,
                await(durableNode.submitCommand(distributedPut("vote-log-key-2", "vote-log-value-2"))));
        long higherTerm = durableNode.getCurrentTerm() + 7;

        VoteResponse response = await(durableNode.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(higherTerm).setCandidateId("candidate-behind-2").setLastLogTerm(0).setLastLogIndex(0).build()));

        assertFalse(response.getVoteGranted());
        assertEquals(higherTerm, response.getTerm());
        await(durableNode.stop());
        RaftStorageFactory.DurableStorage recoveryStorage = awaitPersistence(storageDir);
        try {
            RaftStorage.PersistentMeta persistedMeta = recoveryStorage.wal().loadMetadata().get(10, TimeUnit.SECONDS);
            assertEquals(higherTerm, persistedMeta.currentTerm(),
                    "refusing a higher-term stale candidate still persists the observed term");
            assertEquals(Optional.empty(), persistedMeta.votedFor(), "no candidate is persisted when the vote is refused");
        } finally {
            recoveryStorage.snapshots().close();
            recoveryStorage.wal().close();
        }
    }

    @Test
    void aHigherTermCandidateWithAStaleLogGetsAFailureAndNoDurableTermOrVoteWhenTheTermWriteFails() throws Exception {
        TestRaftStorage delegate = new TestRaftStorage();
        final long higherTerm = 5;
        MetadataFailureStorage flakyMetadataStorage = MetadataFailureStorage.failTermWithEmptyVote(delegate, higherTerm);
        flakyMetadataStorage.open(null).get(10, TimeUnit.SECONDS);
        RaftNode durableNode = node("node1", Set.of("node1"), new InMemoryTransportSimulator("node1"),
                new QraftStateStore(), RaftNodeMode.durable(flakyMetadataStorage, delegate));
        await(durableNode.start());
        elect(durableNode);
        assertInstanceOf(RaftCommandResult.Success.class,
                await(durableNode.submitCommand(distributedPut("stale-check-key", "stale-check-value"))));

        ExecutionException voteFailure = assertThrows(ExecutionException.class,
                () -> durableNode.handleVoteRequest(VoteRequest.newBuilder()
                                .setTerm(higherTerm).setCandidateId("candidate-behind-failing-persist")
                                .setLastLogTerm(0).setLastLogIndex(0).build())
                        .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));

        assertInstanceOf(IllegalStateException.class, voteFailure.getCause());
        logExpectedFailure("vote-rejection higher-term metadata persistence", voteFailure.getCause());
        assertTrue(flakyMetadataStorage.hasFailed(), "the higher-term empty-vote write was the one that failed");
        RaftStorage.PersistentMeta persistedMeta = flakyMetadataStorage.loadMetadata().get(10, TimeUnit.SECONDS);
        assertTrue(persistedMeta.currentTerm() < higherTerm,
                "a failed write does not durably advance the term to the observed value");
        assertNotEquals(Optional.of("candidate-behind-failing-persist"), persistedMeta.votedFor(),
                "refusing a stale candidate with a failed metadata write never persists a vote for it");
    }

    @Test
    void anAppendIsRefusedWithTheDurableTermWhenItsHigherTermCannotBePersisted() throws Exception {
        TestRaftStorage delegate = new TestRaftStorage();
        MetadataFailureStorage flakyMetadataStorage = MetadataFailureStorage.failFirstUpdate(delegate);
        flakyMetadataStorage.open(null).get(10, TimeUnit.SECONDS);
        RaftNode durableNode = node("node1", Set.of("node1"), new InMemoryTransportSimulator("node1"),
                new QraftStateStore(), RaftNodeMode.durable(flakyMetadataStorage, delegate));
        await(durableNode.start());

        AppendEntriesResponse response = await(durableNode.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                .setTerm(9).setLeaderId("leader-x").setPrevLogIndex(0).setPrevLogTerm(0).setLeaderCommit(0).build()));

        assertFalse(response.getSuccess(), "an append is refused unless the higher term is durably persisted first");
        assertEquals(0, response.getTerm(), "a refusal reports the last durable local term, not the observed one");
        assertEquals(0, flakyMetadataStorage.loadMetadata().get(10, TimeUnit.SECONDS).currentTerm());
        logExpectedFailure("append-entries higher-term metadata persistence",
                new IllegalStateException("Injected one-shot metadata failure expected by test"));
    }

    @Test
    void aSnapshotInstallIsRefusedWithTheDurableTermWhenItsHigherTermCannotBePersisted() throws Exception {
        TestRaftStorage delegate = new TestRaftStorage();
        MetadataFailureStorage flakyMetadataStorage = MetadataFailureStorage.failFirstUpdate(delegate);
        flakyMetadataStorage.open(null).get(10, TimeUnit.SECONDS);
        RaftNode durableNode = node("node1", Set.of("node1"), new InMemoryTransportSimulator("node1"),
                new QraftStateStore(), RaftNodeMode.durable(flakyMetadataStorage, delegate));
        await(durableNode.start());

        InstallSnapshotResponse response = await(durableNode.handleInstallSnapshot(InstallSnapshotRequest.newBuilder()
                .setTerm(11).setLeaderId("leader-y").setLastIncludedIndex(1).setLastIncludedTerm(1)
                .setChunkIndex(0).setTotalChunks(1)
                .setData(com.google.protobuf.ByteString.copyFrom(new byte[]{1})).setDone(false).build()));

        assertFalse(response.getSuccess(), "an installation is refused unless the higher term is durably persisted first");
        assertEquals(0, response.getTerm(), "a refusal reports the last durable local term, not the observed one");
        assertEquals(0, flakyMetadataStorage.loadMetadata().get(10, TimeUnit.SECONDS).currentTerm());
        logExpectedFailure("install-snapshot higher-term metadata persistence",
                new IllegalStateException("Injected one-shot metadata failure expected by test"));
    }

    // ---------------------------------------------------------------------------------------------------

    private static void logExpectedFailure(String scenario, Throwable failure) {
        LOG.info("[EXPECTED-TEST-FAILURE] Scenario={} message={}", scenario, failure.getMessage());
    }

    private static final class MetadataFailureStorage implements RaftStorage {
        private final TestRaftStorage delegate;
        private final Long targetedTerm;
        private final boolean failFirst;
        private int calls;
        private boolean failed;

        private MetadataFailureStorage(TestRaftStorage delegate, Long targetedTerm, boolean failFirst) {
            this.delegate = delegate;
            this.targetedTerm = targetedTerm;
            this.failFirst = failFirst;
        }

        static MetadataFailureStorage failFirstUpdate(TestRaftStorage delegate) {
            return new MetadataFailureStorage(delegate, null, true);
        }

        static MetadataFailureStorage failTermWithEmptyVote(TestRaftStorage delegate, long term) {
            return new MetadataFailureStorage(delegate, term, false);
        }

        boolean hasFailed() { return failed; }

        int updateCalls() { return calls; }

        @Override public CompletableFuture<Void> open(Path dataDir) { return delegate.open(dataDir); }

        @Override
        public CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor) {
            calls++;
            boolean shouldFail = !failed && ((failFirst && calls == 1)
                    || (targetedTerm != null && targetedTerm == term && votedFor.isEmpty()));
            if (shouldFail) {
                failed = true;
                return CompletableFuture.failedFuture(new IllegalStateException(
                        failFirst ? "Simulated one-shot metadata failure"
                                : "Simulated targeted metadata failure"));
            }
            return delegate.updateMetadata(term, votedFor);
        }

        @Override public CompletableFuture<PersistentMeta> loadMetadata() { return delegate.loadMetadata(); }
        @Override public CompletableFuture<Void> appendEntries(List<LogEntryData> entries) { return delegate.appendEntries(entries); }
        @Override public CompletableFuture<Void> truncateSuffix(long fromIndex) { return delegate.truncateSuffix(fromIndex); }
        @Override public CompletableFuture<Void> truncatePrefix(long toIndex) { return delegate.truncatePrefix(toIndex); }
        @Override public CompletableFuture<Void> sync() { return delegate.sync(); }
        @Override public CompletableFuture<List<LogEntryData>> replayLog() { return delegate.replayLog(); }
        @Override public void close() { delegate.close(); }
        @Override public CompletableFuture<Void> closeAsync() { return delegate.closeAsync(); }
    }

    /** Builds a node on manual timers; the cluster stops it (releasing its storage) after the test. */
    private RaftNode node(String nodeId, Set<String> members, RaftTransport transport, QraftStateStore store,
                          RaftNodeMode mode) {
        return cluster.add(cluster.builder(nodeId, members, transport, store, mode));
    }

    private RaftNode durableSingleNode(String nodeId, QraftStateStore store, RaftStorageFactory.DurableStorage storage) {
        return node(nodeId, Set.of(nodeId), new InMemoryTransportSimulator(nodeId), store,
                RaftNodeMode.durable(storage.wal(), storage.snapshots()));
    }

    private RaftNode durableNode(String nodeId, Set<String> members, QraftStateStore store, Path storageDirectory)
            throws Exception {
        RaftStorageFactory.DurableStorage storage = awaitPersistence(storageDirectory);
        return cluster.add(cluster.builder(nodeId, members, new InMemoryTransportSimulator(nodeId), store,
                RaftNodeMode.durable(storage.wal(), storage.snapshots())).snapshotEnabled(false));
    }

    private static void startAll(RaftNode... nodes) throws Exception {
        ManualRaftCluster.startAll(nodes);
    }

    private RaftNode elect(RaftNode candidate) throws Exception {
        return cluster.elect(candidate);
    }

    private void heartbeatUntil(RaftNode leader, BooleanSupplier condition) throws InterruptedException {
        cluster.heartbeatUntil(leader, condition, "the cluster converges");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return ManualRaftCluster.await(future);
    }

    private static RaftStorageFactory.DurableStorage awaitPersistence(Path directory) throws Exception {
        return RaftStorageFactory.createDurable(directory, true).toCompletionStage().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    private static ServiceInstance serviceInstance(String serviceId, int port) {
        return new ServiceInstance(serviceId, "catalog", "node-1", "127.0.0.1", port,
                List.of("v1"), Map.of("team", "platform"), ServiceHealth.PASSING);
    }

    private static RaftCommand distributedPut(String key, String value) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, value));
    }

    private static dev.mars.qraft.controller.raft.grpc.LogEntry grpcEntry(long term, byte[] data) {
        return dev.mars.qraft.controller.raft.grpc.LogEntry.newBuilder()
                .setTerm(term)
                .setData(com.google.protobuf.ByteString.copyFrom(data))
                .build();
    }
}

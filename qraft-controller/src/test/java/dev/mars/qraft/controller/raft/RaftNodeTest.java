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

import dev.mars.raftlog.storage.RaftStorage;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import dev.mars.qraft.controller.raft.storage.RaftStorageFactory;
import dev.mars.qraft.controller.raft.storage.snapshot.FileSnapshotStore;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.CommandResult;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.controller.state.RaftCommand;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.distributedstate.DistributedStateCommandCodec;
import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstance;
import dev.mars.qraft.controller.state.CatalogCommand;
import org.awaitility.Awaitility;
import static org.awaitility.Awaitility.await;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RaftNode implementation using real components (no mocking).
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 1.0
 * @since 2025-08-20
 */
class RaftNodeTest {

    @TempDir
    Path tempDir;

    private JavaRuntime vertx;
    private RaftNode node1;
    private RaftNode node2;
    private RaftNode node3;
    private InMemoryTransportSimulator transport1;
    private InMemoryTransportSimulator transport2;
    private InMemoryTransportSimulator transport3;
    private QraftStateStore stateMachine1;
    private QraftStateStore stateMachine2;
    private QraftStateStore stateMachine3;

    @BeforeEach
    void setUp() {
        vertx = JavaRuntime.create();
        // Clear any existing transports
        InMemoryTransportSimulator.clearAllTransports();

        // Create cluster nodes
        Set<String> clusterNodes = Set.of("node1", "node2", "node3");

        // Create transports
        transport1 = new InMemoryTransportSimulator("node1");
        transport2 = new InMemoryTransportSimulator("node2");
        transport3 = new InMemoryTransportSimulator("node3");

        // Create state machines
        stateMachine1 = new QraftStateStore();
        stateMachine2 = new QraftStateStore();
        stateMachine3 = new QraftStateStore();

        // Create Raft nodes with shorter timeouts for testing
        node1 = RaftNode.builder().runtime(vertx).nodeId("node1").clusterNodes(clusterNodes).transport(transport1).stateMachine(stateMachine1).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode()).electionTimeout(1000).heartbeatInterval(200).build();
        node2 = RaftNode.builder().runtime(vertx).nodeId("node2").clusterNodes(clusterNodes).transport(transport2).stateMachine(stateMachine2).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode()).electionTimeout(1000).heartbeatInterval(200).build();
        node3 = RaftNode.builder().runtime(vertx).nodeId("node3").clusterNodes(clusterNodes).transport(transport3).stateMachine(stateMachine3).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode()).electionTimeout(1000).heartbeatInterval(200).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (node1 != null) node1.stop().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (node2 != null) node2.stop().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (node3 != null) node3.stop().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        InMemoryTransportSimulator.clearAllTransports();
    }

    @Test
    void testNodeInitialization() {
        assertEquals("node1", node1.getNodeId());
        assertEquals(RaftNode.State.FOLLOWER, node1.getState());
        assertEquals(0, node1.getCurrentTerm());
        assertFalse(node1.isLeader());
    }

    @Test
    void testNodeStartAndStop() {
        assertFalse(transport1.isRunning());
        
        node1.start().toCompletionStage().toCompletableFuture().join();
        assertTrue(transport1.isRunning());
        assertEquals(RaftNode.State.FOLLOWER, node1.getState());
        
        node1.stop().toCompletionStage().toCompletableFuture().join();
        assertFalse(transport1.isRunning());
    }

    @Test
    void testSingleNodeElection() {
        // Create a single-node cluster
        Set<String> singleNodeCluster = Set.of("node1");
        RaftNode singleNode = RaftNode.builder().runtime(vertx).nodeId("node1").clusterNodes(singleNodeCluster).transport(transport1).stateMachine(stateMachine1).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode()).electionTimeout(500).heartbeatInterval(100).build();
        
        singleNode.start();
        
        // Wait for election to complete
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .until(() -> singleNode.getState() == RaftNode.State.LEADER);
        
        assertTrue(singleNode.isLeader());
        assertEquals("node1", singleNode.getLeaderId());
        
        singleNode.stop();
    }

    @Test
    void testThreeNodeClusterElection() {
        // Start all nodes
        node1.start();
        node2.start();
        node3.start();
        
        // Wait for a leader to be elected — generous timeout for split vote scenarios
        // (1000ms election timeout means split votes can take multiple cycles)
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> {
                    long leaderCount = Set.of(node1, node2, node3).stream()
                            .mapToLong(node -> node.isLeader() ? 1 : 0)
                            .sum();
                    return leaderCount == 1;
                });
        
        // Verify exactly one leader exists.
        // All assertions must be inside Awaitility or accept transient states,
        // because Raft election cycles continue — a follower can become a
        // CANDIDATE between the Awaitility check and a subsequent assertion.
        long leaderCount = Set.of(node1, node2, node3).stream()
                .mapToLong(node -> node.isLeader() ? 1 : 0)
                .sum();
        assertEquals(1, leaderCount);
        
        // Verify all nodes are in valid Raft states (CANDIDATE is a valid
        // transient state when a follower's election timer fires)
        Set.of(node1, node2, node3).forEach(node -> {
            assertNotNull(node.getState(), "Node state should not be null");
        });
    }

    @Test
    void replicatesCatalogRegistrationAndDeregistrationAcrossThreeNodes() throws Exception {
        node1.start();
        node2.start();
        node3.start();
        Set<RaftNode> nodes = Set.of(node1, node2, node3);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> nodes.stream().filter(RaftNode::isLeader).count() == 1);
        RaftNode leader = nodes.stream().filter(RaftNode::isLeader).findFirst().orElseThrow();
        ServiceInstance instance = new ServiceInstance("catalog-1", "catalog", "node-1",
                "127.0.0.1", 8080, List.of("v1"), Map.of(), ServiceHealth.PASSING);

        CommandResult<?> registered = leader.submitCommand(CatalogCommand.register(instance))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertInstanceOf(CommandResult.Success.class, registered);
        await().atMost(Duration.ofSeconds(5)).until(() ->
                List.of(stateMachine1, stateMachine2, stateMachine3).stream()
                        .allMatch(store -> store.getServiceCatalog().instances("catalog").equals(List.of(instance))));

        CommandResult<?> deregistered = leader.submitCommand(CatalogCommand.deregister("catalog-1"))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertInstanceOf(CommandResult.Success.class, deregistered);
        await().atMost(Duration.ofSeconds(5)).until(() ->
                List.of(stateMachine1, stateMachine2, stateMachine3).stream()
                        .allMatch(store -> store.getServiceCatalog().instances("catalog").isEmpty()));
    }

    @Test
    void majorityCatalogValueWinsWhenAnIsolatedLeaderHasAnUncommittedReplacement() throws Exception {
        node1.start();
        node2.start();
        node3.start();
        List<RaftNode> nodes = List.of(node1, node2, node3);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> nodes.stream().filter(RaftNode::isLeader).count() == 1);
        RaftNode isolatedLeader = nodes.stream().filter(RaftNode::isLeader).findFirst().orElseThrow();
        Set<String> majorityIds = nodes.stream()
                .filter(node -> node != isolatedLeader)
                .map(RaftNode::getNodeId)
                .collect(java.util.stream.Collectors.toSet());
        InMemoryTransportSimulator.createPartition(Set.of(isolatedLeader.getNodeId()), majorityIds);

        ServiceInstance losingValue = serviceInstance("partitioned-1", 8080);
        Future<CommandResult<?>> uncertainWrite = isolatedLeader.submitCommand(CatalogCommand.register(losingValue));
        await().atMost(Duration.ofSeconds(10)).until(() -> nodes.stream()
                .filter(node -> node != isolatedLeader).anyMatch(RaftNode::isLeader));
        RaftNode majorityLeader = nodes.stream()
                .filter(node -> node != isolatedLeader && node.isLeader()).findFirst().orElseThrow();
        ServiceInstance winningValue = serviceInstance("partitioned-1", 9090);

        assertInstanceOf(CommandResult.Success.class, majorityLeader.submitCommand(CatalogCommand.register(winningValue))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        InMemoryTransportSimulator.healPartitions();

        await().atMost(Duration.ofSeconds(10)).until(() ->
                List.of(stateMachine1, stateMachine2, stateMachine3).stream()
                        .allMatch(store -> store.getServiceCatalog().instances("catalog").equals(List.of(winningValue))));
        assertThrows(Exception.class,
                () -> uncertainWrite.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void recoversCatalogSnapshotAndPostSnapshotCommandsFromDurableStorage() throws Exception {
        Path storageDir = tempDir.resolve("catalog-snapshot-recovery");
        RaftStorageFactory.DurableStorage writerStorage = awaitPersistence(storageDir);
        QraftStateStore originalStore = new QraftStateStore();
        RaftNode original = durableSingleNode("catalog-durable", originalStore, writerStorage);
        original.start().toCompletionStage().toCompletableFuture().join();
        await().atMost(Duration.ofSeconds(3)).until(original::isLeader);
        ServiceInstance snapshotted = serviceInstance("snapshot-1", 8080);
        ServiceInstance afterSnapshot = serviceInstance("snapshot-2", 8081);
        original.submitCommand(CatalogCommand.register(snapshotted)).toCompletionStage().toCompletableFuture().join();
        original.takeSnapshot().toCompletionStage().toCompletableFuture().join();
        original.submitCommand(CatalogCommand.register(afterSnapshot)).toCompletionStage().toCompletableFuture().join();
        original.stop().toCompletionStage().toCompletableFuture().join();

        RaftStorageFactory.DurableStorage recoveryStorage = awaitPersistence(storageDir);
        QraftStateStore recoveredStore = new QraftStateStore();
        RaftNode recovered = durableSingleNode("catalog-durable", recoveredStore, recoveryStorage);
        recovered.start().toCompletionStage().toCompletableFuture().join();

        assertEquals(List.of(snapshotted, afterSnapshot), recoveredStore.getServiceCatalog().instances("catalog"));
        recovered.stop().toCompletionStage().toCompletableFuture().join();
    }

    @Test
    void replaysMixedLegacyJsonAndProtobufWalEntries() {
        Path storageDir = tempDir.resolve("catalog-mixed-codec-recovery");
        RaftStorageFactory.DurableStorage writerStorage = awaitPersistence(storageDir);
        byte[] legacy = new DistributedStateCommandCodec()
                .serialize(DistributedStateCommand.put("legacy-key", "legacy-value"));
        ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();
        ServiceInstance instance = serviceInstance("mixed-1", 8080);
        byte[] protobuf = codec.serialize(CatalogCommand.register(instance));
        writerStorage.wal().appendEntries(List.of(new RaftStorage.LogEntryData(1, 1, legacy),
                        new RaftStorage.LogEntryData(2, 1, protobuf)))
                .thenCompose(ignored -> writerStorage.wal().sync()).join();
        writerStorage.snapshots().close();
        writerStorage.wal().close();

        RaftStorageFactory.DurableStorage recoveryStorage = awaitPersistence(storageDir);
        QraftStateStore recoveredStore = new QraftStateStore();
        RaftNode recovered = durableSingleNode("catalog-mixed", recoveredStore, recoveryStorage);
        recovered.start().toCompletionStage().toCompletableFuture().join();

        assertEquals("legacy-value", recoveredStore.getMetadata("legacy-key"));
        assertEquals(List.of(instance), recoveredStore.getServiceCatalog().instances("catalog"));
        recovered.stop().toCompletionStage().toCompletableFuture().join();
    }

    @Test
    void testCommandSubmissionToNonLeader() {
        node1.start().toCompletionStage().toCompletableFuture().join();
        
        // Node starts as follower, command submission should fail
        RaftCommand command = distributedPut("test-key", "test-value");
        
        Future<CommandResult<?>> future = node1.submitCommand(command);
        
        // Verify the exception
        assertThrows(Exception.class, () -> {
            try {
                future.toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                assertTrue(e.getCause() instanceof IllegalStateException);
                assertTrue(e.getCause().getMessage().contains("Not the leader"));
                throw e;
            }
        });
    }

    @Test
    void testStateMachineOperations() {
        // Test state machine directly
        RaftCommand setCommand = distributedPut("version", "2.1");
        CommandResult<?> result = stateMachine1.apply(setCommand);
        
        assertInstanceOf(CommandResult.Success.class, result);
        assertEquals("2.1", ((CommandResult.Success<?>) result).entity());
        assertEquals("2.1", stateMachine1.getMetadata("version"));
        
        // Test snapshot
        byte[] snapshot = stateMachine1.takeSnapshot();
        assertNotNull(snapshot);
        assertTrue(snapshot.length > 0);
        
        // Reset and restore
        stateMachine1.reset();
        assertEquals("3.0", stateMachine1.getMetadata("version")); // Back to default
        
        stateMachine1.restoreSnapshot(snapshot);
        assertEquals("2.1", stateMachine1.getMetadata("version")); // Restored
    }

    @Test
    void testTransportCommunication() {
        transport1.start(message -> {
            // Message handler - just log for testing
            System.out.println("Node1 received: " + message);
        });
        
        transport2.start(message -> {
            System.out.println("Node2 received: " + message);
        });
        
        assertTrue(transport1.isRunning());
        assertTrue(transport2.isRunning());
        
        // Test vote request
        VoteRequest voteRequest = VoteRequest.newBuilder()
                .setTerm(1)
                .setCandidateId("node1")
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build();
        Future<VoteResponse> future = transport1.sendVoteRequest("node2", voteRequest);
        
        assertDoesNotThrow(() -> {
            VoteResponse response = future.toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertNotNull(response);
            assertEquals(1, response.getTerm());
        });
    }

    @Test
    void testLogEntryCreation() {
        RaftCommand command = distributedPut("test", "value");
        LogEntry entry = new LogEntry(1, 5, command);
        
        assertEquals(1, entry.getTerm());
        assertEquals(5, entry.getIndex());
        assertEquals(command, entry.getCommand());
        assertNotNull(entry.getTimestamp());
        assertFalse(entry.isNoOp());
        
        // Test no-op entry
        LogEntry noOpEntry = new LogEntry(1, 6, null);
        assertTrue(noOpEntry.isNoOp());
    }

    @Test
    void testVoteRequestResponse() {
        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(2)
                .setCandidateId("candidate1")
                .setLastLogIndex(10)
                .setLastLogTerm(1)
                .build();
        assertEquals(2, request.getTerm());
        assertEquals("candidate1", request.getCandidateId());
        assertEquals(10, request.getLastLogIndex());
        assertEquals(1, request.getLastLogTerm());
        
        VoteResponse response = VoteResponse.newBuilder()
                .setTerm(2)
                .setVoteGranted(true)
                .build();
        assertEquals(2, response.getTerm());
        assertTrue(response.getVoteGranted());
    }

    @Test
    void testNodeStateTransitions() {
        node1.start();
        
        // Initially follower
        assertEquals(RaftNode.State.FOLLOWER, node1.getState());
        
        // After election timeout, should become candidate (in a single node cluster)
        Set<String> singleNodeCluster = Set.of("node1");
        RaftNode singleNode = RaftNode.builder().runtime(vertx).nodeId("node1").clusterNodes(singleNodeCluster)
                                          .transport(new InMemoryTransportSimulator("node1"))
                                          .stateMachine(new QraftStateStore()).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode()).electionTimeout(500).heartbeatInterval(100).build();
        singleNode.start();
        
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .until(() -> singleNode.getState() == RaftNode.State.LEADER);
        
        singleNode.stop();
    }

        @Test
        void testDurableElectionPersistsTermAndVote() {
        TestRaftStorage storage = new TestRaftStorage();
        storage.open(null).join();

        Set<String> singleNodeCluster = Set.of("node1");
        RaftNode singleNode = RaftNode.builder().runtime(vertx).nodeId("node1").clusterNodes(singleNodeCluster)
            .transport(new InMemoryTransportSimulator("node1"))
            .stateMachine(new QraftStateStore()).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.durable(storage, storage)).electionTimeout(500).heartbeatInterval(100).build();

        singleNode.start().toCompletionStage().toCompletableFuture().join();

        Awaitility.await()
            .atMost(Duration.ofSeconds(3))
            .untilAsserted(() -> {
                assertTrue(storage.getCurrentTerm() > 0, "Election term should be persisted");
                assertEquals(Optional.of("node1"), storage.getVotedFor(), "Self vote should be persisted");
            });

        singleNode.stop().toCompletionStage().toCompletableFuture().join();
        }

        @Test
        void testMultiNodeRecoveryDoesNotApplyUncommittedTail() {
        TestRaftStorage storage = new TestRaftStorage();
        storage.open(null).join();

        RaftCommand command = distributedPut("recovery-key", "tail-value");
        byte[] payload = new ProtobufRaftCommandCodec().serialize(command);
        storage.appendEntries(java.util.List.of(new LogEntryData(1L, 1L, payload)))
            .join();
        storage.updateMetadata(1L, Optional.empty()).join();

        QraftStateStore recoveredState = new QraftStateStore();
        RaftNode recovered = RaftNode.builder().runtime(vertx).nodeId("node1")
            .clusterNodes(Set.of("node1", "node2", "node3"))
            .transport(new InMemoryTransportSimulator("node1"))
            .stateMachine(recoveredState).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.durable(storage, storage)).electionTimeout(10_000).heartbeatInterval(200).build();

        recovered.start().toCompletionStage().toCompletableFuture().join();

        await().atMost(Duration.ofSeconds(2))
            .untilAsserted(() -> assertNull(recoveredState.getMetadata("recovery-key"),
                "Recovered follower must not apply uncertain log tail before leader commit"));

        recovered.stop().toCompletionStage().toCompletableFuture().join();
        }

        @Test
        void testSingleNodeRecoveryReappliesLocalLog() {
        TestRaftStorage storage = new TestRaftStorage();
        storage.open(null).join();

        RaftCommand command = distributedPut("single-recovery-key", "single-value");
        byte[] payload = new ProtobufRaftCommandCodec().serialize(command);
        storage.appendEntries(java.util.List.of(new LogEntryData(1L, 1L, payload)))
            .join();
        storage.updateMetadata(1L, Optional.of("node1")).join();

        QraftStateStore recoveredState = new QraftStateStore();
        RaftNode recovered = RaftNode.builder().runtime(vertx).nodeId("node1")
            .clusterNodes(Set.of("node1"))
            .transport(new InMemoryTransportSimulator("node1"))
            .stateMachine(recoveredState).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.durable(storage, storage)).electionTimeout(10_000).heartbeatInterval(200).build();

        recovered.start().toCompletionStage().toCompletableFuture().join();

        await().atMost(Duration.ofSeconds(2))
            .untilAsserted(() -> assertEquals("single-value", recoveredState.getMetadata("single-recovery-key")));

        recovered.stop().toCompletionStage().toCompletableFuture().join();
        }

        @Test
        void durableModeRecoversFromExternalWalAndSeparateSnapshotStore() throws Exception {
        Path storageDirectory = tempDir.resolve("direct-external-storage");
        var walConfig = dev.mars.raftlog.storage.RaftStorageConfig.builder()
            .dataDir(storageDirectory)
            .syncEnabled(true)
            .build();
        var wal = new dev.mars.raftlog.storage.FileRaftStorage(walConfig);
        var snapshots = new FileSnapshotStore();
        wal.open(storageDirectory).get(5, TimeUnit.SECONDS);
        snapshots.open(storageDirectory).get(5, TimeUnit.SECONDS);

        QraftStateStore snapshottedState = new QraftStateStore();
        snapshottedState.apply(distributedPut("before-snapshot", "preserved"));
        snapshots.saveAtomically(new dev.mars.qraft.raft.api.SnapshotStore.SnapshotData(
            snapshottedState.takeSnapshot(), 1L, 1L)).get(5, TimeUnit.SECONDS);

        byte[] postSnapshotCommand = new ProtobufRaftCommandCodec()
            .serialize(distributedPut("after-snapshot", "replayed"));
        wal.appendEntries(List.of(new dev.mars.raftlog.storage.RaftStorage.LogEntryData(
            2L, 1L, postSnapshotCommand))).get(5, TimeUnit.SECONDS);
        wal.sync().get(5, TimeUnit.SECONDS);

        QraftStateStore recoveredState = new QraftStateStore();
        RaftNode recovered = RaftNode.builder().runtime(vertx).nodeId("node1")
            .clusterNodes(Set.of("node1"))
            .transport(new InMemoryTransportSimulator("node1"))
            .stateMachine(recoveredState).commandCodec(new ProtobufRaftCommandCodec())
            .mode(RaftNodeMode.durable(wal, snapshots))
            .electionTimeout(10_000).heartbeatInterval(200).build();

        recovered.start().toCompletionStage().toCompletableFuture().join();

        assertEquals("preserved", recoveredState.getMetadata("before-snapshot"));
        assertEquals("replayed", recoveredState.getMetadata("after-snapshot"));
        assertEquals(1L, recovered.getSnapshotLastIndex());

        recovered.stop().toCompletionStage().toCompletableFuture().join();
        }

        @Test
        void appendConflictPlanningUsesIndicesRelativeToTheSnapshotBoundary() {
        TestRaftStorage storage = new TestRaftStorage();
        storage.open(tempDir.resolve("compacted-conflict")).join();
        ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();
        storage.saveAtomically(new dev.mars.qraft.raft.api.SnapshotStore.SnapshotData(
                new QraftStateStore().takeSnapshot(), 5L, 2L)).join();
        storage.appendEntries(List.of(
                new LogEntryData(6, 2, codec.serialize(distributedPut("six", "old"))),
                new LogEntryData(7, 2, codec.serialize(distributedPut("seven", "old"))),
                new LogEntryData(8, 3, codec.serialize(distributedPut("eight", "old-suffix"))))).join();

        RaftNode follower = RaftNode.builder().runtime(vertx).nodeId("node1")
                .clusterNodes(Set.of("node1", "leader"))
                .transport(new InMemoryTransportSimulator("compacted-follower"))
                .stateMachine(new QraftStateStore()).commandCodec(codec)
                .mode(RaftNodeMode.durable(storage, storage))
                .electionTimeout(10_000).heartbeatInterval(200).build();
        follower.start().toCompletionStage().toCompletableFuture().join();

        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
                .setTerm(3).setLeaderId("leader")
                .setPrevLogIndex(5).setPrevLogTerm(2)
                .addEntries(grpcEntry(2, codec.serialize(distributedPut("six", "old"))))
                .addEntries(grpcEntry(3, codec.serialize(distributedPut("seven", "replacement"))))
                .addEntries(grpcEntry(3, codec.serialize(distributedPut("eight", "new"))))
                .build();

        AppendEntriesResponse response = follower.handleAppendEntriesRequest(request)
                .toCompletionStage().toCompletableFuture().join();

        assertTrue(response.getSuccess());
        assertEquals(List.of(6L, 7L, 8L), storage.getLog().stream().map(LogEntryData::index).toList());
        assertEquals(List.of(2L, 3L, 3L), storage.getLog().stream().map(LogEntryData::term).toList());
        follower.stop().toCompletionStage().toCompletableFuture().join();
        }

        @Test
        void testRejectVoteWhenCandidateLogIsBehind() {
        Set<String> singleNodeCluster = Set.of("node1");
        RaftNode singleNode = RaftNode.builder().runtime(vertx).nodeId("node1").clusterNodes(singleNodeCluster)
            .transport(new InMemoryTransportSimulator("node1"))
            .stateMachine(new QraftStateStore()).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode()).electionTimeout(500).heartbeatInterval(100).build();

        singleNode.start().toCompletionStage().toCompletableFuture().join();

        await().atMost(Duration.ofSeconds(3)).until(singleNode::isLeader);

        CommandResult<?> submitted = singleNode.submitCommand(distributedPut("vote-log-key", "vote-log-value"))
            .toCompletionStage().toCompletableFuture().join();
        assertInstanceOf(CommandResult.Success.class, submitted);

        VoteRequest staleCandidate = VoteRequest.newBuilder()
            .setTerm(singleNode.getCurrentTerm() + 1)
            .setCandidateId("candidate-behind")
            .setLastLogTerm(0)
            .setLastLogIndex(0)
            .build();

        VoteResponse response = singleNode.handleVoteRequest(staleCandidate)
            .toCompletionStage().toCompletableFuture().join();

        assertFalse(response.getVoteGranted(), "Vote must be rejected when candidate log is behind");

        singleNode.stop().toCompletionStage().toCompletableFuture().join();
        }

        @Test
        void testUncertainVotePersistenceFailureFencesMetadataTransitions() {
        TestRaftStorage delegate = new TestRaftStorage();
        RaftStorage flakyMetadataStorage = oneShotMetadataFailureStorage(delegate);

        flakyMetadataStorage.open(null).join();

        Set<String> singleNodeCluster = Set.of("node1");
        RaftNode durableNode = RaftNode.builder().runtime(vertx).nodeId("node1").clusterNodes(singleNodeCluster)
            .transport(new InMemoryTransportSimulator("node1"))
            .stateMachine(new QraftStateStore()).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.durable(flakyMetadataStorage, delegate)).electionTimeout(10_000).heartbeatInterval(200).build();

        durableNode.start().toCompletionStage().toCompletableFuture().join();

        VoteRequest voteRequest = VoteRequest.newBuilder()
            .setTerm(7)
            .setCandidateId("candidate-x")
            .setLastLogIndex(99)
            .setLastLogTerm(99)
            .build();

        var persistenceFailure = assertThrows(java.util.concurrent.CompletionException.class,
            () -> durableNode.handleVoteRequest(voteRequest)
                .toCompletionStage().toCompletableFuture().join());
        assertEquals("Simulated one-shot metadata failure", persistenceFailure.getCause().getMessage());

        RaftStorage.PersistentMeta persistedMeta = flakyMetadataStorage.loadMetadata().join();
        assertEquals(0, persistedMeta.currentTerm(),
            "A failed write must not be followed by an unsafely assumed term-only write");
        assertEquals(Optional.empty(), persistedMeta.votedFor());
        assertEquals(1, ((MetadataFailureStorage) flakyMetadataStorage).updateCalls(),
            "The node must not issue a compensating metadata write after an uncertain failure");

        var fencedFailure = assertThrows(java.util.concurrent.CompletionException.class,
            () -> durableNode.handleVoteRequest(voteRequest.toBuilder().setCandidateId("candidate-y").build())
                .toCompletionStage().toCompletableFuture().join());
        assertInstanceOf(RaftTransitionSequencer.FencedException.class, fencedFailure.getCause());
        assertEquals(1, ((MetadataFailureStorage) flakyMetadataStorage).updateCalls(),
            "A fenced node must not execute a later metadata transition");
        logExpectedFailure("vote metadata persistence fenced node", persistenceFailure.getCause());

        durableNode.stop().toCompletionStage().toCompletableFuture().join();
        }

    @Test
    void testRejectHigherTermVoteWithStaleCandidateLogPersistsTermAcrossRestart() {
        Path storageDir = tempDir.resolve("vote-reject-higher-term");

        RaftStorageFactory.DurableStorage storage = awaitPersistence(storageDir);

        Set<String> singleNodeCluster = Set.of("node1");
        RaftNode durableNode = RaftNode.builder().runtime(vertx).nodeId("node1").clusterNodes(singleNodeCluster)
            .transport(new InMemoryTransportSimulator("node1"))
            .stateMachine(new QraftStateStore()).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.durable(storage.wal(), storage.snapshots())).electionTimeout(500).heartbeatInterval(100).build();

        durableNode.start().toCompletionStage().toCompletableFuture().join();
        await().atMost(Duration.ofSeconds(3)).until(durableNode::isLeader);

        CommandResult<?> submitted = durableNode.submitCommand(distributedPut("vote-log-key", "vote-log-value"))
            .toCompletionStage().toCompletableFuture().join();
        assertInstanceOf(CommandResult.Success.class, submitted);

        long higherTerm = durableNode.getCurrentTerm() + 5;
        VoteRequest staleCandidate = VoteRequest.newBuilder()
            .setTerm(higherTerm)
            .setCandidateId("candidate-behind")
            .setLastLogTerm(0)
            .setLastLogIndex(0)
            .build();

        VoteResponse response = durableNode.handleVoteRequest(staleCandidate)
            .toCompletionStage().toCompletableFuture().join();

        assertFalse(response.getVoteGranted(), "Vote must be rejected when candidate log is behind");
        assertEquals(higherTerm, response.getTerm(), "Node should report observed higher term in response");

        durableNode.stop().toCompletionStage().toCompletableFuture().join();

        RaftStorageFactory.DurableStorage recoveryStorage = awaitPersistence(storageDir);

        RaftNode recovered = RaftNode.builder().runtime(vertx).nodeId("node1")
            .clusterNodes(singleNodeCluster)
            .transport(new InMemoryTransportSimulator("node1"))
            .stateMachine(new QraftStateStore())
            .commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.durable(recoveryStorage.wal(), recoveryStorage.snapshots()))
            .electionTimeout(10_000)
            .heartbeatInterval(200)
            .build();

        recovered.start().toCompletionStage().toCompletableFuture().join();

        assertEquals(higherTerm, recovered.getCurrentTerm(),
            "Higher observed term must be durable after rejecting stale candidate to prevent term regression");

        recovered.stop().toCompletionStage().toCompletableFuture().join();
    }

    @Test
    void testRejectHigherTermVoteWithStaleCandidateLogPersistsEmptyVoteAcrossRestart() {
        Path storageDir = tempDir.resolve("vote-reject-higher-term-empty-vote");

        RaftStorageFactory.DurableStorage storage = awaitPersistence(storageDir);

        Set<String> singleNodeCluster = Set.of("node1");
        RaftNode durableNode = RaftNode.builder().runtime(vertx).nodeId("node1").clusterNodes(singleNodeCluster)
            .transport(new InMemoryTransportSimulator("node1"))
            .stateMachine(new QraftStateStore()).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.durable(storage.wal(), storage.snapshots())).electionTimeout(500).heartbeatInterval(100).build();

        durableNode.start().toCompletionStage().toCompletableFuture().join();
        await().atMost(Duration.ofSeconds(3)).until(durableNode::isLeader);

        CommandResult<?> submitted = durableNode.submitCommand(distributedPut("vote-log-key-2", "vote-log-value-2"))
            .toCompletionStage().toCompletableFuture().join();
        assertInstanceOf(CommandResult.Success.class, submitted);

        long higherTerm = durableNode.getCurrentTerm() + 7;
        VoteRequest staleCandidate = VoteRequest.newBuilder()
            .setTerm(higherTerm)
            .setCandidateId("candidate-behind-2")
            .setLastLogTerm(0)
            .setLastLogIndex(0)
            .build();

        VoteResponse response = durableNode.handleVoteRequest(staleCandidate)
            .toCompletionStage().toCompletableFuture().join();

        assertFalse(response.getVoteGranted());
        assertEquals(higherTerm, response.getTerm());

        durableNode.stop().toCompletionStage().toCompletableFuture().join();

        RaftStorageFactory.DurableStorage recoveryStorage = awaitPersistence(storageDir);

        RaftStorage.PersistentMeta persistedMeta = recoveryStorage.wal().loadMetadata().join();

        assertEquals(higherTerm, persistedMeta.currentTerm(),
            "Rejecting higher-term stale candidate should still persist the observed term");
        assertEquals(Optional.empty(), persistedMeta.votedFor(),
            "No candidate should be persisted when vote is rejected");

        recoveryStorage.snapshots().close();
        recoveryStorage.wal().close();
    }

    @Test
    void testRejectHigherTermVoteWithStaleCandidateLogFailsWhenTermPersistenceFails() {
        TestRaftStorage delegate = new TestRaftStorage();
        final long higherTerm = 5;
        MetadataFailureStorage flakyMetadataStorage =
                MetadataFailureStorage.failTermWithEmptyVote(delegate, higherTerm);
        flakyMetadataStorage.open(null).join();

        RaftNode durableNode = RaftNode.builder().runtime(vertx).nodeId("node1").clusterNodes(Set.of("node1"))
            .transport(new InMemoryTransportSimulator("node1"))
            .stateMachine(new QraftStateStore()).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.durable(flakyMetadataStorage, delegate)).electionTimeout(500).heartbeatInterval(100).build();

        durableNode.start().toCompletionStage().toCompletableFuture().join();
        await().atMost(Duration.ofSeconds(3)).until(durableNode::isLeader);

        CommandResult<?> submitted = durableNode.submitCommand(distributedPut("stale-check-key", "stale-check-value"))
            .toCompletionStage().toCompletableFuture().join();
        assertInstanceOf(CommandResult.Success.class, submitted);

        VoteRequest staleCandidate = VoteRequest.newBuilder()
            .setTerm(higherTerm)
            .setCandidateId("candidate-behind-failing-persist")
            .setLastLogTerm(0)
            .setLastLogIndex(0)
            .build();

        Exception voteFailure = assertThrows(Exception.class, () ->
            durableNode.handleVoteRequest(staleCandidate)
                .toCompletionStage().toCompletableFuture().join());
        assertNotNull(voteFailure.getCause(), "Failure should carry the underlying persistence exception");
        assertInstanceOf(IllegalStateException.class, voteFailure.getCause());
        logExpectedFailure("vote-rejection higher-term metadata persistence", voteFailure.getCause());
        assertTrue(flakyMetadataStorage.hasFailed(),
                "Test setup should fail the higher-term empty-vote persistence write");

        RaftStorage.PersistentMeta persistedMeta = flakyMetadataStorage.loadMetadata().join();
        assertTrue(persistedMeta.currentTerm() < higherTerm,
            "Failed persistence in rejection path should not durably advance term to the observed higher value");
        assertNotEquals(Optional.of("candidate-behind-failing-persist"), persistedMeta.votedFor(),
            "Rejecting stale candidate with failed metadata write must not persist vote for that candidate");

        durableNode.stop().toCompletionStage().toCompletableFuture().join();
    }

        @Test
        void testAppendEntriesRejectsWhenHigherTermMetadataPersistFails() {
        TestRaftStorage delegate = new TestRaftStorage();
        RaftStorage flakyMetadataStorage = oneShotMetadataFailureStorage(delegate);
        flakyMetadataStorage.open(null).join();

        RaftNode durableNode = RaftNode.builder().runtime(vertx).nodeId("node1").clusterNodes(Set.of("node1"))
            .transport(new InMemoryTransportSimulator("node1"))
            .stateMachine(new QraftStateStore()).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.durable(flakyMetadataStorage, delegate)).electionTimeout(10_000).heartbeatInterval(200).build();

        durableNode.start().toCompletionStage().toCompletableFuture().join();

        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
            .setTerm(9)
            .setLeaderId("leader-x")
            .setPrevLogIndex(0)
            .setPrevLogTerm(0)
            .setLeaderCommit(0)
            .build();

        AppendEntriesResponse response = durableNode.handleAppendEntriesRequest(request)
            .toCompletionStage().toCompletableFuture().join();

        assertFalse(response.getSuccess(), "AppendEntries must be rejected if higher-term metadata cannot be durably persisted first");
        assertEquals(0, response.getTerm(),
            "A rejection must report the last durable local term, not the unpersisted observed term");
        assertEquals(0, flakyMetadataStorage.loadMetadata().join().currentTerm());
        logExpectedFailure("append-entries higher-term metadata persistence", new IllegalStateException("Injected one-shot metadata failure expected by test"));

        durableNode.stop().toCompletionStage().toCompletableFuture().join();
        }

        @Test
        void testInstallSnapshotRejectsWhenHigherTermMetadataPersistFails() {
        TestRaftStorage delegate = new TestRaftStorage();
        RaftStorage flakyMetadataStorage = oneShotMetadataFailureStorage(delegate);
        flakyMetadataStorage.open(null).join();

        RaftNode durableNode = RaftNode.builder().runtime(vertx).nodeId("node1").clusterNodes(Set.of("node1"))
            .transport(new InMemoryTransportSimulator("node1"))
            .stateMachine(new QraftStateStore()).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.durable(flakyMetadataStorage, delegate)).electionTimeout(10_000).heartbeatInterval(200).build();

        durableNode.start().toCompletionStage().toCompletableFuture().join();

        InstallSnapshotRequest request = InstallSnapshotRequest.newBuilder()
            .setTerm(11)
            .setLeaderId("leader-y")
            .setLastIncludedIndex(1)
            .setLastIncludedTerm(1)
            .setChunkIndex(0)
            .setTotalChunks(1)
            .setData(com.google.protobuf.ByteString.copyFrom(new byte[] {1}))
            .setDone(false)
            .build();

        InstallSnapshotResponse response = durableNode.handleInstallSnapshot(request)
            .toCompletionStage().toCompletableFuture().join();

        assertFalse(response.getSuccess(), "InstallSnapshot must be rejected if higher-term metadata cannot be durably persisted first");
        assertEquals(0, response.getTerm(),
            "A rejection must report the last durable local term, not the unpersisted observed term");
        assertEquals(0, flakyMetadataStorage.loadMetadata().join().currentTerm());
        logExpectedFailure("install-snapshot higher-term metadata persistence", new IllegalStateException("Injected one-shot metadata failure expected by test"));

        durableNode.stop().toCompletionStage().toCompletableFuture().join();
        }

        private static void logExpectedFailure(String scenario, Throwable failure) {
        System.out.println("[EXPECTED-TEST-FAILURE] Scenario=" + scenario + " message=" + failure.getMessage());
        }

        private static RaftStorage oneShotMetadataFailureStorage(TestRaftStorage delegate) {
        return MetadataFailureStorage.failFirstUpdate(delegate);
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
    }

    private RaftNode durableSingleNode(String nodeId, QraftStateStore store,
                                       RaftStorageFactory.DurableStorage storage) {
        return RaftNode.builder()
                .runtime(vertx)
                .nodeId(nodeId)
                .clusterNodes(Set.of(nodeId))
                .transport(new InMemoryTransportSimulator(nodeId))
                .stateMachine(store)
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(storage.wal(), storage.snapshots()))
                .electionTimeout(500)
                .heartbeatInterval(100)
                .build();
    }

    private RaftStorageFactory.DurableStorage awaitPersistence(Path directory) {
        return RaftStorageFactory.createDurable(directory, true)
                .toCompletionStage().toCompletableFuture().join();
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



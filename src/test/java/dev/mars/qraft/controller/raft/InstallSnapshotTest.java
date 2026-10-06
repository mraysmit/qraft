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

import dev.mars.qraft.testing.fault.IntentionalErrors;

import com.google.protobuf.ByteString;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.raft.api.SnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static dev.mars.qraft.controller.raft.ManualRaftCluster.await;
import static dev.mars.qraft.controller.raft.ManualRaftCluster.startAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the InstallSnapshot RPC. A follower cut off while the leader compacted its log is brought up to date
 * with the leader's snapshot, restores its state machine from it, and then keeps replicating ordinary entries;
 * the leader moves its indices for that follower past the snapshot. A stale-term snapshot is refused, and an
 * installed snapshot is persisted. The chunk assembler reassembles split data.
 *
 * <p>Elections, heartbeats and the leader's snapshot check fire only when a test fires them through
 * {@link ManualRaftCluster}; the snapshot is still taken by the node's own threshold check, not called
 * directly. Every node is stopped after the test.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-02-13
 * @version 2.0
 */
class InstallSnapshotTest {
    private static final long SNAPSHOT_CHECK_MS = 300;
    private static final long SNAPSHOT_THRESHOLD = 5;

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
    void aFollowerCutOffDuringCompactionCatchesUpByInstallingTheLeadersSnapshot() throws Exception {
        IntentionalErrors.expect(dev.mars.qraft.testing.fault.IntentionalError.RAFT_PEER_UNREACHABLE);
        Cluster nodes = compactWhileNode3IsCutOff(8);

        InMemoryTransportSimulator.healPartitions();

        cluster.heartbeatUntil(nodes.leader(), () -> nodes.node3().getSnapshotLastIndex() > 0
                && hasKeys(nodes.store3(), 0, 8), "node3 installs the leader's snapshot");
    }

    @Test
    void aFollowerRestoredFromASnapshotKeepsReplicatingTheEntriesThatFollowIt() throws Exception {
        IntentionalErrors.expect(dev.mars.qraft.testing.fault.IntentionalError.RAFT_PEER_UNREACHABLE);
        Cluster nodes = compactWhileNode3IsCutOff(6);
        InMemoryTransportSimulator.healPartitions();
        cluster.heartbeatUntil(nodes.leader(), () -> nodes.node3().getSnapshotLastIndex() > 0,
                "node3 installs the leader's snapshot");

        submitCommands(nodes.leader(), 6, 3);

        cluster.heartbeatUntil(nodes.leader(), () -> hasKeys(nodes.store3(), 0, 9),
                "node3 has the snapshot's keys and the entries replicated after it");
    }

    @Test
    void theLeaderMovesAFollowersNextIndexPastTheSnapshotItInstalled() throws Exception {
        IntentionalErrors.expect(dev.mars.qraft.testing.fault.IntentionalError.RAFT_PEER_UNREACHABLE);
        Cluster nodes = compactWhileNode3IsCutOff(7);
        long leaderSnapshotIndex = nodes.leader().getSnapshotLastIndex();

        InMemoryTransportSimulator.healPartitions();

        cluster.heartbeatUntil(nodes.leader(), () -> nodes.node3().getSnapshotLastIndex() > 0
                        && nodes.leader().getNextIndex("node3") > leaderSnapshotIndex,
                "the leader moves node3's next index past its snapshot");
    }

    @Test
    void aFollowerRefusesASnapshotFromAStaleTerm() throws Exception {
        TestRaftStorage storage = openStorage();
        QraftStateStore store = new QraftStateStore();
        Set<String> members = Set.of("node1", "leader");
        RaftNode follower = cluster.add(cluster.builder("node1", members,
                new InMemoryTransportSimulator("node1"), store, RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(false));
        await(follower.start());
        assertTrue(await(follower.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                .setTerm(2).setLeaderId("leader").setPrevLogIndex(0).setPrevLogTerm(0).setLeaderCommit(0).build()))
                .getSuccess(), "node1 follows leader in term 2");
        InstallSnapshotRequest fromTerm1 = validSnapshot("leader", 1, members);

        InstallSnapshotResponse response = await(follower.handleInstallSnapshot(fromTerm1));

        assertFalse(response.getSuccess(), "an InstallSnapshot from a stale term is refused");
        assertEquals(2, response.getTerm(), "the refusal reports the follower's current term");
        assertEquals(0, follower.getSnapshotLastIndex(), "nothing is installed");
        assertNull(store.getMetadata("snap-key-1"));
        assertTrue(await(follower.handleInstallSnapshot(fromTerm1.toBuilder().setTerm(2).build())).getSuccess(),
                "the same snapshot in the current term is installed, so only its term was refused");
    }

    @Test
    void theChunkAssemblerReassemblesDataSplitIntoSeveralChunks() {
        byte[] original = new byte[256];
        for (int i = 0; i < 256; i++) original[i] = (byte) i;
        int chunkSize = 64;
        int totalChunks = 4;
        RaftNode.SnapshotChunkAssembler assembler = new RaftNode.SnapshotChunkAssembler(InstallSnapshotRequest
                .newBuilder().setTerm(1).setLeaderId("leader").setLastIncludedIndex(10)
                .setLastIncludedTerm(1).setTotalChunks(totalChunks).build());

        for (int i = 0; i < totalChunks; i++) {
            byte[] chunk = new byte[chunkSize];
            System.arraycopy(original, i * chunkSize, chunk, 0, chunkSize);
            assembler = assembler.withChunk(i, chunk);
            assertEquals(i + 1, assembler.getNextExpectedChunk());
        }

        assertArrayEquals(original, assembler.assemble(), "the reassembled data matches the original");
    }

    @Test
    void theChunkAssemblerReturnsASingleChunkAsTheWholeData() {
        byte[] data = "snapshot-data-content".getBytes();
        RaftNode.SnapshotChunkAssembler assembler = new RaftNode.SnapshotChunkAssembler(InstallSnapshotRequest
                .newBuilder().setTerm(1).setLeaderId("leader").setLastIncludedIndex(10)
                .setLastIncludedTerm(1).setTotalChunks(1).build());
        assertEquals(0, assembler.getNextExpectedChunk());

        assembler = assembler.withChunk(0, data);

        assertEquals(1, assembler.getNextExpectedChunk());
        assertArrayEquals(data, assembler.assemble());
    }

    @Test
    void anInstalledSnapshotIsPersistedAndRestoresTheStateMachine() throws Exception {
        TestRaftStorage storage = openStorage();
        QraftStateStore store = new QraftStateStore();
        RaftNode node = cluster.add(cluster.builder("follower-persist", Set.of("follower-persist"),
                new InMemoryTransportSimulator("follower-persist"), store, RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(false));
        await(node.start());

        InstallSnapshotResponse response = await(node.handleInstallSnapshot(
                validSnapshot("some-leader", 1, Set.of("follower-persist"))));

        assertTrue(response.getSuccess(), "the snapshot is installed");
        Optional<SnapshotStore.SnapshotData> saved = storage.loadLatest().get(10, TimeUnit.SECONDS);
        assertTrue(saved.isPresent(), "the snapshot is saved in the follower's storage");
        assertEquals(10, saved.get().lastIncludedIndex());
        assertEquals(1, saved.get().lastIncludedTerm());
        assertTrue(saved.get().data().length > 0);
        assertEquals("snap-val-1", store.getMetadata("snap-key-1"));
        assertEquals("snap-val-2", store.getMetadata("snap-key-2"));
        assertEquals(10, node.getSnapshotLastIndex());
        assertEquals(1, node.getSnapshotLastTerm());
    }

    // ========== Helpers ==========

    private record Cluster(RaftNode leader, RaftNode node3, QraftStateStore store3) { }

    /**
     * A complete single-chunk snapshot through index 10 of term 1, holding snap-key-1 and snap-key-2, and
     * recording the configuration of {@code members}.
     */
    private static InstallSnapshotRequest validSnapshot(String leaderId, long term, Set<String> members) {
        QraftStateStore leaderState = new QraftStateStore();
        leaderState.apply(distributedPut("snap-key-1", "snap-val-1"));
        leaderState.apply(distributedPut("snap-key-2", "snap-val-2"));
        return InstallSnapshotRequest.newBuilder()
                .setTerm(term).setLeaderId(leaderId).setLastIncludedIndex(10).setLastIncludedTerm(1)
                .setChunkIndex(0).setTotalChunks(1)
                .setData(ByteString.copyFrom(ManualRaftCluster.snapshotOf(members, leaderState.takeSnapshot())))
                .setDone(true).build();
    }

    /**
     * Starts three durable nodes with node3 cut off, elects node1 with node2's vote, commits {@code commands}
     * entries, and fires node1's snapshot check so it compacts them away. node3 has none of them.
     */
    private Cluster compactWhileNode3IsCutOff(int commands) throws Exception {
        Set<String> members = Set.of("node1", "node2", "node3");
        QraftStateStore store3 = new QraftStateStore();
        RaftNode node1 = snapshottingNode("node1", members, new QraftStateStore());
        RaftNode node2 = snapshottingNode("node2", members, new QraftStateStore());
        RaftNode node3 = snapshottingNode("node3", members, store3);
        InMemoryTransportSimulator.createPartition(Set.of("node1", "node2"), Set.of("node3"));
        startAll(node1, node2, node3);
        cluster.elect(node1);

        submitCommands(node1, 0, commands);
        cluster.timers(node1).firePeriodic(SNAPSHOT_CHECK_MS);

        awaitTrue(() -> node1.getSnapshotLastIndex() >= SNAPSHOT_THRESHOLD, "the leader compacts its log");
        assertEquals(1, node3.getLastLogIndex(),
                "node3 was cut off throughout: it holds only its bootstrap configuration entry");
        return new Cluster(node1, node3, store3);
    }

    private RaftNode snapshottingNode(String nodeId, Set<String> members, QraftStateStore store) throws Exception {
        TestRaftStorage storage = openStorage();
        return cluster.add(cluster.builder(nodeId, members, new InMemoryTransportSimulator(nodeId), store,
                        RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(true).snapshotThreshold(SNAPSHOT_THRESHOLD).snapshotCheckInterval(SNAPSHOT_CHECK_MS));
    }

    /** In-memory storage: the directory is ignored. */
    private static TestRaftStorage openStorage() throws Exception {
        TestRaftStorage storage = new TestRaftStorage();
        storage.open(null).get(10, TimeUnit.SECONDS);
        return storage;
    }

    private static void submitCommands(RaftNode leader, int first, int count) throws Exception {
        for (int i = first; i < first + count; i++) {
            await(leader.submitCommand(distributedPut("key" + i, "value" + i)));
        }
    }

    private static boolean hasKeys(QraftStateStore store, int first, int count) {
        for (int i = first; i < first + count; i++) {
            if (!("value" + i).equals(store.getMetadata("key" + i))) return false;
        }
        return true;
    }

    private static DistributedStateRaftCommand distributedPut(String key, String value) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, value));
    }

    /** Bounds a wait for in-memory work; the bound only diagnoses a hang. */
    private static void awaitTrue(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), description);
    }
}

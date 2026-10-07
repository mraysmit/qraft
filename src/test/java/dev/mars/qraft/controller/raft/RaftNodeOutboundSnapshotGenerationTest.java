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

import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.runtime.Promise;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.raft.api.SnapshotStore;
import dev.mars.raftlog.storage.RaftStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static dev.mars.qraft.controller.raft.RaftAwaitHelper.awaitStateLoop;
import static dev.mars.qraft.controller.raft.RaftAwaitHelper.await;
import static dev.mars.qraft.controller.raft.RaftAwaitHelper.logEnd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that leader-side snapshot transfers in {@link RaftNode} ignore stale leadership
 * generations, wait for snapshot loads at shutdown, and restart or abandon transfers correctly.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-14
 * @version 1.0
 */
class RaftNodeOutboundSnapshotGenerationTest {
    private static final String PEER_SERVER_ID = ManualRaftClusterFixture.serverIdOf("peer-1");

    private JavaRuntime runtime;
    private GatedSnapshotLoadStorageFixture storage;
    private SnapshotTransportFixture transport;
    private RaftNode node;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        storage = new GatedSnapshotLoadStorageFixture();
        storage.open(null).join();
        transport = new SnapshotTransportFixture();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("node-1")
                .serverId(ManualRaftClusterFixture.serverIdOf("node-1"))
                .clusterNodes(Set.of("node-1", "peer-1"))
                .initialConfiguration(ManualRaftClusterFixture.configurationOf(Set.of("node-1", "peer-1")))
                .transport(transport)
                .stateMachine(new QraftStateStore())
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(false)
                .electionTimeout(30)
                .heartbeatInterval(10_000)
                .build();
        await(node.start());
        awaitLeaderAtOrAboveTerm(1);
        await(node.submitCommand(put("before", "snapshot")));
        await(node.takeSnapshot());
        // Configuration, leadership no-op, then the first client command.
        assertEquals(3, node.getSnapshotLastIndex());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) await(node.stop());
        if (runtime != null) {
            runtime.shutdown().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void delayedSnapshotLoadFromPreviousLeadershipCannotStartTransfer() throws Exception {
        storage.blockNextSnapshotLoad();
        triggerSnapshotTransfer();
        storage.awaitBlockedSnapshotLoad();
        long oldTerm = node.getCurrentTerm();

        long nextIndex = stepDownAndReelect(oldTerm);

        storage.releaseBlockedSnapshotLoad();
        settle();

        assertNull(transport.pollSnapshot(0),
                "a snapshot loaded for an old leadership must not be transmitted");
        assertEquals(nextIndex, node.getNextIndex("peer-1"));
    }

    @Test
    void delayedSnapshotAcknowledgementCannotRewriteNewLeaderPeerIndexes() throws Exception {
        triggerSnapshotTransfer();
        PendingSnapshot stale = transport.takeSnapshot();
        long oldTerm = stale.request().getTerm();

        long nextIndex = stepDownAndReelect(oldTerm);

        stale.response().complete(InstallSnapshotResponse.newBuilder()
                .setTerm(oldTerm)
                .setFollowerServerId(PEER_SERVER_ID)
                .setSuccess(true)
                .setNextChunkIndex(stale.request().getTotalChunks())
                .build());
        awaitStateLoop(runtime);

        assertEquals(nextIndex, node.getNextIndex("peer-1"),
                "an acknowledgement from an old transfer must be ignored");
    }

    @Test
    void higherTermSnapshotResponseIsAppliedEvenWhenTransferIsStale() throws Exception {
        triggerSnapshotTransfer();
        PendingSnapshot stale = transport.takeSnapshot();
        long oldTerm = stale.request().getTerm();

        stepDownAndReelect(oldTerm);
        long responseTerm = node.getCurrentTerm() + 1;
        Future<RaftNode.State> follower = node.awaitState(RaftNode.State.FOLLOWER, 10_000);
        stale.response().complete(InstallSnapshotResponse.newBuilder()
                .setTerm(responseTerm)
                .setFollowerServerId(PEER_SERVER_ID)
                .setSuccess(false)
                .build());
        await(follower);

        assertEquals(responseTerm, node.getCurrentTerm());
        assertEquals(RaftNode.State.FOLLOWER, node.getState());
        assertEquals(responseTerm, storage.loadMetadata().join().currentTerm());
        assertEquals(Optional.empty(), storage.loadMetadata().join().votedFor());
    }

    @Test
    void staleSnapshotFailureCannotCancelCurrentLeadershipTransfer() throws Exception {
        triggerSnapshotTransfer();
        PendingSnapshot stale = transport.takeSnapshot();
        long oldTerm = stale.request().getTerm();

        stepDownAndReelect(oldTerm);
        // Each rejected append lowers the peer's next index by one, until it reaches entries the
        // snapshot has compacted, which the current leadership can only send as a new transfer.
        for (int step = 0; node.getNextIndex("peer-1") > node.getSnapshotLastIndex(); step++) {
            lowerFollowerNextIndex("lower-" + step, String.valueOf(step));
        }
        node.submitCommand(put("current", "snapshot-transfer"));
        PendingSnapshot current = transport.takeSnapshot();

        stale.response().fail(new IllegalStateException("late failure"));
        awaitStateLoop(runtime);
        current.response().complete(InstallSnapshotResponse.newBuilder()
                .setTerm(current.request().getTerm())
                .setFollowerServerId(PEER_SERVER_ID)
                .setSuccess(true)
                .setNextChunkIndex(current.request().getTotalChunks())
                .build());
        long acknowledgedNextIndex = current.request().getLastIncludedIndex() + 1;
        awaitNextIndexAtLeast(acknowledgedNextIndex);

        assertTrue(node.getNextIndex("peer-1") >= acknowledgedNextIndex,
                "a stale failure must not remove the current transfer before its acknowledgement");
    }

    @Test
    void shutdownWaitsForOwnedSnapshotLoadBeforeClosingStorage() throws Exception {
        storage.blockNextSnapshotLoad();
        triggerSnapshotTransfer();
        storage.awaitBlockedSnapshotLoad();

        Future<Void> stop = node.stop();
        awaitStateLoop(runtime);

        assertTrue(!stop.isComplete(), "shutdown must wait for an accepted snapshot load");
        assertEquals(0, storage.closeCount.get());

        storage.releaseBlockedSnapshotLoad();
        await(stop);

        assertEquals(1, storage.closeCount.get());
        assertNull(transport.pollSnapshot(0),
                "a transfer invalidated by shutdown must not send after its load completes");
    }

    @Test
    void persistenceRejectionAbandonsTransferInsteadOfRetryingChunkZeroImmediately() throws Exception {
        triggerSnapshotTransfer();
        PendingSnapshot rejected = transport.takeSnapshot();

        rejected.response().complete(InstallSnapshotResponse.newBuilder()
                .setTerm(rejected.request().getTerm())
                .setFollowerServerId(PEER_SERVER_ID)
                .setSuccess(false)
                .setNextChunkIndex(0)
                .setRejectionReason(
                        InstallSnapshotResponse.RejectionReason.PERSISTENCE_REJECTED)
                .build());
        settle();

        assertNull(transport.pollSnapshot(0),
                "a persistence rejection must not create a tight chunk-zero retry loop");
    }

    @Test
    void lostAssemblerRestartsAtChunkZeroWithoutHeartbeatBackoff() throws Exception {
        triggerSnapshotTransfer();
        PendingSnapshot rejected = transport.takeSnapshot();

        rejected.response().complete(InstallSnapshotResponse.newBuilder()
                .setTerm(rejected.request().getTerm())
                .setFollowerServerId(PEER_SERVER_ID)
                .setSuccess(false)
                .setNextChunkIndex(0)
                .setRejectionReason(
                        InstallSnapshotResponse.RejectionReason.ASSEMBLER_STATE_LOST)
                .build());

        PendingSnapshot restarted = transport.takeSnapshot();
        assertEquals(0, restarted.request().getChunkIndex());
        assertEquals(rejected.request().getLastIncludedIndex(),
                restarted.request().getLastIncludedIndex());
    }

    private void triggerSnapshotTransfer() throws Exception {
        transport.rejectNextAppend();
        node.submitCommand(put("after", "snapshot"));
        transport.awaitRejectedAppend();
        awaitStateLoop(runtime);
        node.submitCommand(put("trigger", "snapshot-transfer"));
    }

    private void lowerFollowerNextIndex(String key, String value) throws Exception {
        long expected = Math.max(1, node.getNextIndex("peer-1") - 1);
        transport.rejectNextAppend();
        node.submitCommand(put(key, value));
        transport.awaitRejectedAppend();
        awaitNextIndex(expected);
    }

    private void awaitNextIndex(long expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (node.getNextIndex("peer-1") != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, node.getNextIndex("peer-1"));
    }

    private void awaitNextIndexAtLeast(long expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (node.getNextIndex("peer-1") < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(node.getNextIndex("peer-1") >= expected,
                () -> "expected nextIndex at least " + expected + " but was " + node.getNextIndex("peer-1"));
    }

    /**
     * Re-elects the node in a later term and waits until the new leadership's no-op has committed, so no
     * replication of it can move the peer's indexes during the test. The entries the peer rejected are
     * uncommitted, so a new leader always appends that no-op (design section 14.3).
     *
     * @return the peer's next index, which a fresh leadership sets just past its log
     */
    private long stepDownAndReelect(long oldTerm) throws Exception {
        RaftAwaitHelper.LogEnd end = logEnd(runtime, node);
        AppendEntriesResponse vote = await(node.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                .setTerm(oldTerm + 1)
                .setLeaderId("peer-1")
                .setPrevLogIndex(end.index())
                .setPrevLogTerm(end.term())
                .build()));
        assertTrue(vote.getSuccess());
        awaitLeaderAtOrAboveTerm(oldTerm + 2);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (node.getCommitIndex() < node.getLastLogIndex() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        awaitStateLoop(runtime);
        assertEquals(node.getLastLogIndex(), node.getCommitIndex(), "the new leadership's no-op commits");
        assertEquals(node.getLastLogIndex() + 1, node.getNextIndex("peer-1"));
        return node.getNextIndex("peer-1");
    }

    private DistributedStateRaftCommand put(String key, String value) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, value));
    }

    private void awaitLeaderAtOrAboveTerm(long minimumTerm) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((!node.isLeader() || node.getCurrentTerm() < minimumTerm)
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        if (!node.isLeader() || node.getCurrentTerm() < minimumTerm) {
            throw new AssertionError("node did not become leader at term " + minimumTerm);
        }
    }

    /**
     * Lets the state loop run every continuation already queued, including ones those continuations queue.
     * A send decided by any of them has then happened, so an empty transport afterwards is exact.
     */
    private void settle() throws Exception {
        for (int pass = 0; pass < 5; pass++) awaitStateLoop(runtime);
    }

    private record PendingSnapshot(
            InstallSnapshotRequest request, Promise<InstallSnapshotResponse> response) {}

    /** Test transport fixture that records and controls snapshot requests for generation assertions. */
    private static final class SnapshotTransportFixture implements RaftTransport {
        private final BlockingQueue<PendingSnapshot> snapshots = new LinkedBlockingQueue<>();
        private final AtomicBoolean rejectNextAppend = new AtomicBoolean();
        private volatile CompletableFuture<Void> rejectedAppend;

        void rejectNextAppend() {
            rejectedAppend = new CompletableFuture<>();
            rejectNextAppend.set(true);
        }

        void awaitRejectedAppend() throws Exception {
            rejectedAppend.get(10, TimeUnit.SECONDS);
        }

        PendingSnapshot takeSnapshot() throws Exception {
            PendingSnapshot snapshot = snapshots.poll(10, TimeUnit.SECONDS);
            if (snapshot == null) throw new AssertionError("leader did not send a snapshot");
            return snapshot;
        }

        PendingSnapshot pollSnapshot(long timeoutMs) throws Exception {
            return snapshots.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }

        @Override public void start(Consumer<RaftMessage> messageHandler) {}
        @Override public void stop() {}

        @Override
        public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return Future.succeededFuture(VoteResponse.newBuilder()
                    .setTerm(request.getTerm()).setVoterServerId(ManualRaftClusterFixture.serverIdOf(targetId))
                    .setVoteGranted(true).build());
        }

        @Override
        public Future<AppendEntriesResponse> sendAppendEntries(
                String targetId, AppendEntriesRequest request) {
            if (rejectNextAppend.compareAndSet(true, false)) {
                rejectedAppend.complete(null);
                return Future.succeededFuture(AppendEntriesResponse.newBuilder()
                        .setTerm(request.getTerm()).setFollowerServerId(ManualRaftClusterFixture.serverIdOf(targetId))
                        .setSuccess(false).build());
            }
            long matchIndex = request.getEntriesCount() == 0
                    ? request.getPrevLogIndex()
                    : request.getEntries(request.getEntriesCount() - 1).getIndex();
            return Future.succeededFuture(AppendEntriesResponse.newBuilder()
                    .setTerm(request.getTerm()).setFollowerServerId(ManualRaftClusterFixture.serverIdOf(targetId))
                    .setSuccess(true).setMatchIndex(matchIndex).build());
        }

        @Override
        public Future<InstallSnapshotResponse> sendInstallSnapshot(
                String targetId, InstallSnapshotRequest request) {
            Promise<InstallSnapshotResponse> response = Promise.promise();
            snapshots.add(new PendingSnapshot(request, response));
            return response.future();
        }
    }

    /** Test storage fixture that holds snapshot loading until the test releases it. */
    private static final class GatedSnapshotLoadStorageFixture implements RaftStorage, SnapshotStore {
        private final TestRaftStorageFixture delegate = new TestRaftStorageFixture();
        private final AtomicInteger closeCount = new AtomicInteger();
        private volatile CompletableFuture<Void> nextLoadGate;
        private volatile CompletableFuture<Void> blockedLoadGate;
        private volatile CompletableFuture<Void> loadEntered;

        void blockNextSnapshotLoad() {
            nextLoadGate = new CompletableFuture<>();
            loadEntered = new CompletableFuture<>();
        }

        void awaitBlockedSnapshotLoad() throws Exception { loadEntered.get(10, TimeUnit.SECONDS); }
        void releaseBlockedSnapshotLoad() { blockedLoadGate.complete(null); }

        @Override public CompletableFuture<Void> open(Path dataDir) { return delegate.open(dataDir); }
        @Override public CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor) { return delegate.updateMetadata(term, votedFor); }
        @Override public CompletableFuture<PersistentMeta> loadMetadata() { return delegate.loadMetadata(); }
        @Override public CompletableFuture<Void> appendEntries(List<LogEntryData> entries) { return delegate.appendEntries(entries); }
        @Override public CompletableFuture<Void> truncateSuffix(long fromIndex) { return delegate.truncateSuffix(fromIndex); }
        @Override public CompletableFuture<Void> truncatePrefix(long toIndex) { return delegate.truncatePrefix(toIndex); }
        @Override public CompletableFuture<Void> sync() { return delegate.sync(); }
        @Override public CompletableFuture<List<LogEntryData>> replayLog() { return delegate.replayLog(); }
        @Override public CompletableFuture<Void> saveAtomically(SnapshotData snapshot) { return delegate.saveAtomically(snapshot); }

        @Override
        public CompletableFuture<Optional<SnapshotData>> loadLatest() {
            CompletableFuture<Void> gate = nextLoadGate;
            if (gate != null) {
                nextLoadGate = null;
                blockedLoadGate = gate;
                loadEntered.complete(null);
                return gate.thenCompose(ignored -> delegate.loadLatest());
            }
            return delegate.loadLatest();
        }

        @Override public void close() {
            closeCount.incrementAndGet();
            delegate.close();
        }
        @Override public CompletableFuture<Void> closeAsync() { return SnapshotStore.super.closeAsync(); }
    }
}

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

import dev.mars.qraft.controller.testsupport.RemediationTest;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RemediationTest(phase = "5", scenarioPrefix = "RAFT-OUTBOUND-SNAPSHOT")
class RaftNodeOutboundSnapshotGenerationTest {
    private JavaRuntime runtime;
    private GatedSnapshotLoadStorage storage;
    private SnapshotTransport transport;
    private RaftNode node;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        storage = new GatedSnapshotLoadStorage();
        storage.open(null).join();
        transport = new SnapshotTransport();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("node-1")
                .clusterNodes(Set.of("node-1", "peer-1"))
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
        assertEquals(1, node.getSnapshotLastIndex());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) await(node.stop());
        if (runtime != null) {
            runtime.shutdown().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void delayedSnapshotLoadFromPreviousLeadershipCannotStartTransfer() throws Exception {
        storage.blockNextSnapshotLoad();
        triggerSnapshotTransfer();
        storage.awaitBlockedSnapshotLoad();
        long oldTerm = node.getCurrentTerm();

        stepDownAndReelect(oldTerm);
        assertEquals(4, node.getNextIndex("peer-1"));

        storage.releaseBlockedSnapshotLoad();
        awaitStateLoop();

        assertNull(transport.pollSnapshot(200),
                "a snapshot loaded for an old leadership must not be transmitted");
        assertEquals(4, node.getNextIndex("peer-1"));
    }

    @Test
    void delayedSnapshotAcknowledgementCannotRewriteNewLeaderPeerIndexes() throws Exception {
        triggerSnapshotTransfer();
        PendingSnapshot stale = transport.takeSnapshot();
        long oldTerm = stale.request().getTerm();

        stepDownAndReelect(oldTerm);
        assertEquals(4, node.getNextIndex("peer-1"));

        stale.response().complete(InstallSnapshotResponse.newBuilder()
                .setTerm(oldTerm)
                .setSuccess(true)
                .setNextChunkIndex(stale.request().getTotalChunks())
                .build());
        awaitStateLoop();

        assertEquals(4, node.getNextIndex("peer-1"),
                "an acknowledgement from an old transfer must be ignored");
    }

    @Test
    void higherTermSnapshotResponseIsAppliedEvenWhenTransferIsStale() throws Exception {
        triggerSnapshotTransfer();
        PendingSnapshot stale = transport.takeSnapshot();
        long oldTerm = stale.request().getTerm();

        stepDownAndReelect(oldTerm);
        long responseTerm = node.getCurrentTerm() + 1;
        Future<RaftNode.State> follower = node.awaitState(RaftNode.State.FOLLOWER, 2_000);
        stale.response().complete(InstallSnapshotResponse.newBuilder()
                .setTerm(responseTerm)
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
        lowerFollowerNextIndex("lower-to-three", "3");
        lowerFollowerNextIndex("lower-to-two", "2");
        lowerFollowerNextIndex("lower-to-one", "1");
        node.submitCommand(put("current", "snapshot-transfer"));
        PendingSnapshot current = transport.takeSnapshot();

        stale.response().fail(new IllegalStateException("late failure"));
        awaitStateLoop();
        current.response().complete(InstallSnapshotResponse.newBuilder()
                .setTerm(current.request().getTerm())
                .setSuccess(true)
                .setNextChunkIndex(current.request().getTotalChunks())
                .build());
        awaitNextIndex(2);

        assertEquals(2, node.getNextIndex("peer-1"),
                "a stale failure must not remove the current transfer before its acknowledgement");
    }

    @Test
    void shutdownWaitsForOwnedSnapshotLoadBeforeClosingStorage() throws Exception {
        storage.blockNextSnapshotLoad();
        triggerSnapshotTransfer();
        storage.awaitBlockedSnapshotLoad();

        Future<Void> stop = node.stop();
        awaitStateLoop();

        assertTrue(!stop.isComplete(), "shutdown must wait for an accepted snapshot load");
        assertEquals(0, storage.closeCount.get());

        storage.releaseBlockedSnapshotLoad();
        await(stop);

        assertEquals(1, storage.closeCount.get());
        assertNull(transport.pollSnapshot(200),
                "a transfer invalidated by shutdown must not send after its load completes");
    }

    @Test
    void persistenceRejectionAbandonsTransferInsteadOfRetryingChunkZeroImmediately() throws Exception {
        triggerSnapshotTransfer();
        PendingSnapshot rejected = transport.takeSnapshot();

        rejected.response().complete(InstallSnapshotResponse.newBuilder()
                .setTerm(rejected.request().getTerm())
                .setSuccess(false)
                .setNextChunkIndex(0)
                .build());
        awaitStateLoop();

        assertNull(transport.pollSnapshot(100),
                "a persistence rejection must not create a tight chunk-zero retry loop");
    }

    private void triggerSnapshotTransfer() throws Exception {
        transport.rejectNextAppend();
        node.submitCommand(put("after", "snapshot"));
        transport.awaitRejectedAppend();
        awaitStateLoop();
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (node.getNextIndex("peer-1") != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, node.getNextIndex("peer-1"));
    }

    private void stepDownAndReelect(long oldTerm) throws Exception {
        VoteResponse vote = await(node.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(oldTerm + 1)
                .setCandidateId("peer-1")
                .setLastLogIndex(node.getLastLogIndex())
                .setLastLogTerm(node.getLastLogTerm())
                .build()));
        assertTrue(vote.getVoteGranted());
        awaitLeaderAtOrAboveTerm(oldTerm + 2);
        awaitStateLoop();
    }

    private DistributedStateRaftCommand put(String key, String value) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, value));
    }

    private void awaitLeaderAtOrAboveTerm(long minimumTerm) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while ((!node.isLeader() || node.getCurrentTerm() < minimumTerm)
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        if (!node.isLeader() || node.getCurrentTerm() < minimumTerm) {
            throw new AssertionError("node did not become leader at term " + minimumTerm);
        }
    }

    private void awaitStateLoop() throws Exception {
        CompletableFuture<Void> marker = new CompletableFuture<>();
        runtime.runOnContext(ignored -> marker.complete(null));
        marker.get(2, TimeUnit.SECONDS);
    }

    private static <T> T await(Future<T> future) {
        return future.timeout(5, TimeUnit.SECONDS).toCompletionStage().toCompletableFuture().join();
    }

    private record PendingSnapshot(
            InstallSnapshotRequest request, Promise<InstallSnapshotResponse> response) {}

    private static final class SnapshotTransport implements RaftTransport {
        private final BlockingQueue<PendingSnapshot> snapshots = new LinkedBlockingQueue<>();
        private final AtomicBoolean rejectNextAppend = new AtomicBoolean();
        private volatile CompletableFuture<Void> rejectedAppend;

        void rejectNextAppend() {
            rejectedAppend = new CompletableFuture<>();
            rejectNextAppend.set(true);
        }

        void awaitRejectedAppend() throws Exception {
            rejectedAppend.get(2, TimeUnit.SECONDS);
        }

        PendingSnapshot takeSnapshot() throws Exception {
            PendingSnapshot snapshot = snapshots.poll(2, TimeUnit.SECONDS);
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
                    .setTerm(request.getTerm()).setVoteGranted(true).build());
        }

        @Override
        public Future<AppendEntriesResponse> sendAppendEntries(
                String targetId, AppendEntriesRequest request) {
            if (rejectNextAppend.compareAndSet(true, false)) {
                rejectedAppend.complete(null);
                return Future.succeededFuture(AppendEntriesResponse.newBuilder()
                        .setTerm(request.getTerm()).setSuccess(false).build());
            }
            long matchIndex = request.getEntriesCount() == 0
                    ? request.getPrevLogIndex()
                    : request.getEntries(request.getEntriesCount() - 1).getIndex();
            return Future.succeededFuture(AppendEntriesResponse.newBuilder()
                    .setTerm(request.getTerm()).setSuccess(true).setMatchIndex(matchIndex).build());
        }

        @Override
        public Future<InstallSnapshotResponse> sendInstallSnapshot(
                String targetId, InstallSnapshotRequest request) {
            Promise<InstallSnapshotResponse> response = Promise.promise();
            snapshots.add(new PendingSnapshot(request, response));
            return response.future();
        }
    }

    private static final class GatedSnapshotLoadStorage implements RaftStorage, SnapshotStore {
        private final TestRaftStorage delegate = new TestRaftStorage();
        private final AtomicInteger closeCount = new AtomicInteger();
        private volatile CompletableFuture<Void> nextLoadGate;
        private volatile CompletableFuture<Void> blockedLoadGate;
        private volatile CompletableFuture<Void> loadEntered;

        void blockNextSnapshotLoad() {
            nextLoadGate = new CompletableFuture<>();
            loadEntered = new CompletableFuture<>();
        }

        void awaitBlockedSnapshotLoad() throws Exception { loadEntered.get(2, TimeUnit.SECONDS); }
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
    }
}

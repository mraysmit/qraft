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
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RemediationTest(phase = "5", scenarioPrefix = "RAFT-TIMER")
class RaftNodeTimerSequencingTest {
    private JavaRuntime runtime;
    private RaftNode node;

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) await(node.stop());
        if (runtime != null) {
            runtime.shutdown().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void expiredElectionTimerCannotStartNewTermAfterVoteResetsIt() throws Exception {
        runtime = JavaRuntime.create();
        GatedTimerStorage storage = new GatedTimerStorage();
        storage.open(null).join();
        storage.blockNextMetadataUpdate();
        node = newNode(storage, new AutoTransport(false),
                Set.of("node-1", "peer-1"), 200, 10_000, false, 60_000);
        await(node.start());

        Future<VoteResponse> vote = node.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(1)
                .setCandidateId("peer-1")
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build());
        storage.awaitBlockedMetadataUpdate();
        awaitRuntimeDelay(450);

        storage.releaseBlockedMetadataUpdate();
        assertTrue(await(vote).getVoteGranted());
        awaitStateLoop();

        assertEquals(RaftNode.State.FOLLOWER, node.getState(),
                "the expired timer must be invalid after the granted vote resets it");
        assertEquals(1, node.getCurrentTerm());
    }

    @Test
    void heartbeatTimerCannotSendWhileWalTransitionIsBlocked() throws Exception {
        runtime = JavaRuntime.create();
        GatedTimerStorage storage = new GatedTimerStorage();
        storage.open(null).join();
        AutoTransport transport = new AutoTransport(true);
        node = newNode(storage, transport,
                Set.of("node-1", "peer-1"), 25, 200, false, 60_000);
        await(node.start());
        awaitLeader();
        transport.resetAppendCount();

        storage.blockNextSync();
        node.submitCommand(new DistributedStateRaftCommand(
                DistributedStateCommand.put("blocked", "value")));
        storage.awaitBlockedSync();
        transport.resetAppendCount();
        awaitRuntimeDelay(450);

        assertEquals(0, transport.appendCount(),
                "timer work must wait behind the active WAL transition");

        storage.releaseBlockedSync();
        awaitStateLoop();
    }

    @Test
    void scheduledSnapshotCannotRunAfterQueuedHigherTermStepDown() throws Exception {
        runtime = JavaRuntime.create();
        GatedTimerStorage storage = new GatedTimerStorage();
        storage.open(null).join();
        node = newNode(storage, new AutoTransport(true),
                Set.of("node-1"), 1_000, 10_000, true, 200);
        await(node.start());
        awaitLeader();
        await(node.submitCommand(new DistributedStateRaftCommand(
                DistributedStateCommand.put("snapshot", "candidate"))));

        storage.blockNextMetadataUpdate();
        Future<VoteResponse> vote = node.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(node.getCurrentTerm() + 1)
                .setCandidateId("peer-1")
                .setLastLogIndex(node.getLastLogIndex())
                .setLastLogTerm(node.getLastLogTerm())
                .build());
        storage.awaitBlockedMetadataUpdate();
        awaitRuntimeDelay(450);

        storage.releaseBlockedMetadataUpdate();
        assertTrue(await(vote).getVoteGranted());
        awaitRuntimeDelay(50);

        assertEquals(RaftNode.State.FOLLOWER, node.getState());
        assertEquals(0, storage.snapshotSaveCount(),
                "a leader-owned timer must not publish after leadership is lost");
        assertEquals(0, node.getSnapshotLastIndex());
    }

    private RaftNode newNode(
            GatedTimerStorage storage, RaftTransport transport, Set<String> members,
            long electionTimeout, long heartbeatInterval,
            boolean snapshotEnabled, long snapshotInterval) {
        return RaftNode.builder()
                .runtime(runtime)
                .nodeId("node-1")
                .clusterNodes(members)
                .transport(transport)
                .stateMachine(new QraftStateStore())
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(snapshotEnabled)
                .snapshotThreshold(1)
                .snapshotCheckInterval(snapshotInterval)
                .electionTimeout(electionTimeout)
                .heartbeatInterval(heartbeatInterval)
                .build();
    }

    private void awaitLeader() {
        await(node.awaitState(RaftNode.State.LEADER, 3_000));
    }

    private void awaitRuntimeDelay(long delayMs) throws Exception {
        CompletableFuture<Void> elapsed = new CompletableFuture<>();
        runtime.setTimer(delayMs, ignored -> elapsed.complete(null));
        elapsed.get(delayMs + 2_000, TimeUnit.MILLISECONDS);
    }

    private void awaitStateLoop() throws Exception {
        CompletableFuture<Void> marker = new CompletableFuture<>();
        runtime.runOnContext(ignored -> marker.complete(null));
        marker.get(2, TimeUnit.SECONDS);
    }

    private static <T> T await(Future<T> future) {
        return future.timeout(5, TimeUnit.SECONDS).toCompletionStage().toCompletableFuture().join();
    }

    private static final class AutoTransport implements RaftTransport {
        private final boolean grantVotes;
        private final AtomicInteger appendCount = new AtomicInteger();

        private AutoTransport(boolean grantVotes) {
            this.grantVotes = grantVotes;
        }

        int appendCount() { return appendCount.get(); }
        void resetAppendCount() { appendCount.set(0); }

        @Override public void start(Consumer<RaftMessage> messageHandler) {}
        @Override public void stop() {}

        @Override
        public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            if (!grantVotes) return Promise.<VoteResponse>promise().future();
            return Future.succeededFuture(VoteResponse.newBuilder()
                    .setTerm(request.getTerm()).setVoteGranted(true).build());
        }

        @Override
        public Future<AppendEntriesResponse> sendAppendEntries(
                String targetId, AppendEntriesRequest request) {
            appendCount.incrementAndGet();
            long matchIndex = request.getEntriesCount() == 0
                    ? request.getPrevLogIndex()
                    : request.getEntries(request.getEntriesCount() - 1).getIndex();
            return Future.succeededFuture(AppendEntriesResponse.newBuilder()
                    .setTerm(request.getTerm()).setSuccess(true).setMatchIndex(matchIndex).build());
        }

        @Override
        public Future<InstallSnapshotResponse> sendInstallSnapshot(
                String targetId, InstallSnapshotRequest request) {
            return Future.succeededFuture(InstallSnapshotResponse.newBuilder()
                    .setTerm(request.getTerm()).setSuccess(true)
                    .setNextChunkIndex(request.getTotalChunks()).build());
        }
    }

    private static final class GatedTimerStorage implements RaftStorage, SnapshotStore {
        private final TestRaftStorage delegate = new TestRaftStorage();
        private final AtomicInteger snapshotSaveCount = new AtomicInteger();
        private volatile CompletableFuture<Void> nextMetadataGate;
        private volatile CompletableFuture<Void> blockedMetadataGate;
        private volatile CompletableFuture<Void> metadataEntered;
        private volatile CompletableFuture<Void> nextSyncGate;
        private volatile CompletableFuture<Void> blockedSyncGate;
        private volatile CompletableFuture<Void> syncEntered;

        int snapshotSaveCount() { return snapshotSaveCount.get(); }

        void blockNextMetadataUpdate() {
            nextMetadataGate = new CompletableFuture<>();
            metadataEntered = new CompletableFuture<>();
        }

        void awaitBlockedMetadataUpdate() throws Exception {
            metadataEntered.get(2, TimeUnit.SECONDS);
        }

        void releaseBlockedMetadataUpdate() { blockedMetadataGate.complete(null); }

        void blockNextSync() {
            nextSyncGate = new CompletableFuture<>();
            syncEntered = new CompletableFuture<>();
        }

        void awaitBlockedSync() throws Exception { syncEntered.get(2, TimeUnit.SECONDS); }
        void releaseBlockedSync() { blockedSyncGate.complete(null); }

        @Override public CompletableFuture<Void> open(Path dataDir) { return delegate.open(dataDir); }

        @Override
        public CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor) {
            CompletableFuture<Void> gate = nextMetadataGate;
            if (gate != null) {
                nextMetadataGate = null;
                blockedMetadataGate = gate;
                metadataEntered.complete(null);
                return gate.thenCompose(ignored -> delegate.updateMetadata(term, votedFor));
            }
            return delegate.updateMetadata(term, votedFor);
        }

        @Override public CompletableFuture<PersistentMeta> loadMetadata() { return delegate.loadMetadata(); }
        @Override public CompletableFuture<Void> appendEntries(List<LogEntryData> entries) { return delegate.appendEntries(entries); }
        @Override public CompletableFuture<Void> truncateSuffix(long fromIndex) { return delegate.truncateSuffix(fromIndex); }
        @Override public CompletableFuture<Void> truncatePrefix(long toIndex) { return delegate.truncatePrefix(toIndex); }

        @Override
        public CompletableFuture<Void> sync() {
            CompletableFuture<Void> gate = nextSyncGate;
            if (gate != null) {
                nextSyncGate = null;
                blockedSyncGate = gate;
                syncEntered.complete(null);
                return gate.thenCompose(ignored -> delegate.sync());
            }
            return delegate.sync();
        }

        @Override public CompletableFuture<List<LogEntryData>> replayLog() { return delegate.replayLog(); }

        @Override
        public CompletableFuture<Void> saveAtomically(SnapshotData snapshot) {
            snapshotSaveCount.incrementAndGet();
            return delegate.saveAtomically(snapshot);
        }

        @Override public CompletableFuture<Optional<SnapshotData>> loadLatest() { return delegate.loadLatest(); }
        @Override public void close() { delegate.close(); }
    }
}

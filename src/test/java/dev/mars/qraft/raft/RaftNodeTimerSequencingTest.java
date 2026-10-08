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

package dev.mars.qraft.raft;

import dev.mars.qraft.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;
import dev.mars.qraft.common.async.Future;
import dev.mars.qraft.common.async.JavaRuntime;
import dev.mars.qraft.common.async.Promise;
import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import dev.mars.qraft.raft.api.SnapshotStore;
import dev.mars.raftlog.storage.RaftStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static dev.mars.qraft.raft.RaftAwaitHelper.awaitStateLoop;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that election, heartbeat and snapshot timers in {@link RaftNode} run through the transition
 * sequencer and cannot act on stale state or while a WAL transition is blocked; a snapshot queued behind
 * a step-down runs after it, in the follower role, over applied entries only.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-14
 * @version 1.1
 */
class RaftNodeTimerSequencingTest {
    private static final Set<String> MEMBERS = Set.of("node-1", "peer-1");

    private JavaRuntime runtime;
    private ManualTimerSchedulerHelper timers;
    private RaftNode node;

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) await(node.stop());
        if (runtime != null) {
            runtime.shutdown().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void expiredElectionTimerCannotStartNewTermAfterVoteResetsIt() throws Exception {
        runtime = JavaRuntime.create();
        timers = new ManualTimerSchedulerHelper(runtime);
        GatedTimerStorageFixture storage = new GatedTimerStorageFixture();
        storage.open(null).join();
        storage.blockNextMetadataUpdate();
        node = newNode(storage, new AutoTransportFixture(false),
                MEMBERS, 200, 10_000, false, 60_000);
        await(node.start());

        Future<VoteResponse> vote = node.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(1)
                .setCandidateId("peer-1")
                .setCandidateServerId(ManualRaftClusterFixture.serverIdOf("peer-1"))
                .setLastLogIndex(1)
                .setLastLogTerm(0)
                .build());
        storage.awaitBlockedMetadataUpdate();
        timers.fireNextOneShot();
        awaitStateLoop(runtime);

        storage.releaseBlockedMetadataUpdate();
        assertTrue(await(vote).getVoteGranted());
        awaitStateLoop(runtime);

        assertEquals(RaftNode.State.FOLLOWER, node.getState(),
                "the expired timer must be invalid after the granted vote resets it");
        assertEquals(1, node.getCurrentTerm());
    }

    @Test
    void heartbeatTimerCannotSendWhileWalTransitionIsBlocked() throws Exception {
        runtime = JavaRuntime.create();
        timers = new ManualTimerSchedulerHelper(runtime);
        GatedTimerStorageFixture storage = new GatedTimerStorageFixture();
        storage.open(null).join();
        AutoTransportFixture transport = new AutoTransportFixture(true);
        node = newNode(storage, transport,
                MEMBERS, 25, 200, false, 60_000);
        await(node.start());
        electLeader();
        transport.resetAppendCount();

        storage.blockNextSync();
        node.submitCommand(new DistributedStateRaftCommand(
                DistributedStateCommand.put("blocked", "value")));
        storage.awaitBlockedSync();
        transport.resetAppendCount();
        timers.firePeriodic(200);
        awaitStateLoop(runtime);

        assertEquals(0, transport.appendCount(),
                "timer work must wait behind the active WAL transition");

        storage.releaseBlockedSync();
        awaitStateLoop(runtime);
    }

    @Test
    void aScheduledSnapshotQueuedBehindAStepDownWaitsForItAndCompactsOnlyAppliedEntries() throws Exception {
        runtime = JavaRuntime.create();
        timers = new ManualTimerSchedulerHelper(runtime);
        GatedTimerStorageFixture storage = new GatedTimerStorageFixture();
        storage.open(null).join();
        node = newNode(storage, new AutoTransportFixture(true),
                Set.of("node-1"), 1_000, 10_000, true, 200);
        await(node.start());
        electLeader();
        await(node.submitCommand(new DistributedStateRaftCommand(
                DistributedStateCommand.put("snapshot", "candidate"))));

        storage.blockNextMetadataUpdate();
        Future<AppendEntriesResponse> vote = node.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                .setTerm(node.getCurrentTerm() + 1)
                .setLeaderId("peer-1")
                .setPrevLogIndex(node.getLastLogIndex())
                .setPrevLogTerm(node.getLastLogTerm())
                .build());
        storage.awaitBlockedMetadataUpdate();
        timers.firePeriodic(200);
        awaitStateLoop(runtime);
        assertEquals(0, storage.snapshotSaveCount(),
                "the snapshot is queued behind the step-down's blocked WAL transition");

        storage.releaseBlockedMetadataUpdate();
        assertTrue(await(vote).getSuccess());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (storage.snapshotSaveCount() == 0 && System.nanoTime() < deadline) awaitStateLoop(runtime);
        awaitStateLoop(runtime);

        assertEquals(RaftNode.State.FOLLOWER, node.getState());
        assertEquals(1, storage.snapshotSaveCount(), "every role compacts its own log");
        assertEquals(node.getCommitIndex(), onStateLoop(node::getSnapshotLastIndex),
                "the snapshot covers exactly the applied entries");
    }

    private <T> T onStateLoop(java.util.function.Supplier<T> read) throws Exception {
        CompletableFuture<T> value = new CompletableFuture<>();
        runtime.runOnContext(ignored -> value.complete(read.get()));
        return value.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aVoteAppliedWhileStoppingDoesNotReArmTheElectionTimer() throws Exception {
        runtime = JavaRuntime.create();
        timers = new ManualTimerSchedulerHelper(runtime);
        GatedTimerStorageFixture storage = new GatedTimerStorageFixture();
        storage.open(null).join();
        node = RaftNode.builder().runtime(runtime).nodeId("node-1").clusterNodes(MEMBERS)
                .serverId(ManualRaftClusterFixture.serverIdOf("node-1"))
                .initialConfiguration(ManualRaftClusterFixture.configurationOf(MEMBERS))
                .transport(new AutoTransportFixture(false)).stateMachine(new QraftStateStore())
                .commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(false).electionTimeout(10_000).heartbeatInterval(10_000)
                .timerScheduler(timers).build();
        await(node.start());
        storage.blockNextMetadataUpdate();
        Future<VoteResponse> vote = node.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(1).setCandidateId("peer-1").setCandidateServerId(ManualRaftClusterFixture.serverIdOf("peer-1"))
                .setLastLogIndex(1).setLastLogTerm(0).build());
        storage.awaitBlockedMetadataUpdate();

        Future<Void> stop = node.stop();
        awaitStateLoop(runtime);
        storage.releaseBlockedMetadataUpdate();
        await(vote);
        await(stop);
        awaitStateLoop(runtime);

        assertEquals(0, timers.oneShotCount(), "a stopped node must not hold an armed election timer");
    }

    @Test
    void queueRejectedElectionRearmsTheElectionTimer() throws Exception {
        runtime = JavaRuntime.create();
        timers = new ManualTimerSchedulerHelper(runtime);
        GatedTimerStorageFixture storage = new GatedTimerStorageFixture();
        storage.open(null).join();
        storage.blockNextMetadataUpdate();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("node-1")
                .serverId(ManualRaftClusterFixture.serverIdOf("node-1"))
                .initialConfiguration(ManualRaftClusterFixture.configurationOf(MEMBERS))
                .clusterNodes(MEMBERS)
                .transport(new AutoTransportFixture(false))
                .stateMachine(new QraftStateStore())
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(false)
                .electionTimeout(10_000)
                .heartbeatInterval(10_000)
                .timerScheduler(timers)
                .transitionQueueCapacity(1)
                .build();
        await(node.start());

        Future<VoteResponse> vote = node.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(1).setCandidateId("peer-1").setCandidateServerId(ManualRaftClusterFixture.serverIdOf("peer-1"))
                .setLastLogIndex(1).setLastLogTerm(0).build());
        storage.awaitBlockedMetadataUpdate();

        timers.fireNextOneShot();
        awaitStateLoop(runtime);
        awaitStateLoop(runtime);

        assertEquals(1, timers.oneShotCount(),
                "queue rejection must install a replacement election timer");
        assertEquals(0, node.getCurrentTerm());

        storage.releaseBlockedMetadataUpdate();
        assertTrue(await(vote).getVoteGranted());
    }

    private RaftNode newNode(
            GatedTimerStorageFixture storage, RaftTransport transport, Set<String> members,
            long electionTimeout, long heartbeatInterval,
            boolean snapshotEnabled, long snapshotInterval) {
        return RaftNode.builder()
                .runtime(runtime)
                .nodeId("node-1")
                .serverId(ManualRaftClusterFixture.serverIdOf("node-1"))
                .initialConfiguration(ManualRaftClusterFixture.configurationOf(members))
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
                .timerScheduler(timers)
                .build();
    }

    private void electLeader() {
        timers.fireNextOneShot();
        await(node.awaitState(RaftNode.State.LEADER, 10_000));
    }

    private static <T> T await(Future<T> future) {
        return RaftAwaitHelper.await(future, Duration.ofSeconds(15));
    }

    /** Test transport fixture that answers peer requests and counts appends for timer-sequencing assertions. */
    private static final class AutoTransportFixture implements RaftTransport {
        private final boolean grantVotes;
        private final AtomicInteger appendCount = new AtomicInteger();

        private AutoTransportFixture(boolean grantVotes) {
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
                    .setTerm(request.getTerm()).setVoteGranted(true)
                    .setVoterServerId(ManualRaftClusterFixture.serverIdOf(targetId)).build());
        }

        @Override
        public Future<AppendEntriesResponse> sendAppendEntries(
                String targetId, AppendEntriesRequest request) {
            appendCount.incrementAndGet();
            long matchIndex = request.getEntriesCount() == 0
                    ? request.getPrevLogIndex()
                    : request.getEntries(request.getEntriesCount() - 1).getIndex();
            return Future.succeededFuture(AppendEntriesResponse.newBuilder()
                    .setTerm(request.getTerm()).setSuccess(true).setMatchIndex(matchIndex)
                    .setFollowerServerId(ManualRaftClusterFixture.serverIdOf(targetId)).build());
        }

        @Override
        public Future<InstallSnapshotResponse> sendInstallSnapshot(
                String targetId, InstallSnapshotRequest request) {
            return Future.succeededFuture(InstallSnapshotResponse.newBuilder()
                    .setTerm(request.getTerm()).setSuccess(true)
                    .setNextChunkIndex(request.getTotalChunks())
                    .setFollowerServerId(ManualRaftClusterFixture.serverIdOf(targetId)).build());
        }
    }

    /** Test timer helper that records timers and lets the test choose when callbacks run. */
    private static final class ManualTimerSchedulerHelper implements RaftTimerScheduler {
        private final JavaRuntime runtime;
        private final AtomicLong ids = new AtomicLong();
        private final Map<Long, ScheduledAction> oneShots = new ConcurrentHashMap<>();
        private final Map<Long, ScheduledAction> periodics = new ConcurrentHashMap<>();

        private ManualTimerSchedulerHelper(JavaRuntime runtime) { this.runtime = runtime; }

        @Override
        public long setTimer(long delayMs, Consumer<Long> action) {
            long id = ids.incrementAndGet();
            oneShots.put(id, new ScheduledAction(delayMs, action));
            return id;
        }

        @Override
        public long setPeriodic(long periodMs, Consumer<Long> action) {
            long id = ids.incrementAndGet();
            periodics.put(id, new ScheduledAction(periodMs, action));
            return id;
        }

        @Override
        public boolean cancelTimer(long id) {
            return oneShots.remove(id) != null || periodics.remove(id) != null;
        }

        void fireNextOneShot() {
            long id = oneShots.keySet().stream().min(Long::compareTo).orElseThrow();
            ScheduledAction scheduled = oneShots.remove(id);
            runtime.runOnContext(ignored -> scheduled.action().accept(id));
        }

        void firePeriodic(long periodMs) {
            Map.Entry<Long, ScheduledAction> scheduled = periodics.entrySet().stream()
                    .filter(entry -> entry.getValue().delayMs() == periodMs)
                    .findFirst().orElseThrow();
            runtime.runOnContext(ignored -> scheduled.getValue().action().accept(scheduled.getKey()));
        }

        int oneShotCount() { return oneShots.size(); }

        private record ScheduledAction(long delayMs, Consumer<Long> action) { }
    }

    /** Test storage fixture that holds persistence to exercise ordering between timer callbacks and state transitions. */
    private static final class GatedTimerStorageFixture implements RaftStorage, SnapshotStore {
        private final TestRaftStorageFixture delegate = new TestRaftStorageFixture();
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
            metadataEntered.get(10, TimeUnit.SECONDS);
        }

        void releaseBlockedMetadataUpdate() { blockedMetadataGate.complete(null); }

        void blockNextSync() {
            nextSyncGate = new CompletableFuture<>();
            syncEntered = new CompletableFuture<>();
        }

        void awaitBlockedSync() throws Exception { syncEntered.get(10, TimeUnit.SECONDS); }
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
        @Override public CompletableFuture<Void> closeAsync() { return SnapshotStore.super.closeAsync(); }
    }
}

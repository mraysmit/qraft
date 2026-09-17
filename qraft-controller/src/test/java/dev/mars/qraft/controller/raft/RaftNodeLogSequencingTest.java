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
import dev.mars.qraft.controller.testsupport.RemediationTestExtension;

import com.google.protobuf.ByteString;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.RaftCommandResult;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.controller.state.RaftCommand;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.raft.api.CommandCodec;
import dev.mars.qraft.raft.api.SnapshotStore;
import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.raftlog.storage.RaftStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RemediationTest(phase = "3", scenarioPrefix = "RAFT-LOG")
class RaftNodeLogSequencingTest {
    private JavaRuntime runtime;
    private GatedRaftStorage storage;
    private RaftNode node;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        storage = new GatedRaftStorage();
        storage.open(null).join();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("leader-1")
                .clusterNodes(Set.of("leader-1"))
                .transport(new InMemoryTransportSimulator("leader-1"))
                .stateMachine(new QraftStateStore())
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(storage, storage))
                .electionTimeout(25)
                .heartbeatInterval(10_000)
                .build();
        await(node.start());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.onSpinWait();
        if (!node.isLeader()) throw new AssertionError("single node did not become leader");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) await(node.stop());
        if (runtime != null) {
            runtime.shutdown().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        InMemoryTransportSimulator.clearAllTransports();
    }

    @Test
    void secondLeaderSubmissionCannotPrepareOrPersistUntilFirstAppendApplies() {
        storage.blockNextAppendCompletion();

        Future<RaftCommandResult<?>> first = node.submitCommand(put("first", "one"));
        storage.awaitBlockedAppend();
        Future<RaftCommandResult<?>> second = node.submitCommand(put("second", "two"));

        awaitStateLoop();
        storage.assertAppendCount(1);
        assertFalse(first.isComplete());
        assertFalse(second.isComplete());

        storage.releaseBlockedAppend();

        assertInstanceOf(RaftCommandResult.Success.class, await(first));
        assertInstanceOf(RaftCommandResult.Success.class, await(second));
        assertEquals(List.of(1L, 2L), storage.logEntries().stream()
                .map(RaftStorage.LogEntryData::index)
                .toList());
        assertEquals(3, node.getLogSize());
        storage.assertSyncCount(2);
    }

    @Test
    void commandEncodingFailureDoesNotFenceOrReachTheWal() {
        await(node.stop());
        storage = new GatedRaftStorage();
        storage.open(null).join();
        AtomicBoolean rejectEncoding = new AtomicBoolean(true);
        ProtobufRaftCommandCodec delegate = new ProtobufRaftCommandCodec();
        CommandCodec<RaftCommand> codec = new CommandCodec<>() {
            @Override public byte[] serialize(RaftCommand command) {
                if (rejectEncoding.get()) throw new IllegalArgumentException("invalid command encoding");
                return delegate.serialize(command);
            }
            @Override public RaftCommand deserialize(byte[] bytes) { return delegate.deserialize(bytes); }
        };
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("leader-1")
                .clusterNodes(Set.of("leader-1"))
                .transport(new InMemoryTransportSimulator("leader-1"))
                .stateMachine(new QraftStateStore())
                .commandCodec(codec)
                .mode(RaftNodeMode.durable(storage, storage))
                .electionTimeout(25)
                .heartbeatInterval(10_000)
                .build();
        await(node.start());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(node.isLeader());

        CompletionException failure = assertThrows(CompletionException.class,
                () -> await(node.submitCommand(put("invalid", "encoding"))));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertFalse(node.isFenced());
        storage.assertAppendCount(0);

        rejectEncoding.set(false);
        assertInstanceOf(RaftCommandResult.Success.class,
                await(node.submitCommand(put("valid", "encoding"))));
        storage.assertAppendCount(1);
    }

    @Test
    void saturatedClientAdmissionPreservesRoomForHigherTermPeerTraffic() {
        await(node.stop());
        storage = new GatedRaftStorage();
        storage.open(null).join();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("leader-1")
                .clusterNodes(Set.of("leader-1"))
                .transport(new InMemoryTransportSimulator("leader-1"))
                .stateMachine(new QraftStateStore())
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(storage, storage))
                .transitionQueueCapacity(2)
                .electionTimeout(25)
                .heartbeatInterval(10_000)
                .build();
        await(node.start());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(node.isLeader());

        storage.blockNextSyncCompletion();
        Future<RaftCommandResult<?>> active = node.submitCommand(put("active", "one"));
        storage.awaitBlockedSync();
        Future<RaftCommandResult<?>> overflow = node.submitCommand(put("overflow", "rejected"));
        Future<AppendEntriesResponse> higherTerm = node.handleAppendEntriesRequest(
                appendRequest(node.getCurrentTerm() + 1, 0, 0));

        CompletionException rejection = assertThrows(CompletionException.class,
                () -> await(overflow));
        assertInstanceOf(RaftTransitionSequencer.QueueFullException.class, rejection.getCause());
        assertFalse(higherTerm.isComplete());

        storage.releaseBlockedSync();
        assertInstanceOf(RaftCommandResult.Success.class, await(active));
        assertTrue(await(higherTerm).getSuccess());
        assertEquals(RaftNode.State.FOLLOWER, node.getState());
        storage.assertAppendCount(1);
    }

    @Test
    void higherTermAppendContinuesOnStateLoopAfterForeignMetadataCompletion() {
        storage.completeNextMetadataOffLoop();

        AppendEntriesResponse response = await(node.handleAppendEntriesRequest(appendRequest(
                node.getCurrentTerm() + 1, 0, 0, grpcEntry(node.getCurrentTerm() + 1,
                        "foreign", "completion"))));

        assertTrue(response.getSuccess());
        assertEquals(2, node.getCurrentTerm());
        assertFalse(node.isFenced());
        assertEquals(List.of(1L), storage.logEntries().stream()
                .map(RaftStorage.LogEntryData::index).toList());
    }

    @Test
    void higherTermSafePrewriteRejectionStillAppliesDurableTermWithoutFencing() {
        long higherTerm = node.getCurrentTerm() + 1;
        storage.rejectNextAppendBeforeWrite();

        AppendEntriesResponse rejected = await(node.handleAppendEntriesRequest(appendRequest(
                higherTerm, 0, 0, grpcEntry(higherTerm, "too", "large"))));

        assertFalse(rejected.getSuccess());
        assertEquals(higherTerm, rejected.getTerm());
        assertEquals(higherTerm, node.getCurrentTerm());
        assertFalse(node.isFenced());
        assertTrue(storage.logEntries().isEmpty());

        AppendEntriesResponse heartbeat = await(node.handleAppendEntriesRequest(
                appendRequest(higherTerm, 0, 0)));
        assertTrue(heartbeat.getSuccess());
        assertFalse(node.isFenced());
    }

    @Test
    void prewriteShapedMetadataFailureIsNotRecoveredAsAppendRejection() {
        long originalTerm = node.getCurrentTerm();
        storage.rejectNextMetadataWithPrewriteShapedFailure();

        AppendEntriesResponse rejected = await(node.handleAppendEntriesRequest(appendRequest(
                originalTerm + 1, 0, 0, grpcEntry(originalTerm + 1,
                        "metadata", "must-remain-durable"))));

        assertFalse(rejected.getSuccess());
        assertEquals(originalTerm, rejected.getTerm());
        assertEquals(originalTerm, node.getCurrentTerm());
        assertTrue(node.isFenced());
        assertTrue(storage.logEntries().isEmpty());
        storage.assertAppendCount(0);
    }

    @Test
    void leaderAppendWaitsForSyncBeforeMemoryAndClientCompletion() throws Exception {
        storage.blockNextSyncCompletion();
        CompletableFuture<JavaRuntime> completionContext = new CompletableFuture<>();

        Future<RaftCommandResult<?>> first = node.submitCommand(put("sync", "barrier"));
        first.onSuccess(ignored -> completionContext.complete(JavaRuntime.currentContext()));
        storage.awaitBlockedSync();
        Future<RaftCommandResult<?>> second = node.submitCommand(put("after-sync", "queued"));

        awaitStateLoop();

        assertEquals(1, node.getLogSize(), "Only the snapshot sentinel may be visible before sync");
        assertFalse(first.isComplete());
        assertFalse(second.isComplete());
        storage.assertAppendCount(1);
        storage.assertSyncCount(1);

        storage.releaseBlockedSync();

        assertSame(runtime, completionContext.get(2, TimeUnit.SECONDS));
        assertInstanceOf(RaftCommandResult.Success.class, await(first));
        assertInstanceOf(RaftCommandResult.Success.class, await(second));
        assertEquals(3, node.getLogSize());
        storage.assertAppendCount(2);
        storage.assertSyncCount(2);
    }

    @Test
    void uncertainLeaderSyncFailureFencesLaterAppend() {
        storage.failNextSync();
        RemediationTestExtension.logExpectedFailure(
                "leader-sync", storage.syncFailure);

        CompletionException failedSync = assertThrows(CompletionException.class,
                () -> await(node.submitCommand(put("first", "uncertain"))));
        assertSame(storage.syncFailure, failedSync.getCause());
        assertEquals(1, node.getLogSize());
        storage.assertAppendCount(1);
        storage.assertSyncCount(1);

        CompletionException fenced = assertThrows(CompletionException.class,
                () -> await(node.submitCommand(put("second", "blocked"))));
        assertInstanceOf(RaftTransitionSequencer.FencedException.class, fenced.getCause());
        storage.assertAppendCount(1);
        storage.assertSyncCount(1);
    }

    @Test
    void followerRequestCannotPrepareFromLogBeingReplacedByEarlierRequest() {
        restartAsFollower();
        AppendEntriesResponse seed = await(node.handleAppendEntriesRequest(appendRequest(
                1, 0, 0, grpcEntry(1, "seed", "original"))));
        assertTrue(seed.getSuccess());
        storage.assertAppendCount(1);

        storage.blockNextTruncateCompletion();
        Future<AppendEntriesResponse> replacement = node.handleAppendEntriesRequest(appendRequest(
                1, 0, 0, grpcEntry(2, "seed", "replacement")));
        storage.awaitBlockedTruncate();

        Future<AppendEntriesResponse> stalePlan = node.handleAppendEntriesRequest(appendRequest(
                1, 1, 1, grpcEntry(1, "later", "must-not-append")));

        awaitStateLoop();
        storage.assertTruncateCount(1);
        storage.assertAppendCount(1);
        assertFalse(replacement.isComplete());
        assertFalse(stalePlan.isComplete());

        storage.releaseBlockedTruncate();

        assertTrue(await(replacement).getSuccess());
        assertFalse(await(stalePlan).getSuccess(),
                "The second request's prevLogTerm is stale after the first replacement applies");
        assertEquals(List.of(1L), storage.logEntries().stream()
                .map(RaftStorage.LogEntryData::index)
                .toList());
        assertEquals(List.of(2L), storage.logEntries().stream()
                .map(RaftStorage.LogEntryData::term)
                .toList());
    }

    @Test
    void followerRequestCannotEvaluatePreviousIndexUntilEarlierAppendApplies() {
        restartAsFollower();
        storage.blockNextAppendCompletion();

        Future<AppendEntriesResponse> first = node.handleAppendEntriesRequest(appendRequest(
                1, 0, 0, grpcEntry(1, "first", "one")));
        storage.awaitBlockedAppend();
        Future<AppendEntriesResponse> second = node.handleAppendEntriesRequest(appendRequest(
                1, 1, 1, grpcEntry(1, "second", "two")));

        awaitStateLoop();
        storage.assertAppendCount(1);
        assertFalse(first.isComplete());
        assertFalse(second.isComplete());

        storage.releaseBlockedAppend();

        assertTrue(await(first).getSuccess());
        assertTrue(await(second).getSuccess());
        assertEquals(List.of(1L, 2L), storage.logEntries().stream()
                .map(RaftStorage.LogEntryData::index)
                .toList());
        storage.assertAppendCount(2);
        storage.assertSyncCount(2);
    }

    @Test
    void followerRequestCannotObserveAppendUntilEarlierSyncCompletes() {
        restartAsFollower();
        assertTrue(await(node.handleAppendEntriesRequest(appendRequest(
                1, 0, 0, grpcEntry(1, "seed", "one")))).getSuccess());

        storage.blockNextSyncCompletion();
        Future<AppendEntriesResponse> first = node.handleAppendEntriesRequest(appendRequest(
                1, 1, 1, grpcEntry(1, "first", "two")));
        storage.awaitBlockedSync();
        Future<AppendEntriesResponse> second = node.handleAppendEntriesRequest(appendRequest(
                1, 2, 1, grpcEntry(1, "second", "three")));

        awaitStateLoop();
        storage.assertAppendCount(2);
        storage.assertSyncCount(2);
        assertFalse(first.isComplete());
        assertFalse(second.isComplete());

        storage.releaseBlockedSync();

        assertTrue(await(first).getSuccess());
        assertTrue(await(second).getSuccess());
        assertEquals(List.of(1L, 2L, 3L), storage.logEntries().stream()
                .map(RaftStorage.LogEntryData::index)
                .toList());
        storage.assertAppendCount(3);
        storage.assertSyncCount(3);
    }

    @Test
    void uncertainFollowerTruncateFailureFencesLaterAppend() {
        restartAsFollower();
        assertTrue(await(node.handleAppendEntriesRequest(appendRequest(
                1, 0, 0, grpcEntry(1, "seed", "original")))).getSuccess());
        storage.failNextTruncate();
        RemediationTestExtension.logExpectedFailure(
                "follower-suffix-truncate", storage.truncateFailure);

        AppendEntriesResponse failed = await(node.handleAppendEntriesRequest(appendRequest(
                1, 0, 0, grpcEntry(2, "seed", "replacement"))));
        assertFalse(failed.getSuccess());
        storage.assertTruncateCount(1);
        storage.assertAppendCount(1);

        AppendEntriesResponse fenced = await(node.handleAppendEntriesRequest(appendRequest(
                1, 0, 0, grpcEntry(1, "later", "blocked"))));
        assertFalse(fenced.getSuccess());
        storage.assertTruncateCount(1);
        storage.assertAppendCount(1);
    }

    @Test
    void uncertainFollowerFailureFencesWorkThatWasAlreadyQueued() {
        restartAsFollower();
        assertTrue(await(node.handleAppendEntriesRequest(appendRequest(
                1, 0, 0, grpcEntry(1, "seed", "original")))).getSuccess());
        storage.blockNextTruncateCompletion();

        Future<AppendEntriesResponse> failing = node.handleAppendEntriesRequest(appendRequest(
                1, 0, 0, grpcEntry(2, "seed", "replacement")));
        storage.awaitBlockedTruncate();
        Future<AppendEntriesResponse> queued = node.handleAppendEntriesRequest(appendRequest(
                1, 1, 1, grpcEntry(1, "queued", "must-not-persist")));

        awaitStateLoop();
        assertFalse(failing.isComplete());
        assertFalse(queued.isComplete());
        storage.failBlockedTruncate();

        assertFalse(await(failing).getSuccess());
        assertFalse(await(queued).getSuccess());
        assertTrue(node.isFenced());
        storage.assertTruncateCount(1);
        storage.assertAppendCount(1);
    }

    @Test
    void prewriteAppendRejectionAfterSuffixTruncationFencesNode() {
        restartAsFollower();
        assertTrue(await(node.handleAppendEntriesRequest(appendRequest(
                1, 0, 0, grpcEntry(1, "seed", "original")))).getSuccess());
        storage.rejectNextAppendBeforeWrite();

        AppendEntriesResponse failed = await(node.handleAppendEntriesRequest(appendRequest(
                1, 0, 0, grpcEntry(2, "seed", "oversized-replacement"))));

        assertFalse(failed.getSuccess());
        assertTrue(node.isFenced(),
                "The suffix was already truncated before append rejection, so the outcome is partial");
        storage.assertTruncateCount(1);
        storage.assertAppendCount(2);

        AppendEntriesResponse fenced = await(node.handleAppendEntriesRequest(
                appendRequest(1, 0, 0)));
        assertFalse(fenced.getSuccess());
        storage.assertTruncateCount(1);
        storage.assertAppendCount(2);
    }

    private void restartAsFollower() {
        await(node.stop());
        storage = new GatedRaftStorage();
        storage.open(null).join();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("follower-1")
                .clusterNodes(Set.of("follower-1", "leader-1"))
                .transport(new InMemoryTransportSimulator("follower-1"))
                .stateMachine(new QraftStateStore())
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(storage, storage))
                .electionTimeout(60_000)
                .heartbeatInterval(10_000)
                .build();
        await(node.start());
    }

    private static AppendEntriesRequest appendRequest(
            long term, long prevIndex, long prevTerm,
            dev.mars.qraft.controller.raft.grpc.LogEntry... entries) {
        return AppendEntriesRequest.newBuilder()
                .setTerm(term)
                .setLeaderId("leader-1")
                .setPrevLogIndex(prevIndex)
                .setPrevLogTerm(prevTerm)
                .addAllEntries(List.of(entries))
                .build();
    }

    private static dev.mars.qraft.controller.raft.grpc.LogEntry grpcEntry(
            long term, String key, String value) {
        byte[] payload = new ProtobufRaftCommandCodec().serialize(put(key, value));
        return dev.mars.qraft.controller.raft.grpc.LogEntry.newBuilder()
                .setTerm(term)
                .setData(ByteString.copyFrom(payload))
                .build();
    }

    private static DistributedStateRaftCommand put(String key, String value) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, value));
    }

    private static <T> T await(Future<T> future) {
        return future.timeout(5, TimeUnit.SECONDS).toCompletionStage().toCompletableFuture().join();
    }

    private void awaitStateLoop() {
        CompletableFuture<Void> marker = new CompletableFuture<>();
        runtime.runOnContext(ignored -> marker.complete(null));
        try {
            marker.get(2, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new AssertionError("state-loop marker did not run", error);
        }
    }

    private static final class GatedRaftStorage implements RaftStorage, SnapshotStore {
        private final TestRaftStorage delegate = new TestRaftStorage();
        private final AtomicInteger appendCount = new AtomicInteger();
        private final AtomicInteger truncateCount = new AtomicInteger();
        private final AtomicInteger syncCount = new AtomicInteger();
        private final IllegalStateException syncFailure =
                new IllegalStateException("uncertain sync outcome");
        private final IllegalStateException truncateFailure =
                new IllegalStateException("uncertain truncate outcome");
        private volatile CompletableFuture<Void> appendCompletionGate;
        private volatile CompletableFuture<Void> blockedAppendGate;
        private volatile CompletableFuture<Void> appendEntered;
        private volatile CompletableFuture<Void> truncateCompletionGate;
        private volatile CompletableFuture<Void> blockedTruncateGate;
        private volatile CompletableFuture<Void> truncateEntered;
        private volatile CompletableFuture<Void> syncCompletionGate;
        private volatile CompletableFuture<Void> blockedSyncGate;
        private volatile CompletableFuture<Void> syncEntered;
        private volatile boolean failNextSync;
        private volatile boolean failNextTruncate;
        private volatile boolean completeNextMetadataOffLoop;
        private volatile boolean rejectNextMetadataWithPrewriteShapedFailure;
        private volatile boolean rejectNextAppendBeforeWrite;

        void blockNextAppendCompletion() {
            appendCompletionGate = new CompletableFuture<>();
            appendEntered = new CompletableFuture<>();
        }

        void awaitBlockedAppend() {
            try {
                appendEntered.get(2, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new AssertionError("append did not reach its completion gate", error);
            }
        }

        void releaseBlockedAppend() {
            blockedAppendGate.complete(null);
        }

        void blockNextTruncateCompletion() {
            truncateCompletionGate = new CompletableFuture<>();
            truncateEntered = new CompletableFuture<>();
        }

        void awaitBlockedTruncate() {
            try {
                truncateEntered.get(2, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new AssertionError("truncate did not reach its completion gate", error);
            }
        }

        void releaseBlockedTruncate() {
            blockedTruncateGate.complete(null);
        }

        void failBlockedTruncate() {
            blockedTruncateGate.completeExceptionally(truncateFailure);
        }

        void blockNextSyncCompletion() {
            syncCompletionGate = new CompletableFuture<>();
            syncEntered = new CompletableFuture<>();
        }

        void awaitBlockedSync() {
            try {
                syncEntered.get(2, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new AssertionError("sync did not reach its completion gate", error);
            }
        }

        void releaseBlockedSync() {
            blockedSyncGate.complete(null);
        }

        void failNextSync() { failNextSync = true; }

        void failNextTruncate() { failNextTruncate = true; }

        void completeNextMetadataOffLoop() { completeNextMetadataOffLoop = true; }

        void rejectNextMetadataWithPrewriteShapedFailure() {
            rejectNextMetadataWithPrewriteShapedFailure = true;
        }

        void rejectNextAppendBeforeWrite() { rejectNextAppendBeforeWrite = true; }

        void assertAppendCount(int expected) { assertEquals(expected, appendCount.get()); }

        void assertTruncateCount(int expected) { assertEquals(expected, truncateCount.get()); }

        void assertSyncCount(int expected) { assertEquals(expected, syncCount.get()); }

        List<LogEntryData> logEntries() { return delegate.getLog(); }

        @Override public CompletableFuture<Void> open(Path dataDir) { return delegate.open(dataDir); }
        @Override public CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor) {
            if (rejectNextMetadataWithPrewriteShapedFailure) {
                rejectNextMetadataWithPrewriteShapedFailure = false;
                return CompletableFuture.failedFuture(
                        new FileRaftStorage.WriteRejectedException(
                                dev.mars.raftlog.storage.WriteRejectionReason.PAYLOAD_TOO_LARGE,
                                "metadata fixture"));
            }
            CompletableFuture<Void> persisted = delegate.updateMetadata(term, votedFor);
            if (!completeNextMetadataOffLoop) return persisted;
            completeNextMetadataOffLoop = false;
            CompletableFuture<Void> completion = new CompletableFuture<>();
            Thread.ofPlatform().name("foreign-metadata-completion").start(() ->
                    persisted.whenComplete((ignored, error) -> {
                        if (error == null) completion.complete(null);
                        else completion.completeExceptionally(error);
                    }));
            return completion;
        }
        @Override public CompletableFuture<PersistentMeta> loadMetadata() { return delegate.loadMetadata(); }

        @Override
        public CompletableFuture<Void> appendEntries(List<LogEntryData> entries) {
            appendCount.incrementAndGet();
            if (rejectNextAppendBeforeWrite) {
                rejectNextAppendBeforeWrite = false;
                return CompletableFuture.failedFuture(
                        new FileRaftStorage.WriteRejectedException(
                                dev.mars.raftlog.storage.WriteRejectionReason.PAYLOAD_TOO_LARGE,
                                "test fixture"));
            }
            CompletableFuture<Void> persisted = delegate.appendEntries(entries);
            CompletableFuture<Void> gate = appendCompletionGate;
            if (gate == null) return persisted;

            appendCompletionGate = null;
            blockedAppendGate = gate;
            appendEntered.complete(null);
            return persisted.thenCompose(ignored -> gate);
        }

        @Override public CompletableFuture<Void> truncateSuffix(long fromIndex) {
            truncateCount.incrementAndGet();
            CompletableFuture<Void> persisted = delegate.truncateSuffix(fromIndex);
            if (failNextTruncate) {
                failNextTruncate = false;
                return persisted.thenCompose(ignored -> CompletableFuture.failedFuture(truncateFailure));
            }
            CompletableFuture<Void> gate = truncateCompletionGate;
            if (gate == null) return persisted;

            truncateCompletionGate = null;
            blockedTruncateGate = gate;
            truncateEntered.complete(null);
            return persisted.thenCompose(ignored -> gate);
        }
        @Override public CompletableFuture<Void> truncatePrefix(long toIndex) {
            return delegate.truncatePrefix(toIndex);
        }
        @Override public CompletableFuture<Void> sync() {
            syncCount.incrementAndGet();
            if (failNextSync) {
                failNextSync = false;
                return CompletableFuture.failedFuture(syncFailure);
            }
            CompletableFuture<Void> persisted = delegate.sync();
            CompletableFuture<Void> gate = syncCompletionGate;
            if (gate == null) return persisted;

            syncCompletionGate = null;
            blockedSyncGate = gate;
            syncEntered.complete(null);
            return persisted.thenCompose(ignored -> gate);
        }
        @Override public CompletableFuture<List<LogEntryData>> replayLog() { return delegate.replayLog(); }
        @Override public CompletableFuture<Void> saveAtomically(SnapshotData snapshot) {
            return delegate.saveAtomically(snapshot);
        }
        @Override public CompletableFuture<Optional<SnapshotData>> loadLatest() { return delegate.loadLatest(); }
        @Override public void close() { delegate.close(); }
        @Override public CompletableFuture<Void> closeAsync() { return SnapshotStore.super.closeAsync(); }
    }
}

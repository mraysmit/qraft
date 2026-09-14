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

import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.controller.state.RaftCommand;
import dev.mars.qraft.controller.state.RaftCommandResult;
import dev.mars.qraft.controller.testsupport.RemediationTest;
import dev.mars.qraft.controller.testsupport.RemediationTestExtension;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RemediationTest(phase = "4", scenarioPrefix = "RAFT-SNAPSHOT")
class RaftNodeSnapshotSequencingTest {
    private JavaRuntime runtime;
    private GatedSnapshotStorage storage;
    private RecordingStateMachine stateMachine;
    private RaftNode node;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        storage = new GatedSnapshotStorage();
        storage.open(null).join();
        stateMachine = new RecordingStateMachine();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("leader-1")
                .clusterNodes(Set.of("leader-1"))
                .transport(new InMemoryTransportSimulator("leader-1"))
                .stateMachine(stateMachine)
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(false)
                .electionTimeout(25)
                .heartbeatInterval(10_000)
                .build();
        await(node.start());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.onSpinWait();
        if (!node.isLeader()) throw new AssertionError("single node did not become leader");
        assertInstanceOf(RaftCommandResult.Success.class,
                await(node.submitCommand(put("before", "captured"))));
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
    void publicationBlocksLaterAppendAndCapturesStateOnTheOwningLoop() {
        storage.blockNextSnapshotPublication();

        Future<Void> snapshot = node.takeSnapshot();
        storage.awaitBlockedSnapshotPublication();
        Future<RaftCommandResult<?>> later = node.submitCommand(put("after", "excluded"));

        awaitStateLoop();
        assertSame(runtime, stateMachine.snapshotContext());
        storage.assertSaveCount(1);
        storage.assertPrefixTruncateCount(0);
        storage.assertAppendCount(1);
        assertEquals(0, node.getSnapshotLastIndex());
        assertFalse(snapshot.isComplete());
        assertFalse(later.isComplete());

        storage.releaseBlockedSnapshotPublication();

        await(snapshot);
        assertInstanceOf(RaftCommandResult.Success.class, await(later));
        assertEquals(1, node.getSnapshotLastIndex());
        SnapshotStore.SnapshotData published = storage.latestSnapshot().orElseThrow();
        assertEquals(1, published.lastIncludedIndex());
        assertEquals(1, published.lastIncludedTerm());
        QraftStateStore restored = new QraftStateStore();
        restored.restoreSnapshot(published.data());
        assertEquals("captured", restored.getMetadata("before"));
        assertNull(restored.getMetadata("after"));
        assertEquals(published.lastIncludedIndex(), restored.getLastAppliedIndex());
        assertEquals(List.of(2L), storage.logEntries().stream()
                .map(RaftStorage.LogEntryData::index)
                .toList());
    }

    @Test
    void prefixCompactionBlocksLaterAppendAndBoundaryApplication() {
        storage.blockNextPrefixCompaction();

        Future<Void> snapshot = node.takeSnapshot();
        storage.awaitBlockedPrefixCompaction();
        Future<RaftCommandResult<?>> later = node.submitCommand(put("after", "compaction"));

        awaitStateLoop();
        storage.assertSaveCount(1);
        storage.assertPrefixTruncateCount(1);
        storage.assertAppendCount(1);
        assertEquals(0, node.getSnapshotLastIndex());
        assertFalse(snapshot.isComplete());
        assertFalse(later.isComplete());

        storage.releaseBlockedPrefixCompaction();

        await(snapshot);
        assertInstanceOf(RaftCommandResult.Success.class, await(later));
        assertEquals(1, node.getSnapshotLastIndex());
        assertEquals(List.of(2L), storage.logEntries().stream()
                .map(RaftStorage.LogEntryData::index)
                .toList());
    }

    @Test
    void snapshotWaitsForInFlightCommandAndCapturesItsAppliedState() {
        storage.blockNextSyncCompletion();

        Future<RaftCommandResult<?>> command = node.submitCommand(put("during", "included"));
        storage.awaitBlockedSync();
        Future<Void> snapshot = node.takeSnapshot();

        awaitStateLoop();
        storage.assertSaveCount(0);
        assertFalse(command.isComplete());
        assertFalse(snapshot.isComplete());

        storage.releaseBlockedSync();

        assertInstanceOf(RaftCommandResult.Success.class, await(command));
        await(snapshot);
        SnapshotStore.SnapshotData published = storage.latestSnapshot().orElseThrow();
        assertEquals(2, published.lastIncludedIndex());
        assertEquals(1, published.lastIncludedTerm());
        QraftStateStore restored = new QraftStateStore();
        restored.restoreSnapshot(published.data());
        assertEquals("captured", restored.getMetadata("before"));
        assertEquals("included", restored.getMetadata("during"));
        assertEquals(published.lastIncludedIndex(), restored.getLastAppliedIndex());
    }

    @Test
    void higherTermVoteCannotOvertakeSnapshotPublication() {
        storage.blockNextSnapshotPublication();

        Future<Void> snapshot = node.takeSnapshot();
        storage.awaitBlockedSnapshotPublication();
        Future<VoteResponse> vote = node.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(2)
                .setCandidateId("candidate-2")
                .setLastLogIndex(1)
                .setLastLogTerm(1)
                .build());

        awaitStateLoop();
        storage.assertMetadataUpdateCount(1);
        assertEquals(1, node.getCurrentTerm());
        assertEquals(RaftNode.State.LEADER, node.getState());
        assertFalse(vote.isComplete());

        storage.releaseBlockedSnapshotPublication();

        await(snapshot);
        VoteResponse response = await(vote);
        assertTrue(response.getVoteGranted());
        assertEquals(2, response.getTerm());
        assertEquals(1, node.getSnapshotLastIndex());
        assertEquals(RaftNode.State.FOLLOWER, node.getState());
        storage.assertMetadataUpdateCount(2);
    }

    @Test
    void concurrentDirectSnapshotsPublishAndCompactOneBoundaryOnce() {
        storage.blockNextSnapshotPublication();

        Future<Void> first = node.takeSnapshot();
        storage.awaitBlockedSnapshotPublication();
        Future<Void> second = node.takeSnapshot();

        awaitStateLoop();
        storage.assertSaveCount(1);
        storage.assertPrefixTruncateCount(0);
        assertFalse(first.isComplete());
        assertFalse(second.isComplete());

        storage.releaseBlockedSnapshotPublication();

        await(first);
        await(second);
        storage.assertSaveCount(1);
        storage.assertPrefixTruncateCount(1);
        assertEquals(1, node.getSnapshotLastIndex());
    }

    @Test
    void publicationFailureLeavesWalAndMemoryUnchangedAndAllowsLaterWork() {
        storage.failNextSnapshotPublication();
        RemediationTestExtension.logExpectedFailure(
                "local-snapshot-publication", storage.publicationFailure);

        CompletionException failure = assertThrows(CompletionException.class,
                () -> await(node.takeSnapshot()));
        assertSame(storage.publicationFailure, failure.getCause());
        assertEquals(0, node.getSnapshotLastIndex());
        storage.assertPrefixTruncateCount(0);
        assertEquals(List.of(1L), storage.logEntries().stream()
                .map(RaftStorage.LogEntryData::index)
                .toList());

        assertInstanceOf(RaftCommandResult.Success.class,
                await(node.submitCommand(put("after", "publication-failure"))));
        storage.assertAppendCount(2);
    }

    @Test
    void uncertainCompactionFailureLeavesMemoryUntrimmedAndFencesLaterWork() {
        storage.failNextPrefixCompaction();
        RemediationTestExtension.logExpectedFailure(
                "local-snapshot-prefix-compaction", storage.compactionFailure);

        CompletionException failure = assertThrows(CompletionException.class,
                () -> await(node.takeSnapshot()));
        assertSame(storage.compactionFailure, failure.getCause());
        assertEquals(0, node.getSnapshotLastIndex());
        storage.assertSaveCount(1);
        storage.assertPrefixTruncateCount(1);

        CompletionException fenced = assertThrows(CompletionException.class,
                () -> await(node.submitCommand(put("after", "must-not-run"))));
        assertInstanceOf(RaftTransitionSequencer.FencedException.class, fenced.getCause());
        storage.assertAppendCount(1);
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

    private static final class RecordingStateMachine implements RaftLogApplicator {
        private final QraftStateStore delegate = new QraftStateStore();
        private volatile JavaRuntime snapshotContext;

        JavaRuntime snapshotContext() { return snapshotContext; }

        @Override public RaftCommandResult<?> apply(RaftCommand command) { return delegate.apply(command); }
        @Override public byte[] takeSnapshot() {
            snapshotContext = JavaRuntime.currentContext();
            return delegate.takeSnapshot();
        }
        @Override public void restoreSnapshot(byte[] snapshot) { delegate.restoreSnapshot(snapshot); }
        @Override public long getLastAppliedIndex() { return delegate.getLastAppliedIndex(); }
        @Override public void setLastAppliedIndex(long index) { delegate.setLastAppliedIndex(index); }
        @Override public void reset() { delegate.reset(); }
    }

    private static final class GatedSnapshotStorage implements RaftStorage, SnapshotStore {
        private final TestRaftStorage delegate = new TestRaftStorage();
        private final AtomicInteger appendCount = new AtomicInteger();
        private final AtomicInteger metadataUpdateCount = new AtomicInteger();
        private final AtomicInteger saveCount = new AtomicInteger();
        private final AtomicInteger prefixTruncateCount = new AtomicInteger();
        private final IllegalStateException publicationFailure =
                new IllegalStateException("snapshot publication failed");
        private final IllegalStateException compactionFailure =
                new IllegalStateException("uncertain prefix compaction outcome");
        private volatile CompletableFuture<Void> publicationGate;
        private volatile CompletableFuture<Void> blockedPublicationGate;
        private volatile CompletableFuture<Void> publicationEntered;
        private volatile CompletableFuture<Void> compactionGate;
        private volatile CompletableFuture<Void> blockedCompactionGate;
        private volatile CompletableFuture<Void> compactionEntered;
        private volatile CompletableFuture<Void> syncGate;
        private volatile CompletableFuture<Void> blockedSyncGate;
        private volatile CompletableFuture<Void> syncEntered;
        private volatile boolean failNextPublication;
        private volatile boolean failNextCompaction;

        void blockNextSnapshotPublication() {
            publicationGate = new CompletableFuture<>();
            publicationEntered = new CompletableFuture<>();
        }

        void awaitBlockedSnapshotPublication() {
            awaitGate(publicationEntered, "snapshot publication");
        }

        void releaseBlockedSnapshotPublication() { blockedPublicationGate.complete(null); }

        void blockNextPrefixCompaction() {
            compactionGate = new CompletableFuture<>();
            compactionEntered = new CompletableFuture<>();
        }

        void awaitBlockedPrefixCompaction() { awaitGate(compactionEntered, "prefix compaction"); }

        void releaseBlockedPrefixCompaction() { blockedCompactionGate.complete(null); }

        void failNextSnapshotPublication() { failNextPublication = true; }

        void failNextPrefixCompaction() { failNextCompaction = true; }

        void blockNextSyncCompletion() {
            syncGate = new CompletableFuture<>();
            syncEntered = new CompletableFuture<>();
        }

        void awaitBlockedSync() { awaitGate(syncEntered, "sync"); }

        void releaseBlockedSync() { blockedSyncGate.complete(null); }

        void assertAppendCount(int expected) { assertEquals(expected, appendCount.get()); }
        void assertMetadataUpdateCount(int expected) { assertEquals(expected, metadataUpdateCount.get()); }
        void assertSaveCount(int expected) { assertEquals(expected, saveCount.get()); }
        void assertPrefixTruncateCount(int expected) { assertEquals(expected, prefixTruncateCount.get()); }
        List<LogEntryData> logEntries() { return delegate.getLog(); }
        Optional<SnapshotData> latestSnapshot() { return delegate.loadLatest().join(); }

        @Override public CompletableFuture<Void> open(Path dataDir) { return delegate.open(dataDir); }
        @Override public CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor) {
            metadataUpdateCount.incrementAndGet();
            return delegate.updateMetadata(term, votedFor);
        }
        @Override public CompletableFuture<PersistentMeta> loadMetadata() { return delegate.loadMetadata(); }
        @Override public CompletableFuture<Void> appendEntries(List<LogEntryData> entries) {
            appendCount.incrementAndGet();
            return delegate.appendEntries(entries);
        }
        @Override public CompletableFuture<Void> truncateSuffix(long fromIndex) {
            return delegate.truncateSuffix(fromIndex);
        }
        @Override public CompletableFuture<Void> truncatePrefix(long toIndex) {
            prefixTruncateCount.incrementAndGet();
            CompletableFuture<Void> persisted = delegate.truncatePrefix(toIndex);
            if (failNextCompaction) {
                failNextCompaction = false;
                return persisted.thenCompose(ignored -> CompletableFuture.failedFuture(compactionFailure));
            }
            CompletableFuture<Void> gate = compactionGate;
            if (gate == null) return persisted;
            compactionGate = null;
            blockedCompactionGate = gate;
            compactionEntered.complete(null);
            return gate.thenCompose(ignored -> persisted);
        }
        @Override public CompletableFuture<Void> sync() {
            CompletableFuture<Void> persisted = delegate.sync();
            CompletableFuture<Void> gate = syncGate;
            if (gate == null) return persisted;
            syncGate = null;
            blockedSyncGate = gate;
            syncEntered.complete(null);
            return persisted.thenCompose(ignored -> gate);
        }
        @Override public CompletableFuture<List<LogEntryData>> replayLog() { return delegate.replayLog(); }
        @Override public CompletableFuture<Void> saveAtomically(SnapshotData snapshot) {
            saveCount.incrementAndGet();
            if (failNextPublication) {
                failNextPublication = false;
                return CompletableFuture.failedFuture(publicationFailure);
            }
            CompletableFuture<Void> gate = publicationGate;
            if (gate == null) return delegate.saveAtomically(snapshot);
            publicationGate = null;
            blockedPublicationGate = gate;
            publicationEntered.complete(null);
            return gate.thenCompose(ignored -> delegate.saveAtomically(snapshot));
        }
        @Override public CompletableFuture<Optional<SnapshotData>> loadLatest() {
            return delegate.loadLatest();
        }
        @Override public void close() { delegate.close(); }

        private static void awaitGate(CompletableFuture<Void> entered, String operation) {
            try {
                entered.get(2, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new AssertionError(operation + " did not reach its gate", error);
            }
        }
    }
}

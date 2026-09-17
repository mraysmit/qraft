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
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RemediationTest(phase = "6-shutdown", scenarioPrefix = "RAFT-SHUTDOWN")
class RaftNodeShutdownSequencingTest {
    private JavaRuntime runtime;
    private ShutdownStorage storage;
    private ShutdownTransport transport;
    private RaftNode node;
    private List<String> closeEvents;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        closeEvents = new CopyOnWriteArrayList<>();
        storage = new ShutdownStorage(closeEvents);
        storage.open(null).join();
        transport = new ShutdownTransport(closeEvents);
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("node-1")
                .clusterNodes(Set.of("node-1"))
                .transport(transport)
                .stateMachine(new QraftStateStore())
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(false)
                .electionTimeout(25)
                .heartbeatInterval(10_000)
                .build();
        await(node.start());
        awaitLeader();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) {
            storage.releaseBlockedSyncIfPresent();
            storage.releaseBlockedMetadataLoadIfPresent();
            storage.releaseBlockedSnapshotPublicationIfPresent();
            storage.releaseBlockedPrefixCompactionIfPresent();
            try {
                await(node.stop());
            } catch (CompletionException ignored) {
                // Individual tests assert deliberate close failures themselves.
            }
        }
        if (runtime != null) {
            runtime.shutdown().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void stopWaitsForAcceptedWalTransitionsBeforeClosingResources() {
        storage.blockNextSync();
        Future<RaftCommandResult<?>> first = node.submitCommand(put("first", "one"));
        storage.awaitBlockedSync();
        Future<RaftCommandResult<?>> queued = node.submitCommand(put("queued", "two"));

        Future<Void> stop = node.stop();
        awaitStateLoop();

        assertFalse(stop.isComplete(), "shutdown must wait for the active WAL transition");
        assertEquals(0, transport.stopCount.get(), "transport must remain open while accepted work drains");
        assertEquals(0, storage.closeCount.get(), "storage must remain open while accepted work drains");

        CompletionException rejected = assertThrows(CompletionException.class,
                () -> await(node.submitCommand(put("late", "rejected"))));
        assertInstanceOf(RaftTransitionSequencer.DrainingException.class, rejected.getCause());

        storage.releaseBlockedSync();

        assertInstanceOf(RaftCommandResult.Success.class, await(first));
        assertInstanceOf(RaftCommandResult.Success.class, await(queued));
        await(stop);
        assertEquals(1, transport.stopCount.get());
        assertEquals(1, storage.closeCount.get(),
                "one object serving both storage contracts must be closed once");
        assertTrue(storage.closed);
        assertEquals(List.of(1L, 2L), storage.appendedIndexes);
    }

    @Test
    void concurrentStopsShareOneCompletionAndCloseResourcesOnce() {
        storage.blockNextSync();
        Future<RaftCommandResult<?>> active = node.submitCommand(put("active", "one"));
        storage.awaitBlockedSync();

        Future<Void> firstStop = node.stop();
        Future<Void> secondStop = node.stop();

        assertSame(firstStop, secondStop, "all shutdown callers must observe one shared operation");
        assertFalse(firstStop.isComplete());

        storage.releaseBlockedSync();
        assertInstanceOf(RaftCommandResult.Success.class, await(active));
        await(firstStop);
        assertEquals(1, transport.stopCount.get());
        assertEquals(1, storage.closeCount.get());
    }

    @Test
    void stopWaitsForAcceptedSnapshotPublicationBeforeClosingResources() {
        assertInstanceOf(RaftCommandResult.Success.class,
                await(node.submitCommand(put("snapshotted", "value"))));
        storage.blockNextSnapshotPublication();
        Future<Void> snapshot = node.takeSnapshot();
        storage.awaitBlockedSnapshotPublication();

        Future<Void> stop = node.stop();
        awaitStateLoop();

        assertFalse(stop.isComplete(), "shutdown must wait for snapshot publication and compaction");
        assertEquals(0, storage.closeCount.get());

        storage.releaseBlockedSnapshotPublication();

        await(snapshot);
        await(stop);
        assertEquals(1, storage.closeCount.get());
        assertEquals(1, storage.prefixTruncateCount.get(),
                "the accepted snapshot must finish WAL compaction before close");
    }

    @Test
    void stopWaitsForPostPublicationPrefixCompactionBeforeClosingResources() {
        assertInstanceOf(RaftCommandResult.Success.class,
                await(node.submitCommand(put("snapshotted", "value"))));
        storage.blockNextPrefixCompaction();

        Future<Void> snapshot = node.takeSnapshot();
        storage.awaitBlockedPrefixCompaction();

        Future<Void> stop = node.stop();
        awaitStateLoop();

        assertFalse(snapshot.isComplete(),
                "snapshot completion must include its post-publication WAL compaction");
        assertFalse(stop.isComplete(),
                "shutdown must not close storage after publication but before prefix compaction");
        assertEquals(0, storage.closeCount.get());

        storage.releaseBlockedPrefixCompaction();

        await(snapshot);
        await(stop);
        assertEquals(1, storage.prefixTruncateCount.get());
        assertEquals(1, storage.closeCount.get());
    }

    @Test
    void resourcesCloseOffStateLoopInTransportThenStorageOrder() {
        await(node.stop());

        assertEquals(List.of("transport", "storage"), closeEvents);
        assertNull(transport.closeContext,
                "transport close must not block the Raft state loop");
        assertNull(storage.closeContext,
                "storage close must not block the Raft state loop");
    }

    @Test
    void stopDuringRecoveryWaitsAndPreventsLateStartup() {
        await(node.stop());
        storage = new ShutdownStorage(closeEvents);
        storage.open(null).join();
        storage.blockNextMetadataLoad();
        transport = new ShutdownTransport(closeEvents);
        node = buildNode(Set.of("node-1"));

        Future<Void> start = node.start();
        storage.awaitBlockedMetadataLoad();
        Future<Void> stop = node.stop();
        awaitStateLoop();

        assertFalse(stop.isComplete(), "shutdown must own an in-flight recovery operation");
        assertEquals(0, storage.closeCount.get());

        storage.releaseBlockedMetadataLoad();

        CompletionException stoppedStart = assertThrows(CompletionException.class,
                () -> await(start));
        assertInstanceOf(RaftTransitionSequencer.DrainingException.class,
                stoppedStart.getCause());
        await(stop);
        assertFalse(node.isRunning(), "recovery completion must not resurrect a stopped node");
        assertEquals(0, transport.startCount.get());
        assertEquals(1, transport.stopCount.get());
        assertEquals(1, storage.closeCount.get());
    }

    @Test
    void partialTransportStartFailureRollsBackAllNodeResources() {
        await(node.stop());
        storage = new ShutdownStorage(closeEvents);
        storage.open(null).join();
        transport = new ShutdownTransport(closeEvents);
        IllegalStateException startFailure =
                new IllegalStateException("transport failed after partial start");
        transport.startFailure = startFailure;
        node = buildNode(Set.of("node-1"));
        RemediationTestExtension.logExpectedFailure(
                "partial-transport-start", startFailure);

        CompletionException failedStart = assertThrows(CompletionException.class,
                () -> await(node.start()));

        assertSame(startFailure, failedStart.getCause());
        assertFalse(node.isRunning());
        assertEquals(1, transport.startCount.get());
        assertEquals(1, transport.stopCount.get(),
                "a transport that throws after partial start must still be stopped");
        assertEquals(1, storage.closeCount.get(),
                "failed startup is terminal and must close its storage ownership");
        assertSame(node.stop(), node.stop());
    }

    @Test
    void lateReplicationResponseCannotMutateStoppedNode() {
        await(node.stop());
        storage = new ShutdownStorage(closeEvents);
        storage.open(null).join();
        transport = new ShutdownTransport(closeEvents);
        transport.holdAppendResponses();
        node = buildNode(Set.of("node-1", "peer-1", "peer-2"));
        await(node.start());
        awaitLeader();

        Future<RaftCommandResult<?>> command = node.submitCommand(put("pending", "commit"));
        transport.awaitHeldAppend();
        Future<Void> stop = node.stop();

        CompletionException unresolved = assertThrows(CompletionException.class,
                () -> await(command));
        assertTrue(unresolved.getCause().getMessage().contains("outcome may be unknown"));
        await(stop);
        long nextIndexAtStop = node.getNextIndex("peer-1");

        transport.completeHeldAppend(1);
        awaitStateLoop();

        assertEquals(RaftNode.State.FOLLOWER, node.getState());
        assertEquals(nextIndexAtStop, node.getNextIndex("peer-1"),
                "late transport completion must not mutate replication state");
    }

    @Test
    void stopBeforeStartClosesResourcesAndMakesNodeTerminal() {
        await(node.stop());
        storage = new ShutdownStorage(closeEvents);
        storage.open(null).join();
        transport = new ShutdownTransport(closeEvents);
        node = buildNode(Set.of("node-1"));

        await(node.stop());

        assertEquals(1, transport.stopCount.get());
        assertEquals(1, storage.closeCount.get());
        CompletionException restart = assertThrows(CompletionException.class,
                () -> await(node.start()));
        assertInstanceOf(RaftTransitionSequencer.DrainingException.class,
                restart.getCause());
    }

    @Test
    void shutdownAttemptsEveryCloseAndReportsCombinedFailure() {
        IllegalStateException transportFailure =
                new IllegalStateException("transport close failed");
        IllegalStateException storageFailure =
                new IllegalStateException("storage close failed");
        transport.stopFailure = transportFailure;
        storage.closeFailure = storageFailure;
        RemediationTestExtension.logExpectedFailure(
                "transport-and-storage-close", transportFailure);

        CompletionException shutdown = assertThrows(CompletionException.class,
                () -> await(node.stop()));

        assertSame(transportFailure, shutdown.getCause());
        assertEquals(List.of(storageFailure),
                List.of(shutdown.getCause().getSuppressed()));
        assertEquals(1, transport.stopCount.get());
        assertEquals(1, storage.closeCount.get());
        assertTrue(storage.closed);
        assertSame(node.stop(), node.stop());
    }

    private RaftNode buildNode(Set<String> members) {
        return RaftNode.builder()
                .runtime(runtime)
                .nodeId("node-1")
                .clusterNodes(members)
                .transport(transport)
                .stateMachine(new QraftStateStore())
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(false)
                .electionTimeout(25)
                .heartbeatInterval(10_000)
                .build();
    }

    private DistributedStateRaftCommand put(String key, String value) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, value));
    }

    private void awaitLeader() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.onSpinWait();
        if (!node.isLeader()) throw new AssertionError("single node did not become leader");
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

    private static <T> T await(Future<T> future) {
        return future.timeout(5, TimeUnit.SECONDS).toCompletionStage().toCompletableFuture().join();
    }

    private static final class ShutdownTransport implements RaftTransport {
        private final List<String> closeEvents;
        private final AtomicInteger startCount = new AtomicInteger();
        private final AtomicInteger stopCount = new AtomicInteger();
        private volatile boolean holdAppendResponses;
        private final List<dev.mars.qraft.controller.runtime.Promise<AppendEntriesResponse>>
                heldAppends = new CopyOnWriteArrayList<>();
        private volatile CompletableFuture<Void> appendHeld;
        private volatile RuntimeException stopFailure;
        private volatile RuntimeException startFailure;
        private volatile JavaRuntime closeContext;

        private ShutdownTransport(List<String> closeEvents) {
            this.closeEvents = closeEvents;
        }

        void holdAppendResponses() {
            holdAppendResponses = true;
            appendHeld = new CompletableFuture<>();
        }

        void awaitHeldAppend() {
            try {
                appendHeld.get(2, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new AssertionError("append request was not held", error);
            }
        }

        void completeHeldAppend(long matchIndex) {
            heldAppends.forEach(response -> response.complete(AppendEntriesResponse.newBuilder()
                    .setTerm(1).setSuccess(true).setMatchIndex(matchIndex).build()));
        }

        @Override public void start(Consumer<RaftMessage> messageHandler) {
            startCount.incrementAndGet();
            if (startFailure != null) throw startFailure;
        }
        @Override public void stop() {
            closeContext = JavaRuntime.currentContext();
            closeEvents.add("transport");
            stopCount.incrementAndGet();
            if (stopFailure != null) throw stopFailure;
        }
        @Override public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return Future.succeededFuture(VoteResponse.newBuilder()
                    .setTerm(request.getTerm()).setVoteGranted(true).build());
        }
        @Override public Future<AppendEntriesResponse> sendAppendEntries(
                String targetId, AppendEntriesRequest request) {
            if (holdAppendResponses && request.getEntriesCount() > 0) {
                dev.mars.qraft.controller.runtime.Promise<AppendEntriesResponse> heldAppend =
                        dev.mars.qraft.controller.runtime.Promise.promise();
                heldAppends.add(heldAppend);
                appendHeld.complete(null);
                return heldAppend.future();
            }
            return Future.succeededFuture(AppendEntriesResponse.newBuilder()
                    .setTerm(request.getTerm()).setSuccess(true).build());
        }
        @Override public Future<InstallSnapshotResponse> sendInstallSnapshot(
                String targetId, InstallSnapshotRequest request) {
            return Future.succeededFuture(InstallSnapshotResponse.newBuilder()
                    .setTerm(request.getTerm()).setSuccess(true).build());
        }
    }

    private static final class ShutdownStorage implements RaftStorage, SnapshotStore {
        private final List<String> closeEvents;
        private final TestRaftStorage delegate = new TestRaftStorage();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicInteger prefixTruncateCount = new AtomicInteger();
        private final List<Long> appendedIndexes = new CopyOnWriteArrayList<>();
        private volatile CompletableFuture<Void> nextSyncGate;
        private volatile CompletableFuture<Void> blockedSyncGate;
        private volatile CompletableFuture<Void> syncEntered;
        private volatile CompletableFuture<Void> nextMetadataGate;
        private volatile CompletableFuture<Void> blockedMetadataGate;
        private volatile CompletableFuture<Void> metadataEntered;
        private volatile CompletableFuture<Void> nextSnapshotPublicationGate;
        private volatile CompletableFuture<Void> blockedSnapshotPublicationGate;
        private volatile CompletableFuture<Void> snapshotPublicationEntered;
        private volatile CompletableFuture<Void> nextPrefixCompactionGate;
        private volatile CompletableFuture<Void> blockedPrefixCompactionGate;
        private volatile CompletableFuture<Void> prefixCompactionEntered;
        private volatile boolean closed;
        private volatile RuntimeException closeFailure;
        private volatile JavaRuntime closeContext;

        private ShutdownStorage(List<String> closeEvents) {
            this.closeEvents = closeEvents;
        }

        void blockNextSync() {
            nextSyncGate = new CompletableFuture<>();
            syncEntered = new CompletableFuture<>();
        }

        void awaitBlockedSync() {
            try {
                syncEntered.get(2, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new AssertionError("sync did not reach its gate", error);
            }
        }

        void releaseBlockedSync() { blockedSyncGate.complete(null); }
        void releaseBlockedSyncIfPresent() {
            CompletableFuture<Void> gate = blockedSyncGate;
            if (gate != null) gate.complete(null);
        }

        void blockNextMetadataLoad() {
            nextMetadataGate = new CompletableFuture<>();
            metadataEntered = new CompletableFuture<>();
        }

        void awaitBlockedMetadataLoad() {
            try {
                metadataEntered.get(2, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new AssertionError("metadata load did not reach its gate", error);
            }
        }

        void releaseBlockedMetadataLoad() { blockedMetadataGate.complete(null); }
        void releaseBlockedMetadataLoadIfPresent() {
            CompletableFuture<Void> gate = blockedMetadataGate;
            if (gate != null) gate.complete(null);
        }

        void blockNextSnapshotPublication() {
            nextSnapshotPublicationGate = new CompletableFuture<>();
            snapshotPublicationEntered = new CompletableFuture<>();
        }

        void awaitBlockedSnapshotPublication() {
            try {
                snapshotPublicationEntered.get(2, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new AssertionError("snapshot publication did not reach its gate", error);
            }
        }

        void releaseBlockedSnapshotPublication() {
            blockedSnapshotPublicationGate.complete(null);
        }

        void releaseBlockedSnapshotPublicationIfPresent() {
            CompletableFuture<Void> gate = blockedSnapshotPublicationGate;
            if (gate != null) gate.complete(null);
        }

        void blockNextPrefixCompaction() {
            nextPrefixCompactionGate = new CompletableFuture<>();
            prefixCompactionEntered = new CompletableFuture<>();
        }

        void awaitBlockedPrefixCompaction() {
            try {
                prefixCompactionEntered.get(2, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new AssertionError("prefix compaction did not reach its gate", error);
            }
        }

        void releaseBlockedPrefixCompaction() {
            blockedPrefixCompactionGate.complete(null);
        }

        void releaseBlockedPrefixCompactionIfPresent() {
            CompletableFuture<Void> gate = blockedPrefixCompactionGate;
            if (gate != null) gate.complete(null);
        }

        private <T> CompletableFuture<T> ifOpen(CompletableFuture<T> operation) {
            return closed
                    ? CompletableFuture.failedFuture(new IllegalStateException("storage is closed"))
                    : operation;
        }

        @Override public CompletableFuture<Void> open(Path dataDir) { return delegate.open(dataDir); }
        @Override public CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor) { return ifOpen(delegate.updateMetadata(term, votedFor)); }
        @Override public CompletableFuture<PersistentMeta> loadMetadata() {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("storage is closed"));
            CompletableFuture<Void> gate = nextMetadataGate;
            if (gate != null) {
                nextMetadataGate = null;
                blockedMetadataGate = gate;
                metadataEntered.complete(null);
                return gate.thenCompose(ignored -> closed
                        ? CompletableFuture.failedFuture(new IllegalStateException("storage is closed"))
                        : delegate.loadMetadata());
            }
            return delegate.loadMetadata();
        }
        @Override public CompletableFuture<Void> appendEntries(List<LogEntryData> entries) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("storage is closed"));
            appendedIndexes.addAll(entries.stream().map(LogEntryData::index).toList());
            return delegate.appendEntries(entries);
        }
        @Override public CompletableFuture<Void> truncateSuffix(long fromIndex) { return ifOpen(delegate.truncateSuffix(fromIndex)); }
        @Override public CompletableFuture<Void> truncatePrefix(long toIndex) {
            prefixTruncateCount.incrementAndGet();
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("storage is closed"));
            CompletableFuture<Void> gate = nextPrefixCompactionGate;
            if (gate == null) return delegate.truncatePrefix(toIndex);
            nextPrefixCompactionGate = null;
            blockedPrefixCompactionGate = gate;
            prefixCompactionEntered.complete(null);
            return gate.thenCompose(ignored -> closed
                    ? CompletableFuture.failedFuture(new IllegalStateException("storage is closed"))
                    : delegate.truncatePrefix(toIndex));
        }

        @Override
        public CompletableFuture<Void> sync() {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("storage is closed"));
            CompletableFuture<Void> gate = nextSyncGate;
            if (gate != null) {
                nextSyncGate = null;
                blockedSyncGate = gate;
                syncEntered.complete(null);
                return gate.thenCompose(ignored -> closed
                        ? CompletableFuture.failedFuture(new IllegalStateException("storage is closed"))
                        : delegate.sync());
            }
            return delegate.sync();
        }

        @Override public CompletableFuture<List<LogEntryData>> replayLog() { return ifOpen(delegate.replayLog()); }
        @Override public CompletableFuture<Void> saveAtomically(SnapshotData snapshot) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("storage is closed"));
            CompletableFuture<Void> gate = nextSnapshotPublicationGate;
            if (gate == null) return delegate.saveAtomically(snapshot);
            nextSnapshotPublicationGate = null;
            blockedSnapshotPublicationGate = gate;
            snapshotPublicationEntered.complete(null);
            return gate.thenCompose(ignored -> closed
                    ? CompletableFuture.failedFuture(new IllegalStateException("storage is closed"))
                    : delegate.saveAtomically(snapshot));
        }
        @Override public CompletableFuture<Optional<SnapshotData>> loadLatest() { return ifOpen(delegate.loadLatest()); }

        @Override
        public void close() {
            closeContext = JavaRuntime.currentContext();
            closeEvents.add("storage");
            closeCount.incrementAndGet();
            closed = true;
            delegate.close();
            if (closeFailure != null) throw closeFailure;
        }
        @Override public CompletableFuture<Void> closeAsync() { return SnapshotStore.super.closeAsync(); }
    }
}

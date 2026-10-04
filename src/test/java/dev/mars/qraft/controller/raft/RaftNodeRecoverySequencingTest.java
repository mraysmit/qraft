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
import dev.mars.qraft.controller.state.RaftCommand;
import dev.mars.qraft.controller.state.RaftCommandResult;
import dev.mars.qraft.controller.testsupport.RemediationTest;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.raft.api.SnapshotStore;
import dev.mars.raftlog.storage.RaftStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static dev.mars.qraft.controller.raft.RaftAwait.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link RaftNode} startup recovery from a full log or snapshot plus suffix mutates node
 * state only on the state loop after each storage completion.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-14
 * @version 1.0
 */
@RemediationTest(phase = "6-recovery", scenarioPrefix = "RAFT-RECOVERY")
class RaftNodeRecoverySequencingTest {
    private final ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();
    private JavaRuntime runtime;
    private RaftNode node;
    private AsyncRecoveryStorage storage;

    @AfterEach
    void tearDown() throws Exception {
        if (storage != null) storage.releaseAll();
        if (node != null) await(node.stop());
        if (runtime != null) {
            runtime.shutdown().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void fullLogRecoveryReentersStateLoopAfterEveryStorageCompletion() throws Exception {
        runtime = JavaRuntime.create();
        DistributedStateRaftCommand command = put("recovered", "from-log");
        storage = new AsyncRecoveryStorage(
                new RaftStorage.PersistentMeta(7, Optional.of("node-1")),
                Optional.empty(),
                List.of(ManualRaftCluster.bootstrapEntry(Set.of("node-1")),
                        new RaftStorage.LogEntryData(2, 7, codec.serialize(command))));
        RecordingStateMachine stateMachine = new RecordingStateMachine();
        node = buildNode(storage, stateMachine);

        Future<Void> start = node.start();
        assertSame(runtime, storage.awaitMetadataRequest());
        completeOffLoop(() -> storage.metadata.complete(storage.metadataValue));
        assertSame(runtime, storage.awaitSnapshotRequest(),
                "snapshot load must be initiated from the state loop after metadata completion");
        completeOffLoop(() -> storage.snapshot.complete(storage.snapshotValue));
        assertSame(runtime, storage.awaitReplayRequest(),
                "log replay must be initiated from the state loop after snapshot completion");
        completeOffLoop(() -> storage.replay.complete(storage.replayValue));

        await(start);

        assertEquals(7, node.getCurrentTerm());
        assertEquals("from-log", stateMachine.delegate.getMetadata("recovered"));
        assertEquals(List.of(runtime), stateMachine.resetContexts);
        assertEquals(List.of(runtime), stateMachine.applyContexts);
        assertTrue(stateMachine.mutationContexts().stream().allMatch(context -> context == runtime));
    }

    @Test
    void snapshotAndSuffixRecoveryMutateStateOnlyOnStateLoop() throws Exception {
        runtime = JavaRuntime.create();
        QraftStateStore snapshotSource = new QraftStateStore();
        snapshotSource.apply(put("snapshot", "restored"));
        snapshotSource.setLastAppliedIndex(5);
        SnapshotStore.SnapshotData snapshot = new SnapshotStore.SnapshotData(
                ManualRaftCluster.snapshotOf(Set.of("node-1"), snapshotSource.takeSnapshot()), 5, 3, 1);
        DistributedStateRaftCommand suffix = put("suffix", "replayed");
        storage = new AsyncRecoveryStorage(
                new RaftStorage.PersistentMeta(4, Optional.empty()),
                Optional.of(snapshot),
                List.of(new RaftStorage.LogEntryData(6, 4, codec.serialize(suffix))));
        RecordingStateMachine stateMachine = new RecordingStateMachine();
        node = buildNode(storage, stateMachine);

        Future<Void> start = node.start();
        storage.awaitMetadataRequest();
        completeOffLoop(() -> storage.metadata.complete(storage.metadataValue));
        storage.awaitSnapshotRequest();
        completeOffLoop(() -> storage.snapshot.complete(storage.snapshotValue));
        storage.awaitReplayRequest();
        completeOffLoop(() -> storage.replay.complete(storage.replayValue));

        await(start);

        assertEquals(5, node.getSnapshotLastIndex());
        assertEquals(6, node.getLastApplied());
        assertEquals("restored", stateMachine.delegate.getMetadata("snapshot"));
        assertEquals("replayed", stateMachine.delegate.getMetadata("suffix"));
        assertEquals(List.of(runtime), stateMachine.restoreContexts);
        assertEquals(List.of(runtime), stateMachine.applyContexts);
        assertTrue(stateMachine.mutationContexts().stream().allMatch(context -> context == runtime));
    }

    @Test
    void raftRequestsArrivingDuringRecoveryAreRejectedWithoutTouchingDurableState() throws Exception {
        runtime = JavaRuntime.create();
        storage = new AsyncRecoveryStorage(new RaftStorage.PersistentMeta(5, Optional.of("node-2")),
                Optional.empty(), List.of());
        node = buildNode(storage, new RecordingStateMachine(), Set.of("node-1", "node-2", "node-3"));

        Future<Void> start = node.start();
        storage.awaitMetadataRequest();
        CompletableFuture<VoteResponse> vote = node.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(5).setCandidateId("node-3").setLastLogIndex(100).setLastLogTerm(5).build())
                .toCompletionStage().toCompletableFuture();
        CompletableFuture<AppendEntriesResponse> append = node.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                .setTerm(6).setLeaderId("node-3").setPrevLogIndex(0).setPrevLogTerm(0).setLeaderCommit(0).build())
                .toCompletionStage().toCompletableFuture();

        assertTrue(vote.handle((response, failure) -> failure != null || !response.getVoteGranted())
                .get(10, TimeUnit.SECONDS), "a vote cannot be decided against unrecovered state");
        assertTrue(append.handle((response, failure) -> failure != null || !response.getSuccess())
                .get(10, TimeUnit.SECONDS), "an append cannot be accepted against unrecovered state");
        assertEquals(List.of(), storage.metadataWrites, "the recovered term and vote must not be overwritten");

        completeOffLoop(() -> storage.metadata.complete(storage.metadataValue));
        storage.awaitSnapshotRequest();
        completeOffLoop(() -> storage.snapshot.complete(storage.snapshotValue));
        storage.awaitReplayRequest();
        completeOffLoop(() -> storage.replay.complete(storage.replayValue));
        await(start);

        assertEquals(5, node.getCurrentTerm());
        assertEquals("node-2", node.getVotedFor());
        VoteResponse secondVote = await(node.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(5).setCandidateId("node-3").setLastLogIndex(100).setLastLogTerm(5).build()));
        assertFalse(secondVote.getVoteGranted(), "one vote per term: node-2 already holds this term's vote");
        assertEquals(List.of(), storage.metadataWrites);
    }

    private RaftNode buildNode(AsyncRecoveryStorage storage, RecordingStateMachine stateMachine) {
        return buildNode(storage, stateMachine, Set.of("node-1"));
    }

    private RaftNode buildNode(AsyncRecoveryStorage storage, RecordingStateMachine stateMachine, Set<String> members) {
        return RaftNode.builder()
                .runtime(runtime)
                .nodeId("node-1")
                .serverId(ManualRaftCluster.serverIdOf("node-1"))
                .clusterNodes(members)
                .transport(new RecoveryTransport())
                .stateMachine(stateMachine)
                .commandCodec(codec)
                .mode(RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(false)
                .electionTimeout(10_000)
                .heartbeatInterval(10_000)
                .build();
    }

    private static DistributedStateRaftCommand put(String key, String value) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, value));
    }

    private static void completeOffLoop(Runnable completion) throws Exception {
        AtomicReference<JavaRuntime> context = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().name("recovery-storage-completion").start(() -> {
            context.set(JavaRuntime.currentContext());
            completion.run();
        });
        thread.join();
        assertNull(context.get(), "storage completion fixture must run outside the state loop");
    }

    private static final class RecordingStateMachine implements RaftLogApplicator {
        private final QraftStateStore delegate = new QraftStateStore();
        private final List<JavaRuntime> resetContexts = new ArrayList<>();
        private final List<JavaRuntime> restoreContexts = new ArrayList<>();
        private final List<JavaRuntime> applyContexts = new ArrayList<>();
        private final List<JavaRuntime> indexContexts = new ArrayList<>();

        @Override public RaftCommandResult<?> apply(RaftCommand command) {
            applyContexts.add(JavaRuntime.currentContext());
            return delegate.apply(command);
        }
        @Override public byte[] takeSnapshot() { return delegate.takeSnapshot(); }
        @Override public void restoreSnapshot(byte[] snapshot) {
            restoreContexts.add(JavaRuntime.currentContext());
            delegate.restoreSnapshot(snapshot);
        }
        @Override public long getLastAppliedIndex() { return delegate.getLastAppliedIndex(); }
        @Override public void setLastAppliedIndex(long index) {
            indexContexts.add(JavaRuntime.currentContext());
            delegate.setLastAppliedIndex(index);
        }
        @Override public void reset() {
            resetContexts.add(JavaRuntime.currentContext());
            delegate.reset();
        }

        List<JavaRuntime> mutationContexts() {
            List<JavaRuntime> contexts = new ArrayList<>();
            contexts.addAll(resetContexts);
            contexts.addAll(restoreContexts);
            contexts.addAll(applyContexts);
            contexts.addAll(indexContexts);
            return contexts;
        }
    }

    private static final class AsyncRecoveryStorage implements RaftStorage, SnapshotStore {
        private final PersistentMeta metadataValue;
        private final Optional<SnapshotData> snapshotValue;
        private final List<LogEntryData> replayValue;
        private final CompletableFuture<PersistentMeta> metadata = new CompletableFuture<>();
        private final CompletableFuture<Optional<SnapshotData>> snapshot = new CompletableFuture<>();
        private final CompletableFuture<List<LogEntryData>> replay = new CompletableFuture<>();
        private final CompletableFuture<JavaRuntime> metadataRequested = new CompletableFuture<>();
        private final CompletableFuture<JavaRuntime> snapshotRequested = new CompletableFuture<>();
        private final CompletableFuture<JavaRuntime> replayRequested = new CompletableFuture<>();
        private final List<String> metadataWrites = new java.util.concurrent.CopyOnWriteArrayList<>();

        private AsyncRecoveryStorage(PersistentMeta metadataValue,
                                     Optional<SnapshotData> snapshotValue,
                                     List<LogEntryData> replayValue) {
            this.metadataValue = metadataValue;
            this.snapshotValue = snapshotValue;
            this.replayValue = replayValue;
        }

        JavaRuntime awaitMetadataRequest() throws Exception { return metadataRequested.get(10, TimeUnit.SECONDS); }
        JavaRuntime awaitSnapshotRequest() throws Exception { return snapshotRequested.get(10, TimeUnit.SECONDS); }
        JavaRuntime awaitReplayRequest() throws Exception { return replayRequested.get(10, TimeUnit.SECONDS); }

        void releaseAll() {
            metadata.complete(metadataValue);
            snapshot.complete(snapshotValue);
            replay.complete(replayValue);
        }

        @Override public CompletableFuture<Void> open(Path dataDir) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor) {
            metadataWrites.add(term + ":" + votedFor.orElse(""));
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<PersistentMeta> loadMetadata() {
            metadataRequested.complete(JavaRuntime.currentContext());
            return metadata;
        }
        @Override public CompletableFuture<Void> appendEntries(List<LogEntryData> entries) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> truncateSuffix(long fromIndex) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> truncatePrefix(long toIndex) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> sync() { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<List<LogEntryData>> replayLog() {
            replayRequested.complete(JavaRuntime.currentContext());
            return replay;
        }
        @Override public CompletableFuture<Void> saveAtomically(SnapshotData snapshot) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Optional<SnapshotData>> loadLatest() {
            snapshotRequested.complete(JavaRuntime.currentContext());
            return snapshot;
        }
        @Override public void close() {}
        @Override public CompletableFuture<Void> closeAsync() { return SnapshotStore.super.closeAsync(); }
    }

    private static final class RecoveryTransport implements RaftTransport {
        @Override public void start(Consumer<RaftMessage> messageHandler) {}
        @Override public void stop() {}
        @Override public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return Future.failedFuture("unexpected vote request");
        }
        @Override public Future<AppendEntriesResponse> sendAppendEntries(String targetId, AppendEntriesRequest request) {
            return Future.failedFuture("unexpected append request");
        }
        @Override public Future<InstallSnapshotResponse> sendInstallSnapshot(String targetId, InstallSnapshotRequest request) {
            return Future.failedFuture("unexpected snapshot request");
        }
    }
}

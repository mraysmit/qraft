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
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.controller.state.RaftCommand;
import dev.mars.qraft.controller.state.RaftCommandResult;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RemediationTest(phase = "5", scenarioPrefix = "RAFT-INSTALLED-SNAPSHOT")
class RaftNodeInstalledSnapshotSequencingTest {
    private JavaRuntime runtime;
    private GatedStorage storage;
    private RecordingStateMachine stateMachine;
    private ProtobufRaftCommandCodec codec;
    private RaftNode node;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        storage = new GatedStorage();
        storage.open(null).join();
        stateMachine = new RecordingStateMachine();
        codec = new ProtobufRaftCommandCodec();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("follower-1")
                .clusterNodes(Set.of("follower-1", "leader-1"))
                .transport(new InMemoryTransportSimulator("follower-1"))
                .stateMachine(stateMachine)
                .commandCodec(codec)
                .mode(RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(false)
                .electionTimeout(10_000)
                .heartbeatInterval(10_000)
                .build();
        await(node.start());
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
    void finalPublicationBlocksAppendAndRestoresOnOwningLoop() {
        storage.blockNextSnapshotPublication();
        Future<InstallSnapshotResponse> install = node.handleInstallSnapshot(
                installRequest(1, 5, 3, 0, 1, snapshotBytes(5, "installed", "yes"), true));
        storage.awaitBlockedSnapshotPublication();

        Future<AppendEntriesResponse> append = node.handleAppendEntriesRequest(
                heartbeat(1, 0, 0));
        awaitStateLoop();

        assertFalse(install.isComplete());
        assertFalse(append.isComplete(), "AppendEntries must not prepare across installation publication");
        assertEquals(0, storage.prefixTruncateCount());
        assertEquals(0, node.getSnapshotLastIndex());
        assertNull(stateMachine.restoreContext());

        storage.releaseBlockedSnapshotPublication();

        assertTrue(await(install).getSuccess());
        assertFalse(await(append).getSuccess(), "queued AppendEntries must see the installed boundary");
        assertSame(runtime, stateMachine.restoreContext());
        assertEquals(5, node.getSnapshotLastIndex());
        assertEquals(5, node.getLastApplied());
        assertEquals(5, node.getCommitIndex());
        assertEquals("yes", stateMachine.getMetadata("installed"));
    }

    @Test
    void staleSnapshotCannotRollCommittedApplicationStateBackwards() {
        AppendEntriesResponse appended = await(node.handleAppendEntriesRequest(appendPut(1, 1, "new", "value")));
        assertTrue(appended.getSuccess());
        assertEquals(1, node.getLastApplied());

        InstallSnapshotResponse stale = await(node.handleInstallSnapshot(
                installRequest(1, 0, 0, 0, 1, snapshotBytes(0, "old", "value"), true)));

        assertFalse(stale.getSuccess());
        assertEquals(1, node.getLastApplied());
        assertEquals(1, node.getCommitIndex());
        assertEquals("value", stateMachine.getMetadata("new"));
        assertNull(stateMachine.getMetadata("old"));
        assertEquals(0, storage.saveCount());
        assertEquals(0, storage.prefixTruncateCount());
    }

    @Test
    void duplicateCompletedInstallationIsAcknowledgedWithoutRepublishing() {
        InstallSnapshotRequest request = installRequest(
                1, 5, 3, 0, 1, snapshotBytes(5, "once", "only"), true);

        assertTrue(await(node.handleInstallSnapshot(request)).getSuccess());
        InstallSnapshotResponse duplicate = await(node.handleInstallSnapshot(request));

        assertTrue(duplicate.getSuccess());
        assertEquals(1, duplicate.getNextChunkIndex());
        assertEquals(1, storage.saveCount());
        assertEquals(1, storage.prefixTruncateCount());
    }

    @Test
    void doneBeforeFinalChunkIsRejectedWithoutPublication() {
        InstallSnapshotResponse response = await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 0, 2, snapshotBytes(5, "partial", "bad"), true)));

        assertFalse(response.getSuccess());
        assertEquals(0, response.getNextChunkIndex());
        assertEquals(0, storage.saveCount());
        assertEquals(0, storage.prefixTruncateCount());
        assertEquals(0, node.getSnapshotLastIndex());
    }

    @Test
    void higherTermInvalidatesPartiallyAssembledTransfer() {
        byte[] bytes = snapshotBytes(5, "term", "changed");
        int split = bytes.length / 2;
        InstallSnapshotResponse first = await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 0, 2, slice(bytes, 0, split), false)));
        assertTrue(first.getSuccess());
        assertEquals(1, first.getNextChunkIndex());

        InstallSnapshotResponse second = await(node.handleInstallSnapshot(
                installRequest(2, 5, 3, 1, 2, slice(bytes, split, bytes.length), true)));

        assertFalse(second.getSuccess());
        assertEquals(0, second.getNextChunkIndex());
        assertEquals(2, second.getTerm());
        assertEquals(0, storage.saveCount());
        assertEquals(0, node.getSnapshotLastIndex());
    }

    @Test
    void uncertainCompactionFailureFencesLaterWalMutation() {
        storage.failNextPrefixCompaction();
        RemediationTestExtension.logExpectedFailure(
                "installed-snapshot-prefix-compaction", "IllegalStateException",
                "uncertain installed-snapshot compaction outcome");

        InstallSnapshotResponse failed = await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 0, 1, snapshotBytes(5, "fenced", "snapshot"), true)));
        assertFalse(failed.getSuccess());
        assertEquals(1, storage.saveCount());
        assertEquals(1, storage.prefixTruncateCount());
        assertEquals(0, node.getSnapshotLastIndex());

        AppendEntriesResponse later = await(node.handleAppendEntriesRequest(appendPut(1, 1, "after", "forbidden")));
        assertFalse(later.getSuccess());
        assertEquals(0, storage.appendCount());
        assertNull(stateMachine.getMetadata("after"));
    }

    @Test
    void publicationFailureCleansAssemblerAndAllowsRetry() {
        storage.failNextSnapshotPublication();
        RemediationTestExtension.logExpectedFailure(
                "installed-snapshot-publication", "IllegalStateException",
                "installed snapshot publication failed");
        InstallSnapshotRequest request = installRequest(
                1, 5, 3, 0, 1, snapshotBytes(5, "retried", "yes"), true);

        InstallSnapshotResponse failed = await(node.handleInstallSnapshot(request));
        assertFalse(failed.getSuccess());
        assertEquals(0, storage.prefixTruncateCount());
        assertEquals(0, node.getSnapshotLastIndex());

        InstallSnapshotResponse retried = await(node.handleInstallSnapshot(request));
        assertTrue(retried.getSuccess());
        assertEquals(2, storage.saveCount());
        assertEquals(1, storage.prefixTruncateCount());
        assertEquals("yes", stateMachine.getMetadata("retried"));
    }

    @Test
    void matchingUncommittedSuffixIsRetainedAcrossInstallation() {
        assertTrue(await(node.handleAppendEntriesRequest(appendPut(1, 1, "one", "1"))).getSuccess());
        assertTrue(await(node.handleAppendEntriesRequest(appendPutUncommitted(1, 2, "two", "2"))).getSuccess());
        assertTrue(await(node.handleAppendEntriesRequest(appendPutUncommitted(1, 3, "three", "3"))).getSuccess());

        InstallSnapshotResponse installed = await(node.handleInstallSnapshot(
                installRequest(1, 2, 1, 0, 1, snapshotBytes(2, "snapshot", "two"), true)));

        assertTrue(installed.getSuccess());
        assertEquals(3, node.getLastLogIndex());
        assertTrue(await(node.handleAppendEntriesRequest(heartbeat(1, 3, 1))).getSuccess());
    }

    @Test
    void sameTermDifferentLeaderCannotStartSimultaneousTransfer() {
        byte[] bytes = snapshotBytes(5, "leader", "one");
        int split = bytes.length / 2;
        assertTrue(await(node.handleInstallSnapshot(installRequest(
                "leader-1", 1, 5, 3, 0, 2, slice(bytes, 0, split), false))).getSuccess());

        InstallSnapshotResponse competing = await(node.handleInstallSnapshot(installRequest(
                "leader-2", 1, 6, 3, 0, 2, slice(bytes, 0, split), false)));

        assertFalse(competing.getSuccess());
        assertEquals(0, competing.getNextChunkIndex());
        assertEquals(0, storage.saveCount());
        assertTrue(await(node.handleInstallSnapshot(installRequest(
                "leader-1", 1, 5, 3, 1, 2, slice(bytes, split, bytes.length), true))).getSuccess());
    }

    @Test
    void restorationFailureAfterCompactionFencesLaterWalMutation() {
        stateMachine.failNextRestore();
        RemediationTestExtension.logExpectedFailure(
                "installed-snapshot-state-restoration", "IllegalStateException",
                "installed snapshot restoration failed");

        InstallSnapshotResponse failed = await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 0, 1, snapshotBytes(5, "restore", "fails"), true)));

        assertFalse(failed.getSuccess());
        assertEquals(1, storage.saveCount());
        assertEquals(1, storage.prefixTruncateCount());
        assertEquals(0, node.getSnapshotLastIndex());
        assertFalse(await(node.handleAppendEntriesRequest(appendPut(1, 1, "after", "forbidden"))).getSuccess());
        assertEquals(0, storage.appendCount());
    }

    private AppendEntriesRequest heartbeat(long term, long previousIndex, long previousTerm) {
        return AppendEntriesRequest.newBuilder()
                .setTerm(term)
                .setLeaderId("leader-1")
                .setPrevLogIndex(previousIndex)
                .setPrevLogTerm(previousTerm)
                .setLeaderCommit(0)
                .build();
    }

    private AppendEntriesRequest appendPut(long term, long index, String key, String value) {
        return appendPut(term, index, index, key, value);
    }

    private AppendEntriesRequest appendPutUncommitted(long term, long index, String key, String value) {
        return appendPut(term, index, 1, key, value);
    }

    private AppendEntriesRequest appendPut(
            long term, long index, long leaderCommit, String key, String value) {
        RaftCommand command = put(key, value);
        return AppendEntriesRequest.newBuilder()
                .setTerm(term)
                .setLeaderId("leader-1")
                .setPrevLogIndex(index - 1)
                .setPrevLogTerm(index == 1 ? 0 : term)
                .setLeaderCommit(leaderCommit)
                .addEntries(dev.mars.qraft.controller.raft.grpc.LogEntry.newBuilder()
                        .setTerm(term)
                        .setData(ByteString.copyFrom(codec.serialize(command)))
                        .build())
                .build();
    }

    private static InstallSnapshotRequest installRequest(
            long requestTerm, long index, long snapshotTerm, int chunkIndex,
            int totalChunks, byte[] data, boolean done) {
        return installRequest("leader-1", requestTerm, index, snapshotTerm,
                chunkIndex, totalChunks, data, done);
    }

    private static InstallSnapshotRequest installRequest(
            String leaderId, long requestTerm, long index, long snapshotTerm, int chunkIndex,
            int totalChunks, byte[] data, boolean done) {
        return InstallSnapshotRequest.newBuilder()
                .setTerm(requestTerm)
                .setLeaderId(leaderId)
                .setLastIncludedIndex(index)
                .setLastIncludedTerm(snapshotTerm)
                .setChunkIndex(chunkIndex)
                .setTotalChunks(totalChunks)
                .setData(ByteString.copyFrom(data))
                .setDone(done)
                .build();
    }

    private static byte[] snapshotBytes(long index, String key, String value) {
        QraftStateStore state = new QraftStateStore();
        state.apply(put(key, value));
        state.setLastAppliedIndex(index);
        return state.takeSnapshot();
    }

    private static byte[] slice(byte[] bytes, int from, int to) {
        return java.util.Arrays.copyOfRange(bytes, from, to);
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
        private volatile JavaRuntime restoreContext;
        private volatile boolean failNextRestore;

        JavaRuntime restoreContext() { return restoreContext; }
        String getMetadata(String key) { return delegate.getMetadata(key); }
        void failNextRestore() { failNextRestore = true; }

        @Override public RaftCommandResult<?> apply(RaftCommand command) { return delegate.apply(command); }
        @Override public byte[] takeSnapshot() { return delegate.takeSnapshot(); }
        @Override public void restoreSnapshot(byte[] snapshot) {
            restoreContext = JavaRuntime.currentContext();
            if (failNextRestore) {
                failNextRestore = false;
                throw new IllegalStateException("installed snapshot restoration failed");
            }
            delegate.restoreSnapshot(snapshot);
        }
        @Override public long getLastAppliedIndex() { return delegate.getLastAppliedIndex(); }
        @Override public void setLastAppliedIndex(long index) { delegate.setLastAppliedIndex(index); }
        @Override public void reset() { delegate.reset(); }
    }

    private static final class GatedStorage implements RaftStorage, SnapshotStore {
        private final TestRaftStorage delegate = new TestRaftStorage();
        private final AtomicInteger appendCount = new AtomicInteger();
        private final AtomicInteger saveCount = new AtomicInteger();
        private final AtomicInteger prefixTruncateCount = new AtomicInteger();
        private volatile CompletableFuture<Void> publicationGate;
        private volatile CompletableFuture<Void> blockedPublicationGate;
        private volatile CompletableFuture<Void> publicationEntered;
        private volatile boolean failNextPublication;
        private volatile boolean failNextCompaction;

        void blockNextSnapshotPublication() {
            publicationGate = new CompletableFuture<>();
            publicationEntered = new CompletableFuture<>();
        }

        void awaitBlockedSnapshotPublication() {
            try {
                publicationEntered.get(2, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new AssertionError("snapshot publication did not reach its gate", error);
            }
        }

        void releaseBlockedSnapshotPublication() { blockedPublicationGate.complete(null); }
        void failNextSnapshotPublication() { failNextPublication = true; }
        void failNextPrefixCompaction() { failNextCompaction = true; }
        int appendCount() { return appendCount.get(); }
        int saveCount() { return saveCount.get(); }
        int prefixTruncateCount() { return prefixTruncateCount.get(); }

        @Override public CompletableFuture<Void> open(Path dataDir) { return delegate.open(dataDir); }
        @Override public CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor) {
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
            if (!failNextCompaction) return persisted;
            failNextCompaction = false;
            return persisted.thenCompose(ignored -> CompletableFuture.failedFuture(
                    new IllegalStateException("uncertain installed-snapshot compaction outcome")));
        }
        @Override public CompletableFuture<Void> sync() { return delegate.sync(); }
        @Override public CompletableFuture<List<LogEntryData>> replayLog() { return delegate.replayLog(); }
        @Override public CompletableFuture<Void> saveAtomically(SnapshotData snapshot) {
            saveCount.incrementAndGet();
            if (failNextPublication) {
                failNextPublication = false;
                return CompletableFuture.failedFuture(
                        new IllegalStateException("installed snapshot publication failed"));
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
    }
}

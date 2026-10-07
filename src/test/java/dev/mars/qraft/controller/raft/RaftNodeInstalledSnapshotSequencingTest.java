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
import dev.mars.qraft.raft.api.SnapshotStore.PublicationOutcome;
import dev.mars.qraft.raft.api.SnapshotStore.SnapshotPublicationException;
import dev.mars.qraft.testing.fault.InjectedFaultFixture;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
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

import static dev.mars.qraft.controller.raft.RaftAwaitHelper.awaitStateLoop;
import static dev.mars.qraft.controller.raft.RaftAwaitHelper.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link RaftNode} sequences follower snapshot installation: publication, restore,
 * suffix retention or removal, stale and duplicate transfers, fencing on uncertain failures, and
 * transfers in several chunks, including chunks out of order, a missing first chunk, a repeated chunk,
 * and a transfer the leader restarts.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-14
 * @version 1.0
 */
class RaftNodeInstalledSnapshotSequencingTest {
    private static final Set<String> MEMBERS = Set.of("follower-1", "leader-1");
    /** The follower bootstraps its configuration at index 1, so a leader's first command is at index 2. */
    private static final long FIRST_COMMAND_INDEX = 2;

    private JavaRuntime runtime;
    private GatedStorageFixture storage;
    private RecordingStateMachineFixture stateMachine;
    private ProtobufRaftCommandCodec codec;
    private RaftNode node;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        storage = new GatedStorageFixture();
        storage.open(null).join();
        stateMachine = new RecordingStateMachineFixture();
        codec = new ProtobufRaftCommandCodec();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("follower-1")
                .serverId(ManualRaftClusterFixture.serverIdOf("follower-1"))
                .clusterNodes(MEMBERS)
                .initialConfiguration(ManualRaftClusterFixture.configurationOf(MEMBERS))
                .transport(new InMemoryTransportSimulatorFixture("follower-1"))
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
            runtime.shutdown().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        InMemoryTransportSimulatorFixture.clearAllTransports();
    }

    @Test
    void finalPublicationBlocksAppendAndRestoresOnOwningLoop() {
        storage.blockNextSnapshotPublication();
        Future<InstallSnapshotResponse> install = node.handleInstallSnapshot(
                installRequest(1, 5, 3, 0, 1, snapshotBytes(5, "installed", "yes"), true));
        storage.awaitBlockedSnapshotPublication();

        Future<AppendEntriesResponse> append = node.handleAppendEntriesRequest(
                heartbeat(1, 1, 0));
        awaitStateLoop(runtime);

        assertFalse(install.isComplete());
        assertFalse(append.isComplete(), "AppendEntries must not prepare across installation publication");
        assertEquals(0, storage.prefixTruncateCount());
        assertEquals(0, node.getSnapshotLastIndex());
        assertNull(stateMachine.restoreContext());

        storage.releaseBlockedSnapshotPublication();

        assertTrue(await(install).getSuccess());
        AppendEntriesResponse queued = await(append);
        assertTrue(queued.getSuccess(), "a previous entry inside the snapshot is committed and matches");
        assertEquals(5, queued.getMatchIndex(), "queued AppendEntries must see the installed boundary");
        assertSame(runtime, stateMachine.restoreContext());
        assertEquals(5, node.getSnapshotLastIndex());
        assertEquals(5, node.getLastApplied());
        assertEquals(5, node.getCommitIndex());
        assertEquals("yes", stateMachine.getMetadata("installed"));
    }

    @Test
    void staleSnapshotCannotRollCommittedApplicationStateBackwards() {
        AppendEntriesResponse appended = await(node.handleAppendEntriesRequest(appendPut(1, 2, "new", "value")));
        assertTrue(appended.getSuccess());
        assertEquals(2, node.getLastApplied());

        InstallSnapshotResponse stale = await(node.handleInstallSnapshot(
                installRequest(1, 1, 0, 0, 1, snapshotBytes(1, "old", "value"), true)));

        assertFalse(stale.getSuccess());
        assertEquals(2, node.getLastApplied());
        assertEquals(2, node.getCommitIndex());
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
    void aTransferInThreeChunksInstallsTheWholeSnapshotOnlyAtTheLastChunk() {
        byte[][] chunks = thirds(snapshotBytes(5, "chunked", "whole"));

        for (int chunk = 0; chunk < 2; chunk++) {
            InstallSnapshotResponse response = await(node.handleInstallSnapshot(
                    installRequest(1, 5, 3, chunk, 3, chunks[chunk], false)));
            assertTrue(response.getSuccess());
            assertEquals(chunk + 1, response.getNextChunkIndex());
            assertEquals(0, storage.saveCount(), "nothing is published before the last chunk");
            assertEquals(0, node.getSnapshotLastIndex());
        }
        InstallSnapshotResponse last = await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 2, 3, chunks[2], true)));

        assertTrue(last.getSuccess());
        assertEquals(1, storage.saveCount());
        assertEquals(5, node.getSnapshotLastIndex());
        assertEquals("whole", stateMachine.getMetadata("chunked"));
    }

    @Test
    void aSkippedChunkIsRefusedWithTheCursorOfTheMissingChunk() {
        byte[][] chunks = thirds(snapshotBytes(5, "skipped", "recovered"));
        assertTrue(await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 0, 3, chunks[0], false))).getSuccess());

        InstallSnapshotResponse skipped = await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 2, 3, chunks[2], true)));

        assertFalse(skipped.getSuccess());
        assertEquals(1, skipped.getNextChunkIndex(), "the follower asks for the chunk it is missing");
        assertEquals(0, storage.saveCount());

        assertTrue(await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 1, 3, chunks[1], false))).getSuccess());
        assertTrue(await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 2, 3, chunks[2], true))).getSuccess());
        assertEquals("recovered", stateMachine.getMetadata("skipped"),
                "the transfer completes from the cursor with the original bytes");
    }

    @Test
    void aTransferThatDoesNotStartAtTheFirstChunkIsRefusedFromTheStart() {
        byte[][] chunks = thirds(snapshotBytes(5, "no", "start"));

        InstallSnapshotResponse response = await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 1, 3, chunks[1], false)));

        assertFalse(response.getSuccess());
        assertEquals(0, response.getNextChunkIndex());
        assertEquals(InstallSnapshotResponse.RejectionReason.ASSEMBLER_STATE_LOST, response.getRejectionReason());
        assertEquals(0, storage.saveCount());
    }

    @Test
    void aRepeatedChunkIsNotAppendedTwice() {
        byte[][] chunks = thirds(snapshotBytes(5, "repeated", "once"));
        assertTrue(await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 0, 3, chunks[0], false))).getSuccess());

        InstallSnapshotResponse repeat = await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 0, 3, chunks[0], false)));
        assertFalse(repeat.getSuccess());
        assertEquals(1, repeat.getNextChunkIndex(), "the follower keeps its cursor");

        assertTrue(await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 1, 3, chunks[1], false))).getSuccess());
        assertTrue(await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 2, 3, chunks[2], true))).getSuccess());
        assertEquals("once", stateMachine.getMetadata("repeated"),
                "a duplicated chunk would corrupt the assembled bytes");
    }

    @Test
    void aLeaderThatRestartsWithANewerSnapshotReplacesThePartialTransfer() {
        byte[][] older = thirds(snapshotBytes(5, "older", "partial"));
        assertTrue(await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 0, 3, older[0], false))).getSuccess());
        assertTrue(await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 1, 3, older[1], false))).getSuccess());

        byte[][] newer = thirds(snapshotBytes(8, "newer", "complete"));
        for (int chunk = 0; chunk < 3; chunk++) {
            InstallSnapshotResponse response = await(node.handleInstallSnapshot(
                    installRequest(1, 8, 3, chunk, 3, newer[chunk], chunk == 2)));
            assertTrue(response.getSuccess(), "chunk " + chunk + ": " + response);
        }

        assertEquals(1, storage.saveCount());
        assertEquals(8, node.getSnapshotLastIndex());
        assertEquals("complete", stateMachine.getMetadata("newer"));
        assertNull(stateMachine.getMetadata("older"), "no byte of the abandoned transfer is installed");
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
        assertEquals(InstallSnapshotResponse.RejectionReason.ASSEMBLER_STATE_LOST,
                second.getRejectionReason());
        assertEquals(2, second.getTerm());
        assertEquals(0, storage.saveCount());
        assertEquals(0, node.getSnapshotLastIndex());
    }

    @Test
    void higherTermInstallationContinuesOnStateLoopAfterForeignMetadataCompletion() {
        storage.completeNextMetadataOffLoop();

        InstallSnapshotResponse response = await(node.handleInstallSnapshot(
                installRequest(2, 5, 3, 0, 1,
                        snapshotBytes(5, "foreign", "completion"), true)));

        assertTrue(response.getSuccess());
        assertEquals(2, node.getCurrentTerm());
        assertFalse(node.isFenced());
        assertEquals("completion", stateMachine.getMetadata("foreign"));
    }

    @Test
    void uncertainCompactionFailureFencesLaterWalMutation() {
        IntentionalErrorsHelper.expect(dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_FENCED_OPERATION, 1);
        storage.failNextPrefixCompaction();

        InstallSnapshotResponse failed = await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 0, 1, snapshotBytes(5, "fenced", "snapshot"), true)));
        assertFalse(failed.getSuccess());
        assertTrue(node.isFenced());
        assertEquals(1, storage.saveCount());
        assertEquals(1, storage.prefixTruncateCount());
        assertEquals(0, node.getSnapshotLastIndex());

        AppendEntriesResponse later = await(node.handleAppendEntriesRequest(appendPut(1, 2, "after", "forbidden")));
        assertFalse(later.getSuccess());
        assertEquals(1, storage.appendCount(), "only the bootstrap configuration entry was appended");
        assertNull(stateMachine.getMetadata("after"));
    }

    @Test
    void publicationFailureCleansAssemblerAndAllowsRetry() {
        storage.failNextSnapshotPublication();
        InstallSnapshotRequest request = installRequest(
                1, 5, 3, 0, 1, snapshotBytes(5, "retried", "yes"), true);

        InstallSnapshotResponse failed = await(node.handleInstallSnapshot(request));
        assertFalse(failed.getSuccess());
        assertEquals(InstallSnapshotResponse.RejectionReason.PERSISTENCE_REJECTED,
                failed.getRejectionReason());
        assertEquals(0, storage.prefixTruncateCount());
        assertEquals(0, node.getSnapshotLastIndex());

        InstallSnapshotResponse retried = await(node.handleInstallSnapshot(request));
        assertTrue(retried.getSuccess());
        assertEquals(2, storage.saveCount());
        assertEquals(1, storage.prefixTruncateCount());
        assertEquals("yes", stateMachine.getMetadata("retried"));
    }

    @Test
    void ambiguousPublicationFailureFencesLaterWalMutation() {
        IntentionalErrorsHelper.expect(dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_FENCED_OPERATION, 1);
        storage.failNextSnapshotPublicationAmbiguously();

        InstallSnapshotResponse failed = await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 0, 1,
                        snapshotBytes(5, "ambiguous", "publication"), true)));

        assertFalse(failed.getSuccess());
        assertTrue(node.isFenced());
        assertEquals(0, storage.prefixTruncateCount());
        assertEquals(0, node.getSnapshotLastIndex());

        AppendEntriesResponse later = await(node.handleAppendEntriesRequest(
                appendPut(1, 2, "after", "forbidden")));
        assertFalse(later.getSuccess());
        assertEquals(1, storage.appendCount(), "only the bootstrap configuration entry was appended");
    }

    @Test
    void matchingUncommittedSuffixIsRetainedAcrossInstallation() {
        assertTrue(await(node.handleAppendEntriesRequest(appendPut(1, 2, "one", "1"))).getSuccess());
        assertTrue(await(node.handleAppendEntriesRequest(appendPutUncommitted(1, 3, "two", "2"))).getSuccess());
        assertTrue(await(node.handleAppendEntriesRequest(appendPutUncommitted(1, 4, "three", "3"))).getSuccess());

        InstallSnapshotResponse installed = await(node.handleInstallSnapshot(
                installRequest(1, 3, 1, 0, 1, snapshotBytes(3, "snapshot", "two"), true)));

        assertTrue(installed.getSuccess());
        assertEquals(4, node.getLastLogIndex());
        assertTrue(await(node.handleAppendEntriesRequest(heartbeat(1, 4, 1))).getSuccess());
    }

    @Test
    void nonMatchingSuffixIsRemovedFromMemoryAndWalBeforeNewAppend() {
        assertTrue(await(node.handleAppendEntriesRequest(appendPut(1, 2, "one", "1"))).getSuccess());
        assertTrue(await(node.handleAppendEntriesRequest(appendPutUncommitted(1, 3, "two", "2"))).getSuccess());
        assertTrue(await(node.handleAppendEntriesRequest(appendPutUncommitted(1, 4, "stale", "3"))).getSuccess());

        InstallSnapshotResponse installed = await(node.handleInstallSnapshot(
                installRequest(1, 3, 99, 0, 1,
                        snapshotBytes(3, "snapshot", "replacement"), true)));

        assertTrue(installed.getSuccess());
        assertEquals(3, node.getLastLogIndex());
        assertEquals(1, storage.suffixTruncateCount());
        assertEquals(List.of(), storage.logEntries().stream()
                .map(RaftStorage.LogEntryData::index).toList());

        assertTrue(await(node.handleAppendEntriesRequest(appendPutWithPreviousTerm(
                1, 4, 99, "fresh", "3"))).getSuccess());
        assertEquals(List.of(4L), storage.logEntries().stream()
                .map(RaftStorage.LogEntryData::index).toList());
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
        IntentionalErrorsHelper.expect(dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_FENCED_OPERATION, 1);
        stateMachine.failNextRestore();

        InstallSnapshotResponse failed = await(node.handleInstallSnapshot(
                installRequest(1, 5, 3, 0, 1, snapshotBytes(5, "restore", "fails"), true)));

        assertFalse(failed.getSuccess());
        assertEquals(1, storage.saveCount());
        assertEquals(1, storage.prefixTruncateCount());
        assertEquals(0, node.getSnapshotLastIndex());
        assertFalse(await(node.handleAppendEntriesRequest(appendPut(1, 2, "after", "forbidden"))).getSuccess());
        assertEquals(1, storage.appendCount(), "only the bootstrap configuration entry was appended");
    }

    private static InjectedFaultFixture snapshotFault(String message) {
        return new InjectedFaultFixture(
                dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_SNAPSHOT_OPERATION_FAILURE, message);
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

    /** Commits only the leader's first command, so this entry and any after it stay uncommitted. */
    private AppendEntriesRequest appendPutUncommitted(long term, long index, String key, String value) {
        return appendPut(term, index, FIRST_COMMAND_INDEX, key, value);
    }

    private AppendEntriesRequest appendPut(
            long term, long index, long leaderCommit, String key, String value) {
        RaftCommand command = put(key, value);
        return AppendEntriesRequest.newBuilder()
                .setTerm(term)
                .setLeaderId("leader-1")
                .setPrevLogIndex(index - 1)
                // The entry before the first command is the bootstrap configuration, written in term 0
                .setPrevLogTerm(index == FIRST_COMMAND_INDEX ? 0 : term)
                .setLeaderCommit(leaderCommit)
                .addEntries(dev.mars.qraft.controller.raft.grpc.LogEntry.newBuilder()
                        .setTerm(term)
                        .setData(ByteString.copyFrom(codec.serialize(command)))
                        .build())
                .build();
    }

    private AppendEntriesRequest appendPutWithPreviousTerm(
            long term, long index, long previousTerm, String key, String value) {
        return AppendEntriesRequest.newBuilder()
                .setTerm(term)
                .setLeaderId("leader-1")
                .setPrevLogIndex(index - 1)
                .setPrevLogTerm(previousTerm)
                .setLeaderCommit(1)
                .addEntries(dev.mars.qraft.controller.raft.grpc.LogEntry.newBuilder()
                        .setTerm(term)
                        .setData(ByteString.copyFrom(codec.serialize(put(key, value))))
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
        return ManualRaftClusterFixture.snapshotOf(MEMBERS, state.takeSnapshot());
    }

    private static byte[][] thirds(byte[] bytes) {
        int first = bytes.length / 3;
        int second = 2 * bytes.length / 3;
        return new byte[][]{slice(bytes, 0, first), slice(bytes, first, second), slice(bytes, second, bytes.length)};
    }

    private static byte[] slice(byte[] bytes, int from, int to) {
        return java.util.Arrays.copyOfRange(bytes, from, to);
    }

    private static DistributedStateRaftCommand put(String key, String value) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, value));
    }

    /** Test state-machine fixture that records application and snapshot operations for sequencing assertions. */
    private static final class RecordingStateMachineFixture implements RaftLogApplicator {
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
                throw new IllegalStateException("installed snapshot restoration failed",
                        snapshotFault("installed snapshot restoration failed"));
            }
            delegate.restoreSnapshot(snapshot);
        }
        @Override public long getLastAppliedIndex() { return delegate.getLastAppliedIndex(); }
        @Override public void setLastAppliedIndex(long index) { delegate.setLastAppliedIndex(index); }
        @Override public void reset() { delegate.reset(); }
    }

    /** Test storage and snapshot fixture with controlled operation completion for installation-sequencing assertions. */
    private static final class GatedStorageFixture implements RaftStorage, SnapshotStore {
        private final TestRaftStorageFixture delegate = new TestRaftStorageFixture();
        private final AtomicInteger appendCount = new AtomicInteger();
        private final AtomicInteger saveCount = new AtomicInteger();
        private final AtomicInteger prefixTruncateCount = new AtomicInteger();
        private final AtomicInteger suffixTruncateCount = new AtomicInteger();
        private volatile CompletableFuture<Void> publicationGate;
        private volatile CompletableFuture<Void> blockedPublicationGate;
        private volatile CompletableFuture<Void> publicationEntered;
        private volatile boolean failNextPublication;
        private volatile boolean failNextPublicationAmbiguously;
        private volatile boolean failNextCompaction;
        private volatile boolean completeNextMetadataOffLoop;

        void blockNextSnapshotPublication() {
            publicationGate = new CompletableFuture<>();
            publicationEntered = new CompletableFuture<>();
        }

        void awaitBlockedSnapshotPublication() {
            try {
                publicationEntered.get(10, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new AssertionError("snapshot publication did not reach its gate", error);
            }
        }

        void releaseBlockedSnapshotPublication() { blockedPublicationGate.complete(null); }
        void failNextSnapshotPublication() { failNextPublication = true; }
        void failNextSnapshotPublicationAmbiguously() {
            failNextPublicationAmbiguously = true;
        }
        void failNextPrefixCompaction() { failNextCompaction = true; }
        void completeNextMetadataOffLoop() { completeNextMetadataOffLoop = true; }
        int appendCount() { return appendCount.get(); }
        int saveCount() { return saveCount.get(); }
        int prefixTruncateCount() { return prefixTruncateCount.get(); }
        int suffixTruncateCount() { return suffixTruncateCount.get(); }
        List<LogEntryData> logEntries() { return delegate.getLog(); }

        @Override public CompletableFuture<Void> open(Path dataDir) { return delegate.open(dataDir); }
        @Override public CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor) {
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
        @Override public CompletableFuture<Void> appendEntries(List<LogEntryData> entries) {
            appendCount.incrementAndGet();
            return delegate.appendEntries(entries);
        }
        @Override public CompletableFuture<Void> truncateSuffix(long fromIndex) {
            suffixTruncateCount.incrementAndGet();
            return delegate.truncateSuffix(fromIndex);
        }
        @Override public CompletableFuture<Void> truncatePrefix(long toIndex) {
            prefixTruncateCount.incrementAndGet();
            CompletableFuture<Void> persisted = delegate.truncatePrefix(toIndex);
            if (!failNextCompaction) return persisted;
            failNextCompaction = false;
            return persisted.thenCompose(ignored -> CompletableFuture.failedFuture(
                    new IllegalStateException("uncertain installed-snapshot compaction outcome",
                            snapshotFault("uncertain installed-snapshot compaction outcome"))));
        }
        @Override public CompletableFuture<Void> sync() { return delegate.sync(); }
        @Override public CompletableFuture<List<LogEntryData>> replayLog() { return delegate.replayLog(); }
        @Override public CompletableFuture<Void> saveAtomically(SnapshotData snapshot) {
            saveCount.incrementAndGet();
            if (failNextPublication) {
                failNextPublication = false;
                return CompletableFuture.failedFuture(new SnapshotPublicationException(
                        PublicationOutcome.NOT_PUBLISHED,
                        "installed snapshot publication failed before publication",
                        new IllegalStateException("installed snapshot publication failed",
                                snapshotFault("installed snapshot publication failed"))));
            }
            if (failNextPublicationAmbiguously) {
                failNextPublicationAmbiguously = false;
                return CompletableFuture.failedFuture(new SnapshotPublicationException(
                        PublicationOutcome.PUBLICATION_MAY_HAVE_OCCURRED,
                        "installed snapshot publication outcome is uncertain",
                        new IllegalStateException("installed snapshot publication failed",
                                snapshotFault("installed snapshot publication outcome is uncertain"))));
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
        @Override public CompletableFuture<Void> closeAsync() { return SnapshotStore.super.closeAsync(); }
    }
}

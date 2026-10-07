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
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.raft.api.SnapshotStore;
import dev.mars.raftlog.storage.RaftStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static dev.mars.qraft.controller.raft.RaftAwaitHelper.awaitStateLoop;
import static dev.mars.qraft.controller.raft.RaftAwaitHelper.await;
import static dev.mars.qraft.controller.raft.RaftAwaitHelper.logEnd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link RaftNode} discards delayed append responses from a previous leadership and does
 * not let granted votes overtake a blocked higher-term transition. Elections happen only when a test
 * fires the election timeout, so no timer can move the term while a transition is held. The node bootstraps
 * its configuration at index 1, and every simulated answer carries the server ID configured for its peer, so
 * an answer that is discarded is discarded for its leadership or its ordering, not for its sender.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-14
 * @version 1.1
 */
class RaftNodeTransportGenerationTest {
    private static final Set<String> MEMBERS = Set.of("node-1", "peer-1");

    private JavaRuntime runtime;
    private RaftNode node;
    private ManualRaftTimersHelper timers;

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) await(node.stop());
        if (runtime != null) {
            runtime.shutdown().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void delayedAppendSuccessFromPreviousLeadershipCannotAdvancePeerIndexes() throws Exception {
        runtime = JavaRuntime.create();
        timers = new ManualRaftTimersHelper(runtime);
        ControlledTransportFixture transport = new ControlledTransportFixture();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("node-1")
                .serverId(ManualRaftClusterFixture.serverIdOf("node-1"))
                .initialConfiguration(ManualRaftClusterFixture.configurationOf(MEMBERS))
                .clusterNodes(MEMBERS)
                .transport(transport)
                .stateMachine(new QraftStateStore())
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode())
                .snapshotEnabled(false)
                .electionTimeout(30)
                .heartbeatInterval(10_000)
                .timerScheduler(timers)
                .build();
        await(node.start());
        timers.fireElectionTimeout();
        awaitLeaderAtOrAboveTerm(1);

        PendingAppend stale = transport.takeAppend();
        long firstLeadershipTerm = stale.request().getTerm();
        // The leadership no-op may still be landing on the state loop: read the log's end there, in one step.
        RaftAwaitHelper.LogEnd end = logEnd(runtime, node);
        AppendEntriesResponse stepDown = await(node.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                .setTerm(firstLeadershipTerm + 1)
                .setLeaderId("peer-1")
                .setPrevLogIndex(end.index())
                .setPrevLogTerm(end.term())
                .build()));
        assertTrue(stepDown.getSuccess());
        long expectedNextIndex = logEnd(runtime, node).index() + 1;

        timers.fireElectionTimeout();
        awaitLeaderAtOrAboveTerm(firstLeadershipTerm + 2);
        awaitStateLoop(runtime);
        // The new leader starts peer-1 after the retained leadership no-op.
        assertEquals(expectedNextIndex, node.getNextIndex("peer-1"));

        stale.response().complete(AppendEntriesResponse.newBuilder()
                .setTerm(firstLeadershipTerm)
                .setSuccess(true)
                .setMatchIndex(100)
                .setFollowerServerId(ManualRaftClusterFixture.serverIdOf("peer-1"))
                .build());
        awaitStateLoop(runtime);

        assertEquals(expectedNextIndex, node.getNextIndex("peer-1"),
                "a completion from an earlier leadership must be ignored");
    }

    @Test
    void grantedVoteCannotOvertakeBlockedHigherTermTransition() throws Exception {
        runtime = JavaRuntime.create();
        timers = new ManualRaftTimersHelper(runtime);
        GatedMetadataStorageFixture storage = new GatedMetadataStorageFixture();
        storage.open(null).join();
        ControlledTransportFixture transport = new ControlledTransportFixture(true);
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("node-1")
                .serverId(ManualRaftClusterFixture.serverIdOf("node-1"))
                .initialConfiguration(ManualRaftClusterFixture.configurationOf(MEMBERS))
                .clusterNodes(MEMBERS)
                .transport(transport)
                .stateMachine(new QraftStateStore())
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(false)
                .electionTimeout(100)
                .heartbeatInterval(10_000)
                .timerScheduler(timers)
                .build();
        await(node.start());
        timers.fireElectionTimeout();

        PendingVote oldElection = transport.takeVote();
        long oldTerm = oldElection.request().getTerm();
        storage.blockNextMetadataUpdate();
        Future<VoteResponse> higherTermVote = node.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(oldTerm + 1)
                .setCandidateId("peer-1")
                .setCandidateServerId(ManualRaftClusterFixture.serverIdOf("peer-1"))
                .setLastLogIndex(1)
                .setLastLogTerm(0)
                .build());
        storage.awaitBlockedMetadataUpdate();

        oldElection.response().complete(VoteResponse.newBuilder()
                .setTerm(oldTerm)
                .setVoteGranted(true)
                .setVoterServerId(ManualRaftClusterFixture.serverIdOf("peer-1"))
                .build());
        awaitStateLoop(runtime);

        assertEquals(RaftNode.State.CANDIDATE, node.getState(),
                "a vote completion must wait behind the active durable transition");
        assertEquals(oldTerm, node.getCurrentTerm());

        storage.releaseBlockedMetadataUpdateOffLoop();
        assertTrue(await(higherTermVote).getVoteGranted());
        assertEquals(RaftNode.State.FOLLOWER, node.getState());
        assertEquals(oldTerm + 1, node.getCurrentTerm());
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

    private record PendingAppend(AppendEntriesRequest request, Promise<AppendEntriesResponse> response) {}
    private record PendingVote(VoteRequest request, Promise<VoteResponse> response) {}

    /** Test transport fixture that lets tests complete pending requests across transport replacement. */
    private static final class ControlledTransportFixture implements RaftTransport {
        private final BlockingQueue<PendingAppend> appends = new LinkedBlockingQueue<>();
        private final BlockingQueue<PendingVote> votes = new LinkedBlockingQueue<>();
        private final boolean holdVotes;

        private ControlledTransportFixture() {
            this(false);
        }

        private ControlledTransportFixture(boolean holdVotes) {
            this.holdVotes = holdVotes;
        }

        PendingAppend takeAppend() throws InterruptedException {
            PendingAppend append = appends.poll(10, TimeUnit.SECONDS);
            if (append == null) throw new AssertionError("leader did not send AppendEntries");
            return append;
        }

        PendingVote takeVote() throws InterruptedException {
            PendingVote vote = votes.poll(10, TimeUnit.SECONDS);
            if (vote == null) throw new AssertionError("candidate did not request a vote");
            return vote;
        }

        @Override public void start(Consumer<RaftMessage> messageHandler) {}
        @Override public void stop() {}
        @Override public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            if (holdVotes) {
                Promise<VoteResponse> response = Promise.promise();
                votes.add(new PendingVote(request, response));
                return response.future();
            }
            return Future.succeededFuture(VoteResponse.newBuilder()
                    .setTerm(request.getTerm()).setVoteGranted(true)
                    .setVoterServerId(ManualRaftClusterFixture.serverIdOf(targetId)).build());
        }
        @Override public Future<AppendEntriesResponse> sendAppendEntries(
                String targetId, AppendEntriesRequest request) {
            Promise<AppendEntriesResponse> response = Promise.promise();
            appends.add(new PendingAppend(request, response));
            return response.future();
        }
        @Override public Future<InstallSnapshotResponse> sendInstallSnapshot(
                String targetId, InstallSnapshotRequest request) {
            return Future.succeededFuture(InstallSnapshotResponse.newBuilder()
                    .setTerm(request.getTerm()).setSuccess(true)
                    .setNextChunkIndex(request.getTotalChunks())
                    .setFollowerServerId(ManualRaftClusterFixture.serverIdOf(targetId)).build());
        }
    }

    /** Test storage fixture with controlled metadata persistence for sequencing assertions. */
    private static final class GatedMetadataStorageFixture implements RaftStorage, SnapshotStore {
        private final TestRaftStorageFixture delegate = new TestRaftStorageFixture();
        private volatile CompletableFuture<Void> nextMetadataGate;
        private volatile CompletableFuture<Void> blockedMetadataGate;
        private volatile CompletableFuture<Void> metadataUpdateEntered;

        void blockNextMetadataUpdate() {
            nextMetadataGate = new CompletableFuture<>();
            metadataUpdateEntered = new CompletableFuture<>();
        }

        void awaitBlockedMetadataUpdate() throws Exception {
            metadataUpdateEntered.get(10, TimeUnit.SECONDS);
        }

        void releaseBlockedMetadataUpdate() {
            blockedMetadataGate.complete(null);
        }

        void releaseBlockedMetadataUpdateOffLoop() throws InterruptedException {
            Thread thread = Thread.ofPlatform().name("foreign-generation-metadata-completion")
                    .start(() -> blockedMetadataGate.complete(null));
            thread.join();
        }

        @Override public CompletableFuture<Void> open(Path dataDir) { return delegate.open(dataDir); }

        @Override
        public CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor) {
            CompletableFuture<Void> gate = nextMetadataGate;
            if (gate != null) {
                nextMetadataGate = null;
                blockedMetadataGate = gate;
                metadataUpdateEntered.complete(null);
                return gate.thenCompose(ignored -> delegate.updateMetadata(term, votedFor));
            }
            return delegate.updateMetadata(term, votedFor);
        }

        @Override public CompletableFuture<PersistentMeta> loadMetadata() { return delegate.loadMetadata(); }
        @Override public CompletableFuture<Void> appendEntries(List<LogEntryData> entries) { return delegate.appendEntries(entries); }
        @Override public CompletableFuture<Void> truncateSuffix(long fromIndex) { return delegate.truncateSuffix(fromIndex); }
        @Override public CompletableFuture<Void> truncatePrefix(long toIndex) { return delegate.truncatePrefix(toIndex); }
        @Override public CompletableFuture<Void> sync() { return delegate.sync(); }
        @Override public CompletableFuture<List<LogEntryData>> replayLog() { return delegate.replayLog(); }
        @Override public CompletableFuture<Void> saveAtomically(SnapshotData snapshot) { return delegate.saveAtomically(snapshot); }
        @Override public CompletableFuture<Optional<SnapshotData>> loadLatest() { return delegate.loadLatest(); }
        @Override public void close() { delegate.close(); }
        @Override public CompletableFuture<Void> closeAsync() { return SnapshotStore.super.closeAsync(); }
    }
}

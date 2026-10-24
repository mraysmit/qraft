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

import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.api.SnapshotStore;
import dev.mars.qraft.raft.grpc.VoteResponse;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.raftlog.storage.RaftStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mars.qraft.raft.RaftAwaitHelper.awaitStateLoop;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests what a campaigning node does with a vote response that reaches it after its shutdown has begun. Its
 * transition sequencer is then draining and refuses the response at admission, before any step of it runs, so
 * the refusal must leave everything as it was: no term or vote written, no fence, and no leadership won from a vote
 * that arrived too late. The control case shows that the same response, arriving before shutdown, does change the
 * node, so the others are not passing for want of an effect.
 *
 * <p>Jenkins build 37 logged such a refusal as an error when a Docker test stopped a campaigning server. A refusal
 * at admission is the expected end of a shutdown and is logged at debug, as a refused client command is, so none of
 * these cases declares an error: the suite's error audit fails any that logs one.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
class RaftNodeShutdownVoteResponseTest {
    private static final Set<String> MEMBERS = Set.of("node-1", "peer-2", "peer-3");

    private RaftExecutorsFixture runtime;
    private ManualRaftTimersHelper timers;
    private HeldRaftTransportFixture transport;
    private MetadataCountingStorageFixture storage;
    private RaftNode node;

    @BeforeEach
    void becomeACandidateWhoseVotesAreHeld() throws Exception {
        runtime = RaftExecutorsFixture.create();
        timers = new ManualRaftTimersHelper(runtime);
        transport = new HeldRaftTransportFixture();
        storage = new MetadataCountingStorageFixture();
        storage.open(null).join();
        node = RaftNode.builder()
                .stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("node-1")
                .serverId(ManualRaftClusterFixture.serverIdOf("node-1"))
                .clusterNodes(MEMBERS)
                .initialConfiguration(ManualRaftClusterFixture.configurationOf(MEMBERS))
                .transport(transport)
                .stateMachine(new QraftStateStore())
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(false)
                .electionTimeout(300)
                .heartbeatInterval(100)
                .timerScheduler(timers)
                .build();
        node.start().get(10, TimeUnit.SECONDS);
        timers.fireElectionTimeout();
        awaitCandidacyInTermOne();
        awaitBothVoteRequestsHeld();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        if (runtime != null) runtime.shutdown().get(10, TimeUnit.SECONDS);
    }

    @Test
    void aHigherTermRefusalThatArrivesAfterShutdownBeganChangesNothing() throws Exception {
        int updatesBefore = storage.metadataUpdates();
        node.stop().get(10, TimeUnit.SECONDS);

        transport.vote("peer-2").complete(refusal("peer-2", 5));
        settle();

        RaftStatus status = status();
        assertEquals(1, status.term(), "the higher term the late response carried was not taken");
        assertEquals(updatesBefore, storage.metadataUpdates(), "and nothing was written for it");
        assertFalse(status.fenced(), "a refusal at admission is not a failure that fences the node");
        assertFalse(status.running());
        assertNotEquals(RaftNode.State.LEADER, status.state());
    }

    @Test
    void aGrantedVoteThatArrivesAfterShutdownBeganDoesNotWinLeadership() throws Exception {
        node.stop().get(10, TimeUnit.SECONDS);

        transport.vote("peer-2").complete(grant("peer-2", 1));
        settle();

        RaftStatus status = status();
        assertNotEquals(RaftNode.State.LEADER, status.state(), "a vote that came too late wins nothing");
        assertNull(status.leaderId());
        assertFalse(status.fenced());
        assertEquals(1, status.term());
        assertEquals(1, status.lastLogIndex(), "no leadership no-op was appended: the log is the bootstrap entry");
    }

    @Test
    void aSnapshotAskedForAfterShutdownBeganIsRefusedAsDrainingAndNotLoggedAsAnError() throws Exception {
        node.stop().get(10, TimeUnit.SECONDS);

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> node.takeSnapshot().get(10, TimeUnit.SECONDS));

        assertInstanceOf(RaftTransitionSequencer.DrainingException.class, refused.getCause());
    }

    @Test
    void theSameRefusalBeforeShutdownIsApplied() throws Exception {
        int updatesBefore = storage.metadataUpdates();

        transport.vote("peer-2").complete(refusal("peer-2", 5));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (status().term() != 5 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        RaftStatus status = status();
        assertEquals(5, status.term(), "arriving in time, the response moves the node to the higher term");
        assertEquals(RaftNode.State.FOLLOWER, status.state());
        assertEquals(updatesBefore + 1, storage.metadataUpdates(), "and writes it");
    }

    private void awaitCandidacyInTermOne() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((status().term() != 1 || status().state() != RaftNode.State.CANDIDATE)
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(1, status().term());
        assertEquals(RaftNode.State.CANDIDATE, status().state());
    }

    private void awaitBothVoteRequestsHeld() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (transport.votes.size() < 2 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(2, transport.votes.size(), "a vote request went to each peer");
    }

    private RaftStatus status() throws Exception {
        return node.status().get(10, TimeUnit.SECONDS);
    }

    /** Lets the state loop run every continuation already queued, including ones those continuations queue. */
    private void settle() throws Exception {
        for (int pass = 0; pass < 5; pass++) awaitStateLoop(runtime);
    }

    private static VoteResponse refusal(String peer, long term) {
        return VoteResponse.newBuilder().setTerm(term).setVoteGranted(false)
                .setVoterServerId(ManualRaftClusterFixture.serverIdOf(peer)).build();
    }

    private static VoteResponse grant(String peer, long term) {
        return VoteResponse.newBuilder().setTerm(term).setVoteGranted(true)
                .setVoterServerId(ManualRaftClusterFixture.serverIdOf(peer)).build();
    }

    /** Test storage fixture that counts the metadata writes (term and vote) a node asks for. */
    private static final class MetadataCountingStorageFixture implements RaftStorage, SnapshotStore {
        private final TestRaftStorageFixture delegate = new TestRaftStorageFixture();
        private final AtomicInteger metadataUpdates = new AtomicInteger();

        int metadataUpdates() {
            return metadataUpdates.get();
        }

        @Override public CompletableFuture<Void> open(Path dataDir) { return delegate.open(dataDir); }

        @Override
        public CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor) {
            metadataUpdates.incrementAndGet();
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

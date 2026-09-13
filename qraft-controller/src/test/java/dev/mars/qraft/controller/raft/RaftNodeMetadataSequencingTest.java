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
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
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

class RaftNodeMetadataSequencingTest {
    private JavaRuntime runtime;
    private GatedMetadataStorage storage;
    private RaftNode node;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        storage = new GatedMetadataStorage();
        storage.open(null).join();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("node-1")
                .clusterNodes(Set.of("node-1", "node-2", "node-3"))
                .transport(new InMemoryTransportSimulator("node-1"))
                .stateMachine(new QraftStateStore())
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(storage, storage))
                .electionTimeout(60_000)
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
    void concurrentCandidatesInOneTermCannotBothReachPersistenceOrReceiveVotes() {
        storage.blockNextUpdate();

        Future<VoteResponse> candidateA = node.handleVoteRequest(vote(1, "candidate-a"));
        storage.awaitBlockedUpdate();
        Future<VoteResponse> candidateB = node.handleVoteRequest(vote(1, "candidate-b"));

        awaitStateLoop();
        storage.assertUpdateCount(1);
        assertFalse(candidateA.isComplete());
        assertFalse(candidateB.isComplete());

        storage.releaseBlockedUpdate();

        assertTrue(await(candidateA).getVoteGranted());
        assertFalse(await(candidateB).getVoteGranted());
        assertEquals("candidate-a", node.getVotedFor());
        storage.assertUpdateCount(1);
    }

    @Test
    void higherTermVoteWaitsForEarlierVoteThenAdvancesTheDurableTerm() {
        storage.blockNextUpdate();

        Future<VoteResponse> termOne = node.handleVoteRequest(vote(1, "candidate-a"));
        storage.awaitBlockedUpdate();
        Future<VoteResponse> termTwo = node.handleVoteRequest(vote(2, "candidate-b"));

        awaitStateLoop();
        storage.assertUpdateCount(1);
        assertFalse(termTwo.isComplete());
        storage.releaseBlockedUpdate();

        assertTrue(await(termOne).getVoteGranted());
        VoteResponse response = await(termTwo);
        assertTrue(response.getVoteGranted());
        assertEquals(2, response.getTerm());
        assertEquals(2, storage.loadMetadata().join().currentTerm());
        assertEquals(Optional.of("candidate-b"), storage.loadMetadata().join().votedFor());
        assertEquals(2, node.getCurrentTerm());
        assertEquals("candidate-b", node.getVotedFor());
    }

    @Test
    void higherTermAppendCannotOvertakeAnInFlightVoteMetadataWrite() {
        storage.blockNextUpdate();

        Future<VoteResponse> vote = node.handleVoteRequest(vote(1, "candidate-a"));
        storage.awaitBlockedUpdate();
        Future<AppendEntriesResponse> append = node.handleAppendEntriesRequest(
                AppendEntriesRequest.newBuilder()
                        .setTerm(2)
                        .setLeaderId("leader-2")
                        .setPrevLogIndex(0)
                        .setPrevLogTerm(0)
                        .build());

        awaitStateLoop();
        storage.assertUpdateCount(1);
        assertFalse(append.isComplete());

        storage.releaseBlockedUpdate();

        assertTrue(await(vote).getVoteGranted());
        AppendEntriesResponse response = await(append);
        assertTrue(response.getSuccess());
        assertEquals(2, response.getTerm());
        assertEquals(2, storage.loadMetadata().join().currentTerm());
        assertEquals(Optional.empty(), storage.loadMetadata().join().votedFor());
    }

    @Test
    void voteStateAndResponseCompleteOnTheOwningStateLoop() {
        storage.blockNextUpdate();
        CompletableFuture<Boolean> completionOnStateLoop = new CompletableFuture<>();

        Future<VoteResponse> response = node.handleVoteRequest(vote(1, "candidate-a"));
        response.onSuccess(ignored -> completionOnStateLoop.complete(JavaRuntime.currentContext() == runtime));
        storage.awaitBlockedUpdate();
        CompletableFuture.runAsync(storage::releaseBlockedUpdate).join();

        assertTrue(await(response).getVoteGranted());
        try {
            assertTrue(completionOnStateLoop.get(2, TimeUnit.SECONDS));
        } catch (Exception error) {
            throw new AssertionError("vote completion callback did not run", error);
        }
    }

    @Test
    void uncertainMetadataFailureFencesLaterVotesWithoutAnotherStorageCall() {
        storage.failNextUpdate();

        CompletionException firstFailure = assertThrows(CompletionException.class,
                () -> await(node.handleVoteRequest(vote(1, "candidate-a"))));
        assertSame(storage.failure, firstFailure.getCause());
        storage.assertUpdateCount(1);

        CompletionException fenced = assertThrows(CompletionException.class,
                () -> await(node.handleVoteRequest(vote(2, "candidate-b"))));
        assertInstanceOf(RaftTransitionSequencer.FencedException.class, fenced.getCause());
        storage.assertUpdateCount(1);
    }

    @Test
    void selfVoteIsAppliedOnlyAfterElectionMetadataIsDurable() {
        await(node.stop());
        GatedMetadataStorage electionStorage = new GatedMetadataStorage();
        electionStorage.open(null).join();
        electionStorage.blockNextUpdate();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("single")
                .clusterNodes(Set.of("single"))
                .transport(new InMemoryTransportSimulator("single"))
                .stateMachine(new QraftStateStore())
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(electionStorage, electionStorage))
                .electionTimeout(25)
                .heartbeatInterval(10_000)
                .build();
        await(node.start());

        electionStorage.awaitBlockedUpdate();
        assertEquals(0, node.getCurrentTerm());
        assertEquals(RaftNode.State.FOLLOWER, node.getState());
        assertNull(node.getVotedFor());

        electionStorage.releaseBlockedUpdate();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.onSpinWait();

        assertTrue(node.isLeader());
        assertEquals(1, node.getCurrentTerm());
        assertEquals("single", node.getVotedFor());
        assertEquals(Optional.of("single"), electionStorage.loadMetadata().join().votedFor());
    }

    private static VoteRequest vote(long term, String candidate) {
        return VoteRequest.newBuilder()
                .setTerm(term)
                .setCandidateId(candidate)
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build();
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

    private static final class GatedMetadataStorage implements RaftStorage, SnapshotStore {
        private final TestRaftStorage delegate = new TestRaftStorage();
        private final AtomicInteger updateCount = new AtomicInteger();
        private final CompletableFuture<Void> updateEntered = new CompletableFuture<>();
        private final IllegalStateException failure = new IllegalStateException("uncertain metadata write");
        private volatile CompletableFuture<Void> updateGate;
        private volatile CompletableFuture<Void> blockedUpdateGate;
        private volatile boolean failNext;

        void blockNextUpdate() {
            updateGate = new CompletableFuture<>();
        }

        void awaitBlockedUpdate() {
            try {
                updateEntered.get(2, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new AssertionError("metadata update did not reach its gate", error);
            }
        }

        void releaseBlockedUpdate() {
            blockedUpdateGate.complete(null);
        }

        void failNextUpdate() {
            failNext = true;
        }

        void assertUpdateCount(int expected) {
            assertEquals(expected, updateCount.get());
        }

        @Override public CompletableFuture<Void> open(Path dataDir) { return delegate.open(dataDir); }

        @Override
        public CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor) {
            updateCount.incrementAndGet();
            if (failNext) {
                failNext = false;
                return CompletableFuture.failedFuture(failure);
            }
            CompletableFuture<Void> gate = updateGate;
            if (gate != null) {
                updateGate = null;
                blockedUpdateGate = gate;
                updateEntered.complete(null);
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
    }
}

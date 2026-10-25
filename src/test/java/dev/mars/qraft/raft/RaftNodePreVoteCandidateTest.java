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
import dev.mars.qraft.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.raft.grpc.PreVoteRequest;
import dev.mars.qraft.raft.grpc.PreVoteResponse;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.testing.fault.IntentionalErrorFixture;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static dev.mars.qraft.raft.RaftAwaitHelper.awaitStateLoop;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests what a voter does when its election timeout fires (Raft thesis, section 9.6): it asks the other voters for
 * pre-votes first, at its term plus one, without taking that term. Only a quorum of willing voters, itself
 * included, starts the real election. A denial, a failure, an answer from a server other than the configured one,
 * or a heartbeat in the meantime does not. A peer that cannot pre-vote is counted as willing, so a cluster with
 * servers of both kinds still elects, and a voter with no other voter elects at once.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
class RaftNodePreVoteCandidateTest {
    private static final Set<String> MEMBERS = Set.of("node-1", "peer-2", "peer-3");

    private RaftExecutorsFixture runtime;
    private ManualRaftTimersHelper timers;
    private PreVoteTransportFixture transport;
    private RaftNode node;

    private void start(Set<String> members) throws Exception {
        runtime = RaftExecutorsFixture.create();
        timers = new ManualRaftTimersHelper(runtime);
        transport = new PreVoteTransportFixture();
        node = RaftNode.builder()
                .stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks()).nodeId("node-1")
                .serverId(ManualRaftClusterFixture.serverIdOf("node-1")).clusterNodes(members)
                .initialConfiguration(ManualRaftClusterFixture.configurationOf(members))
                .transport(transport)
                .stateMachine(new QraftStateStore()).commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode()).snapshotEnabled(false)
                .electionTimeout(300).heartbeatInterval(100)
                .timerScheduler(timers).build();
        node.start().get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        if (runtime != null) runtime.shutdown().get(10, TimeUnit.SECONDS);
    }

    @Test
    void anElectionTimeoutAsksEachVoterForAPreVoteAtTheNextTermAndStartsNoElection() throws Exception {
        start(MEMBERS);

        timers.fireElectionTimeout();
        await(() -> transport.preVotes.size() == 2, "a pre-vote request went to each peer");
        settle();

        assertEquals(Set.of("peer-2", "peer-3"), Set.copyOf(transport.preVotes.stream()
                .map(HeldRaftTransportFixture.Held::target).toList()));
        for (var held : transport.preVotes) {
            assertEquals(1, held.request().getTerm(), "it asks for the term after its own");
            assertEquals("node-1", held.request().getCandidateId());
            assertEquals(ManualRaftClusterFixture.serverIdOf("node-1"), held.request().getCandidateServerId());
            assertEquals(1, held.request().getLastLogIndex(), "its log holds the bootstrap configuration");
            assertEquals(0, held.request().getLastLogTerm());
        }
        RaftStatus status = status();
        assertEquals(0, status.term(), "asking takes no term");
        assertEquals(RaftNode.State.FOLLOWER, status.state());
        assertTrue(transport.votes.isEmpty(), "and starts no election");
    }

    @Test
    void aQuorumOfWillingVotersStartsTheElectionAtTheNextTerm() throws Exception {
        start(MEMBERS);
        timers.fireElectionTimeout();
        await(() -> transport.preVotes.size() == 2, "pre-votes held");

        transport.preVote("peer-2").complete(answer("peer-2", 0, true));

        await(() -> transport.votes.size() == 2, "a real vote request went to each peer");
        assertEquals(1, status().term());
        assertEquals(RaftNode.State.CANDIDATE, status().state());
        for (var held : transport.votes) assertEquals(1, held.request().getTerm());
    }

    @Test
    void denialsStartNoElectionAndTheNextTimeoutAsksAgain() throws Exception {
        start(MEMBERS);
        timers.fireElectionTimeout();
        await(() -> transport.preVotes.size() == 2, "pre-votes held");

        transport.preVote("peer-2").complete(answer("peer-2", 0, false));
        transport.preVote("peer-3").complete(answer("peer-3", 0, false));
        settle();

        assertTrue(transport.votes.isEmpty());
        assertEquals(0, status().term());
        timers.fireElectionTimeout();
        await(() -> transport.preVotes.size() == 4, "the next election timeout asks again");
        assertTrue(transport.votes.isEmpty());
    }

    @Test
    void aHeartbeatDuringTheRoundCancelsIt() throws Exception {
        start(MEMBERS);
        timers.fireElectionTimeout();
        await(() -> transport.preVotes.size() == 2, "pre-votes held");

        assertTrue(node.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                .setTerm(1).setLeaderId("peer-2").setPrevLogIndex(1).setPrevLogTerm(0).setLeaderCommit(1)
                .build()).get(10, TimeUnit.SECONDS).getSuccess());
        transport.preVote("peer-2").complete(answer("peer-2", 1, true));
        transport.preVote("peer-3").complete(answer("peer-3", 1, true));
        settle();

        assertTrue(transport.votes.isEmpty(), "answers that arrive after a leader was heard start nothing");
        RaftStatus status = status();
        assertEquals(RaftNode.State.FOLLOWER, status.state());
        assertEquals("peer-2", status.leaderId());
        assertEquals(1, status.term());
    }

    @Test
    void aLeaderThatHasBeenSilentForAWholeElectionTimeoutIsNoLongerReportedUntilItIsHeardAgain() throws Exception {
        start(MEMBERS);
        assertTrue(heartbeatFrom("peer-2", 1).getSuccess());
        assertEquals("peer-2", status().leaderId());

        timers.fireElectionTimeout();
        await(() -> transport.preVotes.size() == 2, "pre-votes held");
        settle();

        RaftStatus silent = status();
        assertNull(silent.leaderId(), "a server cut off from its leader must not go on reporting it, or it is 'ready'");
        assertEquals(RaftNode.State.FOLLOWER, silent.state(), "it is still a follower and has taken no term");
        assertEquals(1, silent.term());
        assertTrue(heartbeatFrom("peer-2", 1).getSuccess());
        assertEquals("peer-2", status().leaderId(), "and the next heartbeat makes the leader known again");
    }

    @Test
    void aVoterWithNoOtherVoterElectsAtOnceWithoutAskingAnyone() throws Exception {
        start(Set.of("node-1"));

        timers.fireElectionTimeout();
        await(() -> {
            try {
                return status().state() == RaftNode.State.LEADER;
            } catch (Exception unavailable) {
                return false;
            }
        }, "the sole voter leads");

        assertTrue(transport.preVotes.isEmpty());
        assertEquals(1, status().term());
    }

    @Test
    void aPeerThatCannotPreVoteIsCountedAsWilling() throws Exception {
        start(MEMBERS);
        transport.failPreVote("peer-2", new UnsupportedOperationException("peer-2 has no pre-vote"));

        timers.fireElectionTimeout();

        await(() -> transport.votes.size() == 2, "itself and peer-2 are a quorum, so the election starts");
        assertEquals(1, status().term());
    }

    @Test
    void anUnreachablePeerIsNotCountedAsWilling() throws Exception {
        IntentionalErrorsHelper.expect(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE);
        start(MEMBERS);
        transport.failPreVote("peer-2", new IOException("UNAVAILABLE: io exception"));
        transport.failPreVote("peer-3", new IOException("UNAVAILABLE: io exception"));

        timers.fireElectionTimeout();
        settle();

        assertTrue(transport.votes.isEmpty(), "a peer that cannot be reached has not said yes");
        assertEquals(0, status().term());
    }

    @Test
    void aVoterCutOffForManyElectionTimeoutsKeepsItsTermAndAnswersTheLeaderInTheLeadersTerm() throws Exception {
        IntentionalErrorsHelper.expect(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE);
        start(MEMBERS);
        transport.failPreVote("peer-2", new IOException("UNAVAILABLE: io exception"));
        transport.failPreVote("peer-3", new IOException("UNAVAILABLE: io exception"));

        for (int timeout = 0; timeout < 10; timeout++) {
            timers.fireElectionTimeout();
            settle();
        }

        RaftStatus cutOff = status();
        assertEquals(0, cutOff.term(), "ten election timeouts without a quorum raised no term (it was 10 before pre-votes)");
        assertEquals(RaftNode.State.FOLLOWER, cutOff.state());
        assertTrue(transport.votes.isEmpty());

        // The leader's heartbeat reaches it at last, in the leader's term 7.
        AppendEntriesResponse answer = node.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                .setTerm(7).setLeaderId("peer-2").setPrevLogIndex(1).setPrevLogTerm(0).setLeaderCommit(1)
                .build()).get(10, TimeUnit.SECONDS);

        assertTrue(answer.getSuccess());
        assertEquals(7, answer.getTerm(), "its answer carries the leader's term, so the leader is not deposed");
        RaftStatus rejoined = status();
        assertEquals(7, rejoined.term());
        assertEquals(RaftNode.State.FOLLOWER, rejoined.state());
        assertEquals("peer-2", rejoined.leaderId());
    }

    @Test
    void anAnswerFromAServerOtherThanTheConfiguredOneIsNotCounted() throws Exception {
        start(MEMBERS);
        timers.fireElectionTimeout();
        await(() -> transport.preVotes.size() == 2, "pre-votes held");

        transport.preVote("peer-2").complete(PreVoteResponse.newBuilder().setTerm(0).setVoteGranted(true)
                .setVoterServerId("a-server-that-replaced-peer-2").build());
        transport.preVote("peer-3").complete(answer("peer-3", 0, false));
        settle();

        assertTrue(transport.votes.isEmpty(), "a stranger's yes is not peer-2's");
        assertEquals(0, status().term());
    }

    @Test
    void aYesThatArrivesAfterTheNodeStoppedStartsNothing() throws Exception {
        start(MEMBERS);
        timers.fireElectionTimeout();
        await(() -> transport.preVotes.size() == 2, "pre-votes held");
        node.stop().get(10, TimeUnit.SECONDS);

        transport.preVote("peer-2").complete(answer("peer-2", 0, true));
        settle();

        assertTrue(transport.votes.isEmpty());
        assertNull(status().leaderId());
    }

    // ---------------------------------------------------------------------------------------------------

    /** A heartbeat from {@code leader}, whose log, like every member's, begins with the bootstrap configuration. */
    private AppendEntriesResponse heartbeatFrom(String leader, long term) throws Exception {
        return node.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                .setTerm(term).setLeaderId(leader).setPrevLogIndex(1).setPrevLogTerm(0).setLeaderCommit(1)
                .build()).get(10, TimeUnit.SECONDS);
    }

    private static PreVoteResponse answer(String peer, long term, boolean granted) {
        return PreVoteResponse.newBuilder().setTerm(term).setVoteGranted(granted)
                .setVoterServerId(ManualRaftClusterFixture.serverIdOf(peer)).build();
    }

    private RaftStatus status() throws Exception {
        return node.status().get(10, TimeUnit.SECONDS);
    }

    private void settle() throws Exception {
        for (int pass = 0; pass < 5; pass++) awaitStateLoop(runtime);
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    /** Test transport fixture that holds every pre-vote and vote request for the test to answer by hand. */
    private static final class PreVoteTransportFixture implements RaftTransport {
        final List<HeldRaftTransportFixture.Held<PreVoteRequest, PreVoteResponse>> preVotes =
                new CopyOnWriteArrayList<>();
        final List<HeldRaftTransportFixture.Held<VoteRequest, VoteResponse>> votes = new CopyOnWriteArrayList<>();
        private final Map<String, Throwable> failingPreVotes = new ConcurrentHashMap<>();

        /** Fails every pre-vote sent to {@code peer} with {@code failure}. */
        void failPreVote(String peer, Throwable failure) {
            failingPreVotes.put(peer, failure);
        }

        /** The first pre-vote request sent to {@code peer}. */
        CompletableFuture<PreVoteResponse> preVote(String peer) {
            return preVotes.stream().filter(held -> held.target().equals(peer)).findFirst().orElseThrow().response();
        }

        @Override public void start(Consumer<RaftMessage> messageHandler) { }
        @Override public void stop() { }

        @Override
        public CompletableFuture<PreVoteResponse> sendPreVote(String targetId, PreVoteRequest request) {
            Throwable failure = failingPreVotes.get(targetId);
            if (failure != null) return CompletableFuture.failedFuture(failure);
            CompletableFuture<PreVoteResponse> response = new CompletableFuture<>();
            preVotes.add(new HeldRaftTransportFixture.Held<>(targetId, request, response));
            return response;
        }

        @Override
        public CompletableFuture<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            CompletableFuture<VoteResponse> response = new CompletableFuture<>();
            votes.add(new HeldRaftTransportFixture.Held<>(targetId, request, response));
            return response;
        }

        @Override
        public CompletableFuture<AppendEntriesResponse> sendAppendEntries(
                String targetId, AppendEntriesRequest request) {
            return new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(
                String targetId, InstallSnapshotRequest request) {
            return new CompletableFuture<>();
        }
    }
}

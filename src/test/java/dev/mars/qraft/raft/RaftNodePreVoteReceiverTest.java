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
import dev.mars.qraft.raft.grpc.PreVoteRequest;
import dev.mars.qraft.raft.grpc.PreVoteResponse;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how a server answers a pre-vote (Raft thesis, section 9.6): it says whether it would vote for the sender in a
 * real election, and changes nothing by saying so. It would not while it has heard from a leader within an election
 * timeout, while it is a leader, for a term that is not ahead of its own, for a log behind its own, for a server that
 * is not a voter in its configuration, or when it is not running. Saying yes records no vote and adopts no term, which
 * a real vote afterwards shows.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
class RaftNodePreVoteReceiverTest {
    private static final Set<String> MEMBERS = Set.of("node-1", "peer-2", "peer-3");
    private static final long ELECTION_TIMEOUT_MS = 300;

    private RaftExecutorsFixture runtime;
    private ManualRaftTimersHelper timers;
    private HeldRaftTransportFixture transport;
    private RaftNode node;

    @BeforeEach
    void startAFollowerThatKnowsNoLeader() throws Exception {
        runtime = RaftExecutorsFixture.create();
        timers = new ManualRaftTimersHelper(runtime);
        transport = new HeldRaftTransportFixture();
        node = RaftNode.builder()
                .stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks()).nodeId("node-1")
                .serverId(ManualRaftClusterFixture.serverIdOf("node-1")).clusterNodes(MEMBERS)
                .initialConfiguration(ManualRaftClusterFixture.configurationOf(MEMBERS))
                .transport(transport)
                .stateMachine(new QraftStateStore()).commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode()).snapshotEnabled(false)
                .electionTimeout(ELECTION_TIMEOUT_MS).heartbeatInterval(100)
                .timerScheduler(timers).build();
        node.start().get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        if (runtime != null) runtime.shutdown().get(10, TimeUnit.SECONDS);
    }

    @Test
    void anUpToDateVoterIsGrantedWhenNoLeaderHasBeenHeard() throws Exception {
        PreVoteResponse answer = preVote("peer-2", 1, 1, 0);

        assertTrue(answer.getVoteGranted(), answer.toString());
        assertEquals(ManualRaftClusterFixture.serverIdOf("node-1"), answer.getVoterServerId());
        assertEquals(0, answer.getTerm(), "the answer carries the responder's own term");
    }

    @Test
    void grantingAPreVoteRecordsNoVoteAdoptsNoTermAndNotesNoLeader() throws Exception {
        assertTrue(preVote("peer-2", 1, 1, 0).getVoteGranted());

        RaftStatus status = status();
        assertEquals(0, status.term(), "no term is adopted");
        assertEquals(RaftNode.State.FOLLOWER, status.state());
        assertNull(status.leaderId());
        VoteResponse vote = vote("peer-3", 1, 1, 0);
        assertTrue(vote.getVoteGranted(), "no vote was recorded, so another candidate can still have it: " + vote);
        assertEquals(1, vote.getTerm());
    }

    @Test
    void aTermInWhichTheVoterHasAlreadyVotedIsNotAheadOfItAndIsRefusedWhoeverAsks() throws Exception {
        assertTrue(vote("peer-2", 1, 1, 0).getVoteGranted());

        assertFalse(preVote("peer-3", 1, 1, 0).getVoteGranted(), "term 1 is the voter's own term");
        assertFalse(preVote("peer-2", 1, 1, 0).getVoteGranted(), "whoever asks");
        assertTrue(preVote("peer-3", 2, 1, 0).getVoteGranted(), "a later term is ahead");
    }

    @Test
    void aVoterWhoHeardALeaderWithinTheElectionTimeoutRefusesUntilTheTimeoutHasPassed() throws Exception {
        assertTrue(heartbeatFrom("peer-2", 1).getSuccess());

        assertFalse(preVote("peer-3", 2, 1, 0).getVoteGranted(), "a leader is live");
        timers.advanceTime(ELECTION_TIMEOUT_MS - 1);
        assertFalse(preVote("peer-3", 2, 1, 0).getVoteGranted(), "one millisecond before the timeout has passed");
        timers.advanceTime(1);
        assertTrue(preVote("peer-3", 2, 1, 0).getVoteGranted(), "the leader has been silent for a whole timeout");
        assertEquals(1, status().term(), "and the refusals and the grant did not move the term");
    }

    @Test
    void aLeaderRefusesAndStaysLeader() throws Exception {
        becomeLeader();

        assertFalse(preVote("peer-3", 2, 1, 0).getVoteGranted());

        RaftStatus status = status();
        assertEquals(RaftNode.State.LEADER, status.state());
        assertEquals(1, status.term());
    }

    @Test
    void aTermThatIsNotAheadOfTheVotersOwnIsRefusedAndTheAnswerTellsTheSenderTheCurrentTerm() throws Exception {
        assertTrue(heartbeatFrom("peer-2", 3).getSuccess());
        timers.advanceTime(ELECTION_TIMEOUT_MS);

        PreVoteResponse answer = preVote("peer-3", 3, 1, 0);

        assertFalse(answer.getVoteGranted(), "term 3 is not ahead of the voter's term 3");
        assertEquals(3, answer.getTerm());
        assertTrue(preVote("peer-3", 4, 1, 0).getVoteGranted(), "the next term is");
        assertFalse(preVote("peer-3", 2, 1, 0).getVoteGranted(), "an earlier term is not");
    }

    @Test
    void aLogBehindTheVotersOwnIsRefused() throws Exception {
        assertFalse(preVote("peer-2", 1, 0, 0).getVoteGranted(), "its log is empty and the voter's is not");
        assertTrue(preVote("peer-2", 1, 1, 0).getVoteGranted(), "with the voter's one entry it is as up to date");
    }

    @Test
    void aServerThatIsNotAVoterInTheConfigurationIsRefused() throws Exception {
        PreVoteResponse stranger = node.handlePreVoteRequest(PreVoteRequest.newBuilder()
                .setTerm(1).setCandidateId("stranger").setCandidateServerId("not-a-configured-server")
                .setLastLogIndex(1).setLastLogTerm(0).build()).get(10, TimeUnit.SECONDS);

        assertFalse(stranger.getVoteGranted(), "a removed or unknown server cannot start an election");
        assertTrue(preVote("peer-2", 1, 1, 0).getVoteGranted(), "a configured voter can");
    }

    @Test
    void aServerThatHasNoConfigurationYetStillAnswersByLeaderTermAndLog() throws Exception {
        // A server that has not yet joined a cluster has no configuration to check the sender against. Refusing
        // would leave a cluster whose first server bootstrapped alone unable to elect: the others wait to join its
        // leader, and it waits for their votes.
        RaftNode unconfigured = RaftNode.builder()
                .stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks()).nodeId("node-1")
                .serverId(ManualRaftClusterFixture.serverIdOf("node-1")).clusterNodes(MEMBERS)
                .transport(new HeldRaftTransportFixture())
                .stateMachine(new QraftStateStore()).commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode()).snapshotEnabled(false)
                .electionTimeout(ELECTION_TIMEOUT_MS).heartbeatInterval(100)
                .timerScheduler(new ManualRaftTimersHelper(runtime)).build();
        unconfigured.start().get(10, TimeUnit.SECONDS);
        try {
            assertTrue(unconfigured.getConfiguration().isEmpty(), "this server has no configuration");

            PreVoteResponse answer = unconfigured.handlePreVoteRequest(PreVoteRequest.newBuilder()
                    .setTerm(1).setCandidateId("peer-2")
                    .setCandidateServerId(ManualRaftClusterFixture.serverIdOf("peer-2"))
                    .setLastLogIndex(1).setLastLogTerm(0).build()).get(10, TimeUnit.SECONDS);

            assertTrue(answer.getVoteGranted(), "it knows no leader, and the term and the log are acceptable");
            assertFalse(unconfigured.handlePreVoteRequest(PreVoteRequest.newBuilder()
                    .setTerm(0).setCandidateId("peer-2")
                    .setCandidateServerId(ManualRaftClusterFixture.serverIdOf("peer-2"))
                    .setLastLogIndex(1).setLastLogTerm(0).build()).get(10, TimeUnit.SECONDS).getVoteGranted(),
                    "but a term that is not ahead of its own is still refused");
        } finally {
            unconfigured.stop().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void aServerThatIsNotRunningRefuses() throws Exception {
        node.stop().get(10, TimeUnit.SECONDS);

        assertFalse(preVote("peer-2", 1, 1, 0).getVoteGranted());
    }

    private PreVoteResponse preVote(String candidate, long term, long lastLogIndex, long lastLogTerm)
            throws Exception {
        return node.handlePreVoteRequest(PreVoteRequest.newBuilder()
                .setTerm(term).setCandidateId(candidate)
                .setCandidateServerId(ManualRaftClusterFixture.serverIdOf(candidate))
                .setLastLogIndex(lastLogIndex).setLastLogTerm(lastLogTerm).build()).get(10, TimeUnit.SECONDS);
    }

    private VoteResponse vote(String candidate, long term, long lastLogIndex, long lastLogTerm) throws Exception {
        return node.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(term).setCandidateId(candidate)
                .setCandidateServerId(ManualRaftClusterFixture.serverIdOf(candidate))
                .setLastLogIndex(lastLogIndex).setLastLogTerm(lastLogTerm).build()).get(10, TimeUnit.SECONDS);
    }

    /** A heartbeat from {@code leader}, whose log, like every member's, begins with the bootstrap configuration. */
    private dev.mars.qraft.raft.grpc.AppendEntriesResponse heartbeatFrom(String leader, long term) throws Exception {
        return node.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                        .setTerm(term).setLeaderId(leader).setPrevLogIndex(1).setPrevLogTerm(0).setLeaderCommit(1)
                        .build())
                .get(10, TimeUnit.SECONDS);
    }

    /** Elects the node in term 1: its election timeout fires and one peer grants its vote. */
    private void becomeLeader() throws Exception {
        timers.fireElectionTimeout();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (transport.votes.size() < 2 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(2, transport.votes.size(), "a vote request went to each peer");
        transport.vote("peer-2").complete(VoteResponse.newBuilder().setTerm(1).setVoteGranted(true)
                .setVoterServerId(ManualRaftClusterFixture.serverIdOf("peer-2")).build());
        while (status().state() != RaftNode.State.LEADER && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(RaftNode.State.LEADER, status().state());
    }

    private RaftStatus status() throws Exception {
        return node.status().get(10, TimeUnit.SECONDS);
    }
}

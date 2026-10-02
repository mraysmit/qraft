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

import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.runtime.Promise;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static dev.mars.qraft.controller.raft.ManualRaftCluster.await;
import static dev.mars.qraft.controller.raft.ManualRaftCluster.serverIdOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a {@link RaftNode} counts votes and acknowledgements by server ID, as its configuration records them.
 * A peer that answers under its configured name with another server ID, as a server that lost its storage does,
 * is neither counted nor listened to, even when it reports a higher term; the configured server's answer to the
 * same request is counted. A non-voter's acknowledgement does not count towards a commit, and a server that is
 * not a voter in any configuration never campaigns.
 *
 * <p>The test answers each request by hand. Before asserting that something did not happen, it sends the node a
 * vote request, which the node's transition sequencer handles after every earlier transition, so the answer
 * under test has been applied.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
class RaftNodeServerIdCountingTest {
    private static final Set<String> MEMBERS = Set.of("a", "b", "c");
    private static final String WIPED = "00000000-0000-0000-0000-00000000dead";

    private JavaRuntime runtime;
    private ManualRaftCluster cluster;
    private HeldRaftTransport transport;
    private RaftNode node;

    @BeforeEach
    void setUp() throws Exception {
        runtime = JavaRuntime.create();
        cluster = new ManualRaftCluster(runtime);
        transport = new HeldRaftTransport();
        node = cluster.add(cluster.builder("a", MEMBERS, transport, new QraftStateStore(), RaftNodeMode.volatileMode()));
        await(node.start());
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            cluster.close();
        } finally {
            runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void aVoteCountsOnlyFromTheServerConfiguredUnderThatName() throws Exception {
        cluster.timers(node).fireElectionTimeout();
        awaitTrue(() -> transport.votes.size() == 2, "the candidate asks both other voters");

        transport.vote("b").complete(VoteResponse.newBuilder()
                .setTerm(1).setVoteGranted(true).setVoterServerId(WIPED).build());
        barrier();
        assertEquals(RaftNode.State.CANDIDATE, node.getState(), "a vote from another server ID is not counted");

        transport.vote("c").complete(VoteResponse.newBuilder()
                .setTerm(1).setVoteGranted(true).setVoterServerId(serverIdOf("c")).build());
        barrier();
        assertEquals(RaftNode.State.LEADER, node.getState(), "the configured server's vote makes a majority");
    }

    @Test
    void aVoteFromAnotherServerIdCannotMoveTheCandidatesTerm() throws Exception {
        cluster.timers(node).fireElectionTimeout();
        awaitTrue(() -> transport.votes.size() == 2, "the candidate asks both other voters");

        transport.vote("b").complete(VoteResponse.newBuilder()
                .setTerm(99).setVoteGranted(false).setVoterServerId(WIPED).build());
        barrier();

        assertEquals(RaftNode.State.CANDIDATE, node.getState());
        assertEquals(1, node.getCurrentTerm(), "an unconfigured server cannot move the candidate's term");
    }

    @Test
    void anAcknowledgementCountsOnlyFromTheServerConfiguredUnderThatName() throws Exception {
        elect();
        long index = node.getLastLogIndex() + 1;
        Future<?> write = node.submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put("k", "v")));
        awaitTrue(() -> transport.appendCarrying(index, "b") != null && transport.appendCarrying(index, "c") != null,
                "the leader replicates the entry to both followers");

        transport.appendCarrying(index, "b").complete(ack(1, index, WIPED));
        barrier();
        assertTrue(node.getCommitIndex() < index, "an acknowledgement from another server ID is not counted");

        transport.appendCarrying(index, "c").complete(ack(1, index, serverIdOf("c")));
        await(write);
        assertEquals(index, node.getCommitIndex(), "the configured server's acknowledgement makes a majority");
    }

    @Test
    void anOlderAcknowledgementArrivingLateDoesNotMoveAFollowerBack() throws Exception {
        elect();
        awaitTrue(() -> transport.appends.stream().anyMatch(held -> held.target().equals("b")),
                "the new leader heartbeats b");
        Promise<AppendEntriesResponse> heartbeat = transport.appends.stream()
                .filter(held -> held.target().equals("b")).findFirst().orElseThrow().response();
        long heartbeatMatch = transport.appends.stream()
                .filter(held -> held.target().equals("b")).findFirst().orElseThrow().request().getPrevLogIndex();
        long index = node.getLastLogIndex() + 1;
        Future<?> write = node.submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put("k", "v")));
        awaitTrue(() -> transport.appendCarrying(index, "b") != null, "the entry is sent to b");

        transport.appendCarrying(index, "b").complete(ack(1, index, serverIdOf("b")));
        await(write);
        heartbeat.complete(ack(1, heartbeatMatch, serverIdOf("b")));
        barrier();

        assertEquals(index + 1, node.getNextIndex("b"), "b's reply to the earlier heartbeat arrived last");
    }

    @Test
    void anAnswerFromAnotherServerIdIsIgnoredEvenWithAHigherTerm() throws Exception {
        elect();
        awaitTrue(() -> !transport.appends.isEmpty(), "the leader heartbeats");

        transport.appends.getFirst().response().complete(AppendEntriesResponse.newBuilder()
                .setTerm(99).setSuccess(false).setFollowerServerId(WIPED).build());
        barrier();

        assertEquals(RaftNode.State.LEADER, node.getState());
        assertEquals(1, node.getCurrentTerm(), "an unconfigured server cannot move the leader's term");
    }

    @Test
    void aNonVotersAcknowledgementIsNotCountedTowardsACommit() throws Exception {
        HeldRaftTransport held = new HeldRaftTransport();
        RaftConfiguration withLearner = new RaftConfiguration(List.of(
                new RaftConfiguration.Server(serverIdOf("x"), "x", "x", true),
                new RaftConfiguration.Server(serverIdOf("y"), "y", "y", true),
                new RaftConfiguration.Server(serverIdOf("z"), "z", "z", false)));
        RaftNode leader = cluster.add(cluster.unconfiguredBuilder("x", Set.of("x", "y", "z"), held,
                new QraftStateStore(), RaftNodeMode.volatileMode())
                .serverId(serverIdOf("x")).initialConfiguration(withLearner));
        await(leader.start());
        cluster.timers(leader).fireElectionTimeout();
        awaitTrue(() -> held.votes.size() == 1, "the candidate asks only the other voter");
        held.vote("y").complete(VoteResponse.newBuilder()
                .setTerm(1).setVoteGranted(true).setVoterServerId(serverIdOf("y")).build());
        awaitTrue(() -> leader.getState() == RaftNode.State.LEADER, "x leads with y's vote");
        awaitTrue(() -> leader.getLastLogIndex() == 2, "leadership no-op appended before the tested write");
        long index = leader.getLastLogIndex() + 1;
        Future<?> write = leader.submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put("k", "v")));
        awaitTrue(() -> held.appendCarrying(index, "z") != null && held.appendCarrying(index, "y") != null,
                "the leader replicates to the voter and the non-voter");

        held.appendCarrying(index, "z").complete(ack(1, index, serverIdOf("z")));
        await(leader.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(0).setCandidateId("probe").setCandidateServerId("probe").build()));
        assertTrue(leader.getCommitIndex() < index, "a non-voter does not count towards a majority");

        held.appendCarrying(index, "y").complete(ack(1, index, serverIdOf("y")));
        await(write);
        assertEquals(index, leader.getCommitIndex());
    }

    @Test
    void aSnapshotInstallAnswerFromAnotherServerIdIsIgnored() throws Exception {
        HeldRaftTransport held = new HeldRaftTransport();
        TestRaftStorage storage = new TestRaftStorage();
        storage.open(null).get(10, TimeUnit.SECONDS);
        RaftNode leader = cluster.add(cluster.builder("a", MEMBERS, held, new QraftStateStore(),
                        RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(true).snapshotThreshold(3).snapshotCheckInterval(300));
        await(leader.start());
        cluster.timers(leader).fireElectionTimeout();
        awaitTrue(() -> held.votes.size() == 2, "the candidate asks both other voters");
        held.vote("b").complete(VoteResponse.newBuilder()
                .setTerm(1).setVoteGranted(true).setVoterServerId(serverIdOf("b")).build());
        awaitTrue(() -> leader.getState() == RaftNode.State.LEADER, "a leads with b's vote");
        awaitTrue(() -> leader.getLastLogIndex() == 2, "leadership no-op appended");
        for (int i = 0; i < 4; i++) {
            long index = leader.getLastLogIndex() + 1;
            Future<?> write = leader.submitCommand(
                    new DistributedStateRaftCommand(DistributedStateCommand.put("k" + i, "v")));
            awaitTrue(() -> held.appendCarrying(index, "b") != null, "the entry is sent to b");
            held.appendCarrying(index, "b").complete(ack(1, index, serverIdOf("b")));
            await(write);
        }
        cluster.timers(leader).firePeriodic(300);
        awaitTrue(() -> leader.getSnapshotLastIndex() >= 3, "the leader compacts its log");
        // c never answered, so its next index is behind the snapshot and a heartbeat sends it the snapshot.
        cluster.timers(leader).firePeriodic(ManualRaftCluster.HEARTBEAT_MS);
        awaitTrue(() -> !held.snapshots.isEmpty(), "the leader sends c its snapshot");

        held.snapshots.getFirst().response().complete(InstallSnapshotResponse.newBuilder()
                .setTerm(99).setSuccess(false).setFollowerServerId(WIPED).build());
        await(leader.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(0).setCandidateId("probe").setCandidateServerId("probe").build()));

        assertEquals(RaftNode.State.LEADER, leader.getState());
        assertEquals(1, leader.getCurrentTerm(), "an unconfigured server cannot move the leader's term");
    }

    @Test
    void aServerThatIsNotAVoterInAnyConfigurationNeverCampaigns() throws Exception {
        HeldRaftTransport unconfiguredTransport = new HeldRaftTransport();
        RaftNode unconfigured = cluster.add(cluster.unconfiguredBuilder("d", Set.of("d", "e", "f"),
                unconfiguredTransport, new QraftStateStore(), RaftNodeMode.volatileMode()));
        await(unconfigured.start());

        cluster.timers(unconfigured).fireElectionTimeout();
        await(unconfigured.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(0).setCandidateId("probe").setCandidateServerId("probe").build()));

        assertEquals(RaftNode.State.FOLLOWER, unconfigured.getState());
        assertEquals(0, unconfigured.getCurrentTerm());
        assertEquals(List.of(), unconfiguredTransport.votes, "no vote was requested");
    }

    private void elect() throws Exception {
        cluster.timers(node).fireElectionTimeout();
        awaitTrue(() -> transport.votes.size() == 2, "the candidate asks both other voters");
        transport.vote("b").complete(VoteResponse.newBuilder()
                .setTerm(1).setVoteGranted(true).setVoterServerId(serverIdOf("b")).build());
        awaitTrue(() -> node.getState() == RaftNode.State.LEADER, "a leads with b's vote");
        barrier();
        awaitTrue(() -> node.getLastLogIndex() == 2, "leadership no-op appended before client writes");
    }

    private static AppendEntriesResponse ack(long term, long matchIndex, String followerServerId) {
        return AppendEntriesResponse.newBuilder()
                .setTerm(term).setSuccess(true).setMatchIndex(matchIndex).setFollowerServerId(followerServerId).build();
    }

    /** Returns once every transition the node had queued has been applied. */
    private void barrier() throws Exception {
        await(node.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(0).setCandidateId("probe").setCandidateServerId("probe").build()));
    }

    /** Bounds a wait for the state loop; the bound only diagnoses a hang. */
    private static void awaitTrue(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), description);
    }

}

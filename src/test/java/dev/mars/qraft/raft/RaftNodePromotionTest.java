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

import dev.mars.qraft.raft.HeldRaftTransportFixture.Held;
import dev.mars.qraft.raft.RaftConfiguration.Server;
import dev.mars.qraft.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static dev.mars.qraft.raft.ManualRaftClusterFixture.serverIdOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how a {@link RaftNode} leader promotes a non-voter, as Consul's autopilot does:
 * <ul>
 *   <li>a server joins a configuration as a non-voter;</li>
 *   <li>a non-voter is promoted once it has stayed healthy for the stabilization period: it has answered the
 *       leader in the leader's term within the last two heartbeat rounds, and holds the leader's log to within
 *       the allowed number of entries;</li>
 *   <li>a non-voter that is behind, or that has not answered, is not promoted, however long it waits.</li>
 * </ul>
 *
 * <p>The leader's followers are simulated: the test answers every request by hand, round by round, so the
 * number of heartbeat rounds before a promotion is exact. Before each assertion it sends the leader a vote
 * request, which the leader's transition sequencer handles after everything it had queued.
 * The timing boundary cases also cover the quorum grace and the election delay supplied to the scheduler.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
class RaftNodePromotionTest {
    private static final Server A = new Server(serverIdOf("a"), "a", "a", true);
    private static final Server B = new Server(serverIdOf("b"), "b", "b", true);
    private static final Server D_NON_VOTER = new Server(serverIdOf("d"), "d", "d", false);
    private static final long FULL = -1;

    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private HeldRaftTransportFixture transport;
    private RaftNode leader;

    @BeforeEach
    void setUp() {
        runtime = RaftExecutorsFixture.create();
        cluster = new ManualRaftClusterFixture(runtime);
        transport = new HeldRaftTransportFixture();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            cluster.close();
        } finally {
            runtime.close().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void anAddedServerJoinsAsANonVoter() throws Exception {
        start(new RaftConfiguration(List.of(A, B)), 3, 250);
        commitACommand();

        ExecutionException refused = assertThrows(ExecutionException.class, () -> await(leader.proposeConfiguration(
                new RaftConfiguration(List.of(A, B, new Server(serverIdOf("d"), "d", "d", true))))));
        assertInstanceOf(IllegalArgumentException.class, refused.getCause());
        assertTrue(refused.getCause().getMessage().contains("joins as a non-voter"), refused.getCause().getMessage());

        leader.proposeConfiguration(new RaftConfiguration(List.of(A, B, D_NON_VOTER)));
        awaitTrue(() -> leader.getConfiguration().orElseThrow().server(serverIdOf("d")).isPresent(),
                "d joins as a non-voter");
    }

    @Test
    void aCaughtUpNonVoterIsPromotedOnceItHasStayedHealthyForTheStabilizationPeriod() throws Exception {
        start(new RaftConfiguration(List.of(A, B, D_NON_VOTER)), 3, 250);
        commitACommand();

        // d first answers after round 1's heartbeat, so it is healthy from round 2, and has been for 3 rounds
        // at round 5.
        for (int round = 1; round <= 4; round++) {
            round(FULL);
            assertFalse(dIsVoter(), "round " + round + " is before d has been healthy for 3 rounds");
        }
        round(FULL);

        assertTrue(dIsVoter(), "d has stayed healthy for the stabilization period");
    }

    @Test
    void aNonVoterIsPromotedOnlyWithinTheAllowedNumberOfEntriesOfTheLeadersLog() throws Exception {
        start(new RaftConfiguration(List.of(A, B, D_NON_VOTER)), 1, 1);
        commitACommand();
        commitACommand();
        long last = leader.getLastLogIndex();

        for (int round = 0; round < 8; round++) round(last - 2);
        assertFalse(dIsVoter(), "d answers every round, but trails the leader by two entries");

        // The leader judges d at each heartbeat, before d's answer to it: healthy from the second round, and
        // for the one-round stabilization period at the third.
        for (int round = 0; round < 3; round++) round(last - 1);
        assertTrue(dIsVoter(), "d trails by one entry, as allowed, for the one-round stabilization period");
    }

    @Test
    void aNonVoterThatAnswersEveryOtherRoundStaysInContact() throws Exception {
        start(new RaftConfiguration(List.of(A, B, D_NON_VOTER)), 2, 250);
        commitACommand();

        // d answers in rounds 1 and 3, so the leader heard from it two rounds ago at most: healthy from round 2,
        // for 2 rounds at round 4.
        for (int round = 1; round <= 3; round++) round(round % 2 == 1 ? FULL : null);
        assertFalse(dIsVoter(), "round 3 is before d has been healthy for 2 rounds");
        round(null);

        assertTrue(dIsVoter(), "d has stayed in contact for the stabilization period");
    }

    @Test
    void aNonVoterSilentForThreeRoundsStartsItsStabilizationAgain() throws Exception {
        start(new RaftConfiguration(List.of(A, B, D_NON_VOTER)), 2, 250);
        commitACommand();

        // d answers in rounds 1, 4 and 7: each third round finds its last answer three rounds old, and it has
        // never been healthy for the 2 rounds in between.
        for (int round = 1; round <= 9; round++) round(round % 3 == 1 ? FULL : null);

        assertFalse(dIsVoter(), "d is out of contact every third round");
    }

    @Test
    void aNonVoterThatHasNotAnsweredIsNotPromoted() throws Exception {
        start(new RaftConfiguration(List.of(A, B, D_NON_VOTER)), 1, 250);
        commitACommand();

        for (int round = 0; round < 8; round++) round(null);

        assertFalse(dIsVoter(), "the leader has never heard from d in this term");
    }

    @Test
    void aPartialHeartbeatRoundInTheStabilizationDurationRoundsUp() throws Exception {
        startWithStabilizationMillis(new RaftConfiguration(List.of(A, B, D_NON_VOTER)),
                ManualRaftClusterFixture.HEARTBEAT_MS + 1, 250);
        commitACommand();

        // d first answers after round 1 and is healthy from round 2. A duration just over one
        // heartbeat needs two complete rounds of health, so promotion must wait until round 4.
        for (int heartbeat = 1; heartbeat <= 3; heartbeat++) {
            round(FULL);
            assertFalse(dIsVoter(), "the partial round must not shorten stabilization: round " + heartbeat);
        }
        round(FULL);

        assertTrue(dIsVoter(), "the duration rounds up to two complete heartbeat rounds");
    }

    @Test
    void theLargestStabilizationDurationDoesNotOverflowIntoImmediatePromotion() throws Exception {
        startWithStabilizationMillis(new RaftConfiguration(List.of(A, B, D_NON_VOTER)),
                Long.MAX_VALUE, 250);
        commitACommand();

        for (int heartbeat = 1; heartbeat <= 8; heartbeat++) {
            round(FULL);
            assertFalse(dIsVoter(), "the representable duration has not elapsed: round " + heartbeat);
        }
    }

    @Test
    void theLargestElectionTimeoutDoesNotOverflowIntoImmediateQuorumLoss() throws Exception {
        startWithTiming(new RaftConfiguration(List.of(A, B, D_NON_VOTER)),
                10_000, 250, Long.MAX_VALUE);
        commitACommand();

        // Withhold all further peer replies. Eight applied rounds cannot exhaust this grace.
        for (int heartbeat = 1; heartbeat <= 8; heartbeat++) {
            cluster.timers(leader).firePeriodic(ManualRaftClusterFixture.HEARTBEAT_MS);
            barrier();
            assertTrue(leader.isLeader(), "the quorum grace has not elapsed: round " + heartbeat);
        }
    }

    @Test
    void electionJitterCannotWrapTheLargestTimeoutIntoANegativeScheduledDelay() throws Exception {
        leader = cluster.add(cluster.unconfiguredBuilder("a", Set.of("a", "b"), transport,
                        new QraftStateStore(), RaftNodeMode.volatileMode())
                .serverId(serverIdOf("a")).initialConfiguration(new RaftConfiguration(List.of(A, B)))
                .electionTimeout(Long.MAX_VALUE));
        await(leader.start());

        assertEquals(Long.MAX_VALUE, cluster.timers(leader).nextOneShotDelayMs(),
                "the scheduler must receive the representable timeout without overflowing jitter");
    }

    @Test
    void theMembershipPolicyControlsPromotionLagAndStabilization() throws Exception {
        RaftMembershipPolicy policy = new RaftMembershipPolicy(
                ManualRaftClusterFixture.HEARTBEAT_MS + 1, 1, false,
                ManualRaftClusterFixture.ELECTION_TIMEOUT_MS, ManualRaftClusterFixture.HEARTBEAT_MS, 3);
        start(cluster.unconfiguredBuilder("a", Set.of("a", "b", "d"), transport,
                        new QraftStateStore(), RaftNodeMode.volatileMode())
                .serverId(serverIdOf("a")).initialConfiguration(new RaftConfiguration(List.of(A, B, D_NON_VOTER)))
                .membershipPolicy(policy));
        assertEquals(policy, leader.membershipPolicy());
        commitACommand();
        commitACommand();
        long last = leader.getLastLogIndex();
        for (int heartbeat = 0; heartbeat < 4; heartbeat++) round(last - 2);
        assertFalse(dIsVoter(), "the configured one-entry lag prevents promotion");
        for (int heartbeat = 0; heartbeat < 3; heartbeat++) {
            round(last - 1);
            assertFalse(dIsVoter(), "the configured partial duration requires two complete healthy rounds");
        }
        round(last - 1);
        assertTrue(dIsVoter(), "the configured lag and stabilization now permit promotion");
    }

    @Test
    void builderDefaultsFollowFinalTimingsAndPromotionSettersKeepTheirOrder() {
        RaftNode.Builder builder = cluster.unconfiguredBuilder("a", Set.of("a"), transport,
                new QraftStateStore(), RaftNodeMode.volatileMode());
        RaftNode defaults = cluster.add(builder.electionTimeout(90_000).heartbeatInterval(20_000));
        assertEquals(RaftMembershipPolicy.defaults(90_000, 20_000), defaults.membershipPolicy());

        RaftMembershipPolicy policy = new RaftMembershipPolicy(201, 1, false, 90_000, 20_000, 7);
        RaftNode overridden = cluster.add(builder.membershipPolicy(policy)
                .promotionStabilization(0).promotionMaxTrailingEntries(0));
        assertEquals(new RaftMembershipPolicy(0, 0, false, 90_000, 20_000, 7), overridden.membershipPolicy());
        RaftNode reset = cluster.add(builder.membershipPolicy(policy));
        assertEquals(policy, reset.membershipPolicy());
        assertThrows(IllegalArgumentException.class, () -> builder.electionTimeout(90_001).build(),
                "policy must be checked against timings set after it");
    }

    /** Starts {@code a} leading {@code configuration}, promoting after the given rounds and log lag. */
    private void start(RaftConfiguration configuration, int stabilizationRounds, long maxTrailingEntries)
            throws Exception {
        startWithStabilizationMillis(configuration,
                stabilizationRounds * ManualRaftClusterFixture.HEARTBEAT_MS, maxTrailingEntries);
    }

    /** Starts the leader with millisecond values, including durations that cannot be safely added. */
    private void startWithStabilizationMillis(RaftConfiguration configuration, long stabilizationMs,
                                              long maxTrailingEntries) throws Exception {
        startWithTiming(configuration, stabilizationMs, maxTrailingEntries,
                ManualRaftClusterFixture.ELECTION_TIMEOUT_MS);
    }

    /** Starts the leader with explicit stabilization and election durations. */
    private void startWithTiming(RaftConfiguration configuration, long stabilizationMs,
                                 long maxTrailingEntries, long electionTimeoutMs) throws Exception {
        start(cluster.unconfiguredBuilder("a", Set.of("a", "b", "d"), transport,
                        new QraftStateStore(), RaftNodeMode.volatileMode())
                .serverId(serverIdOf("a")).initialConfiguration(configuration)
                .electionTimeout(electionTimeoutMs)
                .promotionStabilization(stabilizationMs)
                .promotionMaxTrailingEntries(maxTrailingEntries));
    }

    /** Starts and elects the node built with the requested local policy and timings. */
    private void start(RaftNode.Builder builder) throws Exception {
        leader = cluster.add(builder);
        await(leader.start());
        cluster.timers(leader).fireElectionTimeout();
        awaitTrue(() -> !transport.votes.isEmpty(), "a asks b for its vote");
        transport.vote("b").complete(VoteResponse.newBuilder()
                .setTerm(1).setVoteGranted(true).setVoterServerId(serverIdOf("b")).build());
        awaitTrue(() -> leader.getState() == RaftNode.State.LEADER, "a leads");
    }

    /** Commits a command with b's acknowledgement, so the leader has committed an entry in its term. */
    private void commitACommand() throws Exception {
        // Leadership is published before the queued no-op append finishes. Drain that transition
        // before choosing the command's index, otherwise this helper can acknowledge the no-op.
        barrier();
        long index = leader.getLastLogIndex() + 1;
        CompletableFuture<?> write = leader.submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put("k", "v")));
        awaitTrue(() -> transport.appendCarrying(index, "b") != null, "the entry is sent to b");
        Held<AppendEntriesRequest, AppendEntriesResponse> held = transport.appends.stream()
                .filter(candidate -> candidate.response() == transport.appendCarrying(index, "b"))
                .findFirst().orElseThrow();
        held.response().complete(ack(held.request(), FULL, "b"));
        await(write);
    }

    /**
     * One heartbeat round: fires the leader's heartbeat, then answers every append b and d have not yet
     * answered. b always holds the whole log. d holds all of it ({@link #FULL}), the given index, or does not
     * answer (null).
     */
    private void round(Long dMatch) throws Exception {
        cluster.timers(leader).firePeriodic(ManualRaftClusterFixture.HEARTBEAT_MS);
        barrier();
        for (Held<AppendEntriesRequest, AppendEntriesResponse> held : transport.unansweredAppends("b")) {
            held.response().complete(ack(held.request(), FULL, "b"));
        }
        if (dMatch != null) {
            for (Held<AppendEntriesRequest, AppendEntriesResponse> held : transport.unansweredAppends("d")) {
                held.response().complete(ack(held.request(), dMatch, "d"));
            }
        }
        barrier();
    }

    private boolean dIsVoter() {
        return leader.getConfiguration().orElseThrow().isVoter(serverIdOf("d"));
    }

    /** A successful reply from {@code peer}, holding the request's entries ({@link #FULL}) or up to {@code match}. */
    private static AppendEntriesResponse ack(AppendEntriesRequest request, long match, String peer) {
        long matched = match == FULL ? request.getPrevLogIndex() + request.getEntriesCount() : match;
        return AppendEntriesResponse.newBuilder().setTerm(request.getTerm()).setSuccess(true)
                .setMatchIndex(matched).setFollowerServerId(serverIdOf(peer)).build();
    }

    /** Returns once every transition the leader had queued has been applied. */
    private void barrier() throws Exception {
        await(leader.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(0).setCandidateId("probe").setCandidateServerId("probe").build()));
    }

    /** Bounds a wait for the state loop; the bound only diagnoses a hang. */
    private static void awaitTrue(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), description);
    }
}

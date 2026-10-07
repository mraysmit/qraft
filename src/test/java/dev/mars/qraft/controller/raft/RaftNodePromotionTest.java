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

import dev.mars.qraft.controller.raft.HeldRaftTransportFixture.Held;
import dev.mars.qraft.controller.raft.RaftConfiguration.Server;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static dev.mars.qraft.controller.raft.ManualRaftClusterFixture.await;
import static dev.mars.qraft.controller.raft.ManualRaftClusterFixture.serverIdOf;
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

    private JavaRuntime runtime;
    private ManualRaftClusterFixture cluster;
    private HeldRaftTransportFixture transport;
    private RaftNode leader;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        cluster = new ManualRaftClusterFixture(runtime);
        transport = new HeldRaftTransportFixture();
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

    /** Starts {@code a} leading {@code configuration}, promoting after the given rounds and log lag. */
    private void start(RaftConfiguration configuration, int stabilizationRounds, long maxTrailingEntries)
            throws Exception {
        leader = cluster.add(cluster.unconfiguredBuilder("a", Set.of("a", "b", "d"), transport,
                        new QraftStateStore(), RaftNodeMode.volatileMode())
                .serverId(serverIdOf("a")).initialConfiguration(configuration)
                .promotionStabilization(stabilizationRounds * ManualRaftClusterFixture.HEARTBEAT_MS)
                .promotionMaxTrailingEntries(maxTrailingEntries));
        await(leader.start());
        cluster.timers(leader).fireElectionTimeout();
        awaitTrue(() -> !transport.votes.isEmpty(), "a asks b for its vote");
        transport.vote("b").complete(VoteResponse.newBuilder()
                .setTerm(1).setVoteGranted(true).setVoterServerId(serverIdOf("b")).build());
        awaitTrue(() -> leader.getState() == RaftNode.State.LEADER, "a leads");
    }

    /** Commits a command with b's acknowledgement, so the leader has committed an entry in its term. */
    private void commitACommand() throws Exception {
        long index = leader.getLastLogIndex() + 1;
        Future<?> write = leader.submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put("k", "v")));
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

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
import dev.mars.qraft.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.raft.storage.RaftStorageFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.nio.file.Path;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.HEARTBEAT_MS;
import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static dev.mars.qraft.raft.ManualRaftClusterFixture.configurationOf;
import static dev.mars.qraft.raft.ManualRaftClusterFixture.serverIdOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests leader-only failed-voter cleanup on manual heartbeat rounds and held transport replies.
 * Replies, configuration commitments and leadership changes are controlled by the test thread.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-21
 * @version 1.0
 */
class RaftNodeCleanupTest {
    @TempDir
    Path walDirectory;
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
    void defaultCleanupRemovesANeverAnsweredFailedVoterAtTheExactGraceBoundary() throws Exception {
        Set<String> healthy = Set.of("b", "c");
        start(configurationOf(Set.of("a", "b", "c", "d")), ignored -> {}, healthy);
        for (int round = 1; round < 300; round++) round(healthy);
        assertTrue(member("d"), "the default grace is 60000 ms, or 300 applied rounds");
        round(healthy);
        assertFalse(member("d"), "a failed fourth voter is removed at the grace and interval boundary");
        assertTrue(leader.getCommitIndex() == leader.getLastLogIndex(), "removal commits with surviving voters");
    }

    @Test
    void durationsRoundUpAndCleanupWaitsForItsOwnInterval() throws Exception {
        Set<String> healthy = Set.of("b", "c");
        start(configurationOf(Set.of("a", "b", "c", "d")), policy(true, 1001, 601, 3), healthy);
        rounds(7, healthy);
        assertTrue(member("d"), "six-round failure grace waits until the next four-round cleanup interval");
        round(healthy);
        assertFalse(member("d"));
    }

    @Test
    void aValidNegativeReplyRestartsTheContinuousFailureGrace() throws Exception {
        Set<String> healthy = Set.of("b", "c");
        start(configurationOf(Set.of("a", "b", "c", "d")), policy(true, 1000, 200, 3), healthy);
        rounds(4, healthy);
        var reply = transport.unansweredAppends("d").getLast();
        reply.response().complete(AppendEntriesResponse.newBuilder().setTerm(leader.getCurrentTerm())
                .setSuccess(false).setFollowerServerId(serverIdOf("d")).build());
        barrier();
        rounds(4, healthy);
        assertTrue(member("d"), "the valid reply at round four restarts the five-round grace");
        round(healthy);
        assertFalse(member("d"));
    }

    @Test
    void aReplyFromAnotherStorageIdentityDoesNotRestartTheGrace() throws Exception {
        Set<String> healthy = Set.of("b", "c");
        start(configurationOf(Set.of("a", "b", "c", "d")), policy(true, 1000, 200, 3), healthy);
        rounds(4, healthy);
        var reply = transport.unansweredAppends("d").getLast();
        reply.response().complete(AppendEntriesResponse.newBuilder().setTerm(leader.getCurrentTerm())
                .setSuccess(true).setFollowerServerId("wiped-d").build());
        barrier();
        round(healthy);
        assertFalse(member("d"), "a different server ID is not contact with the configured voter");
    }

    @Test
    void disabledCleanupPreservesVotersAndStillAllowsOperatorRemoval() throws Exception {
        Set<String> healthy = Set.of("b", "c");
        start(configurationOf(Set.of("a", "b", "c", "d")), policy(false, 1000, 200, 3), healthy);
        rounds(12, healthy);
        assertTrue(member("d"));
        CompletableFuture<Void> removal = leader.removeServer(serverIdOf("d"));
        barrier();
        answer(healthy);
        await(removal);
        assertFalse(member("d"));
    }

    @Test
    void theMinimumPreservesAThreeVoterClusterAndAJoiningCollisionCannotEvictItsMember() throws Exception {
        Set<String> healthy = Set.of("b");
        start(configurationOf(Set.of("a", "b", "c")), policy(true, 1000, 200, 3), healthy);
        rounds(8, healthy);
        assertTrue(member("c"));
        ExecutionException refused = assertThrows(ExecutionException.class, () -> await(leader.admit(
                new RaftConfiguration.Server("wiped-c", "c", "c", false))));
        assertTrue(refused.getCause().getMessage().contains("operator must remove"));
        assertEquals(3, leader.getConfiguration().orElseThrow().voterIds().size());
    }

    @Test
    void cleanupNeverRemovesNonVotersOrTheLeader() throws Exception {
        List<RaftConfiguration.Server> servers = new ArrayList<>(configurationOf(Set.of("a", "b", "c", "d")).servers());
        servers.add(new RaftConfiguration.Server(serverIdOf("e"), "e", "e", false));
        Set<String> healthy = Set.of("b", "c");
        start(new RaftConfiguration(servers), policy(true, 1000, 200, 3), healthy);
        rounds(7, healthy);
        assertFalse(member("d"));
        assertTrue(member("e"), "an unanswered non-voter stays available for catch-up");
        assertTrue(member("a"));
        assertTrue(leader.isLeader());
    }

    @Test
    void aPassFreezesItsBudgetAndCommitsOneRemovalBeforeProposingTheNext() throws Exception {
        Set<String> healthy = Set.of("b", "c");
        start(configurationOf(Set.of("a", "b", "c", "d", "e")), policy(true, 1000, 2000, 3), healthy);
        rounds(9, healthy);
        cluster.timers(leader).firePeriodic(HEARTBEAT_MS);
        barrier();
        barrier();
        String first = serverIdOf("d").compareTo(serverIdOf("e")) < 0 ? "d" : "e";
        String second = first.equals("d") ? "e" : "d";
        assertFalse(member(first), "eligible server IDs are visited in deterministic order");
        assertTrue(member(second), "only one configuration change is proposed before commitment");
        assertTrue(leader.getCommitIndex() < leader.getLastLogIndex());
        answer(healthy);
        assertFalse(member(second), "the five-voter pass keeps its budget of two after the first removal");
        assertEquals(3, leader.getConfiguration().orElseThrow().voterIds().size());
        assertEquals(leader.getLastLogIndex(), leader.getCommitIndex());
    }

    @Test
    void aRecoveredCandidateIsSkippedBeforeTheNextRemoval() throws Exception {
        Set<String> healthy = Set.of("b", "c");
        start(configurationOf(Set.of("a", "b", "c", "d", "e")), policy(true, 1000, 2000, 3), healthy);
        rounds(9, healthy);
        cluster.timers(leader).firePeriodic(HEARTBEAT_MS);
        barrier();
        barrier();
        String returning = member("d") ? "d" : "e";
        answer(Set.of(returning)); // Contact resumes while the first removal is still waiting for b and c.
        answer(healthy);
        assertTrue(member(returning));
        assertEquals(4, leader.getConfiguration().orElseThrow().voterIds().size());
    }

    @Test
    void aPendingOperatorChangeDefersCleanup() throws Exception {
        Set<String> healthy = Set.of("b", "c");
        start(configurationOf(Set.of("a", "b", "c", "d")), policy(true, 1000, 200, 3), healthy);
        rounds(4, healthy);
        CompletableFuture<JoinResult> join = leader.admit(new RaftConfiguration.Server(serverIdOf("e"), "e", "e", false));
        barrier();
        long pendingIndex = leader.getLastLogIndex();
        cluster.timers(leader).firePeriodic(HEARTBEAT_MS);
        barrier();
        assertTrue(member("d"));
        assertEquals(pendingIndex, leader.getLastLogIndex(), "cleanup cannot overlap an uncommitted join");
        answer(healthy);
        await(join);
        round(healthy);
        assertFalse(member("d"));
    }

    @Test
    void aNewLeadershipGetsAFreshGraceAndIgnoresOldGenerationReplies() throws Exception {
        Set<String> healthy = Set.of("b", "c");
        start(configurationOf(Set.of("a", "b", "c", "d")), policy(true, 1000, 200, 3), healthy);
        rounds(4, healthy);
        var old = transport.unansweredAppends("d").getLast();
        long higherTerm = leader.getCurrentTerm() + 1;
        await(leader.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder().setTerm(higherTerm)
                .setLeaderId("b").setLeaderServerId(serverIdOf("b")).setTargetServerId(serverIdOf("a"))
                .setPrevLogIndex(leader.getLastLogIndex()).setPrevLogTerm(leader.getLastLogTerm())
                .setLeaderCommit(leader.getCommitIndex()).build()));
        assertFalse(leader.isLeader());
        elect(healthy);
        rounds(4, healthy);
        assertTrue(member("d"), "the previous leadership's absence does not shorten this grace");
        old.response().complete(AppendEntriesResponse.newBuilder().setTerm(old.request().getTerm())
                .setSuccess(true).setFollowerServerId(serverIdOf("d")).build());
        barrier();
        round(healthy);
        assertFalse(member("d"), "a reply to the former leadership does not reset this leadership's grace");
    }

    @Test
    void losingQuorumStopsCleanupInsteadOfForcingARemoval() throws Exception {
        Set<String> healthy = Set.of("b", "c");
        start(configurationOf(Set.of("a", "b", "c", "d")), policy(true, 1200, 200, 1), healthy);
        rounds(5, Set.of());
        cluster.timers(leader).firePeriodic(HEARTBEAT_MS);
        barrier();
        assertFalse(leader.isLeader());
        assertEquals(4, leader.getConfiguration().orElseThrow().voterIds().size());
    }

    @Test
    void aCurrentQuorumDoesNotExcuseAMissingProposedQuorumAtTheGraceBoundary() throws Exception {
        List<String> ordered = List.of("b", "c", "d", "e").stream()
                .sorted(java.util.Comparator.comparing(ManualRaftClusterFixture::serverIdOf)).toList();
        String oldest = ordered.getFirst();
        String reachable = ordered.getLast();
        start(configurationOf(Set.of("a", "b", "c", "d", "e")), policy(true, 1000, 200, 1),
                Set.of(oldest, reachable));
        rounds(5, Set.of(reachable));
        assertTrue(leader.isLeader(), "self and the two contacts still form the current five-voter quorum");
        assertEquals(5, leader.getConfiguration().orElseThrow().voterIds().size(),
                "removing the first eligible contact would leave two reachable voters out of four, below quorum");
        assertEquals(leader.getLastLogIndex(), leader.getCommitIndex());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theLargestCleanupDurationsDoNotOverflowIntoImmediateRemoval(boolean largeFailureTimeout) throws Exception {
        Set<String> healthy = Set.of("b", "c");
        start(configurationOf(Set.of("a", "b", "c", "d")),
                policy(true, largeFailureTimeout ? Long.MAX_VALUE : 1000,
                        largeFailureTimeout ? 200 : Long.MAX_VALUE, 3), healthy);
        rounds(8, healthy);
        assertTrue(member("d"), "both large duration conversions must preserve the configured waiting period");
    }

    @Test
    void elapsedWallTimeDoesNotInventFailureRounds() throws Exception {
        Set<String> healthy = Set.of("b", "c");
        start(configurationOf(Set.of("a", "b", "c", "d")), policy(true, 1000, 200, 3), healthy);
        cluster.timers(leader).advanceTime(600_000);
        round(healthy);
        assertTrue(member("d"), "one applied heartbeat is one absence round even after a delayed loop");
    }

    @Test
    void aCommittedPromotionExpeditesCleanupBeforeThePeriodicInterval() throws Exception {
        start(configurationOf(Set.of("a", "b", "c")), policy(true, 1000, 2000, 3)
                .andThen(builder -> builder.promotionStabilization(0)), Set.of("b"));
        rounds(5, Set.of("b"));
        assertTrue(member("c"), "cleanup has not reached its ten-round periodic interval");
        CompletableFuture<JoinResult> join = leader.admit(
                new RaftConfiguration.Server(serverIdOf("d"), "d", "d", false));
        barrier();
        answer(Set.of("b", "d"));
        await(join);
        assertFalse(leader.getConfiguration().orElseThrow().isVoter(serverIdOf("d")));
        round(Set.of("b", "d"));
        assertTrue(leader.getConfiguration().orElseThrow().isVoter(serverIdOf("d")));
        assertFalse(member("c"), "committed promotion allows cleanup at round six instead of round ten");
        assertEquals(leader.getLastLogIndex(), leader.getCommitIndex());
    }

    @Test
    void aPromotionCannotBypassTheFailureGraceOrMinimumVoters() throws Exception {
        start(configurationOf(Set.of("a", "b", "c")), policy(true, 2000, 4000, 5)
                .andThen(builder -> builder.promotionStabilization(0)), Set.of("b"));
        CompletableFuture<JoinResult> join = leader.admit(
                new RaftConfiguration.Server(serverIdOf("d"), "d", "d", false));
        barrier();
        answer(Set.of("b", "d"));
        await(join);
        round(Set.of("b", "d"));
        assertTrue(leader.getConfiguration().orElseThrow().isVoter(serverIdOf("d")));
        assertTrue(member("c"), "a new promotion does not shorten the ten-round failure grace");
        rounds(22, Set.of("b", "d"));
        assertTrue(member("c"), "a periodic pass still respects a minimum above the current voter count");
    }

    @Test
    void anUnrelatedConfigurationChangeAbandonsTheRemainderOfThePass() throws Exception {
        Set<String> healthy = Set.of("b", "c");
        start(configurationOf(Set.of("a", "b", "c", "d", "e")), policy(true, 1000, 2000, 3), healthy);
        rounds(9, healthy);
        cluster.timers(leader).firePeriodic(HEARTBEAT_MS);
        barrier();
        String stillFailed = member("d") ? "d" : "e";
        assertEquals(4, leader.getConfiguration().orElseThrow().voterIds().size());
        long removalIndex = leader.getLastLogIndex();
        var bReply = transport.appendCarrying(removalIndex, "b");
        var cReply = transport.appendCarrying(removalIndex, "c");
        bReply.complete(AppendEntriesResponse.newBuilder().setTerm(leader.getCurrentTerm()).setSuccess(true)
                .setFollowerServerId(serverIdOf("b")).setMatchIndex(removalIndex).build());
        barrier();
        List<RaftConfiguration.Server> servers = new ArrayList<>(leader.getConfiguration().orElseThrow().servers());
        servers.add(new RaftConfiguration.Server(serverIdOf("f"), "f", "f", false));
        RaftConfiguration unrelated = new RaftConfiguration(servers);
        CompletableFuture<CompletableFuture<Void>> submitted = new CompletableFuture<>();
        runtime.runOnContext(ignored -> {
            try {
                // Queue the final acknowledgement first, then the operator change. The commit callback's
                // cleanup continuation is queued behind that operator change on the same state loop.
                cReply.complete(AppendEntriesResponse.newBuilder().setTerm(leader.getCurrentTerm()).setSuccess(true)
                        .setFollowerServerId(serverIdOf("c")).setMatchIndex(removalIndex).build());
                submitted.complete(leader.proposeConfiguration(unrelated));
            } catch (Throwable failure) {
                submitted.completeExceptionally(failure);
            }
        });
        CompletableFuture<Void> changed = await(submitted);
        barrier();
        answer(healthy);
        await(changed);
        assertTrue(member(stillFailed), "the old pass must not continue after the unrelated configuration");
        rounds(9, healthy);
        assertTrue(member(stillFailed));
        round(healthy);
        assertFalse(member(stillFailed), "the next interval may start a fresh pass from committed membership");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aRealWalReopensTheReplicatedConfigurationInsteadOfPersistingAFailureMarker(boolean allowRemoval)
            throws Exception {
        var durable = await(RaftStorageFactory.createDurable(walDirectory, true));
        RaftConfiguration initial = configurationOf(Set.of("a", "b", "c", "d"));
        Set<String> healthy = Set.of("b", "c");
        start(initial, policy(true, 1000, 200, allowRemoval ? 3 : 4)
                .andThen(builder -> builder.mode(RaftNodeMode.durable(durable.wal(), durable.snapshots()))
                        .snapshotEnabled(false)), healthy);
        rounds(6, healthy);
        assertEquals(!allowRemoval, member("d"));
        assertEquals(leader.getLastLogIndex(), leader.getCommitIndex());
        RaftConfiguration expected = leader.getConfiguration().orElseThrow();
        await(leader.stop());
        var reopened = await(RaftStorageFactory.createDurable(walDirectory, true));
        transport = new HeldRaftTransportFixture();
        leader = cluster.add(cluster.unconfiguredBuilder("a", Set.of("a", "b", "c", "d"), transport,
                        new QraftStateStore(), RaftNodeMode.durable(reopened.wal(), reopened.snapshots()))
                .serverId(serverIdOf("a")).snapshotEnabled(false));
        await(leader.start());
        assertEquals(expected, leader.getConfiguration().orElseThrow());
        assertEquals(!allowRemoval, member("d"), "only the logged configuration determines voters after reopening");
    }

    @Test
    void aRejectedProposalAbandonsItsPassWithoutChangingMembership() throws Exception {
        Set<String> healthy = Set.of("b", "c");
        start(configurationOf(Set.of("a", "b", "c", "d")), policy(true, 1000, 200, 3)
                .andThen(builder -> builder.logHardLimit(3)), healthy);
        rounds(8, healthy);
        assertTrue(member("d"), "a full log refuses the removal without changing the voter configuration");
        assertEquals(2, leader.getLastLogIndex());
        assertFalse(leader.isFenced(), "an explicit append refusal has no uncertain persistence outcome");
    }

    @Test
    void anAmbiguousCleanupSyncFailureFencesTheNodeAndAbandonsThePass() throws Exception {
        TestRaftStorageFixture storage = new TestRaftStorageFixture();
        await(storage.open(walDirectory));
        start(configurationOf(Set.of("a", "b", "c", "d")), policy(true, 1000, 200, 3)
                .andThen(builder -> builder.mode(RaftNodeMode.durable(storage, storage)).snapshotEnabled(false)),
                Set.of("b", "c"));
        rounds(4, Set.of("b", "c"));
        storage.setFailOnSync(true); // The fixture's injected WAL failure names this test in every logged trace.
        cluster.timers(leader).firePeriodic(HEARTBEAT_MS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!leader.isFenced() && System.nanoTime() < deadline) Thread.sleep(1);
        assertTrue(leader.isFenced());
        assertTrue(member("d"), "the uncertain append must not be applied as a successful removal");
    }

    private static Consumer<RaftNode.Builder> policy(boolean enabled, long failureMs, long intervalMs, int minVoters) {
        return builder -> builder.electionTimeout(1000).membershipPolicy(
                new RaftMembershipPolicy(10_000, 250, enabled, failureMs, intervalMs, minVoters));
    }

    private void rounds(int count, Set<String> healthy) throws Exception {
        for (int round = 0; round < count; round++) round(healthy);
    }

    private void start(RaftConfiguration configuration, Consumer<RaftNode.Builder> customize, Set<String> voters)
            throws Exception {
        RaftNode.Builder builder = cluster.unconfiguredBuilder("a",
                        configuration.servers().stream().map(RaftConfiguration.Server::name)
                                .collect(java.util.stream.Collectors.toSet()),
                        transport, new QraftStateStore(), RaftNodeMode.volatileMode())
                .serverId(serverIdOf("a")).initialConfiguration(configuration);
        customize.accept(builder);
        leader = cluster.add(builder);
        await(leader.start());
        elect(voters);
    }

    private void elect(Set<String> voters) throws Exception {
        cluster.timers(leader).fireElectionTimeout();
        barrier();
        for (var vote : transport.votes) {
            if (!vote.answered() && voters.contains(vote.target())) vote.response().complete(VoteResponse.newBuilder()
                    .setTerm(vote.request().getTerm()).setVoteGranted(true)
                    .setVoterServerId(serverIdOf(vote.target())).build());
        }
        barrier();
        assertTrue(leader.isLeader());
        answer(voters);
    }

    private void round(Set<String> healthy) throws Exception {
        cluster.timers(leader).firePeriodic(HEARTBEAT_MS);
        barrier();
        answer(healthy);
    }

    private void answer(Set<String> healthy) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            barrier();
            boolean answered = false;
            for (String name : healthy) {
                for (var held : transport.unansweredAppends(name)) {
                    held.response().complete(AppendEntriesResponse.newBuilder()
                            .setTerm(held.request().getTerm()).setSuccess(true)
                            .setMatchIndex(held.request().getPrevLogIndex() + held.request().getEntriesCount())
                            .setFollowerServerId(serverIdOf(name)).build());
                    answered = true;
                }
            }
            barrier();
            if (!answered) return;
            assertTrue(System.nanoTime() < deadline, "held replies must settle");
        }
    }

    private boolean member(String name) {
        return leader.getConfiguration().orElseThrow().serverNamed(name).isPresent();
    }

    private void barrier() throws Exception {
        // A public membership call first dispatches to the loop, then admits its sequenced append.
        // Two sequenced probes cover both queue boundaries before the test reads its state.
        for (int boundary = 0; boundary < 2; boundary++) {
            await(leader.handleVoteRequest(VoteRequest.newBuilder()
                    .setTerm(0).setCandidateId("probe").setCandidateServerId("probe").build()));
        }
    }
}

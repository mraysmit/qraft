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
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;
import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.ELECTION_TIMEOUT_MS;
import static dev.mars.qraft.raft.ManualRaftClusterFixture.HEARTBEAT_MS;
import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static dev.mars.qraft.raft.ManualRaftClusterFixture.serverIdOf;
import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates leader stickiness with real Raft nodes on manual timers: removed-server campaigns,
 * the exact contact-lease boundary, higher-term stale-log partition healing, and genuine failover.
 * Test transport gates choose message delivery order without changing requests or responses.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-21
 * @version 1.0
 */
class RaftNodeDisruptionTest {
    private static final Set<String> MEMBERS = Set.of("a", "b", "c");

    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private final Map<String, QraftStateStore> stores = Map.of(
            "a", new QraftStateStore(), "b", new QraftStateStore(), "c", new QraftStateStore());
    private final Map<String, GatedTransportFixture> transports = Map.of(
            "a", new GatedTransportFixture("a"), "b", new GatedTransportFixture("b"),
            "c", new GatedTransportFixture("c"));
    private RaftNode a;
    private RaftNode b;
    private RaftNode c;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryTransportSimulatorFixture.clearAllTransports();
        runtime = RaftExecutorsFixture.create();
        cluster = new ManualRaftClusterFixture(runtime);
        a = node("a");
        b = node("b");
        c = node("c");
        ManualRaftClusterFixture.startAll(a, b, c);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            try {
                for (GatedTransportFixture transport : transports.values()) transport.releaseAll();
                if (cluster != null) cluster.settle();
            } finally {
                if (cluster != null) cluster.close();
            }
        } finally {
            if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
        }
    }

    @Test
    void repeatedCampaignsFromARemovedServerLeaveTheLiveClusterInItsTerm() throws Exception {
        leadAndReplicate();
        InMemoryTransportSimulatorFixture.createPartition(Set.of("a", "b"), Set.of("c"));
        // Removal stops replication to c before another RPC is sent. This partition
        // preserves c's old voter configuration; it does not cause a transport error.
        await(a.removeServer(serverIdOf("c")));
        cluster.heartbeatUntil(a, () -> b.getConfiguration().equals(a.getConfiguration()),
                "the retained follower applies the removal");
        assertTrue(c.getConfiguration().orElseThrow().isVoter(serverIdOf("c")),
                "the isolated server still believes it can campaign");
        InMemoryTransportSimulatorFixture.healPartitions();
        transports.get("c").holdVotes = true;
        long term = a.getCurrentTerm();

        for (int attempt = 0; attempt < 20; attempt++) {
            cluster.timers(c).fireElectionTimeout();
            cluster.settle();
            // The shared clock advanced through c's timeout. Deliver the live leader's
            // heartbeat before c's held campaign, so the follower has a current lease.
            heartbeat(a);
            assertCampaignRefused(transports.get("c").releaseVotes(), term);
            assertEquals(term, a.getCurrentTerm());
            assertEquals(term, b.getCurrentTerm());
            assertTrue(a.isLeader());
            assertEquals(RaftNode.State.CANDIDATE, c.getState());
        }

        assertTrue(c.getCurrentTerm() > term);
        await(a.submitCommand(put("after-campaigns", "committed")));
        cluster.heartbeatUntil(a, () -> "committed".equals(stores.get("b").getMetadata("after-campaigns")),
                "the retained voters still replicate writes");
        assertFalse(a.getConfiguration().orElseThrow().isVoter(serverIdOf("c")));
        assertEquals(term, a.getCurrentTerm());
    }

    @Test
    void eachHeartbeatRenewsTheLeaseAndVotingStartsAtItsExactExpiry() throws Exception {
        leadAndReplicate();
        long term = a.getCurrentTerm();
        VoteRequest campaign = campaignFromC(term + 5);
        cluster.timers(b).advanceTime(ELECTION_TIMEOUT_MS - 1);
        assertProtectedVote(b, campaign, term);

        heartbeat(a);
        cluster.timers(b).advanceTime(1);
        assertProtectedVote(b, campaign, term);
        cluster.timers(b).advanceTime(ELECTION_TIMEOUT_MS - 2);
        assertProtectedVote(b, campaign, term);
        cluster.timers(b).advanceTime(1);

        VoteResponse expired = await(b.handleVoteRequest(campaign));
        assertTrue(expired.getVoteGranted(), "the lease ends at the minimum election timeout");
        assertEquals(campaign.getTerm(), expired.getTerm());
        assertEquals(campaign.getTerm(), b.getCurrentTerm());
        assertNull(b.getLeaderId());
        assertTrue(a.isLeader(), "only b has received the higher-term campaign");
        assertEquals(term, a.getCurrentTerm());
    }

    @Test
    void anExpiredLeaseStillRejectsAHigherTermCandidateWithAStaleLog() throws Exception {
        IntentionalErrorsHelper.expect(RAFT_PEER_UNREACHABLE, "c");
        leadAndReplicate();
        InMemoryTransportSimulatorFixture.createPartition(Set.of("a", "b"), Set.of("c"));
        await(a.submitCommand(put("not-on-c", "committed")));
        heartbeat(a);
        assertTrue(c.getLastLogIndex() < b.getLastLogIndex());
        long term = a.getCurrentTerm();
        VoteRequest staleCampaign = campaignFromC(term + 5);
        cluster.timers(b).advanceTime(ELECTION_TIMEOUT_MS - 1);
        assertProtectedVote(b, staleCampaign, term);
        cluster.timers(b).advanceTime(1);

        VoteResponse expired = await(b.handleVoteRequest(staleCampaign));
        assertFalse(expired.getVoteGranted(), "expiry releases the lease, not the log freshness check");
        assertEquals(staleCampaign.getTerm(), expired.getTerm());
        assertEquals(staleCampaign.getTerm(), b.getCurrentTerm(), "the observed term must still advance");
        assertEquals("committed", stores.get("b").getMetadata("not-on-c"));
    }

    @Test
    void healingAHigherTermStaleCandidateProtectsLiveVotesThenConverges() throws Exception {
        // Both sides send across this partition: c campaigns toward a and b,
        // while a replicates toward c. All three peers are deliberately unreachable.
        IntentionalErrorsHelper.expect(RAFT_PEER_UNREACHABLE);
        leadAndReplicate();
        InMemoryTransportSimulatorFixture.createPartition(Set.of("a", "b"), Set.of("c"));
        await(a.submitCommand(put("during-partition", "preserved")));
        for (int attempt = 0; attempt < 3; attempt++) {
            cluster.timers(c).fireElectionTimeout();
            cluster.settle();
            heartbeat(a);
        }
        assertTrue(c.getCurrentTerm() > a.getCurrentTerm());
        assertTrue(c.getLastLogIndex() < b.getLastLogIndex());
        assertNull(stores.get("c").getMetadata("during-partition"));

        transports.get("a").holdAppendsTo = "c";
        transports.get("c").holdVotes = true;
        InMemoryTransportSimulatorFixture.healPartitions();
        cluster.timers(c).fireElectionTimeout();
        cluster.settle();
        long liveTerm = a.getCurrentTerm();
        long returningTerm = c.getCurrentTerm();
        heartbeat(a);
        assertCampaignRefused(transports.get("c").releaseVotes(), liveTerm);
        assertTrue(a.isLeader());
        assertEquals(liveTerm, a.getCurrentTerm());
        assertEquals(liveTerm, b.getCurrentTerm());

        // Stickiness protects RequestVote. A genuine higher-term AppendEntries response
        // must still be adopted; this is not a pre-vote protocol or a term filter.
        for (CompletableFuture<AppendEntriesResponse> reply : transports.get("a").releaseAppends()) await(reply);
        cluster.settle();
        assertEquals(RaftNode.State.FOLLOWER, a.getState());
        assertEquals(returningTerm, a.getCurrentTerm());
        transports.get("c").holdVotes = false;

        // b first discovers the partition's higher term, then campaigns above it.
        cluster.timers(b).fireElectionTimeout();
        cluster.settle();
        assertEquals(returningTerm, b.getCurrentTerm());
        assertEquals(RaftNode.State.FOLLOWER, b.getState());
        cluster.elect(b);
        assertEquals(returningTerm + 1, b.getCurrentTerm());
        converge(b, "during-partition", "preserved");
        await(b.submitCommand(put("after-healing", "committed")));
        converge(b, "after-healing", "committed");
    }

    @Test
    void anIsolatedLeaderStepsDownAndRealFailoverReplacesItsUncommittedTail() throws Exception {
        // a loses b and c; the surviving majority then campaigns and replicates toward a.
        IntentionalErrorsHelper.expect(RAFT_PEER_UNREACHABLE);
        leadAndReplicate();
        long term = a.getCurrentTerm();
        InMemoryTransportSimulatorFixture.createPartition(Set.of("a"), Set.of("b", "c"));
        CompletableFuture<?> pending = a.submitCommand(put("isolated-tail", "uncommitted"));
        cluster.settle();
        for (int round = 0; round < ELECTION_TIMEOUT_MS / HEARTBEAT_MS; round++) heartbeat(a);
        assertTrue(a.isLeader(), "contact exactly one timeout of rounds old still counts");
        heartbeat(a);
        assertEquals(RaftNode.State.FOLLOWER, a.getState());
        assertEquals(term, a.getCurrentTerm(), "check-quorum step-down keeps the term");
        assertNull(a.getLeaderId());
        assertInstanceOf(CommandOutcomeUnknownException.class,
                assertThrows(ExecutionException.class, () -> await(pending)).getCause());

        cluster.elect(b);
        assertEquals(term + 1, b.getCurrentTerm());
        await(b.submitCommand(put("after-failover", "committed")));
        heartbeat(b);
        InMemoryTransportSimulatorFixture.healPartitions();
        converge(b, "before-disruption", "preserved");
        converge(b, "after-failover", "committed");
        for (QraftStateStore store : stores.values()) assertNull(store.getMetadata("isolated-tail"));
    }

    private RaftNode node(String name) {
        return cluster.add(cluster.builder(name, MEMBERS, transports.get(name), stores.get(name),
                RaftNodeMode.volatileMode()).snapshotEnabled(false));
    }

    private void leadAndReplicate() throws Exception {
        cluster.elect(a);
        await(a.submitCommand(put("before-disruption", "preserved")));
        converge(a, "before-disruption", "preserved");
    }

    private void heartbeat(RaftNode leader) throws Exception {
        cluster.timers(leader).firePeriodic(HEARTBEAT_MS);
        cluster.settle();
    }

    private void converge(RaftNode leader, String key, String value) throws Exception {
        cluster.heartbeatUntil(leader, () -> stores.values().stream()
                        .allMatch(store -> value.equals(store.getMetadata(key)))
                        && List.of(a, b, c).stream().allMatch(node -> node.getCurrentTerm() == leader.getCurrentTerm()
                        && node.getCommitIndex() == leader.getCommitIndex()),
                "all replicas converge on " + key);
        assertEquals(1, List.of(a, b, c).stream().filter(RaftNode::isLeader).count());
    }

    private VoteRequest campaignFromC(long term) {
        return VoteRequest.newBuilder().setCandidateId("c").setCandidateServerId(serverIdOf("c"))
                .setTerm(term).setLastLogTerm(c.getLastLogTerm()).setLastLogIndex(c.getLastLogIndex()).build();
    }

    private void assertProtectedVote(RaftNode voter, VoteRequest campaign, long term) throws Exception {
        VoteResponse reply = await(voter.handleVoteRequest(campaign));
        assertFalse(reply.getVoteGranted());
        assertEquals(term, reply.getTerm());
        assertEquals(term, voter.getCurrentTerm());
        assertEquals("a", voter.getLeaderId());
    }

    private void assertCampaignRefused(List<CompletableFuture<VoteResponse>> replies, long term) throws Exception {
        assertEquals(2, replies.size(), "the real candidate asks both other configured voters");
        for (CompletableFuture<VoteResponse> reply : replies) {
            VoteResponse response = await(reply);
            assertFalse(response.getVoteGranted());
            assertEquals(term, response.getTerm());
        }
        cluster.settle();
    }

    private static DistributedStateRaftCommand put(String key, String value) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, value));
    }

    /**
     * Test transport fixture that holds selected sends until the test releases them to the existing
     * simulator. All released messages retain their original payloads, real peer handling and replies.
     */
    private static final class GatedTransportFixture extends InMemoryTransportSimulatorFixture {
        private volatile boolean holdVotes;
        private volatile String holdAppendsTo;
        private final List<HeldVoteHelper> votes = new ArrayList<>();
        private final List<HeldAppendHelper> appends = new ArrayList<>();

        GatedTransportFixture(String name) { super(name); }

        @Override
        public synchronized CompletableFuture<VoteResponse> sendVoteRequest(String target, VoteRequest request) {
            if (!holdVotes) return super.sendVoteRequest(target, request);
            CompletableFuture<VoteResponse> reply = new CompletableFuture<>();
            votes.add(new HeldVoteHelper(target, request, reply));
            return reply;
        }

        @Override
        public synchronized CompletableFuture<AppendEntriesResponse> sendAppendEntries(
                String target, AppendEntriesRequest request) {
            if (!target.equals(holdAppendsTo)) return super.sendAppendEntries(target, request);
            CompletableFuture<AppendEntriesResponse> reply = new CompletableFuture<>();
            appends.add(new HeldAppendHelper(target, request, reply));
            return reply;
        }

        synchronized List<CompletableFuture<VoteResponse>> releaseVotes() {
            List<HeldVoteHelper> held = List.copyOf(votes);
            votes.clear();
            for (HeldVoteHelper vote : held) {
                super.sendVoteRequest(vote.target(), vote.request()).whenComplete((response, failure) -> {
                    if (failure == null) vote.reply().complete(response);
                    else vote.reply().completeExceptionally(failure);
                });
            }
            return held.stream().map(HeldVoteHelper::reply).toList();
        }

        synchronized List<CompletableFuture<AppendEntriesResponse>> releaseAppends() {
            holdAppendsTo = null;
            List<HeldAppendHelper> held = List.copyOf(appends);
            appends.clear();
            for (HeldAppendHelper append : held) {
                super.sendAppendEntries(append.target(), append.request()).whenComplete((response, failure) -> {
                    if (failure == null) append.reply().complete(response);
                    else append.reply().completeExceptionally(failure);
                });
            }
            return held.stream().map(HeldAppendHelper::reply).toList();
        }

        void releaseAll() {
            holdVotes = false;
            releaseVotes();
            releaseAppends();
        }
    }

    /** Test helper retaining an original vote request and the future returned to its candidate. */
    private record HeldVoteHelper(String target, VoteRequest request, CompletableFuture<VoteResponse> reply) { }

    /** Test helper retaining an original append request and the future returned to its leader. */
    private record HeldAppendHelper(
            String target, AppendEntriesRequest request, CompletableFuture<AppendEntriesResponse> reply) { }
}

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

import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;

import dev.mars.qraft.raft.RaftConfiguration.Server;
import dev.mars.qraft.common.async.JavaRuntime;
import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static dev.mars.qraft.raft.ManualRaftClusterFixture.serverIdOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how a {@link RaftNode} leader admits a joining server and removes a server:
 * <ul>
 *   <li>a joining server is added as a non-voter, and a server already configured is left as it is;</li>
 *   <li>a server rejoining under a new server ID needs operator removal of a colliding old entry;</li>
 *   <li>a removal is refused when the voters left, among those the leader has heard from, are not a quorum;</li>
 *   <li>a leader that removes itself steps down once the change commits, and does not campaign again.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
class RaftNodeMembershipTest {
    private static final Set<String> MEMBERS = Set.of("a", "b", "c");
    private static final Server D = new Server(serverIdOf("d"), "d", "d", false);

    private JavaRuntime runtime;
    private ManualRaftClusterFixture cluster;
    private RaftNode a;
    private RaftNode b;
    private RaftNode c;

    @BeforeEach
    void setUp() throws Exception {
        runtime = JavaRuntime.create();
        cluster = new ManualRaftClusterFixture(runtime);
        InMemoryTransportSimulatorFixture.clearAllTransports();
        a = node("a");
        b = node("b");
        c = node("c");
        ManualRaftClusterFixture.startAll(a, b, c);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            cluster.close();
        } finally {
            runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
        }
    }

    @Test
    void aJoiningServerIsAddedAsANonVoter() throws Exception {
        IntentionalErrorsHelper.expect(dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE);
        leadWithACommit();

        assertEquals(JoinResult.JOINED, await(a.admit(D)));

        assertEquals(Optional.of(with(D)), a.getConfiguration());
    }

    @Test
    void aServerAlreadyConfiguredIsLeftAsItIs() throws Exception {
        leadWithACommit();

        assertEquals(JoinResult.ALREADY_MEMBER, await(a.admit(new Server(serverIdOf("b"), "b", "b", false))));

        assertEquals(Optional.of(ManualRaftClusterFixture.configurationOf(MEMBERS)), a.getConfiguration());
    }

    @Test
    void aServerRejoiningUnderItsIdAtANewAddressHasItsAddressUpdated() throws Exception {
        leadWithACommit();

        assertEquals(JoinResult.ADDRESS_UPDATED, await(a.admit(new Server(serverIdOf("c"), "c", "c-moved", false))));

        assertEquals(Optional.of(new Server(serverIdOf("c"), "c", "c-moved", true)),
                a.getConfiguration().orElseThrow().server(serverIdOf("c")), "c keeps its vote at its new address");
    }

    @Test
    void anAddressUpdateIsRefusedWhenItWouldTakeAnotherServersAddressOrRenameTheServer() throws Exception {
        leadWithACommit();

        assertRefused(IllegalArgumentException.class, "belongs to",
                () -> await(a.admit(new Server(serverIdOf("c"), "c", "b", false))));
        assertRefused(IllegalArgumentException.class, "configured as c",
                () -> await(a.admit(new Server(serverIdOf("c"), "renamed", "c", false))));
        assertEquals(Optional.of(ManualRaftClusterFixture.configurationOf(MEMBERS)), a.getConfiguration());
    }

    @Test
    void aServerRejoiningUnderANewIdNeedsOperatorRemovalBeforeJoining() throws Exception {
        leadWithACommit();
        Server wipedC = new Server("new-id-of-c", "c", "c", false);

        assertRefused(IllegalArgumentException.class, "operator must remove", () -> await(a.admit(wipedC)));
        assertEquals(Optional.of(ManualRaftClusterFixture.configurationOf(MEMBERS)), a.getConfiguration());
        await(a.removeServer(serverIdOf("c")));
        assertEquals(Optional.of(without("c")), a.getConfiguration());

        assertEquals(JoinResult.JOINED, await(a.admit(wipedC)), "asking again adds the new server");
        assertEquals(Optional.of(new RaftConfiguration(List.of(server("a"), server("b"), wipedC))),
                a.getConfiguration());
    }

    @Test
    void aServerAtAnotherServersAddressCannotReplaceIt() throws Exception {
        leadWithACommit();

        assertRefused(IllegalArgumentException.class, "operator must remove",
                () -> await(a.admit(new Server("new-id", "renamed", "c", false))));

        assertEquals(Optional.of(ManualRaftClusterFixture.configurationOf(MEMBERS)), a.getConfiguration());
    }

    @Test
    void anIdleLeaderCanJoinAndRemoveWithoutAClientWrite() throws Exception {
        IntentionalErrorsHelper.expect(dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE);
        cluster.elect(a);
        cluster.heartbeatUntil(a, () -> a.getCommitIndex() >= 2, "leadership no-op commits");
        assertEquals(2, a.getLastLogIndex());
        assertEquals(JoinResult.JOINED, await(a.admit(D)));
        await(a.removeServer(serverIdOf("d")));
    }

    @Test
    void anIdleClusterPromotesAJoiningServerWithoutAClientWrite() throws Exception {
        RaftNode d = cluster.add(cluster.unconfiguredBuilder("d", Set.of("a", "d"),
                new InMemoryTransportSimulatorFixture("d"), new QraftStateStore(), RaftNodeMode.volatileMode())
                .serverId(serverIdOf("d")));
        await(d.start());
        cluster.elect(a);
        cluster.heartbeatUntil(a, () -> a.getCommitIndex() >= 2, "leadership no-op commits");
        assertEquals(JoinResult.JOINED, await(a.admit(D)));
        cluster.heartbeatUntil(a, () -> a.getConfiguration().orElseThrow().isVoter(serverIdOf("d"))
                && a.getCommitIndex() == a.getLastLogIndex(), "idle cluster promotes d after stabilization");
    }

    @Test
    void aFollowerAcceptsAVoteOnlyAfterLeaderContactExpires() throws Exception {
        leadWithACommit();
        cluster.heartbeatUntil(a, () -> "a".equals(b.getLeaderId()), "b knows the leader");
        var request = dev.mars.qraft.raft.grpc.VoteRequest.newBuilder()
                .setCandidateId("c").setCandidateServerId(serverIdOf("c"))
                .setTerm(a.getCurrentTerm() + 1).setLastLogTerm(a.getLastLogTerm())
                .setLastLogIndex(a.getLastLogIndex()).build();
        cluster.timers(b).advanceTime(ManualRaftClusterFixture.ELECTION_TIMEOUT_MS - 1);
        assertFalse(await(b.handleVoteRequest(request)).getVoteGranted());
        cluster.timers(b).advanceTime(1);
        assertTrue(await(b.handleVoteRequest(request)).getVoteGranted(), "the minimum timeout releases stickiness");
    }

    @Test
    void aRemovedServerCannotRaiseTheActiveLeadersOrFollowersTerm() throws Exception {
        IntentionalErrorsHelper.expect(dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE);
        InMemoryTransportSimulatorFixture.createPartition(Set.of("a", "b"), Set.of("c"));
        leadWithACommit();
        await(a.removeServer(serverIdOf("c")));
        cluster.heartbeatUntil(a, () -> b.getConfiguration().equals(a.getConfiguration()), "b learns removal");
        long term = a.getCurrentTerm();
        for (int attempt = 1; attempt <= 3; attempt++) {
            var request = dev.mars.qraft.raft.grpc.VoteRequest.newBuilder()
                    .setCandidateId("c").setCandidateServerId(serverIdOf("c"))
                    .setTerm(term + attempt).setLastLogTerm(term).setLastLogIndex(100).build();
            assertFalse(await(a.handleVoteRequest(request)).getVoteGranted());
            assertFalse(await(b.handleVoteRequest(request)).getVoteGranted());
            assertEquals(term, a.getCurrentTerm());
            assertEquals(term, b.getCurrentTerm());
        }
        assertTrue(a.isLeader());
        await(a.submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put("after", "removal"))));
    }

    @Test
    void aJoiningVoterIsRefused() throws Exception {
        leadWithACommit();

        assertRefused(IllegalArgumentException.class, "joins as a non-voter",
                () -> await(a.admit(new Server(serverIdOf("d"), "d", "d", true))));
    }

    @Test
    void aRemovalThatLeavesTooFewReachableVotersIsRefused() throws Exception {
        IntentionalErrorsHelper.expect(dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE);
        // c never answers this leader.
        InMemoryTransportSimulatorFixture.createPartition(Set.of("a", "b"), Set.of("c"));
        leadWithACommit();

        assertRefused(IllegalStateException.class, "quorum",
                () -> await(a.removeServer(serverIdOf("b"))));
        assertEquals(Optional.of(ManualRaftClusterFixture.configurationOf(MEMBERS)), a.getConfiguration());

        await(a.removeServer(serverIdOf("c")));
        assertEquals(Optional.of(without("c")), a.getConfiguration(), "a and b are both of the voters left");
    }

    @Test
    void aNonVoterCanAlwaysBeRemoved() throws Exception {
        IntentionalErrorsHelper.expect(dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE);
        InMemoryTransportSimulatorFixture.createPartition(Set.of("a", "b"), Set.of("c"));
        leadWithACommit();
        await(a.admit(D));
        cluster.heartbeatUntil(a, () -> a.getCommitIndex() == a.getLastLogIndex(), "d's addition commits");

        await(a.removeServer(serverIdOf("d")));

        assertEquals(Optional.of(ManualRaftClusterFixture.configurationOf(MEMBERS)), a.getConfiguration());
    }

    @Test
    void removingAServerThatIsNotConfiguredIsRefused() throws Exception {
        leadWithACommit();

        assertRefused(IllegalArgumentException.class, "not in the configuration",
                () -> await(a.removeServer("unknown")));
    }

    @Test
    void onlyALeaderAdmitsOrRemoves() throws Exception {
        leadWithACommit();

        assertRefused(IllegalStateException.class, "Not the leader", () -> await(b.admit(D)));
        assertRefused(IllegalStateException.class, "Not the leader",
                () -> await(b.admit(new Server(serverIdOf("c"), "c", "c", false))),
                "even for a server already configured: only the leader answers for the membership");
        assertRefused(IllegalStateException.class, "Not the leader",
                () -> await(b.removeServer(serverIdOf("c"))));
    }

    @Test
    void aLeaderThatRemovesItselfStepsDownOnceTheChangeCommits() throws Exception {
        leadWithACommit();

        await(a.removeServer(serverIdOf("a")));

        // The removal completes as it is applied; a steps down straight after, on its state loop.
        await(a.awaitState(RaftNode.State.FOLLOWER, 10_000));
        assertEquals(Optional.of(without("a")), a.getConfiguration());
        assertTrue(await(a.status()).removed(), "a reports that it has been removed");
        assertEquals(ClusterBootstrap.Outcome.CONFIGURED, await(new ClusterBootstrap(a,
                InMemoryTransportSimulatorFixture.getAllTransports().get("a")).attempt()),
                "startup reconciliation must not re-admit an intentionally removed server");
        assertFalse(await(b.status()).removed());
        cluster.elect(b);
        assertFalse(a.isLeader());
        assertTrue(b.getConfiguration().orElseThrow().server(serverIdOf("a")).isEmpty());
    }

    private void leadWithACommit() throws Exception {
        cluster.elect(a);
        await(a.submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put("k", "v"))));
        cluster.heartbeatUntil(a, () -> true, "replication replies settle");
    }

    private RaftNode node(String name) {
        return cluster.add(cluster.builder(name, MEMBERS, new InMemoryTransportSimulatorFixture(name),
                new QraftStateStore(), RaftNodeMode.volatileMode()));
    }

    private static Server server(String name) {
        return new Server(serverIdOf(name), name, name, true);
    }

    private static RaftConfiguration with(Server added) {
        return new RaftConfiguration(List.of(server("a"), server("b"), server("c"), added));
    }

    private static RaftConfiguration without(String name) {
        return new RaftConfiguration(ManualRaftClusterFixture.configurationOf(MEMBERS).servers().stream()
                .filter(server -> !server.name().equals(name)).toList());
    }

    private static void assertRefused(Class<? extends Throwable> type, String reason, Executable change) {
        assertRefused(type, reason, change, reason);
    }

    private static void assertRefused(Class<? extends Throwable> type, String reason, Executable change,
                                      String description) {
        ExecutionException refused = assertThrows(ExecutionException.class, change, description);
        assertInstanceOf(type, refused.getCause());
        assertTrue(refused.getCause().getMessage().contains(reason), refused.getCause().getMessage());
    }
}

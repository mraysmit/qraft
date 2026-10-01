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

import dev.mars.qraft.controller.raft.RaftConfiguration.Server;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.controller.raft.ManualRaftCluster.await;
import static dev.mars.qraft.controller.raft.ManualRaftCluster.serverIdOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how a {@link RaftNode} leader admits a joining server and removes a server:
 * <ul>
 *   <li>a joining server is added as a non-voter, and a server already configured is left as it is;</li>
 *   <li>a server rejoining under a new server ID first has its old entry removed, as Consul's autopilot does;</li>
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
    private ManualRaftCluster cluster;
    private RaftNode a;
    private RaftNode b;
    private RaftNode c;

    @BeforeEach
    void setUp() throws Exception {
        runtime = JavaRuntime.create();
        cluster = new ManualRaftCluster(runtime);
        InMemoryTransportSimulator.clearAllTransports();
        a = node("a");
        b = node("b");
        c = node("c");
        ManualRaftCluster.startAll(a, b, c);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            cluster.close();
        } finally {
            runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulator.clearAllTransports();
        }
    }

    @Test
    void aJoiningServerIsAddedAsANonVoter() throws Exception {
        leadWithACommit();

        assertEquals(JoinResult.JOINED, await(a.admit(D)));

        assertEquals(Optional.of(with(D)), a.getConfiguration());
    }

    @Test
    void aServerAlreadyConfiguredIsLeftAsItIs() throws Exception {
        leadWithACommit();

        assertEquals(JoinResult.ALREADY_MEMBER, await(a.admit(new Server(serverIdOf("b"), "b", "b", false))));

        assertEquals(Optional.of(ManualRaftCluster.configurationOf(MEMBERS)), a.getConfiguration());
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
        assertEquals(Optional.of(ManualRaftCluster.configurationOf(MEMBERS)), a.getConfiguration());
    }

    @Test
    void aServerRejoiningUnderANewIdReplacesItsOldEntryAndThenJoins() throws Exception {
        leadWithACommit();
        Server wipedC = new Server("new-id-of-c", "c", "c", false);

        assertEquals(JoinResult.REPLACING, await(a.admit(wipedC)), "the old entry is removed first");
        assertEquals(Optional.of(without("c")), a.getConfiguration());

        assertEquals(JoinResult.JOINED, await(a.admit(wipedC)), "asking again adds the new server");
        assertEquals(Optional.of(new RaftConfiguration(List.of(server("a"), server("b"), wipedC))),
                a.getConfiguration());
    }

    @Test
    void aServerAtAnotherServersAddressAlsoReplacesIt() throws Exception {
        leadWithACommit();

        assertEquals(JoinResult.REPLACING, await(a.admit(new Server("new-id", "renamed", "c", false))));

        assertEquals(Optional.of(without("c")), a.getConfiguration());
    }

    @Test
    void aJoiningVoterIsRefused() throws Exception {
        leadWithACommit();

        assertRefused(IllegalArgumentException.class, "joins as a non-voter",
                () -> await(a.admit(new Server(serverIdOf("d"), "d", "d", true))));
    }

    @Test
    void aRemovalThatLeavesTooFewReachableVotersIsRefused() throws Exception {
        // c never answers this leader.
        InMemoryTransportSimulator.createPartition(Set.of("a", "b"), Set.of("c"));
        leadWithACommit();

        assertRefused(IllegalStateException.class, "quorum",
                () -> await(a.removeServer(serverIdOf("b"))));
        assertEquals(Optional.of(ManualRaftCluster.configurationOf(MEMBERS)), a.getConfiguration());

        await(a.removeServer(serverIdOf("c")));
        assertEquals(Optional.of(without("c")), a.getConfiguration(), "a and b are both of the voters left");
    }

    @Test
    void aNonVoterCanAlwaysBeRemoved() throws Exception {
        InMemoryTransportSimulator.createPartition(Set.of("a", "b"), Set.of("c"));
        leadWithACommit();
        await(a.admit(D));
        cluster.heartbeatUntil(a, () -> a.getCommitIndex() == a.getLastLogIndex(), "d's addition commits");

        await(a.removeServer(serverIdOf("d")));

        assertEquals(Optional.of(ManualRaftCluster.configurationOf(MEMBERS)), a.getConfiguration());
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
        assertFalse(await(b.status()).removed());
        cluster.elect(b);
        assertFalse(a.isLeader());
        assertTrue(b.getConfiguration().orElseThrow().server(serverIdOf("a")).isEmpty());
    }

    private void leadWithACommit() throws Exception {
        cluster.elect(a);
        await(a.submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put("k", "v"))));
    }

    private RaftNode node(String name) {
        return cluster.add(cluster.builder(name, MEMBERS, new InMemoryTransportSimulator(name),
                new QraftStateStore(), RaftNodeMode.volatileMode()));
    }

    private static Server server(String name) {
        return new Server(serverIdOf(name), name, name, true);
    }

    private static RaftConfiguration with(Server added) {
        return new RaftConfiguration(List.of(server("a"), server("b"), server("c"), added));
    }

    private static RaftConfiguration without(String name) {
        return new RaftConfiguration(ManualRaftCluster.configurationOf(MEMBERS).servers().stream()
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

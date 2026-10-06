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

import dev.mars.qraft.testing.fault.IntentionalErrors;

import dev.mars.qraft.controller.raft.RaftConfiguration.Server;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.ConfigurationCommand;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static dev.mars.qraft.controller.raft.ManualRaftCluster.await;
import static dev.mars.qraft.controller.raft.ManualRaftCluster.serverIdOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how a {@link RaftNode} leader changes its cluster's configuration, one server at a time:
 * <ul>
 *   <li>only a leader that has committed an entry in its current term may start a change, the rule that makes
 *       single-server changes safe across leadership changes;</li>
 *   <li>only one change is in flight: another waits until the first is committed;</li>
 *   <li>a change may add, remove, or alter one server, and must change something;</li>
 *   <li>the same rules hold for a configuration entry submitted as an ordinary command;</li>
 *   <li>an added server is replicated to until it holds the new configuration;</li>
 *   <li>a server joins as a non-voter, and once promoted counts towards the leader's quorum.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
class RaftNodeConfigurationChangeTest {
    private static final Set<String> MEMBERS = Set.of("a", "b", "c");

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
        cluster.elect(a);
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
    void aChangeWaitsUntilTheLeaderHasCommittedAnEntryInItsTerm() throws Exception {
        IntentionalErrors.expect(dev.mars.qraft.testing.fault.IntentionalError.RAFT_PEER_UNREACHABLE);
        RaftConfiguration withD = with(new Server(serverIdOf("d"), "d", "d", false));
        cluster.heartbeatUntil(a, () -> a.getCommitIndex() >= 2, "leadership no-op commits without client writes");
        await(a.proposeConfiguration(withD));
        assertEquals(Optional.of(withD), a.getConfiguration());
    }

    @Test
    void onlyOneChangeIsInFlightAtATime() throws Exception {
        IntentionalErrors.expect(dev.mars.qraft.testing.fault.IntentionalError.RAFT_PEER_UNREACHABLE);
        commitACommand();
        InMemoryTransportSimulator.createPartition(Set.of("a"), Set.of("b", "c"));
        RaftConfiguration withD = with(new Server(serverIdOf("d"), "d", "d", false));
        a.proposeConfiguration(withD);
        awaitTrue(() -> a.getConfiguration().equals(Optional.of(withD)), "the change is in force once appended");

        assertRefused(IllegalStateException.class, "already in progress",
                () -> await(a.proposeConfiguration(ManualRaftCluster.configurationOf(Set.of("a", "b")))));

        InMemoryTransportSimulator.healPartitions();
        cluster.heartbeatUntil(a, () -> a.getCommitIndex() >= a.getLastLogIndex(), "the first change commits");
        await(a.proposeConfiguration(without(withD, "d")));
    }

    @Test
    void aChangeAltersExactlyOneServer() throws Exception {
        commitACommand();

        assertRefused(IllegalArgumentException.class, "one server at a time",
                () -> await(a.proposeConfiguration(with(new Server(serverIdOf("d"), "d", "d", true),
                        new Server(serverIdOf("e"), "e", "e", true)))));
        assertRefused(IllegalArgumentException.class, "unchanged",
                () -> await(a.proposeConfiguration(ManualRaftCluster.configurationOf(MEMBERS))));
        assertEquals(Optional.of(ManualRaftCluster.configurationOf(MEMBERS)), a.getConfiguration());
    }

    @Test
    void aConfigurationSubmittedAsACommandMeetsTheSameRules() throws Exception {
        commitACommand();

        assertRefused(IllegalArgumentException.class, "one server at a time",
                () -> await(a.submitCommand(new ConfigurationCommand(with(
                        new Server(serverIdOf("d"), "d", "d", true), new Server(serverIdOf("e"), "e", "e", true))))));
    }

    @Test
    void anAddedServerIsReplicatedToUntilItHoldsTheConfiguration() throws Exception {
        commitACommand();
        RaftNode d = cluster.add(cluster.unconfiguredBuilder("d", Set.of("a", "b", "c", "d"),
                new InMemoryTransportSimulator("d"), new QraftStateStore(), RaftNodeMode.volatileMode())
                .serverId(serverIdOf("d")));
        await(d.start());
        RaftConfiguration withD = with(new Server(serverIdOf("d"), "d", "d", false));

        await(a.proposeConfiguration(withD));
        // Once caught up, d is also promoted; either way it holds the leader's configuration, which includes it.
        cluster.heartbeatUntil(a, () -> d.getConfiguration().equals(a.getConfiguration())
                && d.getLastLogIndex() == a.getLastLogIndex() && d.getCommitIndex() == a.getCommitIndex(),
                "d catches up with the leader and learns what it has committed");

        assertTrue(d.getConfiguration().orElseThrow().server(serverIdOf("d")).isPresent());
    }

    @Test
    void aPromotedServerCountsTowardsTheLeadersQuorum() throws Exception {
        IntentionalErrors.expect(dev.mars.qraft.testing.fault.IntentionalError.RAFT_PEER_UNREACHABLE);
        commitACommand();
        RaftNode d = cluster.add(cluster.unconfiguredBuilder("d", Set.of("a", "b", "c", "d"),
                new InMemoryTransportSimulator("d"), new QraftStateStore(), RaftNodeMode.volatileMode())
                .serverId(serverIdOf("d")));
        await(d.start());
        await(a.proposeConfiguration(with(new Server(serverIdOf("d"), "d", "d", false))));
        RaftConfiguration withVoterD = with(new Server(serverIdOf("d"), "d", "d", true));
        cluster.heartbeatUntil(a, () -> d.getConfiguration().equals(Optional.of(withVoterD))
                && d.getLastLogIndex() == a.getLastLogIndex() && a.getCommitIndex() == a.getLastLogIndex(),
                "d joins, catches up, and is promoted");

        // Four voters need three; with c cut off, only d's answers keep a in office.
        InMemoryTransportSimulator.createPartition(Set.of("a", "b", "d"), Set.of("c"));
        for (int round = 0; round < 60; round++) {
            cluster.timers(a).firePeriodic(ManualRaftCluster.HEARTBEAT_MS);
            // Each commit needs d's acknowledgement, so d's contact is recorded every round.
            await(a.submitCommand(new DistributedStateRaftCommand(
                    DistributedStateCommand.put("round", Integer.toString(round)))));
        }

        assertEquals(RaftNode.State.LEADER, a.getState(), "a, b and d are a majority of four voters");
    }

    @Test
    void aRemovedServerIsNoLongerTrackedSoItsReplacementStartsAfresh() throws Exception {
        commitACommand();
        cluster.heartbeatUntil(a, () -> a.getNextIndex("c") == a.getLastLogIndex() + 1, "c is up to date");
        RaftConfiguration withoutC = without(ManualRaftCluster.configurationOf(MEMBERS), "c");

        await(a.proposeConfiguration(withoutC));

        assertEquals(-1, a.getNextIndex("c"), "a removed server is not tracked");
        await(c.stop());
        RaftNode replacement = cluster.add(cluster.unconfiguredBuilder("c", Set.of("a", "b", "c"),
                new InMemoryTransportSimulator("c"), new QraftStateStore(), RaftNodeMode.volatileMode())
                .serverId("replacement-of-c"));
        await(replacement.start());
        List<Server> servers = new ArrayList<>(withoutC.servers());
        servers.add(new Server("replacement-of-c", "c", "c", false));
        RaftConfiguration withReplacement = new RaftConfiguration(servers);

        await(a.proposeConfiguration(withReplacement));

        cluster.heartbeatUntil(a, () -> replacement.getConfiguration().equals(a.getConfiguration())
                        && replacement.getLastLogIndex() == a.getLastLogIndex(),
                "the replacement is replicated to from its own empty log");
        assertTrue(replacement.getConfiguration().orElseThrow().server("replacement-of-c").isPresent());
    }

    @Test
    void onlyALeaderChangesTheConfiguration() {
        assertRefused(IllegalStateException.class, "Not the leader",
                () -> await(b.proposeConfiguration(with(new Server(serverIdOf("d"), "d", "d", false)))));
    }

    /** A member that promotes a healthy non-voter after one heartbeat round, rather than Consul's 10 seconds. */
    private RaftNode node(String name) {
        return cluster.add(cluster.builder(name, MEMBERS, new InMemoryTransportSimulator(name),
                new QraftStateStore(), RaftNodeMode.volatileMode())
                .promotionStabilization(ManualRaftCluster.HEARTBEAT_MS));
    }

    private void commitACommand() throws Exception {
        await(a.submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put("k", "v"))));
    }

    private static RaftConfiguration with(Server... added) {
        List<Server> servers = new ArrayList<>(ManualRaftCluster.configurationOf(MEMBERS).servers());
        servers.addAll(List.of(added));
        return new RaftConfiguration(servers);
    }

    private static RaftConfiguration without(RaftConfiguration configuration, String name) {
        return new RaftConfiguration(configuration.servers().stream()
                .filter(server -> !server.name().equals(name)).toList());
    }

    private static void assertRefused(Class<? extends Throwable> type, String reason,
                                      Executable change) {
        ExecutionException refused = assertThrows(ExecutionException.class, change);
        assertInstanceOf(type, refused.getCause());
        assertTrue(refused.getCause().getMessage().contains(reason), refused.getCause().getMessage());
    }

    /** Bounds a wait for the state loop; the bound only diagnoses a hang. */
    private static void awaitTrue(BooleanSupplier condition, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), description);
    }
}

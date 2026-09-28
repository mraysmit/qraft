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

import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.controller.state.RaftCommandResult;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static dev.mars.qraft.controller.raft.ManualRaftCluster.await;
import static dev.mars.qraft.controller.raft.ManualRaftCluster.startAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests Raft over the in-memory network's faults: a partition, reordering, bandwidth throttling, and crashed,
 * slow and flaky nodes. Each fault leaves the cluster with one leader that every member follows, and
 * combined chaos never elects two leaders in one term.
 *
 * <p>Elections and heartbeats fire only when a test fires them through {@link ManualRaftCluster}, so each
 * scenario decides who stands for election; the network's faults are seeded, so a run repeats.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 3.0
 * @since 2026-01-20
 */
class EnhancedInMemoryTransportTest {
    private JavaRuntime runtime;
    private ManualRaftCluster cluster;
    private final Map<String, RaftNode> nodes = new LinkedHashMap<>();
    private final Map<String, InMemoryTransportSimulator> transports = new LinkedHashMap<>();
    private final Map<String, QraftStateStore> stores = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        cluster = new ManualRaftCluster(runtime);
        InMemoryTransportSimulator.clearAllTransports();
    }

    @AfterEach
    void tearDown() throws Exception {
        InMemoryTransportSimulator.healPartitions();
        try {
            cluster.close();
        } finally {
            runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulator.clearAllTransports();
        }
    }

    @Test
    void aPartitionedLeaderIsReplacedAndFollowsTheNewLeaderOnceThePartitionHeals() throws Exception {
        build("node1", "node2", "node3");
        startAll(nodes.values().toArray(RaftNode[]::new));
        electAndFollow("node1");

        InMemoryTransportSimulator.createPartition(Set.of("node1"), Set.of("node2", "node3"));
        cluster.elect(node("node2"));
        assertEquals(2, node("node2").getCurrentTerm());

        InMemoryTransportSimulator.healPartitions();
        cluster.heartbeatUntil(node("node2"), () -> everyoneFollows("node2"), "node1 follows node2 once healed");
        assertEquals(List.of("node2"), leaders());
    }

    @Test
    void reorderedMessagesStillElectOneLeaderAndReplicateInOrder() throws Exception {
        build("leader", "follower");
        for (InMemoryTransportSimulator transport : transports.values()) transport.setReorderingConfig(true, 0.3, 50);
        startAll(nodes.values().toArray(RaftNode[]::new));

        electAndFollow("leader");
        for (int value = 0; value < 5; value++) {
            assertInstanceOf(RaftCommandResult.Success.class, await(node("leader").submitCommand(put("k", "v" + value))));
        }

        cluster.heartbeatUntil(node("leader"), () -> "v4".equals(stores.get("follower").getMetadata("k")),
                "the follower applies the writes in log order");
        assertEquals("v4", stores.get("leader").getMetadata("k"));
        assertEquals(List.of("leader"), leaders());
    }

    @Test
    void aThrottledNetworkStillElectsOneLeader() throws Exception {
        build("node1", "node2");
        for (InMemoryTransportSimulator transport : transports.values()) transport.setThrottlingConfig(true, 1000);
        startAll(nodes.values().toArray(RaftNode[]::new));

        electAndFollow("node1");

        assertEquals(List.of("node1"), leaders());
    }

    @Test
    void aCrashedLeaderIsReplacedAndFollowsTheNewLeaderOnceRecovered() throws Exception {
        build("node1", "node2", "node3");
        startAll(nodes.values().toArray(RaftNode[]::new));
        electAndFollow("node1");

        transports.get("node1").setFailureMode(InMemoryTransportSimulator.FailureMode.CRASH);
        cluster.elect(node("node2"));

        transports.get("node1").recoverFromCrash();
        cluster.heartbeatUntil(node("node2"), () -> everyoneFollows("node2"), "node1 follows node2 once recovered");
        assertEquals(List.of("node2"), leaders());
    }

    @Test
    void aSlowNodeIsStillElected() throws Exception {
        build("node1", "node2");
        transports.get("node1").setFailureMode(InMemoryTransportSimulator.FailureMode.SLOW);
        startAll(nodes.values().toArray(RaftNode[]::new));

        electAndFollow("node1");

        assertEquals(List.of("node1"), leaders());
    }

    @Test
    void aFlakyFollowerStillFollowsTheLeader() throws Exception {
        build("node1", "node2", "node3");
        transports.get("node2").setFailureMode(InMemoryTransportSimulator.FailureMode.FLAKY);
        startAll(nodes.values().toArray(RaftNode[]::new));

        electAndFollow("node1");

        assertEquals(List.of("node1"), leaders());
    }

    @Test
    void combinedChaosNeverElectsTwoLeadersInOneTermAndALeaderEmergesOnceItClears() throws Exception {
        build("node1", "node2", "node3");
        for (InMemoryTransportSimulator transport : transports.values()) {
            transport.setChaosConfig(10, 30, 0.1);
            transport.setReorderingConfig(true, 0.2, 40);
        }
        Map<Long, Set<String>> leadersByTerm = new ConcurrentHashMap<>();
        nodes.values().forEach(node -> node.addStateChangeListener(state -> {
            if (state == RaftNode.State.LEADER) {
                leadersByTerm.computeIfAbsent(node.getCurrentTerm(), term -> ConcurrentHashMap.newKeySet())
                        .add(node.getNodeId());
            }
        }));
        startAll(nodes.values().toArray(RaftNode[]::new));

        // Seeded candidacies while messages are dropped and reordered. Every other round two members stand at
        // once: the second fires before the first's vote request can arrive, so both stand in the same term.
        Random candidacies = new Random(42);
        for (int round = 0; round < 20; round++) {
            List<RaftNode> eligible = new ArrayList<>(nodes.values().stream().filter(node -> !node.isLeader()).toList());
            Collections.shuffle(eligible, candidacies);
            List<RaftNode> standing = eligible.subList(0, Math.min(eligible.size(), round % 2 == 0 ? 1 : 2));
            for (RaftNode candidate : standing) {
                try {
                    cluster.timers(candidate).fireElectionTimeout();
                } catch (IllegalStateException notArmed) {
                    // mid-transition (becoming leader, or between elections): it cannot stand this round
                }
            }
            pollUntil(() -> standing.stream().noneMatch(node -> node.getState() == RaftNode.State.CANDIDATE), 200);
        }
        for (InMemoryTransportSimulator transport : transports.values()) {
            transport.setChaosConfig(5, 15, 0.0);
            transport.setReorderingConfig(false, 0.0, 0);
        }
        convergeOnTheHighestTerm();

        assertFalse(leadersByTerm.isEmpty());
        leadersByTerm.forEach((term, leaders) ->
                assertEquals(1, leaders.size(), "term " + term + " elected " + leaders));
    }

    // ---------------------------------------------------------------------------------------------------

    private void build(String... ids) {
        Set<String> members = Set.of(ids);
        for (String id : ids) {
            InMemoryTransportSimulator transport = new InMemoryTransportSimulator(id);
            QraftStateStore store = new QraftStateStore();
            transports.put(id, transport);
            stores.put(id, store);
            nodes.put(id, cluster.add(cluster.builder(id, members, transport, store, RaftNodeMode.volatileMode())));
        }
    }

    private RaftNode node(String id) {
        return nodes.get(id);
    }

    private void electAndFollow(String leaderId) throws Exception {
        cluster.elect(node(leaderId));
        cluster.heartbeatUntil(node(leaderId), () -> everyoneFollows(leaderId), "every member follows " + leaderId);
    }

    private boolean everyoneFollows(String leaderId) {
        return nodes.values().stream().allMatch(node -> leaderId.equals(node.getLeaderId()));
    }

    private List<String> leaders() {
        return nodes.values().stream().filter(RaftNode::isLeader).map(RaftNode::getNodeId).toList();
    }

    /**
     * Drives the cluster to one leader on a clear network: the member with the highest term either leads, and
     * its heartbeat brings the others to its term, or stands for the next term, which it wins because every
     * log is empty.
     */
    private void convergeOnTheHighestTerm() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!(leaders().size() == 1 && everyoneFollows(leaders().getFirst())) && System.nanoTime() < deadline) {
            RaftNode highest = nodes.values().stream().max(Comparator.comparingLong(RaftNode::getCurrentTerm))
                    .orElseThrow();
            try {
                if (highest.isLeader()) cluster.timers(highest).firePeriodic(ManualRaftCluster.HEARTBEAT_MS);
                else cluster.timers(highest).fireElectionTimeout();
            } catch (IllegalStateException roleChanged) {
                continue; // its role changed between the check and the fire
            }
            pollUntil(() -> leaders().size() == 1 && everyoneFollows(leaders().getFirst()), 200);
        }
        List<String> leaders = leaders();
        assertEquals(1, leaders.size(), "one leader once the chaos clears: " + leaders);
        assertTrue(everyoneFollows(leaders.getFirst()), "every member follows " + leaders.getFirst());
    }

    private static void pollUntil(BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
    }

    private static DistributedStateRaftCommand put(String key, String value) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, value));
    }
}

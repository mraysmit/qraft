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

import dev.mars.qraft.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;
import dev.mars.qraft.common.async.Future;
import dev.mars.qraft.common.async.JavaRuntime;
import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import dev.mars.qraft.testing.fault.InjectedFaultFixture;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static dev.mars.qraft.raft.ManualRaftClusterFixture.startAll;
import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE;
import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_TRANSPORT_FAILURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests Raft failure scenarios: repeated start and stop, a new leader after the leader stops, a majority
 * keeping its leader when one member stops, simultaneous candidacies, a transport that cannot start, and a
 * node built with no members.
 *
 * <p>Elections and heartbeats happen only when a test fires them through {@link ManualRaftClusterFixture}, so each
 * scenario runs the same way every time. Every node is stopped after the test.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 2.2
 * @since 2025-08-20
 */
class RaftFailureTest {
    private JavaRuntime runtime;
    private ManualRaftClusterFixture cluster;
    private RaftNode node1;
    private RaftNode node2;
    private RaftNode node3;
    private InMemoryTransportSimulatorFixture transport1;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        cluster = new ManualRaftClusterFixture(runtime);
        InMemoryTransportSimulatorFixture.clearAllTransports();
        Set<String> members = Set.of("node1", "node2", "node3");
        transport1 = new InMemoryTransportSimulatorFixture("node1");
        node1 = node("node1", members, transport1);
        node2 = node("node2", members, new InMemoryTransportSimulatorFixture("node2"));
        node3 = node("node3", members, new InMemoryTransportSimulatorFixture("node3"));
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
    void startingTwiceKeepsTheTransportRunningAndStoppingTwiceKeepsItStopped() throws Exception {
        await(node1.start());
        assertTrue(transport1.isRunning());

        await(node1.start());
        assertTrue(transport1.isRunning(), "a second start is harmless");

        await(node1.stop());
        assertFalse(transport1.isRunning());

        await(node1.stop());
        assertFalse(transport1.isRunning(), "a second stop is harmless");
    }

    @Test
    void aSurvivingMemberIsElectedInANewTermAfterTheLeaderStops() throws Exception {
        IntentionalErrorsHelper.expect(RAFT_PEER_UNREACHABLE);
        startAll(node1, node2, node3);
        cluster.elect(node1);
        cluster.heartbeatUntil(node1, () -> followsNode1(node2) && followsNode1(node3), "both follow node1");

        await(node1.stop());
        cluster.elect(node2);

        cluster.heartbeatUntil(node2, () -> "node2".equals(node3.getLeaderId()), "node3 follows node2");
        assertEquals(2, node2.getCurrentTerm(), "the replacement leads a later term");
        assertEquals(List.of(true, false), List.of(node2.isLeader(), node3.isLeader()));
        assertInstanceOf(RaftCommandResult.Success.class, await(node2.submitCommand(distributedPut("after", "failover"))),
                "the two survivors are a majority, so the new leader commits");
    }

    @Test
    void aLeaderKeepsLeadingAndCommittingWhileAMajorityRemains() throws Exception {
        IntentionalErrorsHelper.expect(RAFT_PEER_UNREACHABLE);
        startAll(node1, node2, node3);
        cluster.elect(node1);
        cluster.heartbeatUntil(node1, () -> followsNode1(node2) && followsNode1(node3), "both follow node1");

        await(node3.stop());

        // More heartbeat rounds than the check-quorum window: node2's replies keep the leader in office. Each
        // commit needs node2's reply, so every round's contact is recorded before the next round starts.
        for (int round = 0; round < 60; round++) {
            cluster.timers(node1).firePeriodic(ManualRaftClusterFixture.HEARTBEAT_MS);
            assertInstanceOf(RaftCommandResult.Success.class,
                    await(node1.submitCommand(distributedPut("round", Integer.toString(round)))));
        }
        assertTrue(node1.isLeader(), "a leader with a majority does not step down");
        assertEquals(1, node1.getCurrentTerm());
    }

    @Test
    void simultaneousCandidaciesSettleOnOneLeaderPerTerm() throws Exception {
        Map<Long, Set<String>> leadersByTerm = new ConcurrentHashMap<>();
        for (RaftNode node : List.of(node1, node2, node3)) {
            node.addStateChangeListener(recordLeader(node, leadersByTerm));
        }
        startAll(node1, node2, node3);

        cluster.timers(node1).fireElectionTimeout();
        cluster.timers(node2).fireElectionTimeout();

        awaitTrue(() -> leaders().size() == 1, "one candidate wins");
        RaftNode leader = leaders().getFirst();
        cluster.heartbeatUntil(leader, () -> List.of(node1, node2, node3).stream()
                .allMatch(node -> leader.getNodeId().equals(node.getLeaderId())), "every member follows the winner");
        assertEquals(List.of(leader), leaders());
        leadersByTerm.forEach((term, leaders) ->
                assertEquals(1, leaders.size(), "term " + term + " had leaders " + leaders));
    }

    @Test
    void aNodeMustBeAMemberOfItsOwnCluster() {
        // With no members, its own vote would exceed half of zero and it would lead a cluster of nobody. The
        // builder is unconfigured, so the refusal comes from build, not from making a configuration of nobody.
        for (Set<String> members : List.of(Set.<String>of(), Set.of("node2", "node3"))) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> cluster.unconfiguredBuilder(
                    "node1", members, transport1, new QraftStateStore(), RaftNodeMode.volatileMode()).build());
            assertEquals("clusterNodes must include this node, node1", refused.getMessage());
        }
    }

    @Test
    void startFailsWithTheTransportsErrorWhenTheTransportCannotStart() {
        RaftTransport failingTransport = new RaftTransport() {
            @Override
            public void start(Consumer<RaftMessage> messageHandler) {
                throw new InjectedFaultFixture(RAFT_TRANSPORT_FAILURE, "Transport failed to start");
            }

            @Override
            public void stop() { }

            @Override
            public Future<VoteResponse> sendVoteRequest(String nodeId, VoteRequest request) {
                return Future.failedFuture(new InjectedFaultFixture(RAFT_TRANSPORT_FAILURE, "Network error"));
            }

            @Override
            public Future<AppendEntriesResponse> sendAppendEntries(String nodeId, AppendEntriesRequest request) {
                return Future.failedFuture(new InjectedFaultFixture(RAFT_TRANSPORT_FAILURE, "Network error"));
            }

            @Override
            public Future<InstallSnapshotResponse> sendInstallSnapshot(String nodeId, InstallSnapshotRequest request) {
                return Future.failedFuture(new InjectedFaultFixture(RAFT_TRANSPORT_FAILURE, "Network error"));
            }
        };
        RaftNode failingNode = cluster.add(cluster.builder("failing", Set.of("failing"), failingTransport,
                new QraftStateStore(), RaftNodeMode.volatileMode()));

        ExecutionException exception = assertThrows(ExecutionException.class, () -> await(failingNode.start()));

        assertInstanceOf(RuntimeException.class, exception.getCause());
        assertEquals("Transport failed to start", exception.getCause().getMessage());
    }

    // ---------------------------------------------------------------------------------------------------

    private RaftNode node(String nodeId, Set<String> members, RaftTransport transport) {
        return cluster.add(cluster.builder(nodeId, members, transport, new QraftStateStore(),
                RaftNodeMode.volatileMode()));
    }

    private List<RaftNode> leaders() {
        return List.of(node1, node2, node3).stream().filter(RaftNode::isLeader).toList();
    }

    private static Consumer<RaftNode.State> recordLeader(RaftNode node, Map<Long, Set<String>> leadersByTerm) {
        return state -> {
            if (state == RaftNode.State.LEADER) {
                leadersByTerm.computeIfAbsent(node.getCurrentTerm(), term -> ConcurrentHashMap.newKeySet())
                        .add(node.getNodeId());
            }
        };
    }

    private static boolean followsNode1(RaftNode node) {
        return "node1".equals(node.getLeaderId());
    }

    private static RaftCommand distributedPut(String key, String value) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, value));
    }

    /** Bounds a wait for in-memory round trips; the bound only diagnoses a hang. */
    private static void awaitTrue(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), description);
    }
}

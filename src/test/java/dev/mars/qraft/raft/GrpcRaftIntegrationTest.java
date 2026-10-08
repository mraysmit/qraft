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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;

import dev.mars.qraft.raft.grpc.JoinRequest;
import dev.mars.qraft.raft.grpc.MembershipResponse;
import dev.mars.qraft.raft.grpc.RemoveServerRequest;
import dev.mars.qraft.common.async.JavaRuntime;
import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests Raft over real gRPC: {@link GrpcRaftServer} and {@link GrpcRaftTransport} carrying elections,
 * heartbeats, and replication between {@link RaftNode}s.
 *
 * <p>Every server listens on port 0 and peers learn the port it bound before any node starts, so no test
 * races another process for a port. Elections happen only when a test fires a chosen node's timeout
 * through {@link ManualRaftTimersHelper}, so who leads, and in which term, is decided by the test.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 2.0
 * @since 2026-01-08
 */
@Execution(ExecutionMode.SAME_THREAD)
class GrpcRaftIntegrationTest {
    private static final long HEARTBEAT_MS = 200;
    private static final String OPERATOR_TOKEN = "operator-secret";

    private JavaRuntime runtime;
    private final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
    private final Map<String, String> addresses = new ConcurrentHashMap<>();
    private final List<Member> members = new ArrayList<>();
    /** The configuration the members bootstrap with, at the addresses their servers bound. */
    private RaftConfiguration configuration;

    private record Member(String id, RaftNode node, GrpcRaftServer server, ManualRaftTimersHelper timers,
                          QraftStateStore state, GrpcRaftTransport transport) {
        void stop() throws Exception {
            server.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            node.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
    }

    @AfterEach
    void tearDown() throws Exception {
        Exception failure = null;
        for (Member member : members) {
            try {
                member.stop();
            } catch (Exception error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (failure != null) throw failure;
    }

    @Test
    void aCandidateIsElectedOverGrpcAndEveryPeerFollowsIt() throws Exception {
        List<Member> cluster = startCluster("node1", "node2", "node3");

        Member leader = elect(cluster.get(0));

        assertEquals(1, leader.node().getCurrentTerm());
        for (Member follower : cluster.subList(1, 3)) {
            awaitTrue(() -> "node1".equals(follower.node().getLeaderId()), follower.id() + " follows node1");
            assertEquals(RaftNode.State.FOLLOWER, follower.node().getState());
            assertEquals(1, follower.node().getCurrentTerm());
        }
    }

    @Test
    void aCommandCommittedOverGrpcIsAppliedByEveryMember() throws Exception {
        List<Member> cluster = startCluster("node1", "node2", "node3");
        Member leader = elect(cluster.get(0));

        RaftCommandResult<?> result = submit(leader, "replicated", "over-grpc");

        assertInstanceOf(RaftCommandResult.Success.class, result);
        leader.timers().firePeriodic(HEARTBEAT_MS); // carries the leader's commit index to the followers
        for (Member member : cluster) {
            awaitTrue(() -> "over-grpc".equals(member.state().getMetadata("replicated")),
                    member.id() + " applies the committed command");
        }
    }

    @Test
    void aMajorityKeepsCommittingAfterAFollowerStops() throws Exception {
        IntentionalErrorsHelper.expect(dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE);
        List<Member> cluster = startCluster("node1", "node2", "node3");
        Member leader = elect(cluster.get(0));
        cluster.get(2).stop();
        members.remove(cluster.get(2));

        RaftCommandResult<?> result = submit(leader, "after-stop", "two-of-three");

        assertInstanceOf(RaftCommandResult.Success.class, result, "two of three members are a majority");
        leader.timers().firePeriodic(HEARTBEAT_MS);
        awaitTrue(() -> "two-of-three".equals(cluster.get(1).state().getMetadata("after-stop")),
                "the remaining follower applies it");
    }

    @Test
    void aNewElectionAfterTheLeaderStopsAdvancesTheTerm() throws Exception {
        IntentionalErrorsHelper.expect(dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE);
        List<Member> cluster = startCluster("node1", "node2", "node3");
        Member first = elect(cluster.get(0));
        submit(first, "before", "failover");
        first.stop();
        members.remove(first);

        Member second = elect(cluster.get(1));

        assertEquals(2, second.node().getCurrentTerm(), "a new leader is elected in a later term");
        Member follower = cluster.get(2);
        awaitTrue(() -> "node2".equals(follower.node().getLeaderId()) && follower.node().getCurrentTerm() == 2,
                "node3 follows the new leader in its term");
        // node2 held the entry but never heard it was committed; as leader it commits it together with the
        // no-op of its own term (Raft section 5.4.2), once node3 acknowledges.
        awaitTrue(() -> "failover".equals(second.state().getMetadata("before")),
                "a committed entry survives the change of leader");
    }

    @Test
    void simultaneousCandidaciesElectExactlyOneLeaderInTheirTerm() throws Exception {
        List<Member> cluster = startCluster("node1", "node2", "node3");
        Map<Long, Set<String>> leadersByTerm = recordLeaders(cluster);

        cluster.get(0).timers().fireElectionTimeout();
        cluster.get(1).timers().fireElectionTimeout();

        // node3 grants its single term-1 vote to whichever request arrives first, so exactly one candidate
        // reaches a majority. The other learns of it from the winner's first heartbeat and steps down.
        awaitTrue(() -> cluster.stream().filter(member -> member.node().isLeader()).count() == 1
                        && cluster.stream().allMatch(member -> member.node().getLeaderId() != null),
                "one leader emerges and every member knows it");
        String leaderId = cluster.stream().filter(member -> member.node().isLeader()).findFirst().orElseThrow().id();
        for (Member member : cluster) {
            assertEquals(leaderId, member.node().getLeaderId());
        }
        leadersByTerm.forEach((term, leaders) -> assertEquals(1, leaders.size(), "term " + term + ": " + leaders));
    }

    @Test
    void aMemberStartedLaterCatchesUpWithTheLeader() throws Exception {
        List<Member> cluster = buildCluster("node1", "node2", "node3");
        start(cluster.get(0));
        start(cluster.get(1));
        Member leader = elect(cluster.get(0));
        submit(leader, "early", "before-node3");

        start(cluster.get(2));
        leader.timers().firePeriodic(HEARTBEAT_MS);

        Member late = cluster.get(2);
        awaitTrue(() -> "before-node3".equals(late.state().getMetadata("early")),
                "the late member receives and applies the entries it missed");
        assertEquals("node1", late.node().getLeaderId());
    }

    @Test
    void aConfiguredServerIsReachedAtItsConfiguredAddressAlone() throws Exception {
        List<Member> cluster = buildCluster("node1", "node2", "node3");
        // No transport lists node3 any more: only the configuration gives its address.
        addresses.remove("node3");
        for (Member member : cluster) start(member);
        Member leader = elect(cluster.get(0));

        submit(leader, "k", "reached");
        leader.timers().firePeriodic(HEARTBEAT_MS);

        awaitTrue(() -> "reached".equals(cluster.get(2).state().getMetadata("k")),
                "node3 is replicated to at its configured address");
    }

    @Test
    void aJoinSentToAFollowerIsForwardedOverGrpcToTheLeader() throws Exception {
        IntentionalErrorsHelper.expect(dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE,
                "node4");
        List<Member> cluster = startCluster("node1", "node2", "node3");
        Member leader = elect(cluster.get(0));
        submit(leader, "k", "v");
        leader.timers().firePeriodic(HEARTBEAT_MS);
        awaitTrue(() -> "node1".equals(cluster.get(1).node().getLeaderId()), "node2 knows the leader");

        Logger logger = (Logger) LoggerFactory.getLogger(RaftNode.class);
        ListAppender<ILoggingEvent> learnerFailureCapture = new ListAppender<>();
        learnerFailureCapture.list = new CopyOnWriteArrayList<>();
        learnerFailureCapture.start();
        logger.addAppender(learnerFailureCapture);
        try {
            MembershipResponse answer = cluster.get(2).transport().join("node2", JoinRequest.newBuilder()
                    .setServerId("joining-id").setName("node4").setAddress("localhost:1").build())
                    .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);

            assertEquals(MembershipResponse.Status.JOINED, answer.getStatus(), answer.getMessage());
            assertEquals(new RaftConfiguration.Server("joining-id", "node4", "localhost:1", false),
                    leader.node().getConfiguration().orElseThrow().server("joining-id").orElseThrow());

            // Joining commits before the unreachable learner's asynchronous RPC necessarily fails.
            // Complete that deliberate fault before teardown, keeping the required error declaration.
            awaitTrue(() -> {
                leader.timers().firePeriodic(HEARTBEAT_MS);
                return learnerFailureCapture.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                            && event.getFormattedMessage().equals(
                                    "Raft peer node4 became unreachable during AppendEntries"));
            },
                    "the deliberately unreachable learner is contacted and reports its flagged failure");
        } finally {
            logger.detachAppender(learnerFailureCapture);
            learnerFailureCapture.stop();
        }
    }

    @Test
    void aRemovalSentToAFollowerIsForwardedWithItsTokenToTheLeader() throws Exception {
        List<Member> cluster = startCluster("node1", "node2", "node3");
        Member leader = elect(cluster.get(0));
        submit(leader, "k", "v");
        leader.timers().firePeriodic(HEARTBEAT_MS);
        awaitTrue(() -> "node1".equals(cluster.get(1).node().getLeaderId()), "node2 knows the leader");

        MembershipResponse answer = cluster.get(2).transport().removeServer("node2", RemoveServerRequest.newBuilder()
                .setName("node3").setToken(OPERATOR_TOKEN).build())
                .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);

        assertEquals(MembershipResponse.Status.REMOVED, answer.getStatus(), answer.getMessage());
        assertTrue(leader.node().getConfiguration().orElseThrow().serverNamed("node3").isEmpty());
    }

    @Test
    void aSoleMemberElectsItself() throws Exception {
        Member solo = startCluster("solo").getFirst();

        elect(solo);

        assertEquals(1, solo.node().getCurrentTerm());
        assertInstanceOf(RaftCommandResult.Success.class, submit(solo, "solo", "committed-alone"));
    }

    // ---------------------------------------------------------------------------------------------------

    private List<Member> startCluster(String... ids) throws Exception {
        List<Member> cluster = buildCluster(ids);
        for (Member member : cluster) start(member);
        return cluster;
    }

    /**
     * Builds each member with its gRPC server listening on port 0, then records the port every server bound
     * as that member's address. Transports read the shared address map when they dial, so every address is
     * real before any node starts. The members bootstrap as they start, as a new cluster does, with a
     * configuration of those real addresses: a transport dials a configured server at its configured address.
     */
    private List<Member> buildCluster(String... ids) throws Exception {
        Set<String> memberIds = Set.of(ids);
        List<Member> cluster = new ArrayList<>();
        for (String id : ids) {
            GrpcRaftTransport transport = new GrpcRaftTransport(runtime, id, addresses);
            ManualRaftTimersHelper timers = new ManualRaftTimersHelper(runtime, clock);
            QraftStateStore state = new QraftStateStore();
            RaftNode node = RaftNode.builder().runtime(runtime).nodeId(id).clusterNodes(memberIds)
                    .serverId(ManualRaftClusterFixture.serverIdOf(id))
                    .transport(transport).stateMachine(state).commandCodec(new ProtobufRaftCommandCodec())
                    .mode(RaftNodeMode.volatileMode()).snapshotEnabled(false)
                    .electionTimeout(1_000).heartbeatInterval(HEARTBEAT_MS).timerScheduler(timers)
                    .build();
            GrpcRaftServer server = new GrpcRaftServer(runtime, 0, node,
                    new MembershipService(node, transport, OPERATOR_TOKEN));
            server.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            addresses.put(id, "localhost:" + server.port());
            Member member = new Member(id, node, server, timers, state, transport);
            cluster.add(member);
            members.add(member);
        }
        configuration = new RaftConfiguration(cluster.stream().map(member -> new RaftConfiguration.Server(
                ManualRaftClusterFixture.serverIdOf(member.id()), member.id(), addresses.get(member.id()), true)).toList());
        return cluster;
    }

    /** Starts the member and bootstraps it; a sole member bootstraps itself as it starts. */
    private void start(Member member) throws Exception {
        member.node().start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (member.node().getConfiguration().isEmpty()) {
            member.node().bootstrap(configuration).toCompletionStage().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
        }
    }

    private static Member elect(Member candidate) throws Exception {
        candidate.timers().fireElectionTimeout();
        candidate.node().awaitState(RaftNode.State.LEADER, 10_000)
                .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        return candidate;
    }

    private static RaftCommandResult<?> submit(Member leader, String key, String value) throws Exception {
        return leader.node().submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put(key, value)))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static Map<Long, Set<String>> recordLeaders(List<Member> cluster) {
        Map<Long, Set<String>> leadersByTerm = new ConcurrentHashMap<>();
        for (Member member : cluster) {
            member.node().addStateChangeListener(state -> {
                if (state == RaftNode.State.LEADER) {
                    leadersByTerm.computeIfAbsent(member.node().getCurrentTerm(),
                            term -> ConcurrentHashMap.newKeySet()).add(member.id());
                }
            });
        }
        return leadersByTerm;
    }

    /** Bounds a wait for gRPC round trips; the bound only diagnoses a hang. */
    private static void awaitTrue(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), description);
    }
}

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

import dev.mars.qraft.raft.ClusterBootstrap.Outcome;
import dev.mars.qraft.raft.RaftConfiguration.Server;
import dev.mars.qraft.common.async.JavaRuntime;
import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.raft.storage.RaftStorageFactory;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.HashMap;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.BOOTSTRAP_SERVER_LISTS_DISAGREE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ClusterBootstrap} with real nodes on the in-memory network, none of which starts with a
 * configuration:
 * <ul>
 *   <li>when every listed server answers, is fresh, and lists the same servers, one attempt bootstraps with every
 *       server's own server ID and address, the others find that cluster and join it, and a leader's
 *       replication gives them the same configuration;</li>
 *   <li>a listed server that does not answer means waiting, and nothing is written;</li>
 *   <li>a listed server that lists different servers means refusing, and nothing is written;</li>
 *   <li>a node that already has a configuration has nothing to do.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
class ClusterBootstrapTest {
    private static final Set<String> MEMBERS = Set.of("a", "b", "c");
    private static final Map<String, String> ADDRESSES = Map.of("a", "a:9080", "b", "b:9080", "c", "c:9080");

    private JavaRuntime runtime;
    private ManualRaftClusterFixture cluster;
    @TempDir Path storageDirectory;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        cluster = new ManualRaftClusterFixture(runtime);
        InMemoryTransportSimulatorFixture.clearAllTransports();
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
    void freshServersThatAgreeFormOneClusterWithEachServersOwnIdAndAddress() throws Exception {
        RaftNode a = fresh("a", MEMBERS);
        RaftNode b = fresh("b", MEMBERS);
        RaftNode c = fresh("c", MEMBERS);
        RaftConfiguration expected = new RaftConfiguration(List.of(
                new Server(a.getServerId(), "a", "a:9080", true),
                new Server(b.getServerId(), "b", "b:9080", true),
                new Server(c.getServerId(), "c", "c:9080", true)));

        assertEquals(Outcome.BOOTSTRAPPED, await(bootstrapOf(a).attempt()));
        assertEquals(Optional.of(expected), a.getConfiguration());

        assertEquals(Outcome.JOINING_EXISTING, await(bootstrapOf(b).attempt()), "a already belongs to a cluster");
        assertEquals(Optional.empty(), b.getConfiguration(), "b waits for a leader rather than writing its own");
        assertEquals(Outcome.CONFIGURED, await(bootstrapOf(a).attempt()), "a has nothing more to do");

        cluster.elect(a);
        cluster.heartbeatUntil(a, () -> b.getConfiguration().isPresent() && c.getConfiguration().isPresent(),
                "a replicates its configuration");
        assertEquals(Optional.of(expected), b.getConfiguration());
        assertEquals(Optional.of(expected), c.getConfiguration());
    }

    @Test
    void aListedServerThatDoesNotAnswerMeansWaitingAndNothingIsWritten() throws Exception {
        RaftNode a = fresh("a", MEMBERS);
        fresh("b", MEMBERS);

        assertEquals(Outcome.WAITING, await(bootstrapOf(a).attempt()), "c was never started");
        assertEquals(Optional.empty(), a.getConfiguration());
        assertEquals(0, a.getLastLogIndex());
    }

    @Test
    void aListedServerThatListsOtherServersMeansRefusingAndNothingIsWritten() throws Exception {
        IntentionalErrorsHelper.expect(BOOTSTRAP_SERVER_LISTS_DISAGREE, 1);
        RaftNode a = fresh("a", MEMBERS);
        fresh("b", Set.of("a", "b", "d"));
        fresh("c", MEMBERS);

        assertEquals(Outcome.LISTS_DISAGREE, await(bootstrapOf(a).attempt()));
        assertEquals(Optional.empty(), a.getConfiguration());
        assertEquals(0, a.getLastLogIndex());
    }

    @Test
    void aServerThatFindsAnExistingClusterAsksToJoinItThroughAnyMember() throws Exception {
        RaftNode a = configured("a", true);
        configured("b", true);
        configured("c", true);
        cluster.elect(a);
        await(a.submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put("k", "v"))));
        // d lists only b, a follower, which forwards the request to a.
        RaftNode d = fresh("d", Set.of("b", "d"));

        assertEquals(Outcome.JOINING_EXISTING, await(bootstrapOf(d).attempt()));

        assertEquals(Optional.of(new Server(d.getServerId(), "d", "d:9080", false)),
                a.getConfiguration().orElseThrow().serverNamed("d"), "d joins as a non-voter, at its own address");
        cluster.heartbeatUntil(a, () -> d.getConfiguration().equals(a.getConfiguration()),
                "a replicates the configuration to d");
    }

    @Test
    void aServerAsksEachMemberInTurnUntilOneTakesItsRequest() throws Exception {
        RaftNode a = configured("a", true);
        // b belongs to the cluster but takes no requests, as a server that removed itself would not.
        configured("b", false);
        RaftNode c = configured("c", true);
        cluster.elect(a);
        await(a.submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put("k", "v"))));
        cluster.heartbeatUntil(a, () -> "a".equals(c.getLeaderId()), "c knows its leader");
        // d lists b before c, so it asks b first.
        RaftNode d = fresh("d", Set.of("b", "c", "d"));

        assertEquals(Outcome.JOINING_EXISTING, await(bootstrapOf(d).attempt()));

        assertTrue(a.getConfiguration().orElseThrow().serverNamed("d").isPresent(),
                "b could not take d's request, so d asked c, which forwarded it to a");
    }

    @Test
    void aConfiguredMemberAtANewAddressAsksToRejoinUntilItsAddressIsReplicated() throws Exception {
        RaftNode a = configured("a", true);
        configured("b", true);
        var originalStorage = await(RaftStorageFactory.createDurable(storageDirectory, true));
        RaftNode original = cluster.add(cluster.builder("c", MEMBERS, new InMemoryTransportSimulatorFixture("c"),
                new QraftStateStore(), RaftNodeMode.durable(originalStorage.wal(), originalStorage.snapshots())));
        await(original.start());
        await(original.stop());
        var recoveredStorage = await(RaftStorageFactory.createDurable(storageDirectory, true));
        // Recover the same server ID and configuration from the real WAL at a changed address.
        InMemoryTransportSimulatorFixture movedTransport = new InMemoryTransportSimulatorFixture("c");
        RaftNode moved = cluster.add(cluster.unconfiguredBuilder("c", MEMBERS, movedTransport,
                new QraftStateStore(), RaftNodeMode.durable(recoveredStorage.wal(), recoveredStorage.snapshots()))
                .serverId(original.getServerId())
                .addresses(Map.of("a", "a", "b", "b", "c", "c-moved")));
        movedTransport.serveMembership(new MembershipService(moved, movedTransport, null));
        await(moved.start());
        cluster.elect(a);
        cluster.heartbeatUntil(a, () -> a.getCommitIndex() >= 2, "leadership no-op commits");

        assertEquals(Outcome.JOINING_EXISTING, await(bootstrapOf(moved).attempt()));
        assertEquals("c-moved", a.getConfiguration().orElseThrow().serverNamed("c").orElseThrow().address());
        assertTrue(a.getConfiguration().orElseThrow().serverNamed("c").orElseThrow().voter());
        cluster.heartbeatUntil(a, () -> moved.getConfiguration().equals(a.getConfiguration()), "new address replicated");
        assertEquals(Outcome.CONFIGURED, await(bootstrapOf(moved).attempt()));
    }

    @Test
    void aMovedLeaderUpdatesItsOwnAddressWithoutAnotherMember() throws Exception {
        RaftNode moved = cluster.add(cluster.builder("a", Set.of("a"), new InMemoryTransportSimulatorFixture("a"),
                new QraftStateStore(), RaftNodeMode.volatileMode()).addresses(Map.of("a", "a-moved")));
        await(moved.start());
        cluster.elect(moved);
        cluster.heartbeatUntil(moved, () -> moved.getCommitIndex() >= 2, "leadership no-op commits");
        assertEquals(Outcome.JOINING_EXISTING, await(bootstrapOf(moved).attempt()));
        assertEquals("a-moved", moved.getConfiguration().orElseThrow().serverNamed("a").orElseThrow().address());
        assertEquals(Outcome.CONFIGURED, await(bootstrapOf(moved).attempt()));
    }

    @Test
    void aNodeThatHasAConfigurationHasNothingToDo() throws Exception {
        RaftNode configured = cluster.add(cluster.builder("a", MEMBERS, new InMemoryTransportSimulatorFixture("a"),
                new QraftStateStore(), RaftNodeMode.volatileMode()));
        await(configured.start());

        assertEquals(Outcome.CONFIGURED, await(bootstrapOf(configured).attempt()));
        assertEquals(1, configured.getLastLogIndex(), "no second configuration is written");
    }

    /** A member of a cluster of a, b and c that serves joins, with its promotions left to their default. */
    private RaftNode configured(String name, boolean servesJoins) throws Exception {
        InMemoryTransportSimulatorFixture transport = new InMemoryTransportSimulatorFixture(name);
        RaftNode node = cluster.add(cluster.builder(name, MEMBERS, transport,
                new QraftStateStore(), RaftNodeMode.volatileMode()));
        if (servesJoins) transport.serveMembership(new MembershipService(node, transport, null));
        await(node.start());
        return node;
    }

    private RaftNode fresh(String name, Set<String> members) throws Exception {
        Map<String, String> addresses = new HashMap<>();
        for (String member : members) addresses.put(member, ADDRESSES.getOrDefault(member, member + ":9080"));
        RaftNode node = cluster.add(cluster.unconfiguredBuilder(name, members, new InMemoryTransportSimulatorFixture(name),
                new QraftStateStore(), RaftNodeMode.volatileMode()).addresses(addresses));
        await(node.start());
        return node;
    }

    private static ClusterBootstrap bootstrapOf(RaftNode node) {
        return new ClusterBootstrap(node, InMemoryTransportSimulatorFixture.getAllTransports().get(node.getNodeId()));
    }
}

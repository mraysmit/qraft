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

import dev.mars.qraft.controller.raft.ClusterBootstrap.Outcome;
import dev.mars.qraft.controller.raft.RaftConfiguration.Server;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.controller.raft.ManualRaftCluster.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    private ManualRaftCluster cluster;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        cluster = new ManualRaftCluster(runtime);
        InMemoryTransportSimulator.clearAllTransports();
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
        RaftNode a = fresh("a", MEMBERS);
        fresh("b", Set.of("a", "b", "d"));
        fresh("c", MEMBERS);

        assertEquals(Outcome.LISTS_DISAGREE, await(bootstrapOf(a).attempt()));
        assertEquals(Optional.empty(), a.getConfiguration());
        assertEquals(0, a.getLastLogIndex());
    }

    @Test
    void aNodeThatHasAConfigurationHasNothingToDo() throws Exception {
        RaftNode configured = cluster.add(cluster.builder("a", MEMBERS, new InMemoryTransportSimulator("a"),
                new QraftStateStore(), RaftNodeMode.volatileMode()));
        await(configured.start());

        assertEquals(Outcome.CONFIGURED, await(bootstrapOf(configured).attempt()));
        assertEquals(1, configured.getLastLogIndex(), "no second configuration is written");
    }

    private RaftNode fresh(String name, Set<String> members) throws Exception {
        Map<String, String> addresses = new HashMap<>();
        for (String member : members) addresses.put(member, ADDRESSES.getOrDefault(member, member + ":9080"));
        RaftNode node = cluster.add(cluster.unconfiguredBuilder(name, members, new InMemoryTransportSimulator(name),
                new QraftStateStore(), RaftNodeMode.volatileMode()).addresses(addresses));
        await(node.start());
        return node;
    }

    private static ClusterBootstrap bootstrapOf(RaftNode node) {
        return new ClusterBootstrap(node, InMemoryTransportSimulator.getAllTransports().get(node.getNodeId()));
    }
}

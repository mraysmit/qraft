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
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.kv.KvCommand;
import dev.mars.qraft.state.kv.KvScope;
import dev.mars.qraft.testing.fault.IntentionalErrorFixture;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the leader's view of each server (observability list, Phase 3, decision 5), Consul's autopilot health: the
 * leader reports itself and every follower with its last contact and the index it holds; a follower that has answered
 * within an election timeout and holds the leader's log is healthy, and one cut off is not, which lowers the failure
 * tolerance; and a follower has no such view.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class RaftNodeServerHealthTest {
    private static final Set<String> MEMBERS = Set.of("h1", "h2", "h3");
    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;

    @BeforeEach
    void setUp() {
        runtime = RaftExecutorsFixture.create();
        cluster = new ManualRaftClusterFixture(runtime);
        InMemoryTransportSimulatorFixture.clearAllTransports();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            InMemoryTransportSimulatorFixture.healPartitions();
            cluster.close();
        } finally {
            runtime.close().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
        }
    }

    private RaftNode[] three() throws Exception {
        RaftNode[] nodes = new RaftNode[3];
        List<String> ids = List.of("h1", "h2", "h3");
        for (int i = 0; i < 3; i++) {
            nodes[i] = cluster.add(cluster.builder(ids.get(i), MEMBERS, new InMemoryTransportSimulatorFixture(ids.get(i)),
                    new QraftStateStore(), RaftNodeMode.volatileMode()));
        }
        ManualRaftClusterFixture.startAll(nodes);
        return nodes;
    }

    private static ServerHealth of(ClusterHealth health, String name) {
        return health.servers().stream().filter(server -> server.name().equals(name)).findFirst().orElseThrow();
    }

    private static ClusterHealth health(RaftNode leader) throws Exception {
        return leader.serverHealth().get(10, TimeUnit.SECONDS).orElseThrow();
    }

    @Test
    void theLeaderReportsEveryServerHealthyWhenAllAnswerAndHoldItsLog() throws Exception {
        RaftNode[] nodes = three();
        RaftNode leader = cluster.elect(nodes[0]);
        await(leader.submitCommand(KvCommand.set(KvScope.DEFAULT, "a", "1".getBytes(StandardCharsets.UTF_8), 0, null)));
        cluster.heartbeatUntil(leader, () -> {
            try {
                return health(leader).healthy();
            } catch (Exception notYet) {
                return false;
            }
        }, "every server answered and holds the leader's log");

        ClusterHealth health = health(leader);
        assertEquals(3, health.servers().size());
        assertEquals(1, health.failureTolerance(), "three voters survive the loss of one");
        ServerHealth self = of(health, "h1");
        assertTrue(self.leader());
        assertEquals(0, self.lastContactMillis());
        ServerHealth follower = of(health, "h2");
        assertFalse(follower.leader());
        assertTrue(follower.voter());
        assertTrue(follower.lastContactMillis() >= 0);
        assertEquals(leader.getLastLogIndex(), follower.lastIndex());
        assertEquals(leader.getCurrentTerm(), follower.lastTerm());

        assertEquals(Optional.empty(), nodes[1].serverHealth().get(10, TimeUnit.SECONDS), "a follower has no view");
    }

    @Test
    void aFollowerCutOffForAnElectionTimeoutIsUnhealthyAndLowersTheFailureTolerance() throws Exception {
        RaftNode[] nodes = three();
        RaftNode leader = cluster.elect(nodes[0]);
        cluster.heartbeatUntil(leader, () -> {
            try {
                return health(leader).healthy();
            } catch (Exception notYet) {
                return false;
            }
        }, "every server answered");

        // The leader logs h3 as unreachable once the partition refuses its AppendEntries.
        IntentionalErrorsHelper.expect(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE);
        InMemoryTransportSimulatorFixture.createPartition(Set.of("h1", "h2"), Set.of("h3"));
        cluster.heartbeatUntil(leader, () -> {
            try {
                return !of(health(leader), "h3").healthy();
            } catch (Exception notYet) {
                return false;
            }
        }, "h3 has not answered for an election timeout");

        ClusterHealth health = health(leader);
        assertFalse(health.healthy());
        assertEquals(0, health.failureTolerance(), "two healthy voters of three survive no further loss");
        assertTrue(of(health, "h2").healthy());
    }
}

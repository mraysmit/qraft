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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the ReadIndex barrier a consistent read waits on (consistency list, decision 3, Phase 1): a leader completes it
 * once a quorum has answered a heartbeat sent after the read began and the read's index is applied; a follower, and a
 * leader that loses its quorum, do not.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class RaftReadBarrierTest {
    private static final Set<String> MEMBERS = Set.of("r1", "r2", "r3");

    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private final QraftStateStore[] stores = {new QraftStateStore(), new QraftStateStore(), new QraftStateStore()};

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
        List<String> ids = List.of("r1", "r2", "r3");
        for (int i = 0; i < 3; i++) {
            nodes[i] = cluster.add(cluster.builder(ids.get(i), MEMBERS, new InMemoryTransportSimulatorFixture(ids.get(i)),
                    stores[i], RaftNodeMode.volatileMode()));
        }
        ManualRaftClusterFixture.startAll(nodes);
        return nodes;
    }

    private static KvCommand write(String key) {
        return KvCommand.set(KvScope.DEFAULT, key, key.getBytes(StandardCharsets.UTF_8), 0, null);
    }

    @Test
    void aSoleServerCompletesAtOnceAtItsAppliedIndex() throws Exception {
        RaftNode sole = cluster.add(cluster.builder("r1", Set.of("r1"), new InMemoryTransportSimulatorFixture("r1"),
                stores[0], RaftNodeMode.volatileMode()));
        await(sole.start());
        cluster.elect(sole);
        await(sole.submitCommand(write("a")));

        long readIndex = sole.readBarrier().get(10, TimeUnit.SECONDS);

        assertTrue(readIndex >= stores[0].getKv().get(KvScope.DEFAULT, "a").orElseThrow().modifyIndex());
        assertTrue(sole.getLastApplied() >= readIndex);
    }

    @Test
    void aLeaderCompletesOnceAQuorumAnswersAndItsReadIndexIsApplied() throws Exception {
        RaftNode[] nodes = three();
        RaftNode leader = cluster.elect(nodes[0]);
        await(leader.submitCommand(write("b")));
        long committed = leader.getCommitIndex();

        CompletableFuture<Long> barrier = leader.readBarrier();
        cluster.settle();

        long readIndex = barrier.get(10, TimeUnit.SECONDS);
        assertTrue(readIndex >= committed, "everything committed when the read began");
        assertTrue(leader.getLastApplied() >= readIndex);
        assertTrue(stores[0].getKv().get(KvScope.DEFAULT, "b").isPresent());
    }

    @Test
    void aFollowerRefusesTheBarrier() throws Exception {
        RaftNode[] nodes = three();
        cluster.elect(nodes[0]);

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> nodes[1].readBarrier().get(10, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, refused.getCause());
    }

    @Test
    void aLeaderCutOffFromItsQuorumNeverCompletesAndFailsWhenItStepsDown() throws Exception {
        IntentionalErrorsHelper.expect(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE);
        RaftNode[] nodes = three();
        RaftNode leader = cluster.elect(nodes[0]);
        InMemoryTransportSimulatorFixture.createPartition(Set.of("r1"), Set.of("r2", "r3"));

        CompletableFuture<Long> barrier = leader.readBarrier();
        cluster.settle();
        assertFalse(barrier.isDone(), "no quorum has answered");

        cluster.heartbeatUntilSteppedDown(leader);
        ExecutionException failed = assertThrows(ExecutionException.class, () -> barrier.get(10, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failed.getCause());
    }
}

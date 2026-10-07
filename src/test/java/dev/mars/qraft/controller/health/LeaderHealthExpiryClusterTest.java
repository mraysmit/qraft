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

package dev.mars.qraft.controller.health;

import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;

import dev.mars.qraft.catalog.ServiceKey;
import dev.mars.qraft.catalog.HealthCheckState;
import dev.mars.qraft.catalog.HealthObservation;
import dev.mars.qraft.catalog.ServiceCheckId;
import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstance;
import dev.mars.qraft.controller.raft.InMemoryTransportSimulatorFixture;
import dev.mars.qraft.controller.raft.ManualRaftClusterFixture;
import dev.mars.qraft.controller.raft.RaftNode;
import dev.mars.qraft.controller.raft.RaftNodeMode;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.CatalogCommand;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.controller.state.RaftCommand;
import dev.mars.qraft.controller.state.RaftCommandResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-node tests with real Raft nodes and replicated state machines, in which expiry time is driven
 * by {@link ManualExpiryTimeHelper}: followers never expire state locally, an isolated former leader steps
 * down for lost quorum without proposing, a replacement leader with a skewed clock grants a full TTL before expiring, a
 * renewal racing an expiry converges to the renewal on every replica, and automatic deregistration is
 * replicated.
 *
 * <p>Elections and heartbeats fire only when a test fires them through {@link ManualRaftClusterFixture}: node a is
 * elected first, and no other election happens unless a test causes one, so the proposal counts are exact.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 2.0
 */
class LeaderHealthExpiryClusterTest {
    private static final Instant START = Instant.parse("2026-09-26T12:00:00Z");
    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private static final ServiceInstance WEB = new ServiceInstance("web", "web", "agent-1", "127.0.0.1", 8080,
            List.of(), Map.of(), ServiceHealth.UNKNOWN, "default", "default", "", "", true);
    private static final ServiceCheckId CHECK = new ServiceCheckId(WEB.identity(), "ttl");

    private final ManualExpiryTimeHelper time = new ManualExpiryTimeHelper(START);
    private final Map<String, RaftNode> nodes = new LinkedHashMap<>();
    private final Map<String, QraftStateStore> stores = new LinkedHashMap<>();
    private final Map<String, AtomicInteger> proposals = new LinkedHashMap<>();
    private final List<LeaderHealthExpiry> expiries = new ArrayList<>();
    private JavaRuntime runtime;
    private ManualRaftClusterFixture cluster;

    @AfterEach
    void stopCluster() throws Exception {
        expiries.forEach(LeaderHealthExpiry::close);
        InMemoryTransportSimulatorFixture.healPartitions();
        try {
            if (cluster != null) cluster.close();
        } finally {
            if (runtime != null) runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
        }
    }

    @Test
    void onlyTheLeaderProposesAndEveryReplicaConvergesOnTheCommittedExpiry() throws Exception {
        String leader = startCluster("a", "b", "c");
        attachExpiry(Map.of());
        submit(leader, CatalogCommand.register(WEB, List.of("ttl")));
        submit(leader, CatalogCommand.observe(observation(1, 0), time.instant()));
        replicateUntil(leader, () -> everyReplica(state -> state.isPresent() && !state.get().expired()));

        time.advance(Duration.ofSeconds(29));
        assertTrue(everyReplica(state -> state.isPresent() && !state.get().expired()));
        assertEquals(0, proposals.get(leader).get(), "nothing is due before the TTL has passed");
        time.advance(Duration.ofSeconds(1));

        replicateUntil(leader, () -> everyReplica(state -> state.isPresent() && state.get().expired()));
        assertEquals(1, proposals.get(leader).get());
        nodes.keySet().stream().filter(id -> !id.equals(leader))
                .forEach(id -> assertEquals(0, proposals.get(id).get(), "follower " + id + " proposed"));
        assertEquals(ServiceHealth.CRITICAL, stores.get(leader).getServiceCatalog().instances(ServiceKey.inDefaultScope("web")).getFirst().health());
    }

    @Test
    void aReplacementLeaderWithASkewedClockGrantsGraceAndConvergesOnOneResult() throws Exception {
        IntentionalErrorsHelper.expect(dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE);
        String oldLeader = startCluster("a", "b", "c");
        Map<String, Duration> skew = new LinkedHashMap<>();
        nodes.keySet().stream().filter(id -> !id.equals(oldLeader)).forEach(id -> skew.put(id, Duration.ofHours(1)));
        attachExpiry(skew);
        submit(oldLeader, CatalogCommand.register(WEB, List.of("ttl")));
        submit(oldLeader, CatalogCommand.observe(observation(1, 60_000), time.instant()));
        replicateUntil(oldLeader, () -> everyReplica(Optional::isPresent));
        time.advance(Duration.ofSeconds(20));

        Set<String> majority = new LinkedHashSet<>(nodes.keySet());
        majority.remove(oldLeader);
        InMemoryTransportSimulatorFixture.createPartition(Set.of(oldLeader), majority);
        String newLeader = majority.iterator().next();
        cluster.elect(nodes.get(newLeader));
        assertNotEquals(oldLeader, newLeader);
        cluster.heartbeatUntilSteppedDown(nodes.get(oldLeader));

        time.advance(Duration.ofSeconds(15));
        // Evaluation and proposal run synchronously inside advance(), so an absent proposal is exact:
        // nothing was proposed, so nothing can commit, without waiting for time to pass.
        assertEquals(0, majority.stream().mapToInt(id -> proposals.get(id).get()).sum(),
                "the new leader is still inside its grace period despite its clock running an hour ahead");
        assertTrue(majority.stream().allMatch(id -> !check(id).orElseThrow().expired()));
        assertEquals(0, proposals.get(oldLeader).get(),
                "the isolated former leader stepped down for lost quorum before anything fell due");

        time.advance(Duration.ofSeconds(15));
        assertEquals(1, proposals.get(newLeader).get(), "the new leader proposes the expiry once its grace ends");
        replicateUntil(newLeader, () -> majority.stream().allMatch(id -> check(id).orElseThrow().expired()));

        InMemoryTransportSimulatorFixture.healPartitions();
        replicateUntil(newLeader, () -> check(oldLeader).map(HealthCheckState::expired).orElse(false));
        assertFalse(nodes.get(oldLeader).isLeader());
        assertEquals(1, nodes.values().stream().filter(RaftNode::isLeader).count());
        assertEquals(1, stores.values().stream().map(store -> store.findHealthCheck(CHECK).orElseThrow())
                .distinct().count(), "every replica holds the same committed check state");

        time.advance(Duration.ofSeconds(60));
        replicateUntil(newLeader, () -> stores.values().stream().allMatch(store ->
                store.getServiceCatalog().instances(ServiceKey.inDefaultScope("web")).isEmpty() && store.healthChecks().isEmpty()));
    }

    @Test
    void aRenewalRacingAnExpiryConvergesToTheRenewalOnEveryReplica() throws Exception {
        String leader = startCluster("a", "b", "c");
        submit(leader, CatalogCommand.register(WEB, List.of("ttl")));
        submit(leader, CatalogCommand.observe(observation(1, 60_000), START));
        replicateUntil(leader, () -> everyReplica(Optional::isPresent));

        CompletableFuture<RaftCommandResult<?>> expiry =
                propose(leader, CatalogCommand.expire(CHECK, 1, START.plusSeconds(30), false));
        CompletableFuture<RaftCommandResult<?>> renewal =
                propose(leader, CatalogCommand.observe(observation(2, 60_000), START.plusSeconds(30)));
        CompletableFuture.allOf(expiry, renewal).get(10, TimeUnit.SECONDS);

        replicateUntil(leader, () -> everyReplica(state -> state.map(current ->
                current.observation().sequenceNumber() == 2 && !current.expired()).orElse(false)));
        assertTrue(stores.values().stream().allMatch(store -> store.getServiceCatalog().instances(ServiceKey.inDefaultScope("web")).size() == 1),
                "in either commit order the renewal leaves the service registered and not expired");
    }

    /** Starts the members on manual timers and elects the first; every member follows it on return. */
    private String startCluster(String... nodeIds) throws Exception {
        InMemoryTransportSimulatorFixture.clearAllTransports();
        runtime = JavaRuntime.create();
        cluster = new ManualRaftClusterFixture(runtime);
        Set<String> members = new LinkedHashSet<>(List.of(nodeIds));
        for (String nodeId : nodeIds) {
            QraftStateStore store = new QraftStateStore();
            nodes.put(nodeId, cluster.add(cluster.builder(nodeId, members, new InMemoryTransportSimulatorFixture(nodeId),
                    store, RaftNodeMode.volatileMode())));
            stores.put(nodeId, store);
            proposals.put(nodeId, new AtomicInteger());
        }
        ManualRaftClusterFixture.startAll(nodes.values().toArray(RaftNode[]::new));
        String leader = nodeIds[0];
        cluster.elect(nodes.get(leader));
        replicateUntil(leader, () -> nodes.values().stream().allMatch(node -> leader.equals(node.getLeaderId())));
        return leader;
    }

    private void attachExpiry(Map<String, Duration> clockSkew) {
        nodes.forEach((id, node) -> {
            Clock clock = Clock.offset(time, clockSkew.getOrDefault(id, Duration.ZERO));
            expiries.add(LeaderHealthExpiry.attach(node, stores.get(id)::healthChecks, command -> {
                proposals.get(id).incrementAndGet();
                return propose(id, command);
            }, time, clock, INTERVAL));
        });
    }

    private CompletableFuture<RaftCommandResult<?>> propose(String nodeId, RaftCommand command) {
        return nodes.get(nodeId).submitCommand(command).toCompletionStage().toCompletableFuture();
    }

    private void submit(String nodeId, RaftCommand command) throws Exception {
        propose(nodeId, command).get(10, TimeUnit.SECONDS);
    }

    private Optional<HealthCheckState> check(String nodeId) {
        return stores.get(nodeId).findHealthCheck(CHECK);
    }

    private boolean everyReplica(java.util.function.Predicate<Optional<HealthCheckState>> condition) {
        return nodes.keySet().stream().allMatch(id -> condition.test(check(id)));
    }

    private static HealthObservation observation(long sequence, long deregisterAfterMillis) {
        return new HealthObservation(CHECK, ServiceHealth.PASSING, sequence, START, 30_000, true, "",
                deregisterAfterMillis);
    }

    /** Fires the leader's heartbeat, which carries its commits to the followers, until the condition holds. */
    private void replicateUntil(String leaderId, BooleanSupplier condition) throws InterruptedException {
        cluster.heartbeatUntil(nodes.get(leaderId), condition, "the replicas converge");
    }
}

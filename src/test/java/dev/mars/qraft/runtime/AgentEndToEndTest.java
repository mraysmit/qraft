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

package dev.mars.qraft.runtime;

import dev.mars.qraft.controller.raft.PeerlessTransportFixture;
import dev.mars.qraft.catalog.ServiceKey;
import dev.mars.qraft.agent.QraftAgent;
import dev.mars.qraft.agent.config.AgentConfiguration;
import dev.mars.qraft.catalog.ServiceDefinition;
import dev.mars.qraft.controller.http.HttpApiServer;
import dev.mars.qraft.controller.raft.InMemoryTransportSimulatorFixture;
import dev.mars.qraft.controller.raft.ManualRaftClusterFixture;
import dev.mars.qraft.controller.raft.RaftNode;
import dev.mars.qraft.controller.raft.RaftNodeMode;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of {@link QraftAgent} against in-process controllers: reconciliation after
 * restart, shared local service IDs, and follower seeds.
 *
 * <p>The controllers' Raft timers are manual ({@link ManualRaftClusterFixture}): the first node is elected by the
 * test, and a cluster's followers learn commits when a wait fires the leader's heartbeat.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 2.0
 */
class AgentEndToEndTest {
    private final List<QraftAgent> agents = new ArrayList<>();
    private ControllerGroupFixture controllers;

    @AfterEach
    void closeResources() throws Exception {
        CleanupHelper cleanup = new CleanupHelper();
        for (QraftAgent agent : agents.reversed()) {
            cleanup.run(() -> agent.shutdown().get(10, TimeUnit.SECONDS));
        }
        cleanup.run(() -> { if (controllers != null) controllers.close(); })
                .run(InMemoryTransportSimulatorFixture::clearAllTransports)
                .rethrow();
    }

    @Test
    void agentReconcilesTwoServicesAfterControllerRestartAndDeregistersBoth() throws Exception {
        controllers = ControllerGroupFixture.single(0);
        int controllerPort = controllers.port(0);
        QraftAgent agent = agent("agent-a", controllers.endpoints(), List.of(
                service("web", "web", 8080), service("api", "api", 8081)), 1_000);

        assertTrue(agent.start().get(10, TimeUnit.SECONDS));
        waitUntil(() -> agent.healthService().isReady()
                && controllers.store(0).getServiceCatalog().instances().size() == 2);

        controllers.close();
        controllers = null;
        waitUntil(() -> !agent.healthService().isReady());

        // The agent knows the controller by URL, so the restarted controller must bind the same port.
        controllers = ControllerGroupFixture.single(controllerPort);
        waitUntil(() -> agent.healthService().isReady()
                && controllers.store(0).getServiceCatalog().instances().size() == 2);

        assertTrue(agent.shutdown().get(10, TimeUnit.SECONDS));
        waitUntil(() -> controllers.store(0).getServiceCatalog().instances().isEmpty()
                && controllers.store(0).findAgent("agent-a").isEmpty());
    }

    @Test
    void twoAgentsCanRegisterTheSameLocalServiceId() throws Exception {
        controllers = ControllerGroupFixture.single(0);
        ServiceDefinition web = service("web", "web", 8080);
        QraftAgent first = agent("agent-a", controllers.endpoints(), List.of(web), 2_000);
        QraftAgent second = agent("agent-b", controllers.endpoints(), List.of(web), 2_000);

        assertTrue(first.start().get(10, TimeUnit.SECONDS));
        assertTrue(second.start().get(10, TimeUnit.SECONDS));
        waitUntil(() -> first.healthService().isReady() && second.healthService().isReady()
                && controllers.store(0).getServiceCatalog().instances(ServiceKey.inDefaultScope("web")).size() == 2);

        var instances = controllers.store(0).getServiceCatalog().instances(ServiceKey.inDefaultScope("web"));
        assertEquals(Set.of("agent-a", "agent-b"),
                instances.stream().map(instance -> instance.nodeId()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("web"),
                instances.stream().map(instance -> instance.serviceId()).collect(java.util.stream.Collectors.toSet()));

        assertTrue(first.shutdown().get(10, TimeUnit.SECONDS));
        assertTrue(second.shutdown().get(10, TimeUnit.SECONDS));
        waitUntil(() -> controllers.store(0).getServiceCatalog().instances(ServiceKey.inDefaultScope("web")).isEmpty());
    }

    @Test
    void threeNodeClusterAcceptsAgentWhenFirstSeedIsAFollower() throws Exception {
        controllers = ControllerGroupFixture.cluster("node-a", "node-b", "node-c");
        int leader = controllers.leaderIndex();
        int follower = leader == 0 ? 1 : 0;
        List<URI> seeds = new ArrayList<>();
        seeds.add(controllers.endpoint(follower));
        for (int index = 0; index < controllers.size(); index++) {
            if (index != follower) seeds.add(controllers.endpoint(index));
        }
        assertNotEquals(leader, follower);

        QraftAgent agent = agent("cluster-agent", seeds,
                List.of(service("web", "web", 8080)), 2_000);
        assertTrue(agent.start().get(10, TimeUnit.SECONDS));
        controllers.replicateUntil(() -> agent.healthService().isReady()
                && controllers.stores().stream().allMatch(store ->
                        store.getServiceCatalog().instances(ServiceKey.inDefaultScope("web")).size() == 1));

        assertEquals("cluster-agent", controllers.store(leader).getServiceCatalog()
                .instances(ServiceKey.inDefaultScope("web")).getFirst().nodeId());
        assertTrue(agent.shutdown().get(10, TimeUnit.SECONDS));
        controllers.replicateUntil(() -> controllers.stores().stream().allMatch(store ->
                store.getServiceCatalog().instances(ServiceKey.inDefaultScope("web")).isEmpty()));
    }

    private QraftAgent agent(String id, List<URI> endpoints,
                             List<ServiceDefinition> services, long freshnessMs) throws Exception {
        QraftAgent agent = new QraftAgent(AgentConfiguration.builder()
                .agentId(id).hostname(id + "-host").address("127.0.0.1")
                .agentPort(0).controllerUrls(endpoints)
                .heartbeatInterval(40).requestTimeoutMs(5_000)
                .registrationRetryMinMs(20).registrationRetryMaxMs(100)
                .contactFreshnessMs(freshnessMs).shutdownTimeoutMs(2_000)
                .services(services).build());
        agents.add(agent);
        return agent;
    }

    private static ServiceDefinition service(String id, String name, int port) {
        return new ServiceDefinition(id, name, "127.0.0.1", port,
                List.of("acceptance"), Map.of("suite", "runtime"), true);
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "condition was not met before the deadline");
    }

    /** Test cluster fixture that owns controllers and their resources for agent end-to-end tests. */
    private static final class ControllerGroupFixture implements AutoCloseable {
        private final JavaRuntime runtime;
        private final ManualRaftClusterFixture raft;
        private final List<RaftNode> nodes;
        private final List<QraftStateStore> stores;
        private final List<HttpApiServer> servers;
        private boolean closed;

        private ControllerGroupFixture(JavaRuntime runtime, ManualRaftClusterFixture raft, List<RaftNode> nodes,
                                List<QraftStateStore> stores, List<HttpApiServer> servers) {
            this.runtime = runtime;
            this.raft = raft;
            this.nodes = nodes;
            this.stores = stores;
            this.servers = servers;
        }

        static ControllerGroupFixture single(int port) throws Exception {
            JavaRuntime runtime = JavaRuntime.create();
            ManualRaftClusterFixture raft = new ManualRaftClusterFixture(runtime);
            QraftStateStore store = new QraftStateStore();
            RaftNode node = raft.add(raft.builder("single", Set.of("single"), new PeerlessTransportFixture(), store,
                    RaftNodeMode.volatileMode()));
            ManualRaftClusterFixture.startAll(node);
            raft.elect(node);
            HttpApiServer server = new HttpApiServer(port, node, store);
            server.start().get(10, TimeUnit.SECONDS);
            return new ControllerGroupFixture(runtime, raft, List.of(node), List.of(store), List.of(server));
        }

        /** Starts the members, elects the first, and returns once every member follows it. */
        static ControllerGroupFixture cluster(String... nodeIds) throws Exception {
            InMemoryTransportSimulatorFixture.clearAllTransports();
            JavaRuntime runtime = JavaRuntime.create();
            ManualRaftClusterFixture raft = new ManualRaftClusterFixture(runtime);
            Set<String> members = new LinkedHashSet<>(List.of(nodeIds));
            List<RaftNode> nodes = new ArrayList<>();
            List<QraftStateStore> stores = new ArrayList<>();
            for (String nodeId : nodeIds) {
                QraftStateStore store = new QraftStateStore();
                stores.add(store);
                nodes.add(raft.add(raft.builder(nodeId, members, new InMemoryTransportSimulatorFixture(nodeId), store,
                        RaftNodeMode.volatileMode())));
            }
            ManualRaftClusterFixture.startAll(nodes.toArray(RaftNode[]::new));
            RaftNode leader = raft.elect(nodes.getFirst());
            raft.heartbeatUntil(leader, () -> nodes.stream().allMatch(node -> leader.getNodeId().equals(node.getLeaderId())),
                    "every member follows " + leader.getNodeId());
            List<HttpApiServer> servers = new ArrayList<>();
            for (int index = 0; index < nodes.size(); index++) {
                HttpApiServer server = new HttpApiServer(0, nodes.get(index), stores.get(index));
                server.start().get(10, TimeUnit.SECONDS);
                servers.add(server);
            }
            return new ControllerGroupFixture(runtime, raft, List.copyOf(nodes), List.copyOf(stores), List.copyOf(servers));
        }

        /** Fires the leader's heartbeat, which carries its commits to the followers, until the condition holds. */
        void replicateUntil(BooleanSupplier condition) throws InterruptedException {
            raft.heartbeatUntil(nodes.get(leaderIndex()), condition, "the controllers converge");
        }

        List<URI> endpoints() { return List.of(endpoint(0)); }
        URI endpoint(int index) { return URI.create("http://127.0.0.1:" + port(index)); }
        int port(int index) { return servers.get(index).port(); }
        int size() { return nodes.size(); }
        QraftStateStore store(int index) { return stores.get(index); }
        List<QraftStateStore> stores() { return stores; }
        int leaderIndex() {
            for (int index = 0; index < nodes.size(); index++) if (nodes.get(index).isLeader()) return index;
            throw new IllegalStateException("cluster has no leader");
        }

        @Override
        public void close() throws Exception {
            if (closed) return;
            closed = true;
            CleanupHelper cleanup = new CleanupHelper();
            for (HttpApiServer server : servers.reversed()) cleanup.run(server::close);
            cleanup.run(raft::close)
                    .run(() -> runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS))
                    .rethrow();
        }
    }
}

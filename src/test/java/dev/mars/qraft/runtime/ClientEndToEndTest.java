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

import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.state.catalog.ServiceKey;
import dev.mars.qraft.client.QraftClient;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.common.ServiceDefinition;
import dev.mars.qraft.server.http.HttpApiServer;
import dev.mars.qraft.raft.InMemoryTransportSimulatorFixture;
import dev.mars.qraft.raft.ManualRaftClusterFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.common.async.JavaRuntime;
import dev.mars.qraft.state.QraftStateStore;
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
 * End-to-end tests of {@link QraftClient} against in-process servers: reconciliation after
 * restart, shared local service IDs, and follower seeds.
 *
 * <p>The servers' Raft timers are manual ({@link ManualRaftClusterFixture}): the first node is elected by the
 * test, and a cluster's followers learn commits when a wait fires the leader's heartbeat.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 2.0
 */
class ClientEndToEndTest {
    private final List<QraftClient> clients = new ArrayList<>();
    private ServerGroupFixture servers;

    @AfterEach
    void closeResources() throws Exception {
        CleanupHelper cleanup = new CleanupHelper();
        for (QraftClient client : clients.reversed()) {
            cleanup.run(() -> client.shutdown().get(10, TimeUnit.SECONDS));
        }
        cleanup.run(() -> { if (servers != null) servers.close(); })
                .run(InMemoryTransportSimulatorFixture::clearAllTransports)
                .rethrow();
    }

    @Test
    void clientReconcilesTwoServicesAfterServerRestartAndDeregistersBoth() throws Exception {
        servers = ServerGroupFixture.single(0);
        int serverPort = servers.port(0);
        QraftClient client = client("client-a", servers.endpoints(), List.of(
                service("web", "web", 8080), service("api", "api", 8081)), 1_000);

        assertTrue(client.start().get(10, TimeUnit.SECONDS));
        waitUntil(() -> client.healthService().isReady()
                && servers.store(0).getServiceCatalog().instances().size() == 2);

        servers.close();
        servers = null;
        waitUntil(() -> !client.healthService().isReady());

        // The client knows the server by URL, so the restarted server must bind the same port.
        servers = ServerGroupFixture.single(serverPort);
        waitUntil(() -> client.healthService().isReady()
                && servers.store(0).getServiceCatalog().instances().size() == 2);

        assertTrue(client.shutdown().get(10, TimeUnit.SECONDS));
        waitUntil(() -> servers.store(0).getServiceCatalog().instances().isEmpty()
                && servers.store(0).findNode("client-a").isEmpty());
    }

    @Test
    void twoClientsCanRegisterTheSameLocalServiceId() throws Exception {
        servers = ServerGroupFixture.single(0);
        ServiceDefinition web = service("web", "web", 8080);
        QraftClient first = client("client-a", servers.endpoints(), List.of(web), 2_000);
        QraftClient second = client("client-b", servers.endpoints(), List.of(web), 2_000);

        assertTrue(first.start().get(10, TimeUnit.SECONDS));
        assertTrue(second.start().get(10, TimeUnit.SECONDS));
        waitUntil(() -> first.healthService().isReady() && second.healthService().isReady()
                && servers.store(0).getServiceCatalog().instances(ServiceKey.inDefaultScope("web")).size() == 2);

        var instances = servers.store(0).getServiceCatalog().instances(ServiceKey.inDefaultScope("web"));
        assertEquals(Set.of("client-a", "client-b"),
                instances.stream().map(instance -> instance.nodeId()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("web"),
                instances.stream().map(instance -> instance.serviceId()).collect(java.util.stream.Collectors.toSet()));

        assertTrue(first.shutdown().get(10, TimeUnit.SECONDS));
        assertTrue(second.shutdown().get(10, TimeUnit.SECONDS));
        waitUntil(() -> servers.store(0).getServiceCatalog().instances(ServiceKey.inDefaultScope("web")).isEmpty());
    }

    @Test
    void threeNodeClusterAcceptsClientWhenFirstSeedIsAFollower() throws Exception {
        servers = ServerGroupFixture.cluster("node-a", "node-b", "node-c");
        int leader = servers.leaderIndex();
        int follower = leader == 0 ? 1 : 0;
        List<URI> seeds = new ArrayList<>();
        seeds.add(servers.endpoint(follower));
        for (int index = 0; index < servers.size(); index++) {
            if (index != follower) seeds.add(servers.endpoint(index));
        }
        assertNotEquals(leader, follower);

        QraftClient client = client("cluster-client", seeds,
                List.of(service("web", "web", 8080)), 2_000);
        assertTrue(client.start().get(10, TimeUnit.SECONDS));
        servers.replicateUntil(() -> client.healthService().isReady()
                && servers.stores().stream().allMatch(store ->
                        store.getServiceCatalog().instances(ServiceKey.inDefaultScope("web")).size() == 1));

        assertEquals("cluster-client", servers.store(leader).getServiceCatalog()
                .instances(ServiceKey.inDefaultScope("web")).getFirst().nodeId());
        assertTrue(client.shutdown().get(10, TimeUnit.SECONDS));
        servers.replicateUntil(() -> servers.stores().stream().allMatch(store ->
                store.getServiceCatalog().instances(ServiceKey.inDefaultScope("web")).isEmpty()));
    }

    private QraftClient client(String id, List<URI> endpoints,
                             List<ServiceDefinition> services, long freshnessMs) throws Exception {
        QraftClient client = new QraftClient(ClientConfiguration.builder()
                .clientId(id).address("127.0.0.1")
                .clientPort(0).serverUrls(endpoints)
                .heartbeatInterval(40).requestTimeoutMs(5_000)
                .registrationRetryMinMs(20).registrationRetryMaxMs(100)
                .contactFreshnessMs(freshnessMs).shutdownTimeoutMs(2_000)
                .services(services).build());
        clients.add(client);
        return client;
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

    /** Test cluster fixture that owns servers and their resources for client end-to-end tests. */
    private static final class ServerGroupFixture implements AutoCloseable {
        private final JavaRuntime runtime;
        private final ManualRaftClusterFixture raft;
        private final List<RaftNode> nodes;
        private final List<QraftStateStore> stores;
        private final List<HttpApiServer> servers;
        private boolean closed;

        private ServerGroupFixture(JavaRuntime runtime, ManualRaftClusterFixture raft, List<RaftNode> nodes,
                                List<QraftStateStore> stores, List<HttpApiServer> servers) {
            this.runtime = runtime;
            this.raft = raft;
            this.nodes = nodes;
            this.stores = stores;
            this.servers = servers;
        }

        static ServerGroupFixture single(int port) throws Exception {
            JavaRuntime runtime = JavaRuntime.create();
            ManualRaftClusterFixture raft = new ManualRaftClusterFixture(runtime);
            QraftStateStore store = new QraftStateStore();
            RaftNode node = raft.add(raft.builder("single", Set.of("single"), new PeerlessTransportFixture(), store,
                    RaftNodeMode.volatileMode()));
            ManualRaftClusterFixture.startAll(node);
            raft.elect(node);
            HttpApiServer server = new HttpApiServer(port, node, store);
            server.start().get(10, TimeUnit.SECONDS);
            return new ServerGroupFixture(runtime, raft, List.of(node), List.of(store), List.of(server));
        }

        /** Starts the members, elects the first, and returns once every member follows it. */
        static ServerGroupFixture cluster(String... nodeIds) throws Exception {
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
            return new ServerGroupFixture(runtime, raft, List.copyOf(nodes), List.copyOf(stores), List.copyOf(servers));
        }

        /** Fires the leader's heartbeat, which carries its commits to the followers, until the condition holds. */
        void replicateUntil(BooleanSupplier condition) throws InterruptedException {
            raft.heartbeatUntil(nodes.get(leaderIndex()), condition, "the servers converge");
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

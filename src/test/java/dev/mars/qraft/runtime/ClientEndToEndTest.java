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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.testing.fault.IntentionalErrorFixture;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
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
 * restart, shared local service IDs, follower seeds, and applications that talk only to their local client
 * over real HTTP, each client standing for one host.
 *
 * <p>The servers' Raft timers are manual ({@link ManualRaftClusterFixture}): the first node is elected by the
 * test, and a cluster's followers learn commits when a wait fires the leader's heartbeat.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 2.0
 */
class ClientEndToEndTest {
    @TempDir
    Path dataRoot;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORDERS = """
            {"id":"orders-1","name":"orders","port":9000,"checks":[{"id":"app","type":"ttl","ttlMs":60000}]}
            """;

    private final List<QraftClient> clients = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();
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
        waitUntil(() -> client.localApi().isReady()
                && servers.store(0).getServiceCatalog().instances().size() == 2);

        servers.close();
        servers = null;
        waitUntil(() -> !client.localApi().isReady());

        // The client knows the server by URL, so the restarted server must bind the same port.
        servers = ServerGroupFixture.single(serverPort);
        waitUntil(() -> client.localApi().isReady()
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
        waitUntil(() -> first.localApi().isReady() && second.localApi().isReady()
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
        servers.replicateUntil(() -> client.localApi().isReady()
                && servers.stores().stream().allMatch(store ->
                        store.getServiceCatalog().instances(ServiceKey.inDefaultScope("web")).size() == 1));

        assertEquals("cluster-client", servers.store(leader).getServiceCatalog()
                .instances(ServiceKey.inDefaultScope("web")).getFirst().nodeId());
        assertTrue(client.shutdown().get(10, TimeUnit.SECONDS));
        servers.replicateUntil(() -> servers.stores().stream().allMatch(store ->
                store.getServiceCatalog().instances(ServiceKey.inDefaultScope("web")).isEmpty()));
    }

    @Test
    void anApplicationRegistersAndReportsPassingThroughItsClientAndIsDiscoveredThroughAnotherHostsClient()
            throws Exception {
        servers = ServerGroupFixture.single(0);
        QraftClient hostA = startedClient("host-a", servers.endpoints());
        QraftClient hostB = startedClient("host-b", servers.endpoints());

        assertEquals(200, local(hostA, "PUT", "/v1/client/service/register", ORDERS).statusCode());
        waitUntil(() -> serviceIds(hostB, "/v1/health/service/orders").equals(List.of("orders-1")));
        assertEquals(List.of(), serviceIds(hostB, "/v1/health/service/orders?passing"),
                "a TTL check that has not been told anything is not passing");

        assertEquals(200, local(hostA, "PUT", "/v1/client/check/pass/app?note=ready", null).statusCode());

        waitUntil(() -> serviceIds(hostB, "/v1/health/service/orders?passing").equals(List.of("orders-1")));
        JsonNode entry = readJson(hostB, "/v1/health/service/orders?passing").get(0);
        assertEquals("host-a", entry.path("service").path("nodeId").textValue(), "the instance is on the other host");
        assertEquals("127.0.0.1", entry.path("service").path("address").textValue(), "its client's address, by default");
        assertEquals(9000, entry.path("service").path("port").intValue());
        JsonNode check = entry.path("checks").get(0);
        assertEquals("app", check.path("checkId").textValue());
        assertEquals("PASSING", check.path("status").textValue());
        assertEquals("ready", check.path("output").textValue());
    }

    @Test
    void anApplicationDeregistersCleanlyAndStaysGoneAfterItsClientRestarts() throws Exception {
        servers = ServerGroupFixture.single(0);
        QraftClient hostA = startedClient("host-a", servers.endpoints());
        QraftClient hostB = startedClient("host-b", servers.endpoints());
        assertEquals(200, local(hostA, "PUT", "/v1/client/service/register", ORDERS).statusCode());
        assertEquals(200, local(hostA, "PUT", "/v1/client/check/pass/app", null).statusCode());
        waitUntil(() -> serviceIds(hostB, "/v1/health/service/orders?passing").equals(List.of("orders-1")));

        HttpResponse<String> deregistered = local(hostA, "PUT", "/v1/client/service/deregister/orders-1", null);

        assertEquals(200, deregistered.statusCode(), deregistered.body());
        assertTrue(JSON.readTree(deregistered.body()).path("deregistered").booleanValue());
        waitUntil(() -> serviceIds(hostB, "/v1/health/service/orders").isEmpty()
                && readJson(hostB, "/v1/catalog/service/orders").isEmpty()
                && !readJson(hostB, "/v1/catalog/services").has("orders"));
        assertTrue(servers.store(0).healthChecks().isEmpty(), "its check went with it and was not left to expire");
        assertTrue(readJson(hostA, "/v1/client/services").isEmpty());
        assertTrue(readJson(hostA, "/v1/client/checks").isEmpty());
        assertEquals(2, readJson(hostB, "/v1/client/members").size(), "the application left, and its host did not");

        assertTrue(hostA.shutdown().get(10, TimeUnit.SECONDS));
        QraftClient restarted = startedClient("host-a", servers.endpoints());
        waitUntil(() -> servers.store(0).findNode("host-a").isPresent());
        assertTrue(readJson(restarted, "/v1/client/services").isEmpty(), "a deregistered service is not restored");
        assertEquals(List.of(), serviceIds(hostB, "/v1/health/service/orders"));
    }

    @Test
    void registrationsMadeThroughTheLocalApiSurviveAClientRestart() throws Exception {
        servers = ServerGroupFixture.single(0);
        QraftClient hostA = startedClient("host-a", servers.endpoints());
        QraftClient hostB = startedClient("host-b", servers.endpoints());
        assertEquals(200, local(hostA, "PUT", "/v1/client/service/register", ORDERS).statusCode());
        assertEquals(200, local(hostA, "PUT", "/v1/client/check/pass/app", null).statusCode());
        waitUntil(() -> serviceIds(hostB, "/v1/health/service/orders?passing").equals(List.of("orders-1")));
        String nodeId = hostA.nodeId();

        assertTrue(hostA.shutdown().get(10, TimeUnit.SECONDS));
        waitUntil(() -> serviceIds(hostB, "/v1/health/service/orders").isEmpty());

        QraftClient restarted = startedClient("host-a", servers.endpoints());
        assertEquals(nodeId, restarted.nodeId());
        waitUntil(() -> serviceIds(hostB, "/v1/health/service/orders").equals(List.of("orders-1")));
        assertTrue(readJson(restarted, "/v1/client/services").has("orders-1"));
        assertTrue(readJson(restarted, "/v1/client/checks").has("app"));
        assertEquals(List.of(), serviceIds(hostB, "/v1/health/service/orders?passing"),
                "a TTL check's status is not kept across a restart, so the application tells it again");

        assertEquals(200, local(restarted, "PUT", "/v1/client/check/pass/app", null).statusCode());
        waitUntil(() -> serviceIds(hostB, "/v1/health/service/orders?passing").equals(List.of("orders-1")));
    }

    @Test
    void theLocalApiKeepsWorkingAcrossAServerLeaderChange() throws Exception {
        // The old leader is cut off from its peers below, so its calls to them fail.
        IntentionalErrorsHelper.expect(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE);
        servers = ServerGroupFixture.cluster("node-a", "node-b", "node-c");
        List<URI> seeds = List.of(servers.endpoint(0), servers.endpoint(1), servers.endpoint(2));
        QraftClient hostA = startedClient("host-a", seeds);
        QraftClient hostB = startedClient("host-b", seeds);
        assertEquals(200, local(hostA, "PUT", "/v1/client/service/register", ORDERS).statusCode());
        assertEquals(200, local(hostA, "PUT", "/v1/client/check/pass/app", null).statusCode());
        servers.replicateUntil(() -> serviceIds(hostB, "/v1/health/service/orders?passing").equals(List.of("orders-1")));
        int oldLeader = servers.leaderIndex();

        servers.failOver();

        assertNotEquals(oldLeader, servers.leaderIndex());
        assertEquals(200, local(hostA, "PUT", "/v1/client/service/register", """
                {"id":"orders-2","name":"orders","port":9001,"checks":[{"id":"app-2","type":"ttl","ttlMs":60000}]}
                """).statusCode());
        assertEquals(200, local(hostA, "PUT", "/v1/client/check/pass/app-2", null).statusCode());
        assertEquals(200, local(hostA, "PUT", "/v1/client/check/fail/app", null).statusCode());
        servers.replicateUntil(() -> serviceIds(hostB, "/v1/health/service/orders?passing").equals(List.of("orders-2"))
                && serviceIds(hostB, "/v1/health/service/orders").equals(List.of("orders-1", "orders-2")));
    }

    /** A client with no services of its own, started and registered, as a host before its applications arrive. */
    private QraftClient startedClient(String id, List<URI> endpoints) throws Exception {
        QraftClient client = client(id, endpoints, List.of(), 2_000);
        assertTrue(client.start().get(10, TimeUnit.SECONDS));
        return client;
    }

    /** What an application on the client's host does: a request to the local API on the loopback address. */
    private HttpResponse<String> local(QraftClient client, String method, String pathAndQuery, String body)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + client.localApi().port() + pathAndQuery))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode readJson(QraftClient client, String pathAndQuery) {
        try {
            HttpResponse<String> answer = local(client, "GET", pathAndQuery, null);
            assertEquals(200, answer.statusCode(), pathAndQuery + " " + answer.body());
            return JSON.readTree(answer.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException(pathAndQuery, failure);
        }
    }

    private List<String> serviceIds(QraftClient client, String pathAndQuery) {
        List<String> ids = new ArrayList<>();
        readJson(client, pathAndQuery).forEach(entry -> ids.add(entry.path("service").path("serviceId").textValue()));
        return ids;
    }

    private QraftClient client(String id, List<URI> endpoints,
                             List<ServiceDefinition> services, long freshnessMs) throws Exception {
        QraftClient client = new QraftClient(ClientConfiguration.builder()
                .nodeName(id).dataDirectory(dataRoot.resolve(id)).address("127.0.0.1")
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
        private final RaftExecutorsFixture runtime;
        private final ManualRaftClusterFixture raft;
        private final List<RaftNode> nodes;
        private final List<QraftStateStore> stores;
        private final List<HttpApiServer> servers;
        private final Set<HttpApiServer> stopped = new java.util.HashSet<>();
        private boolean closed;

        private ServerGroupFixture(RaftExecutorsFixture runtime, ManualRaftClusterFixture raft, List<RaftNode> nodes,
                                List<QraftStateStore> stores, List<HttpApiServer> servers) {
            this.runtime = runtime;
            this.raft = raft;
            this.nodes = nodes;
            this.stores = stores;
            this.servers = servers;
        }

        static ServerGroupFixture single(int port) throws Exception {
            RaftExecutorsFixture runtime = RaftExecutorsFixture.create();
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
            RaftExecutorsFixture runtime = RaftExecutorsFixture.create();
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

        /**
         * Cuts the leader off and stops its API, elects another member, and returns once the old leader has stepped
         * down and the remaining member follows the new one. To a client the old leader is then a seed that is down.
         */
        void failOver() throws Exception {
            int oldIndex = leaderIndex();
            RaftNode oldLeader = nodes.get(oldIndex);
            Set<String> majority = new LinkedHashSet<>();
            nodes.stream().filter(node -> node != oldLeader).forEach(node -> majority.add(node.getNodeId()));
            InMemoryTransportSimulatorFixture.createPartition(Set.of(oldLeader.getNodeId()), majority);
            HttpApiServer oldServer = servers.get(oldIndex);
            oldServer.close();
            stopped.add(oldServer);
            RaftNode newLeader = raft.elect(nodes.get((oldIndex + 1) % nodes.size()));
            raft.heartbeatUntilSteppedDown(oldLeader);
            raft.heartbeatUntil(newLeader, () -> nodes.stream().filter(node -> node != oldLeader)
                    .allMatch(node -> newLeader.getNodeId().equals(node.getLeaderId())), "the majority follows the new leader");
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
            for (HttpApiServer server : servers.reversed()) {
                if (!stopped.contains(server)) cleanup.run(server::close);
            }
            cleanup.run(raft::close)
                    .run(() -> runtime.close().get(10, TimeUnit.SECONDS))
                    .rethrow();
        }
    }
}

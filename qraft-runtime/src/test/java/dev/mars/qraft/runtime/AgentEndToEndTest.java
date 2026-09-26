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

import dev.mars.qraft.agent.QraftAgent;
import dev.mars.qraft.agent.config.AgentConfiguration;
import dev.mars.qraft.catalog.ServiceDefinition;
import dev.mars.qraft.controller.http.HttpApiServer;
import dev.mars.qraft.controller.raft.InMemoryTransportSimulator;
import dev.mars.qraft.controller.raft.RaftMessage;
import dev.mars.qraft.controller.raft.RaftNode;
import dev.mars.qraft.controller.raft.RaftNodeMode;
import dev.mars.qraft.controller.raft.RaftTransport;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of {@link QraftAgent} against in-process controllers: reconciliation after
 * restart, shared local service IDs, and follower seeds.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
class AgentEndToEndTest {
    private final List<QraftAgent> agents = new ArrayList<>();
    private ControllerGroup controllers;

    @AfterEach
    void closeResources() throws Exception {
        for (QraftAgent agent : agents.reversed()) {
            agent.shutdown().get(5, TimeUnit.SECONDS);
        }
        if (controllers != null) controllers.close();
        InMemoryTransportSimulator.clearAllTransports();
    }

    @Test
    void agentReconcilesTwoServicesAfterControllerRestartAndDeregistersBoth() throws Exception {
        int controllerPort = freePort();
        controllers = ControllerGroup.single(controllerPort);
        QraftAgent agent = agent("agent-a", controllers.endpoints(), List.of(
                service("web", "web", 8080), service("api", "api", 8081)), 150);

        assertTrue(agent.start().get(5, TimeUnit.SECONDS));
        waitUntil(() -> agent.healthService().isReady()
                && controllers.store(0).getServiceCatalog().instances().size() == 2);

        controllers.close();
        controllers = null;
        waitUntil(() -> !agent.healthService().isReady());

        controllers = ControllerGroup.single(controllerPort);
        waitUntil(() -> agent.healthService().isReady()
                && controllers.store(0).getServiceCatalog().instances().size() == 2);

        assertTrue(agent.shutdown().get(5, TimeUnit.SECONDS));
        waitUntil(() -> controllers.store(0).getServiceCatalog().instances().isEmpty()
                && controllers.store(0).findAgent("agent-a").isEmpty());
    }

    @Test
    void twoAgentsCanRegisterTheSameLocalServiceId() throws Exception {
        controllers = ControllerGroup.single(0);
        ServiceDefinition web = service("web", "web", 8080);
        QraftAgent first = agent("agent-a", controllers.endpoints(), List.of(web), 2_000);
        QraftAgent second = agent("agent-b", controllers.endpoints(), List.of(web), 2_000);

        assertTrue(first.start().get(5, TimeUnit.SECONDS));
        assertTrue(second.start().get(5, TimeUnit.SECONDS));
        waitUntil(() -> first.healthService().isReady() && second.healthService().isReady()
                && controllers.store(0).getServiceCatalog().instances("web").size() == 2);

        var instances = controllers.store(0).getServiceCatalog().instances("web");
        assertEquals(Set.of("agent-a", "agent-b"),
                instances.stream().map(instance -> instance.nodeId()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("web"),
                instances.stream().map(instance -> instance.serviceId()).collect(java.util.stream.Collectors.toSet()));

        assertTrue(first.shutdown().get(5, TimeUnit.SECONDS));
        assertTrue(second.shutdown().get(5, TimeUnit.SECONDS));
        waitUntil(() -> controllers.store(0).getServiceCatalog().instances("web").isEmpty());
    }

    @Test
    void threeNodeClusterAcceptsAgentWhenFirstSeedIsAFollower() throws Exception {
        controllers = ControllerGroup.cluster("node-a", "node-b", "node-c");
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
        assertTrue(agent.start().get(5, TimeUnit.SECONDS));
        waitUntil(() -> agent.healthService().isReady()
                && controllers.stores().stream().allMatch(store ->
                        store.getServiceCatalog().instances("web").size() == 1));

        assertEquals("cluster-agent", controllers.store(leader).getServiceCatalog()
                .instances("web").getFirst().nodeId());
        assertTrue(agent.shutdown().get(5, TimeUnit.SECONDS));
        waitUntil(() -> controllers.stores().stream().allMatch(store ->
                store.getServiceCatalog().instances("web").isEmpty()));
    }

    private QraftAgent agent(String id, List<URI> endpoints,
                             List<ServiceDefinition> services, long freshnessMs) throws Exception {
        QraftAgent agent = new QraftAgent(AgentConfiguration.builder()
                .agentId(id).hostname(id + "-host").address("127.0.0.1")
                .agentPort(freePort()).controllerUrls(endpoints)
                .heartbeatInterval(40).requestTimeoutMs(500)
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "condition was not met before the deadline");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class ControllerGroup implements AutoCloseable {
        private final JavaRuntime runtime;
        private final List<RaftNode> nodes;
        private final List<QraftStateStore> stores;
        private final List<HttpApiServer> servers;
        private boolean closed;

        private ControllerGroup(JavaRuntime runtime, List<RaftNode> nodes,
                                List<QraftStateStore> stores, List<HttpApiServer> servers) {
            this.runtime = runtime;
            this.nodes = nodes;
            this.stores = stores;
            this.servers = servers;
        }

        static ControllerGroup single(int port) throws Exception {
            JavaRuntime runtime = JavaRuntime.create();
            QraftStateStore store = new QraftStateStore();
            RaftNode node = node(runtime, "single", Set.of("single"),
                    new SingleNodeTransport(), store, 25);
            node.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            waitUntil(node::isLeader);
            HttpApiServer server = new HttpApiServer(port, node, store);
            server.start().get(5, TimeUnit.SECONDS);
            return new ControllerGroup(runtime, List.of(node), List.of(store), List.of(server));
        }

        static ControllerGroup cluster(String... nodeIds) throws Exception {
            InMemoryTransportSimulator.clearAllTransports();
            JavaRuntime runtime = JavaRuntime.create();
            Set<String> members = new LinkedHashSet<>(List.of(nodeIds));
            List<RaftNode> nodes = new ArrayList<>();
            List<QraftStateStore> stores = new ArrayList<>();
            for (String nodeId : nodeIds) {
                QraftStateStore store = new QraftStateStore();
                stores.add(store);
                nodes.add(node(runtime, nodeId, members,
                        new InMemoryTransportSimulator(nodeId), store, 250));
            }
            for (RaftNode node : nodes) {
                node.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            waitUntil(() -> nodes.stream().filter(RaftNode::isLeader).count() == 1);
            List<HttpApiServer> servers = new ArrayList<>();
            for (int index = 0; index < nodes.size(); index++) {
                HttpApiServer server = new HttpApiServer(0, nodes.get(index), stores.get(index));
                server.start().get(5, TimeUnit.SECONDS);
                servers.add(server);
            }
            return new ControllerGroup(runtime, List.copyOf(nodes), List.copyOf(stores), List.copyOf(servers));
        }

        private static RaftNode node(JavaRuntime runtime, String nodeId, Set<String> members,
                                     RaftTransport transport, QraftStateStore store, long electionTimeout) {
            return RaftNode.builder().runtime(runtime).nodeId(nodeId).clusterNodes(members)
                    .transport(transport).stateMachine(store).commandCodec(new ProtobufRaftCommandCodec())
                    .mode(RaftNodeMode.volatileMode()).electionTimeout(electionTimeout)
                    .heartbeatInterval(50).build();
        }

        List<URI> endpoints() { return List.of(endpoint(0)); }
        URI endpoint(int index) { return URI.create("http://127.0.0.1:" + servers.get(index).port()); }
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
            for (HttpApiServer server : servers.reversed()) server.close();
            for (RaftNode node : nodes.reversed()) {
                node.stop().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            runtime.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static final class SingleNodeTransport implements RaftTransport {
        @Override public void start(Consumer<RaftMessage> messageHandler) { }
        @Override public void stop() { }
        @Override public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return Future.failedFuture("single-node transport has no peers");
        }
        @Override public Future<AppendEntriesResponse> sendAppendEntries(
                String targetId, AppendEntriesRequest request) {
            return Future.failedFuture("single-node transport has no peers");
        }
        @Override public Future<InstallSnapshotResponse> sendInstallSnapshot(
                String targetId, InstallSnapshotRequest request) {
            return Future.failedFuture("single-node transport has no peers");
        }
    }
}

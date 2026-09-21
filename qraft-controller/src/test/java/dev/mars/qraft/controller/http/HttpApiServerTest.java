package dev.mars.qraft.controller.http;

import dev.mars.qraft.controller.raft.InMemoryTransportSimulator;
import dev.mars.qraft.controller.raft.RaftNode;
import dev.mars.qraft.controller.raft.RaftNodeMode;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.storage.RaftStorageFactory;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.controller.state.RaftCommand;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.agent.AgentStatus;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.raftlog.storage.RaftStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpApiServerTest {
    @TempDir
    Path directory;
    private HttpApiServer server;
    private RaftNode node;
    private JavaRuntime runtime;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.close();
        }
        if (node != null) node.stop().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (runtime != null) runtime.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        InMemoryTransportSimulator.clearAllTransports();
    }

    @Test
    void exposesHealthStatusAndInfoEndpoints() throws Exception {
        server = new HttpApiServer(0);
        server.start().join();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> live = request(client, "/health/live", "GET");
        HttpResponse<String> ready = request(client, "/health/ready", "GET");
        HttpResponse<String> status = request(client, "/status", "GET");
        HttpResponse<String> info = request(client, "/api/v1/info", "GET");

        assertEquals(200, live.statusCode());
        assertEquals(200, ready.statusCode());
        assertEquals(200, status.statusCode());
        assertEquals(200, info.statusCode());
        assertTrue(live.body().contains("alive"));
        assertTrue(info.body().contains("version"));
    }

    @Test
    void rejectsUnsupportedMethods() throws Exception {
        server = new HttpApiServer(0);
        server.start().join();
        HttpResponse<String> response = request(HttpClient.newHttpClient(), "/health/live", "POST");
        assertEquals(405, response.statusCode());
    }

    @Test
    void registersQueriesAndDeregistersServicesThroughRaft() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().join();
        HttpClient client = HttpClient.newHttpClient();
        String registration = """
                {"serviceId":"payments-1","serviceName":"payments","nodeId":"node-1",
                 "address":"127.0.0.1","port":8080,"tags":["v1","primary"],
                 "metadata":{"team":"platform"},"health":"PASSING"}
                """;

        HttpResponse<String> registered = request(client, "/v1/agent/service/register", "PUT", registration);
        HttpResponse<String> services = request(client, "/v1/catalog/services", "GET");
        HttpResponse<String> instances = request(client, "/v1/catalog/service/payments", "GET");
        HttpResponse<String> health = request(client, "/v1/health/service/payments", "GET");
        HttpResponse<String> deregistered = request(client,
                "/v1/agent/service/deregister/payments-1", "PUT");

        assertEquals(200, registered.statusCode());
        assertEquals(200, services.statusCode());
        assertTrue(services.body().contains("payments"));
        assertTrue(services.body().contains("primary"));
        assertEquals(200, instances.statusCode());
        assertTrue(instances.body().contains("payments-1"));
        assertTrue(health.body().contains("PASSING"));
        assertEquals(200, deregistered.statusCode());
        assertTrue(store.getServiceCatalog().instances("payments").isEmpty());
    }

    @Test
    void registersHeartbeatsAndDeregistersAgentsThroughRaft() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().join();
        HttpClient client = HttpClient.newHttpClient();
        String registration = """
                {"agentId":"agent-1","hostname":"host-1","address":"127.0.0.1","port":8080,
                 "version":"1.0.0","region":"eu-west","datacenter":"dc-1"}
                """;
        String heartbeat = """
                {"agentId":"agent-1","timestamp":"2026-09-21T10:15:30Z",
                 "sequenceNumber":1,"status":"passing"}
                """;

        HttpResponse<String> registered = request(client, "/api/v1/agents/register", "POST", registration);
        HttpResponse<String> heartbeatAccepted = request(client, "/api/v1/agents/heartbeat", "POST", heartbeat);
        HttpResponse<String> agents = request(client, "/api/v1/agents", "GET");

        assertEquals(201, registered.statusCode());
        assertEquals(204, heartbeatAccepted.statusCode());
        assertTrue(agents.body().contains("agent-1"));
        assertEquals(AgentStatus.HEALTHY,
                store.findAgent("agent-1").orElseThrow().getStatus());

        HttpResponse<String> deregistered = request(client, "/api/v1/agents/agent-1", "DELETE");
        assertEquals(204, deregistered.statusCode());
        assertTrue(store.findAgent("agent-1").isEmpty());
    }

    @Test
    void validatesAgentEndpointMethodsAndPayloads() throws Exception {
        startAgentApi();
        HttpClient client = HttpClient.newHttpClient();

        assertEquals(405, request(client, "/api/v1/agents/register", "GET").statusCode());
        assertEquals(400, request(client, "/api/v1/agents/register", "POST", "{").statusCode());
        assertEquals(400, request(client, "/api/v1/agents/register", "POST", "{\"agentId\":\" \"}").statusCode());
        assertEquals(400, request(client, "/api/v1/agents/heartbeat", "POST",
                "{\"agentId\":\"agent-1\",\"status\":\"not-a-status\"}").statusCode());
        assertEquals(400, request(client, "/api/v1/agents/heartbeat", "POST",
                "{\"agentId\":\"agent-1\",\"timestamp\":\"yesterday\"}").statusCode());
    }

    @Test
    void reportsMissingAgentsAndAcceptsOptionalHeartbeatFields() throws Exception {
        QraftStateStore store = startAgentApi();
        HttpClient client = HttpClient.newHttpClient();

        assertEquals(404, request(client, "/api/v1/agents/heartbeat", "POST",
                "{\"agentId\":\"unknown\",\"sequenceNumber\":1}").statusCode());
        assertEquals(404, request(client, "/api/v1/agents/unknown", "DELETE").statusCode());

        assertEquals(201, request(client, "/api/v1/agents/register", "POST", agentRegistration()).statusCode());
        assertEquals(204, request(client, "/api/v1/agents/heartbeat", "POST",
                "{\"agentId\":\"agent-1\",\"sequenceNumber\":1}").statusCode());
        assertEquals(AgentStatus.HEALTHY, store.findAgent("agent-1").orElseThrow().getStatus());
    }

    @Test
    void rejectsAgentRequestsWhileDraining() throws Exception {
        startAgentApi();
        server.enterDrainMode().join();

        assertEquals(503, request(HttpClient.newHttpClient(), "/api/v1/agents/register", "POST",
                agentRegistration()).statusCode());
    }

    @Test
    void rejectsAnOutOfOrderHeartbeatWithoutMovingAgentStateBackward() throws Exception {
        QraftStateStore store = startAgentApi();
        HttpClient client = HttpClient.newHttpClient();
        assertEquals(201, request(client, "/api/v1/agents/register", "POST", agentRegistration()).statusCode());
        assertEquals(204, request(client, "/api/v1/agents/heartbeat", "POST", """
                {"agentId":"agent-1","timestamp":"2026-09-21T10:15:30Z",
                 "sequenceNumber":2,"status":"passing"}
                """).statusCode());

        HttpResponse<String> stale = request(client, "/api/v1/agents/heartbeat", "POST", """
                {"agentId":"agent-1","timestamp":"2026-09-21T10:14:30Z",
                 "sequenceNumber":1,"status":"degraded"}
                """);

        assertEquals(409, stale.statusCode(), "a stale heartbeat sequence must be rejected");
        assertEquals(java.time.Instant.parse("2026-09-21T10:15:30Z"),
                store.findAgent("agent-1").orElseThrow().getLastHeartbeat());
        assertEquals(AgentStatus.HEALTHY, store.findAgent("agent-1").orElseThrow().getStatus());
    }

    @Test
    void rejectsInvalidServiceRegistrations() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().join();

        HttpResponse<String> response = request(HttpClient.newHttpClient(),
                "/v1/agent/service/register", "PUT", "{\"serviceId\":\"missing-fields\"}");

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("invalid_registration"));
    }

    @Test
    void returnsServiceUnavailableWhenCatalogWriteReachesFollower() throws Exception {
        runtime = JavaRuntime.create();
        InMemoryTransportSimulator transport = new InMemoryTransportSimulator("follower");
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder()
                .runtime(runtime).nodeId("follower").clusterNodes(Set.of("follower", "peer"))
                .transport(transport).stateMachine(store).commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode()).electionTimeout(10_000).build();
        node.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        node.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                        .setTerm(1).setLeaderId("peer").setPrevLogIndex(0).setPrevLogTerm(0)
                        .setLeaderCommit(0).build())
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        server = new HttpApiServer(0, node, store);
        server.start().join();
        String registration = """
                {"serviceId":"payments-1","serviceName":"payments","nodeId":"node-1",
                 "address":"127.0.0.1","port":8080,"tags":[],"metadata":{},"health":"PASSING"}
                """;

        HttpResponse<String> response = request(HttpClient.newHttpClient(),
                "/v1/agent/service/register", "PUT", registration);
        HttpResponse<String> agentResponse = request(HttpClient.newHttpClient(),
                "/api/v1/agents/register", "POST", agentRegistration());

        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("leader_unavailable"));
        assertEquals(503, agentResponse.statusCode());
        assertTrue(agentResponse.body().contains("leader_unavailable"));
        assertTrue(agentResponse.body().contains("\"leaderId\":\"peer\""));
        assertEquals("peer", agentResponse.headers().firstValue("X-Qraft-Leader-Id").orElseThrow());
        assertTrue(store.getServiceCatalog().instances().isEmpty());
    }

    @Test
    void reportsCatalogProtocolErrorsAndRejectsQueriesWhileDraining() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().join();
        HttpClient client = HttpClient.newHttpClient();

        assertEquals(405, request(client, "/v1/catalog/services", "POST").statusCode());
        assertEquals(400, request(client, "/v1/agent/service/deregister", "PUT").statusCode());
        assertEquals(404, request(client,
                "/v1/agent/service/deregister/unknown", "PUT").statusCode());

        server.enterDrainMode().join();
        assertEquals(503, request(client, "/v1/catalog/services", "GET").statusCode());
        assertEquals(200, request(client, "/health/live", "GET").statusCode());
    }

    @Test
    void fencedNodeFailsReadinessWhileLivenessRemainsAvailable() throws Exception {
        runtime = JavaRuntime.create();
        QraftStateStore store = new QraftStateStore();
        RaftStorageFactory.DurableStorage durable = RaftStorageFactory
                .createDurable(directory, true).toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        RaftStorage ambiguousStorage = new AmbiguousAppendStorage(durable.wal());
        node = RaftNode.builder()
                .runtime(runtime).nodeId("fenced-node").clusterNodes(Set.of("fenced-node"))
                .transport(new InMemoryTransportSimulator("fenced-node"))
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(ambiguousStorage, durable.snapshots()))
                .snapshotEnabled(false).electionTimeout(25).heartbeatInterval(10_000)
                .build();
        node.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(node.isLeader());

        try {
            node.submitCommand(new DistributedStateRaftCommand(
                            DistributedStateCommand.put("fence", "node")))
                    .toCompletionStage().toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException expected) {
            // The failed transition is the event that fences the node.
        }
        assertTrue(node.isFenced());

        server = new HttpApiServer(0, node, store);
        server.start().join();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> ready = request(client, "/health/ready", "GET");
        HttpResponse<String> live = request(client, "/health/live", "GET");

        assertEquals(503, ready.statusCode());
        assertTrue(ready.body().contains("fenced"));
        assertEquals(200, live.statusCode());
    }

    private static final class AmbiguousAppendStorage implements RaftStorage {
        private final RaftStorage delegate;

        private AmbiguousAppendStorage(RaftStorage delegate) {
            this.delegate = delegate;
        }

        @Override public CompletableFuture<Void> open(Path dataDir) { return delegate.open(dataDir); }
        @Override public CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor) {
            return delegate.updateMetadata(term, votedFor);
        }
        @Override public CompletableFuture<PersistentMeta> loadMetadata() { return delegate.loadMetadata(); }
        @Override public CompletableFuture<Void> appendEntries(List<LogEntryData> entries) {
            return delegate.appendEntries(entries).thenCompose(ignored -> CompletableFuture.failedFuture(
                    new IllegalStateException("append persisted before completion failed")));
        }
        @Override public CompletableFuture<Void> truncateSuffix(long fromIndex) {
            return delegate.truncateSuffix(fromIndex);
        }
        @Override public CompletableFuture<Void> truncatePrefix(long toIndex) {
            return delegate.truncatePrefix(toIndex);
        }
        @Override public CompletableFuture<Void> sync() { return delegate.sync(); }
        @Override public CompletableFuture<List<LogEntryData>> replayLog() { return delegate.replayLog(); }
        @Override public CompletableFuture<Void> closeAsync() { return delegate.closeAsync(); }
        @Override public void close() { closeAsync(); }
    }

    private QraftStateStore startSingleNode() throws Exception {
        runtime = JavaRuntime.create();
        InMemoryTransportSimulator.clearAllTransports();
        InMemoryTransportSimulator transport = new InMemoryTransportSimulator("catalog-node");
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("catalog-node")
                .clusterNodes(Set.of("catalog-node"))
                .transport(transport)
                .stateMachine(store)
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode())
                .electionTimeout(50)
                .heartbeatInterval(20)
                .build();
        node.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(node.isLeader());
        return store;
    }

    private QraftStateStore startAgentApi() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().join();
        return store;
    }

    private static String agentRegistration() {
        return """
                {"agentId":"agent-1","hostname":"host-1","address":"127.0.0.1","port":8080,
                 "version":"1.0.0","region":"eu-west","datacenter":"dc-1"}
                """;
    }

    private HttpResponse<String> request(HttpClient client, String path, String method) throws Exception {
        return request(client, path, method, null);
    }

    private HttpResponse<String> request(HttpClient client, String path, String method, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.port() + path))
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

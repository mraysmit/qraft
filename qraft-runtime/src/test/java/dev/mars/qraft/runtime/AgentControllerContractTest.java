package dev.mars.qraft.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.agent.AgentStatus;
import dev.mars.qraft.agent.QraftAgent;
import dev.mars.qraft.agent.catalog.CatalogOutcome;
import dev.mars.qraft.agent.catalog.HttpCatalogClient;
import dev.mars.qraft.agent.config.AgentConfiguration;
import dev.mars.qraft.catalog.ServiceDefinition;
import dev.mars.qraft.controller.http.HttpApiServer;
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
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentControllerContractTest {
    private JavaRuntime runtime;
    private RaftNode node;
    private HttpApiServer server;
    private QraftAgent agent;
    private HttpCatalogClient catalogClient;
    private HttpServer retryableServer;

    @AfterEach
    void closeResources() throws Exception {
        if (agent != null) agent.shutdown().get(5, TimeUnit.SECONDS);
        if (catalogClient != null) catalogClient.close();
        if (retryableServer != null) retryableServer.stop(0);
        if (server != null) server.close();
        if (node != null) node.stop().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (runtime != null) runtime.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void httpCatalogClientRegistersAndDeregistersAgainstRealController() throws Exception {
        startController();
        URI endpoint = URI.create("http://127.0.0.1:" + server.port());
        URI refused;
        try (ServerSocket socket = new ServerSocket(0)) {
            refused = URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
        AtomicInteger retryableAttempts = new AtomicInteger();
        retryableServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        retryableServer.createContext("/", exchange -> {
            retryableAttempts.incrementAndGet();
            byte[] body = "{\"code\":\"leader_unavailable\",\"message\":\"not leader\","
                    .concat("\"retryable\":true}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        retryableServer.start();
        URI retryable = URI.create("http://127.0.0.1:" + retryableServer.getAddress().getPort());
        catalogClient = new HttpCatalogClient(HttpClient.newHttpClient(), new ObjectMapper(),
                List.of(refused, retryable, endpoint),
                "catalog-agent", "default", "default", "dc-1", "eu-west", Duration.ofSeconds(2));
        ServiceDefinition service = new ServiceDefinition("payments-1", "payments", "127.0.0.1", 9090,
                List.of("blue"), Map.of("team", "platform"), true);

        CatalogOutcome.Success registered = assertInstanceOf(CatalogOutcome.Success.class,
                catalogClient.register(service).get(5, TimeUnit.SECONDS));
        assertTrue(registered.changed());
        assertEquals(1, retryableAttempts.get());
        JsonNode afterRegistration = readCatalog(endpoint, "payments");
        assertEquals(1, afterRegistration.size());
        assertEquals("payments-1", afterRegistration.get(0).path("serviceId").textValue());
        assertEquals("catalog-agent", afterRegistration.get(0).path("nodeId").textValue());

        CatalogOutcome.Success deregistered = assertInstanceOf(CatalogOutcome.Success.class,
                catalogClient.deregister("payments-1").get(5, TimeUnit.SECONDS));
        assertTrue(deregistered.changed());
        assertEquals(1, retryableAttempts.get(), "the successful controller must be preferred next");
        assertTrue(readCatalog(endpoint, "payments").isEmpty());
    }

    @Test
    void realAgentCompletesRegistrationHeartbeatAndDeregistrationAgainstController() throws Exception {
        QraftStateStore store = startController();
        AgentConfiguration configuration = AgentConfiguration.builder()
                .agentId("contract-agent")
                .hostname("contract-host")
                .address("127.0.0.1")
                .agentPort(freePort())
                .controllerUrl("http://localhost:" + server.port())
                .heartbeatInterval(25)
                .httpConnectionTimeout(1_000)
                .build();
        agent = new QraftAgent(configuration);

        assertTrue(agent.start().get(5, TimeUnit.SECONDS),
                () -> "the real agent rejected the controller response; replicated state="
                        + store.findAgent("contract-agent"));
        assertTrue(agent.healthService().isReady());
        waitUntil(() -> store.findAgent("contract-agent")
                .filter(info -> info.getStatus() == AgentStatus.HEALTHY && info.getLastHeartbeat() != null)
                .isPresent());

        assertTrue(agent.shutdown().get(5, TimeUnit.SECONDS));
        waitUntil(() -> store.findAgent("contract-agent").isEmpty());
        assertFalse(agent.isRunning());
        assertEquals(0, store.getAgents().size());
    }

    private QraftStateStore startController() throws Exception {
        runtime = JavaRuntime.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("contract-node")
                .clusterNodes(Set.of("contract-node"))
                .transport(new SingleNodeTransport())
                .stateMachine(store)
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode())
                .electionTimeout(25)
                .heartbeatInterval(10_000)
                .build();
        node.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        waitUntil(node::isLeader);
        server = new HttpApiServer(0, node, store);
        server.start().get(5, TimeUnit.SECONDS);
        return store;
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "condition was not met before the deadline");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static JsonNode readCatalog(URI endpoint, String serviceName) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        endpoint.resolve("/v1/catalog/service/" + serviceName))
                .GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return new ObjectMapper().readTree(response.body());
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

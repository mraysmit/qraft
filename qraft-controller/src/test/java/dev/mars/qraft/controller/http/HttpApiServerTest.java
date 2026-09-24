package dev.mars.qraft.controller.http;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.catalog.ServiceHealth;
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
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertErrorEnvelope(response, "method_not_allowed", false);
    }

    @Test
    void everyCatalogProtocolErrorUsesTheStructuredEnvelopeAndRequestId() throws Exception {
        server = new HttpApiServer(0);
        server.start().join();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> unavailable = request(client, "/v1/catalog/services", "GET", null,
                Map.of("X-Request-Id", "request-123"));
        assertEquals(503, unavailable.statusCode());
        JsonNode unavailableBody = assertErrorEnvelope(unavailable, "catalog_unavailable", true);
        assertEquals("request-123", unavailableBody.get("requestId").textValue());

        server.close();
        server = null;
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().join();
        HttpResponse<String> invalid = request(client, "/v1/agent/service/register", "PUT",
                "{\"serviceId\":\"broken\"}", Map.of("X-Qraft-Node", "node-a"));
        assertEquals(400, invalid.statusCode());
        assertErrorEnvelope(invalid, "invalid_registration", false);

        server.enterDrainMode().join();
        HttpResponse<String> draining = request(client, "/v1/catalog/services", "GET");
        assertEquals(503, draining.statusCode());
        assertErrorEnvelope(draining, "draining", true);
    }

    @Test
    void requestIdIsPresentInHttpHandlerLogContext() throws Exception {
        server = new HttpApiServer(0);
        server.start().join();
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(HttpApiServer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            HttpResponse<String> response = request(HttpClient.newHttpClient(), "/health/live", "GET", null,
                    Map.of("X-Request-Id", "mdc-request-7"));
            assertEquals(200, response.statusCode());
            assertTrue(appender.list.stream().anyMatch(event ->
                    "mdc-request-7".equals(event.getMDCPropertyMap().get("requestId"))));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void registersQueriesAndDeregistersServicesThroughRaft() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().join();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> beforeRegistration = request(client, "/v1/catalog/services", "GET");
        long beforeIndex = Long.parseLong(beforeRegistration.headers()
                .firstValue("X-Qraft-Index").orElseThrow());
        String registration = """
                {"serviceId":"payments-1","serviceName":"payments",
                 "address":"127.0.0.1","port":8080,"tags":["v1","primary"],
                 "metadata":{"team":"platform"},"health":"PASSING"}
                """;

        HttpResponse<String> registered = request(client, "/v1/agent/service/register", "PUT", registration,
                Map.of("X-Qraft-Node", "node-1"));
        HttpResponse<String> services = request(client, "/v1/catalog/services", "GET");
        HttpResponse<String> instances = request(client, "/v1/catalog/service/payments", "GET");
        HttpResponse<String> health = request(client, "/v1/health/service/payments", "GET");
        HttpResponse<String> deregistered = request(client,
                "/v1/agent/service/deregister/payments-1", "PUT", null,
                Map.of("X-Qraft-Node", "node-1"));

        assertEquals(200, registered.statusCode());
        assertEquals(200, services.statusCode());
        long appliedIndex = Long.parseLong(services.headers().firstValue("X-Qraft-Index").orElseThrow());
        assertTrue(appliedIndex > beforeIndex);
        assertEquals(appliedIndex, Long.parseLong(instances.headers()
                .firstValue("X-Qraft-Index").orElseThrow()));
        assertEquals(appliedIndex, Long.parseLong(health.headers()
                .firstValue("X-Qraft-Index").orElseThrow()));
        assertTrue(services.body().contains("payments"));
        assertTrue(services.body().contains("primary"));
        assertEquals(200, instances.statusCode());
        assertTrue(instances.body().contains("payments-1"));
        assertTrue(health.body().contains("UNKNOWN"));
        assertEquals(200, deregistered.statusCode());
        assertTrue(store.getServiceCatalog().instances("payments").isEmpty());
    }

    @Test
    void registrationUsesHeaderIdentityAndForcesServerOwnedHealth() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().join();
        String registration = """
                {"serviceId":"web","serviceName":"frontend","address":"127.0.0.1","port":8080,
                 "tags":["blue"],"metadata":{"team":"platform"},"health":"PASSING",
                 "datacenter":"dc-1","region":"eu-west","enabled":false}
                """;

        HttpResponse<String> response = request(HttpClient.newHttpClient(),
                "/v1/agent/service/register", "PUT", registration,
                Map.of("X-Qraft-Node", "node-a", "X-Qraft-Tenant", "acme",
                        "X-Qraft-Namespace", "payments"));

        assertEquals(200, response.statusCode(), response.body());
        var stored = store.getServiceCatalog().instances("frontend").getFirst();
        assertEquals(ServiceHealth.UNKNOWN, stored.health());
        assertEquals("node-a", stored.nodeId());
        assertEquals("acme", stored.tenantId());
        assertEquals("payments", stored.namespace());
        assertEquals(false, stored.enabled());

        JsonNode body = new ObjectMapper().readTree(response.body());
        assertEquals(Set.of("serviceId", "serviceName", "nodeId", "tenantId", "namespace", "registered"),
                body.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        assertTrue(body.get("registered").booleanValue());
    }

    @Test
    void registrationDefaultsScopeAndRejectsUnknownFields() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().join();
        String valid = """
                {"serviceId":"web","serviceName":"frontend","address":"127.0.0.1","port":8080,
                 "tags":[],"metadata":{}}
                """;

        HttpResponse<String> accepted = request(HttpClient.newHttpClient(),
                "/v1/agent/service/register", "PUT", valid, Map.of("X-Qraft-Node", "node-a"));
        String withUnknownField = """
                {"serviceId":"web-2","serviceName":"frontend","address":"127.0.0.1","port":8080,
                 "tags":[],"metadata":{},"alias":"frontend"}
                """;
        HttpResponse<String> rejected = request(HttpClient.newHttpClient(),
                "/v1/agent/service/register", "PUT", withUnknownField,
                Map.of("X-Qraft-Node", "node-b"));

        assertEquals(200, accepted.statusCode(), accepted.body());
        var stored = store.getServiceCatalog().instances("frontend").getFirst();
        assertEquals("default", stored.tenantId());
        assertEquals("default", stored.namespace());
        assertEquals(400, rejected.statusCode(), rejected.body());
        assertTrue(rejected.body().contains("invalid_registration"));
    }

    @Test
    void deregistrationRequiresNodeIdentityAndIsIdempotent() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().join();
        HttpClient client = HttpClient.newHttpClient();
        String registration = """
                {"serviceId":"web","serviceName":"frontend","address":"127.0.0.1","port":8080,
                 "tags":[],"metadata":{}}
                """;
        assertEquals(200, request(client, "/v1/agent/service/register", "PUT", registration,
                Map.of("X-Qraft-Node", "node-a")).statusCode());

        HttpResponse<String> missingIdentity = request(client,
                "/v1/agent/service/deregister/web", "PUT");
        HttpResponse<String> removed = request(client,
                "/v1/agent/service/deregister/web", "PUT", null, Map.of("X-Qraft-Node", "node-a"));
        HttpResponse<String> absent = request(client,
                "/v1/agent/service/deregister/web", "PUT", null, Map.of("X-Qraft-Node", "node-a"));

        assertEquals(400, missingIdentity.statusCode(), missingIdentity.body());
        assertEquals(200, removed.statusCode(), removed.body());
        assertTrue(removed.body().contains("\"deregistered\":true"));
        assertEquals(200, absent.statusCode(), absent.body());
        assertTrue(absent.body().contains("\"deregistered\":false"));
        assertTrue(store.getServiceCatalog().instances("frontend").isEmpty());
    }

    @Test
    void exposesRaftRoleAndDurableTerm() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().join();

        HttpResponse<String> response = request(HttpClient.newHttpClient(), "/raft/status", "GET");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"nodeId\":\"" + node.getNodeId() + "\""));
        assertTrue(response.body().contains("\"state\":\"LEADER\""));
        assertTrue(response.body().contains("\"term\":" + node.getCurrentTerm()));
        assertTrue(response.body().contains("\"snapshotLastIndex\":" + node.getSnapshotLastIndex()));
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
        assertErrorEnvelope(stale, "stale_heartbeat", false);
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
                {"serviceId":"payments-1","serviceName":"payments",
                 "address":"127.0.0.1","port":8080,"tags":[],"metadata":{},"health":"PASSING"}
                """;

        HttpResponse<String> response = request(HttpClient.newHttpClient(),
                "/v1/agent/service/register", "PUT", registration, Map.of("X-Qraft-Node", "node-1"));
        HttpResponse<String> agentResponse = request(HttpClient.newHttpClient(),
                "/api/v1/agents/register", "POST", agentRegistration());

        assertEquals(503, response.statusCode());
        assertErrorEnvelope(response, "leader_unavailable", true);
        assertTrue(response.body().contains("leader_unavailable"));
        assertEquals(503, agentResponse.statusCode());
        assertErrorEnvelope(agentResponse, "leader_unavailable", true);
        assertTrue(agentResponse.body().contains("leader_unavailable"));
        assertTrue(agentResponse.body().contains("\"leaderId\":\"peer\""));
        assertEquals("peer", agentResponse.headers().firstValue("X-Qraft-Leader-Id").orElseThrow());
        assertTrue(store.getServiceCatalog().instances().isEmpty());
    }

    @Test
    void reportsUnknownOutcomeWhenHttpWriteTimesOut() throws Exception {
        GatedAppendStorage gatedWal = startGatedHttpNode();

        CompletableFuture<HttpResponse<String>> request = HttpClient.newHttpClient().sendAsync(
                serviceRegistrationRequest("pending-service"), HttpResponse.BodyHandlers.ofString());
        gatedWal.awaitBlockedAppend();

        try {
            HttpResponse<String> response = request.get(7, TimeUnit.SECONDS);
            assertEquals(503, response.statusCode());
            assertErrorEnvelope(response, "outcome_unknown", true);
            assertTrue(response.body().contains("\"error\":\"outcome_unknown\""), response.body());
            assertTrue(response.body().contains("\"retryable\":true"), response.body());
        } finally {
            gatedWal.releaseBlockedAppend();
        }
    }

    @Test
    void retriedRegistrationConvergesToOneCompositeInstanceThroughSequencer() throws Exception {
        GatedAppendStorage gatedWal = startGatedHttpNode();
        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<HttpResponse<String>> first = client.sendAsync(
                serviceRegistrationRequest("retry-web", "node-a"), HttpResponse.BodyHandlers.ofString());
        gatedWal.awaitBlockedAppend();
        CompletableFuture<HttpResponse<String>> retry = client.sendAsync(
                serviceRegistrationRequest("retry-web", "node-a"), HttpResponse.BodyHandlers.ofString());

        try {
            gatedWal.releaseBlockedAppend();
            assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
            assertEquals(200, retry.get(5, TimeUnit.SECONDS).statusCode());
            JsonNode instances = new ObjectMapper().readTree(request(client,
                    "/v1/catalog/service/pending", "GET").body());
            assertEquals(1, instances.size());
            assertEquals("node-a", instances.get(0).path("nodeId").asText());
        } finally {
            gatedWal.releaseBlockedAppend();
        }
    }

    @Test
    void concurrentNodeRegistrationsPreserveBothCompositeInstancesThroughSequencer() throws Exception {
        GatedAppendStorage gatedWal = startGatedHttpNode();
        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<HttpResponse<String>> nodeA = client.sendAsync(
                serviceRegistrationRequest("web", "node-a"), HttpResponse.BodyHandlers.ofString());
        gatedWal.awaitBlockedAppend();
        CompletableFuture<HttpResponse<String>> nodeB = client.sendAsync(
                serviceRegistrationRequest("web", "node-b"), HttpResponse.BodyHandlers.ofString());

        try {
            gatedWal.releaseBlockedAppend();
            assertEquals(200, nodeA.get(5, TimeUnit.SECONDS).statusCode());
            assertEquals(200, nodeB.get(5, TimeUnit.SECONDS).statusCode());
            JsonNode instances = new ObjectMapper().readTree(request(client,
                    "/v1/catalog/service/pending", "GET").body());
            assertEquals(2, instances.size());
            assertEquals("node-a", instances.get(0).path("nodeId").asText());
            assertEquals("node-b", instances.get(1).path("nodeId").asText());
        } finally {
            gatedWal.releaseBlockedAppend();
        }
    }

    @Test
    void reportsCatalogProtocolErrorsAndRejectsQueriesWhileDraining() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().join();
        HttpClient client = HttpClient.newHttpClient();

        assertEquals(405, request(client, "/v1/catalog/services", "POST").statusCode());
        assertEquals(400, request(client, "/v1/agent/service/deregister", "PUT").statusCode());
        assertEquals(400, request(client,
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

    @Test
    void corruptWalFencesStartupWithoutMutatingTheEvidence() throws Exception {
        RaftStorageFactory.DurableStorage writer = RaftStorageFactory
                .createDurable(directory, true).toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        writer.wal().appendEntries(List.of(new RaftStorage.LogEntryData(
                1, 1, new byte[]{1, 2, 3}))).join();
        writer.wal().sync().join();
        writer.wal().closeAsync().join();
        writer.snapshots().close();
        Path walPath = directory.resolve("raft.log");
        byte[] corruptWal = Files.readAllBytes(walPath);
        corruptWal[corruptWal.length - 1] ^= 0x01;
        Files.write(walPath, corruptWal);

        runtime = JavaRuntime.create();
        QraftStateStore store = new QraftStateStore();
        RaftStorageFactory.DurableStorage reopened = RaftStorageFactory
                .createDurable(directory, true).toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        node = RaftNode.builder()
                .runtime(runtime).nodeId("corrupt-node").clusterNodes(Set.of("corrupt-node"))
                .transport(new InMemoryTransportSimulator("corrupt-node"))
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(reopened.wal(), reopened.snapshots()))
                .snapshotEnabled(false).electionTimeout(25).build();

        java.util.concurrent.CompletionException failure = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> node.start().toCompletionStage().toCompletableFuture().join());
        String diagnostic = failure.getCause().toString();
        assertTrue(diagnostic.contains("raft.log") || diagnostic.contains(directory.toString()), diagnostic);
        assertTrue(diagnostic.toLowerCase().contains("position")
                || diagnostic.toLowerCase().contains("offset")
                || diagnostic.toLowerCase().contains("byte")
                || diagnostic.toLowerCase().contains("index"), diagnostic);

        server = new HttpApiServer(0, node, store);
        server.start().join();
        HttpResponse<String> ready = request(HttpClient.newHttpClient(), "/health/ready", "GET");
        assertEquals(503, ready.statusCode());
        assertTrue(ready.body().contains("fenced"), ready.body());
        assertArrayEquals(corruptWal, Files.readAllBytes(walPath),
                "failed recovery must preserve the corrupt WAL for diagnosis");
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

    private static final class GatedAppendStorage implements RaftStorage {
        private final RaftStorage delegate;
        private final AtomicReference<CompletableFuture<Void>> nextGate = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<Void>> blockedGate = new AtomicReference<>();
        private final CountDownLatch appendBlocked = new CountDownLatch(1);

        private GatedAppendStorage(RaftStorage delegate) {
            this.delegate = delegate;
        }

        void blockNextAppendCompletion() {
            nextGate.set(new CompletableFuture<>());
        }

        void awaitBlockedAppend() throws InterruptedException {
            assertTrue(appendBlocked.await(5, TimeUnit.SECONDS), "append did not reach its gate");
        }

        void releaseBlockedAppend() {
            CompletableFuture<Void> gate = blockedGate.get();
            if (gate != null) gate.complete(null);
        }

        @Override public CompletableFuture<Void> open(Path dataDir) { return delegate.open(dataDir); }
        @Override public CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor) {
            return delegate.updateMetadata(term, votedFor);
        }
        @Override public CompletableFuture<PersistentMeta> loadMetadata() { return delegate.loadMetadata(); }
        @Override public CompletableFuture<Void> appendEntries(List<LogEntryData> entries) {
            return delegate.appendEntries(entries).thenCompose(ignored -> {
                CompletableFuture<Void> gate = nextGate.getAndSet(null);
                if (gate == null) return CompletableFuture.completedFuture(null);
                blockedGate.set(gate);
                appendBlocked.countDown();
                return gate;
            });
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

    private GatedAppendStorage startGatedHttpNode() throws Exception {
        runtime = JavaRuntime.create();
        QraftStateStore store = new QraftStateStore();
        RaftStorageFactory.DurableStorage durable = RaftStorageFactory
                .createDurable(directory, true).toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        GatedAppendStorage gatedWal = new GatedAppendStorage(durable.wal());
        node = RaftNode.builder()
                .runtime(runtime).nodeId("pending-http-node").clusterNodes(Set.of("pending-http-node"))
                .transport(new InMemoryTransportSimulator("pending-http-node"))
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(gatedWal, durable.snapshots()))
                .snapshotEnabled(false).electionTimeout(25).heartbeatInterval(10_000)
                .build();
        node.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(node.isLeader());
        server = new HttpApiServer(0, node, store);
        server.start().join();
        gatedWal.blockNextAppendCompletion();
        return gatedWal;
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
        return request(client, path, method, body, Map.of());
    }

    private HttpResponse<String> request(HttpClient client, String path, String method, String body,
                                         Map<String, String> headers) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.port() + path))
                .header("Content-Type", "application/json")
                .method(method, publisher);
        headers.forEach(builder::header);
        HttpRequest request = builder.build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest serviceRegistrationRequest(String serviceId) {
        return serviceRegistrationRequest(serviceId, "node-1");
    }

    private HttpRequest serviceRegistrationRequest(String serviceId, String nodeId) {
        String body = """
                {"serviceId":"%s","serviceName":"pending",
                 "address":"127.0.0.1","port":8080,"tags":[],"metadata":{},"health":"PASSING"}
                """.formatted(serviceId);
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.port() + "/v1/agent/service/register"))
                .header("Content-Type", "application/json")
                .header("X-Qraft-Node", nodeId)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static JsonNode assertErrorEnvelope(HttpResponse<String> response, String code,
                                                boolean retryable) throws Exception {
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertEquals(code, body.path("code").textValue(), response.body());
        assertEquals(code, body.path("error").textValue(), response.body());
        assertEquals(retryable, body.path("retryable").booleanValue(), response.body());
        assertTrue(body.hasNonNull("message"), response.body());
        assertTrue(body.hasNonNull("requestId"), response.body());
        assertNotNull(response.headers().firstValue("X-Request-Id").orElse(null));
        return body;
    }
}

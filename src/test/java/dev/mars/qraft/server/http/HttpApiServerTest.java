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

package dev.mars.qraft.server.http;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.state.catalog.ServiceKey;
import dev.mars.qraft.state.catalog.HealthCheckState;
import dev.mars.qraft.state.catalog.ServiceCheckId;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstanceId;
import dev.mars.qraft.raft.InMemoryTransportSimulatorFixture;
import dev.mars.qraft.raft.RaftLogApplicator;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.raft.storage.RaftStorageFactory;
import dev.mars.qraft.common.async.JavaRuntime;
import dev.mars.qraft.state.CatalogCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.common.AgentStatus;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import dev.mars.qraft.testing.fault.InjectedFaultFixture;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link HttpApiServer} health, service catalog, agent, and health observation endpoints,
 * error envelopes, draining, fencing, and write outcomes through Raft.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
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
        if (node != null) node.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (runtime != null) runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        InMemoryTransportSimulatorFixture.clearAllTransports();
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
        assertTrue(store.getServiceCatalog().instances(ServiceKey.inDefaultScope("payments")).isEmpty());
    }

    @Test
    void registrationTheStateMachineDoesNotAcceptAnswersWithTheErrorEnvelope() throws Exception {
        QraftStateStore store = new QraftStateStore();
        startSingleNode(new RejectingRegistrationsFixture(store));
        server = new HttpApiServer(0, node, store);
        server.start().join();

        HttpResponse<String> response = request(HttpClient.newHttpClient(), "/v1/agent/service/register",
                "PUT", """
                        {"serviceId":"payments-1","serviceName":"payments",
                         "address":"127.0.0.1","port":8080}
                        """, Map.of("X-Qraft-Node", "node-1"));

        assertEquals(409, response.statusCode(), response.body());
        JsonNode body = assertErrorEnvelope(response, "registration_rejected", false);
        assertTrue(body.path("registered").isMissingNode(), response.body());
        assertTrue(store.getServiceCatalog().instances(ServiceKey.inDefaultScope("payments")).isEmpty());
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
        var stored = store.getServiceCatalog().instances(new ServiceKey("acme", "payments", "frontend")).getFirst();
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
    void catalogAndHealthReadsAreScopedByTenantAndNamespace() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().join();
        HttpClient client = HttpClient.newHttpClient();
        Map<String, String> acmeProd = Map.of("X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "prod");
        registerIn(client, Map.of(), "web-1", "payments", "default-tag");
        registerIn(client, acmeProd, "web-1", "payments", "acme-tag");
        registerIn(client, acmeProd, "billing-1", "billing", "acme-tag");

        JsonNode defaultInstances = readJson(client, "/v1/catalog/service/payments", Map.of());
        JsonNode acmeInstances = readJson(client, "/v1/catalog/service/payments", acmeProd);
        JsonNode otherNamespace = readJson(client, "/v1/catalog/service/payments",
                Map.of("X-Qraft-Tenant", "acme"));

        assertEquals(1, defaultInstances.size(), defaultInstances.toString());
        assertEquals("default", defaultInstances.get(0).get("tenantId").textValue());
        assertEquals(1, acmeInstances.size(), acmeInstances.toString());
        assertEquals("acme", acmeInstances.get(0).get("tenantId").textValue());
        assertEquals("prod", acmeInstances.get(0).get("namespace").textValue());
        assertEquals(0, otherNamespace.size(), otherNamespace.toString());

        assertEquals(new ObjectMapper().readTree("{\"payments\":[\"default-tag\"]}"),
                readJson(client, "/v1/catalog/services", Map.of()));
        assertEquals(new ObjectMapper().readTree("{\"billing\":[\"acme-tag\"],\"payments\":[\"acme-tag\"]}"),
                readJson(client, "/v1/catalog/services", acmeProd));

        JsonNode acmeHealth = readJson(client, "/v1/health/service/payments", acmeProd);
        assertEquals(List.of("web-1"), serviceIds(acmeHealth));
        assertEquals("acme", acmeHealth.get(0).get("service").get("tenantId").textValue());
        assertEquals(1, readJson(client, "/v1/health/service/payments", Map.of()).size());
    }

    @Test
    void readsRejectBlankScopeHeaders() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().join();
        HttpClient client = HttpClient.newHttpClient();

        for (String path : List.of("/v1/catalog/services", "/v1/catalog/service/payments",
                "/v1/health/service/payments")) {
            HttpResponse<String> blankTenant = request(client, path, "GET", null, Map.of("X-Qraft-Tenant", " "));
            HttpResponse<String> blankNamespace = request(client, path, "GET", null,
                    Map.of("X-Qraft-Namespace", " "));

            assertEquals(400, blankTenant.statusCode(), path + " " + blankTenant.body());
            assertErrorEnvelope(blankTenant, "invalid_scope", false);
            assertEquals(400, blankNamespace.statusCode(), path + " " + blankNamespace.body());
            assertErrorEnvelope(blankNamespace, "invalid_scope", false);
        }
    }

    private void registerIn(HttpClient client, Map<String, String> scope, String serviceId, String serviceName,
                            String tag) throws Exception {
        Map<String, String> headers = new java.util.HashMap<>(scope);
        headers.put("X-Qraft-Node", "node-1");
        HttpResponse<String> response = request(client, "/v1/agent/service/register", "PUT", """
                {"serviceId":"%s","serviceName":"%s","address":"127.0.0.1","port":8080,"tags":["%s"]}
                """.formatted(serviceId, serviceName, tag), headers);
        assertEquals(200, response.statusCode(), response.body());
    }

    private JsonNode readJson(HttpClient client, String path, Map<String, String> headers) throws Exception {
        HttpResponse<String> response = request(client, path, "GET", null, headers);
        assertEquals(200, response.statusCode(), path + " " + response.body());
        return new ObjectMapper().readTree(response.body());
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
        var stored = store.getServiceCatalog().instances(ServiceKey.inDefaultScope("frontend")).getFirst();
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
        assertTrue(store.getServiceCatalog().instances(ServiceKey.inDefaultScope("frontend")).isEmpty());
    }

    @Test
    void exposesRaftRoleAndDurableTerm() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().join();

        HttpResponse<String> response = request(HttpClient.newHttpClient(), "/raft/status", "GET");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"nodeId\":\"" + node.getNodeId() + "\""));
        assertTrue(response.body().contains("\"serverId\":\"" + node.getServerId() + "\""),
                "operators need the durable identity as well as the name: " + response.body());
        assertTrue(response.body().contains("\"state\":\"LEADER\""));
        assertTrue(response.body().contains("\"term\":" + node.getCurrentTerm()));
        assertTrue(response.body().contains("\"snapshotLastIndex\":" + node.getSnapshotLastIndex()));
        assertTrue(response.body().contains("\"lastLogIndex\":" + node.getLastLogIndex()),
                "operators and acceptance tests need the node's last log index: " + response.body());
        assertTrue(response.body().contains("\"lastApplied\":" + node.getLastApplied()), response.body());
        assertTrue(response.body().contains("\"removed\":false"), response.body());
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
    void agentRoutesAnswerEveryRejectionWithTheStructuredEnvelope() throws Exception {
        QraftStateStore store = startAgentApi();
        HttpClient client = HttpClient.newHttpClient();

        assertRejected(request(client, "/api/v1/agents/register", "GET"), 405, "method_not_allowed", null);
        assertRejected(request(client, "/api/v1/agents/register", "POST", "{"), 400, "invalid_agent", null);
        assertRejected(request(client, "/api/v1/agents/register", "POST", "{\"agentId\":\" \"}"),
                400, "invalid_agent", "agentId is required");
        assertRejected(request(client, "/api/v1/agents/heartbeat", "POST",
                "{\"agentId\":\"agent-1\",\"status\":\"not-a-status\"}"), 400, "invalid_heartbeat", null);
        assertRejected(request(client, "/api/v1/agents/heartbeat", "POST",
                "{\"agentId\":\"agent-1\",\"timestamp\":\"yesterday\"}"), 400, "invalid_heartbeat", null);
        assertRejected(request(client, "/api/v1/agents/heartbeat", "POST", "{\"agentId\":\"\"}"),
                400, "invalid_heartbeat", "agentId is required");
        for (String missingId : List.of("/api/v1/agents/", "/api/v1/agents/a/b")) {
            assertRejected(request(client, missingId, "DELETE"), 400, "agent_id_required", null);
        }
        assertTrue(store.getAgents().isEmpty(), "no rejected request registers an agent");
    }

    /** Asserts the status and the non-retryable envelope, and that the message names {@code reason} when given. */
    private static void assertRejected(HttpResponse<String> response, int status, String code, String reason)
            throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        JsonNode body = assertErrorEnvelope(response, code, false);
        if (reason != null) assertTrue(body.path("message").textValue().contains(reason), response.body());
    }

    @Test
    void rejectsTheJobSystemsLegacyStatusesInHeartbeats() throws Exception {
        QraftStateStore store = startAgentApi();
        HttpClient client = HttpClient.newHttpClient();
        assertEquals(201, request(client, "/api/v1/agents/register", "POST", agentRegistration()).statusCode());

        for (String legacy : List.of("active", "idle", "overloaded", "draining")) {
            assertEquals(400, request(client, "/api/v1/agents/heartbeat", "POST",
                    "{\"agentId\":\"agent-1\",\"status\":\"" + legacy + "\"}").statusCode(), legacy);
        }
        assertEquals(AgentStatus.REGISTERING, store.findAgent("agent-1").orElseThrow().getStatus(),
                "a rejected heartbeat changes nothing");
    }

    @Test
    void reportsMissingAgentsAndAcceptsOptionalHeartbeatFields() throws Exception {
        QraftStateStore store = startAgentApi();
        HttpClient client = HttpClient.newHttpClient();

        for (HttpResponse<String> missing : List.of(
                request(client, "/api/v1/agents/heartbeat", "POST", "{\"agentId\":\"unknown\",\"sequenceNumber\":1}"),
                request(client, "/api/v1/agents/unknown", "DELETE"))) {
            assertEquals(404, missing.statusCode(), missing.body());
            assertEquals("unknown", assertErrorEnvelope(missing, "agent_not_found", false).path("agentId").textValue(),
                    "the envelope names the missing agent");
        }

        assertEquals(201, request(client, "/api/v1/agents/register", "POST", agentRegistration()).statusCode());
        assertEquals(204, request(client, "/api/v1/agents/heartbeat", "POST",
                "{\"agentId\":\"agent-1\",\"sequenceNumber\":1}").statusCode());
        assertEquals(AgentStatus.HEALTHY, store.findAgent("agent-1").orElseThrow().getStatus());
    }

    @Test
    void rejectsAgentRequestsWhileDraining() throws Exception {
        startAgentApi();
        server.enterDrainMode().get(10, TimeUnit.SECONDS);

        HttpResponse<String> draining = request(HttpClient.newHttpClient(), "/api/v1/agents/register", "POST",
                agentRegistration());

        assertEquals(503, draining.statusCode());
        assertErrorEnvelope(draining, "draining", true);
    }

    @Test
    void readsWithoutAServiceNameAreRejected() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().get(10, TimeUnit.SECONDS);
        HttpClient client = HttpClient.newHttpClient();

        for (String path : List.of("/v1/catalog/service/", "/v1/health/service/", "/v1/catalog/service/a%2Fb",
                "/v1/health/service/a%2Fb")) {
            assertRejected(request(client, path, "GET"), 400, "service_name_required", null);
        }
    }

    @Test
    void writesRejectBlankScopeHeadersAndChangeNothing() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().get(10, TimeUnit.SECONDS);
        HttpClient client = HttpClient.newHttpClient();
        String registration = """
                {"serviceId":"web","serviceName":"web","address":"127.0.0.1","port":8080}
                """;

        for (String header : List.of("X-Qraft-Tenant", "X-Qraft-Namespace")) {
            Map<String, String> blank = Map.of("X-Qraft-Node", "node-1", header, " ");
            assertRejected(request(client, "/v1/agent/service/register", "PUT", registration, blank),
                    400, "invalid_registration", header);
            assertRejected(request(client, "/v1/agent/service/deregister/web", "PUT", null, blank),
                    400, "invalid_registration", header);
            assertRejected(request(client, "/v1/agent/check/observe", "PUT",
                    observation("web", "ttl", "passing", 1, 30_000, null), blank), 400, "invalid_observation", header);
        }
        assertTrue(store.getServiceCatalog().instances().isEmpty(), "no write with a blank scope reaches the catalog");
        assertTrue(store.healthChecks().isEmpty());
    }

    @Test
    void aNodeThatCannotAnswerInTimeIsReportedUnavailable() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null,
                Duration.ofMillis(200));
        server.start().get(10, TimeUnit.SECONDS);
        HttpClient client = HttpClient.newHttpClient();
        // Hold the node's state loop, where a status read is answered, for as long as the requests take.
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runtime.runOnContext(ignored -> {
            holding.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(holding.await(10, TimeUnit.SECONDS), "the state loop is held");

            HttpResponse<String> status = request(client, "/raft/status", "GET");
            HttpResponse<String> ready = request(client, "/health/ready", "GET");

            assertEquals(503, status.statusCode(), status.body());
            assertErrorEnvelope(status, "raft_unavailable", true);
            assertEquals(503, ready.statusCode(), ready.body());
            JsonNode notReady = assertErrorEnvelope(ready, "not_ready", true);
            assertEquals("[\"unavailable\"]", notReady.path("conditions").toString());
            assertEquals(200, request(client, "/health/live", "GET").statusCode(), "liveness is unaffected");
        } finally {
            release.countDown();
        }
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
        java.time.Instant acceptedHeartbeat = store.findAgent("agent-1").orElseThrow().getLastHeartbeat();

        HttpResponse<String> stale = request(client, "/api/v1/agents/heartbeat", "POST", """
                {"agentId":"agent-1","timestamp":"2026-09-21T10:14:30Z",
                 "sequenceNumber":1,"status":"degraded"}
                """);

        assertEquals(409, stale.statusCode(), "a stale heartbeat sequence must be rejected");
        assertErrorEnvelope(stale, "stale_heartbeat", false);
        assertEquals(acceptedHeartbeat, store.findAgent("agent-1").orElseThrow().getLastHeartbeat());
        assertEquals(AgentStatus.HEALTHY, store.findAgent("agent-1").orElseThrow().getStatus());
    }

    @Test
    void rejectsInvalidServiceRegistrations() throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store);
        server.start().join();

        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> missingField = request(client, "/v1/agent/service/register", "PUT",
                "{\"serviceId\":\"missing-fields\"}", Map.of("X-Qraft-Node", "node-1"));
        HttpResponse<String> missingNode = request(client, "/v1/agent/service/register", "PUT",
                "{\"serviceId\":\"web\",\"serviceName\":\"web\",\"address\":\"127.0.0.1\",\"port\":80}");

        assertEquals(400, missingField.statusCode());
        JsonNode fieldError = assertErrorEnvelope(missingField, "invalid_registration", false);
        assertTrue(fieldError.path("message").textValue().contains("serviceName"), missingField.body());
        assertEquals(400, missingNode.statusCode());
        JsonNode nodeError = assertErrorEnvelope(missingNode, "invalid_registration", false);
        assertTrue(nodeError.path("message").textValue().contains("X-Qraft-Node"), missingNode.body());
    }

    @Test
    void returnsServiceUnavailableWhenCatalogWriteReachesFollower() throws Exception {
        runtime = JavaRuntime.create();
        InMemoryTransportSimulatorFixture transport = new InMemoryTransportSimulatorFixture("follower");
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder()
                .runtime(runtime).nodeId("follower").clusterNodes(Set.of("follower", "peer"))
                .transport(transport).stateMachine(store).commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode()).electionTimeout(10_000).build();
        node.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        node.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                        .setTerm(1).setLeaderId("peer").setPrevLogIndex(0).setPrevLogTerm(0)
                        .setLeaderCommit(0).build())
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
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
        // The append is held until the test releases it, so only the write timeout can answer the request;
        // a short timeout keeps the test from waiting out the production default.
        GatedAppendStorageFixture gatedWal = startGatedHttpNode(Duration.ofMillis(200));

        CompletableFuture<HttpResponse<String>> request = HttpClient.newHttpClient().sendAsync(
                serviceRegistrationRequest("pending-service"), HttpResponse.BodyHandlers.ofString());
        gatedWal.awaitBlockedAppend();

        try {
            HttpResponse<String> response = request.get(10, TimeUnit.SECONDS);
            assertEquals(503, response.statusCode());
            JsonNode body = assertErrorEnvelope(response, "outcome_unknown", true);
            assertEquals("pending-http-node", body.path("leaderId").textValue(),
                    "the envelope names the leader to retry against");
            assertEquals("pending-http-node", response.headers().firstValue("X-Qraft-Leader-Id").orElse(null));
        } finally {
            gatedWal.releaseBlockedAppend();
        }
    }

    @Test
    void aRaftTimeoutMustBePositive() {
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofMillis(-1))) {
            assertThrows(IllegalArgumentException.class, () -> new HttpApiServer(0, null, null, Clock.systemUTC(),
                    AdminUiConfig.disabled(), null, invalid), invalid.toString());
        }
        assertThrows(NullPointerException.class, () -> new HttpApiServer(0, null, null, Clock.systemUTC(),
                AdminUiConfig.disabled(), null, null));
    }

    @Test
    void retriedRegistrationConvergesToOneCompositeInstanceThroughSequencer() throws Exception {
        GatedAppendStorageFixture gatedWal = startGatedHttpNode();
        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<HttpResponse<String>> first = client.sendAsync(
                serviceRegistrationRequest("retry-web", "node-a"), HttpResponse.BodyHandlers.ofString());
        gatedWal.awaitBlockedAppend();
        CompletableFuture<HttpResponse<String>> retry = client.sendAsync(
                serviceRegistrationRequest("retry-web", "node-a"), HttpResponse.BodyHandlers.ofString());

        try {
            gatedWal.releaseBlockedAppend();
            assertEquals(200, first.get(10, TimeUnit.SECONDS).statusCode());
            assertEquals(200, retry.get(10, TimeUnit.SECONDS).statusCode());
            JsonNode instances = new ObjectMapper().readTree(request(client,
                    "/v1/catalog/service/pending", "GET").body());
            assertEquals(1, instances.size());
            assertEquals("node-a", instances.get(0).path("nodeId").asText());
        } finally {
            gatedWal.releaseBlockedAppend();
        }
    }

    @Test
    void twoNodesRegisteringOneServiceIdBothCommitBehindABlockedAppend() throws Exception {
        GatedAppendStorageFixture gatedWal = startGatedHttpNode();
        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<HttpResponse<String>> nodeA = client.sendAsync(
                serviceRegistrationRequest("web", "node-a"), HttpResponse.BodyHandlers.ofString());
        gatedWal.awaitBlockedAppend();
        CompletableFuture<HttpResponse<String>> nodeB = client.sendAsync(
                serviceRegistrationRequest("web", "node-b"), HttpResponse.BodyHandlers.ofString());

        try {
            gatedWal.releaseBlockedAppend();
            assertEquals(200, nodeA.get(10, TimeUnit.SECONDS).statusCode());
            assertEquals(200, nodeB.get(10, TimeUnit.SECONDS).statusCode());
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

        Map<String, String> node1 = Map.of("X-Qraft-Node", "node-1");
        assertEquals(405, request(client, "/v1/catalog/services", "POST").statusCode());
        HttpResponse<String> noServiceId = request(client, "/v1/agent/service/deregister", "PUT", null, node1);
        assertEquals(400, noServiceId.statusCode());
        assertErrorEnvelope(noServiceId, "service_id_required", false);
        HttpResponse<String> unknown = request(client, "/v1/agent/service/deregister/unknown", "PUT", null, node1);
        assertEquals(200, unknown.statusCode(), "deregistering an absent instance is idempotent");
        assertFalse(new ObjectMapper().readTree(unknown.body()).path("deregistered").booleanValue());
        HttpResponse<String> noNode = request(client, "/v1/agent/service/deregister/unknown", "PUT");
        assertEquals(400, noNode.statusCode());
        assertTrue(assertErrorEnvelope(noNode, "invalid_registration", false)
                .path("message").textValue().contains("X-Qraft-Node"), noNode.body());

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
                .get(10, TimeUnit.SECONDS);
        RaftStorage ambiguousStorage = new AmbiguousAppendStorageFixture(durable.wal());
        node = RaftNode.builder()
                .runtime(runtime).nodeId("fenced-node").clusterNodes(Set.of("fenced-node"))
                .transport(new InMemoryTransportSimulatorFixture("fenced-node"))
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(ambiguousStorage, durable.snapshots()))
                .snapshotEnabled(false).electionTimeout(25).heartbeatInterval(10_000)
                .build();
        node.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
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
        IntentionalErrorsHelper.expect(dev.mars.qraft.testing.fault.IntentionalErrorFixture.WAL_AMBIGUOUS_CORRUPTION, 1);
        IntentionalErrorsHelper.expect(dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_RECOVERY_AMBIGUOUS_CORRUPTION, 2);
        RaftStorageFactory.DurableStorage writer = RaftStorageFactory
                .createDurable(directory, true).toCompletionStage().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
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
                .get(10, TimeUnit.SECONDS);
        node = RaftNode.builder()
                .runtime(runtime).nodeId("corrupt-node").clusterNodes(Set.of("corrupt-node"))
                .transport(new InMemoryTransportSimulatorFixture("corrupt-node"))
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

    @Test
    void healthObservationIsCommittedWithAServerReceiptDeadline() throws Exception {
        MutableClockHelper clock = new MutableClockHelper(Instant.parse("2026-09-26T10:00:00.123456Z"));
        QraftStateStore store = startHealthApi(clock);
        HttpClient client = HttpClient.newHttpClient();
        registerService(client, "web", "frontend", "node-a", "acme", "payments");
        long indexBefore = store.getLastAppliedIndex();

        HttpResponse<String> response = request(client, "/v1/agent/check/observe", "PUT",
                observation("web", "http", "passing", 1, 30_000, "200 OK"),
                Map.of("X-Qraft-Node", "node-a", "X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "payments"));

        assertEquals(200, response.statusCode(), response.body());
        long responseIndex = Long.parseLong(response.headers().firstValue("X-Qraft-Index").orElseThrow());
        assertTrue(responseIndex > indexBefore);
        assertEquals(store.getLastAppliedIndex(), responseIndex);
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertEquals(Set.of("serviceId", "checkId", "nodeId", "tenantId", "namespace",
                        "sequenceNumber", "status", "deadline", "accepted"),
                body.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        assertEquals("web", body.get("serviceId").textValue());
        assertEquals("http", body.get("checkId").textValue());
        assertEquals("node-a", body.get("nodeId").textValue());
        assertEquals("acme", body.get("tenantId").textValue());
        assertEquals("payments", body.get("namespace").textValue());
        assertEquals(1, body.get("sequenceNumber").longValue());
        assertEquals("PASSING", body.get("status").textValue());
        assertEquals("2026-09-26T10:00:30.123Z", body.get("deadline").textValue());
        assertTrue(body.get("accepted").booleanValue());

        ServiceCheckId checkId = new ServiceCheckId(
                new ServiceInstanceId("acme", "payments", "node-a", "web"), "http");
        HealthCheckState state = store.findHealthCheck(checkId).orElseThrow();
        assertEquals(Instant.parse("2026-09-26T10:00:00.123Z"), state.acceptedAt());
        assertEquals(Instant.parse("2026-09-26T10:00:30.123Z"), state.deadline());
        assertEquals("200 OK", state.observation().output());
        assertTrue(state.observation().required());
        assertEquals(ServiceHealth.PASSING,
                store.getServiceCatalog().instances(new ServiceKey("acme", "payments", "frontend")).getFirst().health());
    }

    @Test
    void replayingAnAcceptedObservationSucceedsWithoutMovingItsDeadline() throws Exception {
        MutableClockHelper clock = new MutableClockHelper(Instant.parse("2026-09-26T10:00:00Z"));
        QraftStateStore store = startHealthApi(clock);
        HttpClient client = HttpClient.newHttpClient();
        registerService(client, "web", "frontend", "node-a");
        String body = observation("web", "ttl", "passing", 7, 10_000, null);

        HttpResponse<String> first = request(client, "/v1/agent/check/observe", "PUT", body,
                Map.of("X-Qraft-Node", "node-a"));
        clock.advance(Duration.ofSeconds(5));
        HttpResponse<String> replay = request(client, "/v1/agent/check/observe", "PUT", body,
                Map.of("X-Qraft-Node", "node-a"));

        assertEquals(200, first.statusCode(), first.body());
        assertEquals(200, replay.statusCode(), replay.body());
        JsonNode firstBody = new ObjectMapper().readTree(first.body());
        JsonNode replayBody = new ObjectMapper().readTree(replay.body());
        assertEquals(firstBody, replayBody);
        assertEquals("2026-09-26T10:00:10Z", replayBody.get("deadline").textValue());
        assertTrue(replay.headers().firstValue("X-Qraft-Index").isPresent());
        assertEquals(Instant.parse("2026-09-26T10:00:00Z"),
                store.healthChecks().getFirst().acceptedAt());
    }

    @Test
    void staleObservationsAreRejectedWithoutChangingAcceptedState() throws Exception {
        QraftStateStore store = startHealthApi(new MutableClockHelper(Instant.parse("2026-09-26T10:00:00Z")));
        HttpClient client = HttpClient.newHttpClient();
        registerService(client, "web", "frontend", "node-a");
        Map<String, String> identity = Map.of("X-Qraft-Node", "node-a");
        assertEquals(200, request(client, "/v1/agent/check/observe", "PUT",
                observation("web", "http", "warning", 5, 10_000, "slow"), identity).statusCode());

        HttpResponse<String> older = request(client, "/v1/agent/check/observe", "PUT",
                observation("web", "http", "passing", 4, 10_000, null), identity);
        HttpResponse<String> conflictingReplay = request(client, "/v1/agent/check/observe", "PUT",
                observation("web", "http", "critical", 5, 10_000, "down"), identity);

        assertEquals(409, older.statusCode(), older.body());
        JsonNode olderBody = assertErrorEnvelope(older, "stale_observation", false);
        assertEquals(5, olderBody.get("currentSequenceNumber").longValue());
        assertEquals(409, conflictingReplay.statusCode(), conflictingReplay.body());
        assertErrorEnvelope(conflictingReplay, "stale_observation", false);
        HealthCheckState state = store.healthChecks().getFirst();
        assertEquals(5, state.observation().sequenceNumber());
        assertEquals(ServiceHealth.WARNING, state.observation().status());
        assertEquals(ServiceHealth.WARNING, store.getServiceCatalog().instances(ServiceKey.inDefaultScope("frontend")).getFirst().health());
    }

    @Test
    void observationsForUnregisteredCompositeInstancesAreNotFound() throws Exception {
        QraftStateStore store = startHealthApi(new MutableClockHelper(Instant.parse("2026-09-26T10:00:00Z")));
        HttpClient client = HttpClient.newHttpClient();
        registerService(client, "web", "frontend", "node-a");

        HttpResponse<String> otherNode = request(client, "/v1/agent/check/observe", "PUT",
                observation("web", "http", "passing", 1, 10_000, null), Map.of("X-Qraft-Node", "node-b"));
        HttpResponse<String> otherTenant = request(client, "/v1/agent/check/observe", "PUT",
                observation("web", "http", "passing", 1, 10_000, null),
                Map.of("X-Qraft-Node", "node-a", "X-Qraft-Tenant", "acme"));

        assertEquals(404, otherNode.statusCode(), otherNode.body());
        assertErrorEnvelope(otherNode, "service_not_found", false);
        assertEquals(404, otherTenant.statusCode(), otherTenant.body());
        assertErrorEnvelope(otherTenant, "service_not_found", false);
        assertTrue(store.healthChecks().isEmpty());
    }

    @Test
    void invalidObservationsUseTheStructuredEnvelope() throws Exception {
        QraftStateStore store = startHealthApi(new MutableClockHelper(Instant.parse("2026-09-26T10:00:00Z")));
        HttpClient client = HttpClient.newHttpClient();
        registerService(client, "web", "frontend", "node-a");
        Map<String, String> identity = Map.of("X-Qraft-Node", "node-a");
        String valid = observation("web", "http", "passing", 1, 10_000, null);
        List<String> invalidBodies = List.of(
                "not json",
                valid.replace("}", ",\"alias\":\"x\"}"),
                valid.replace("\"serviceId\":\"web\",", ""),
                valid.replace("\"checkId\":\"http\",", "\"checkId\":\" \","),
                valid.replace("\"passing\"", "\"unknown\""),
                valid.replace("\"passing\"", "\"failing\""),
                valid.replace("\"passing\"", "\"bogus\""),
                valid.replace("\"status\":\"passing\",", ""),
                valid.replace("\"sequenceNumber\":1", "\"sequenceNumber\":0"),
                valid.replace("\"sequenceNumber\":1,", ""),
                valid.replace("\"ttlMillis\":10000", "\"ttlMillis\":0"),
                valid.replace(",\"ttlMillis\":10000", ""),
                valid.replace("}", ",\"deregisterAfterMillis\":-1}"),
                valid.replace("}", ",\"deregisterAfterMillis\":\"soon\"}"),
                valid.replace("\"observedAt\":\"2026-09-26T09:59:59Z\",", ""),
                valid.replace("2026-09-26T09:59:59Z", "yesterday"),
                observation("web", "http", "passing", 1, 10_000, "x".repeat(4097)));

        for (String invalid : invalidBodies) {
            assertNotEquals(valid, invalid, "invalid fixture must differ from the valid body");
            HttpResponse<String> response = request(client, "/v1/agent/check/observe", "PUT", invalid, identity);
            assertEquals(400, response.statusCode(), invalid + " -> " + response.body());
            assertErrorEnvelope(response, "invalid_observation", false);
        }
        HttpResponse<String> missingNode = request(client, "/v1/agent/check/observe", "PUT", valid);
        assertEquals(400, missingNode.statusCode(), missingNode.body());
        assertErrorEnvelope(missingNode, "invalid_observation", false);
        HttpResponse<String> wrongMethod = request(client, "/v1/agent/check/observe", "POST", valid, identity);
        assertEquals(405, wrongMethod.statusCode(), wrongMethod.body());
        assertErrorEnvelope(wrongMethod, "method_not_allowed", false);
        assertTrue(store.healthChecks().isEmpty());

        HttpResponse<String> maximumOutput = request(client, "/v1/agent/check/observe", "PUT",
                observation("web", "http", "PASSING", 1, 10_000, "x".repeat(4096)), identity);
        assertEquals(200, maximumOutput.statusCode(), maximumOutput.body());

        server.enterDrainMode().join();
        HttpResponse<String> draining = request(client, "/v1/agent/check/observe", "PUT",
                observation("web", "http", "passing", 2, 10_000, null), identity);
        assertEquals(503, draining.statusCode(), draining.body());
        assertErrorEnvelope(draining, "draining", true);
    }

    @Test
    void healthDiscoveryReportsChecksAndFiltersPassingWithoutMutatingState() throws Exception {
        QraftStateStore store = startHealthApi(new MutableClockHelper(Instant.parse("2026-09-26T10:00:00Z")));
        HttpClient client = HttpClient.newHttpClient();
        registerService(client, "web-1", "frontend", "node-a");
        registerService(client, "web-2", "frontend", "node-b");
        registerService(client, "web-3", "frontend", "node-c");
        registerService(client, "web-4", "frontend", "node-d");
        assertEquals(200, request(client, "/v1/agent/check/observe", "PUT",
                observation("web-1", "http", "passing", 1, 10_000, "ok"),
                Map.of("X-Qraft-Node", "node-a")).statusCode());
        assertEquals(200, request(client, "/v1/agent/check/observe", "PUT",
                observation("web-2", "http", "warning", 1, 10_000, "slow"),
                Map.of("X-Qraft-Node", "node-b")).statusCode());
        assertEquals(200, request(client, "/v1/agent/check/observe", "PUT",
                observation("web-3", "tcp", "critical", 1, 10_000, "refused"),
                Map.of("X-Qraft-Node", "node-c")).statusCode());
        long index = store.getLastAppliedIndex();

        HttpResponse<String> all = request(client, "/v1/health/service/frontend", "GET");
        HttpResponse<String> passing = request(client, "/v1/health/service/frontend?passing", "GET");
        HttpResponse<String> passingTrue = request(client, "/v1/health/service/frontend?passing=true", "GET");
        HttpResponse<String> passingFalse = request(client, "/v1/health/service/frontend?passing=false", "GET");
        HttpResponse<String> invalidFilter = request(client, "/v1/health/service/frontend?passing=maybe", "GET");
        HttpResponse<String> unknownQuery = request(client, "/v1/health/service/frontend?stale=true", "GET");
        HttpResponse<String> catalog = request(client, "/v1/catalog/service/frontend", "GET");

        assertEquals(200, all.statusCode(), all.body());
        JsonNode allBody = new ObjectMapper().readTree(all.body());
        assertEquals(List.of("web-1", "web-2", "web-3", "web-4"), serviceIds(allBody));
        assertEquals(List.of("PASSING", "WARNING", "CRITICAL", "UNKNOWN"), healthStates(allBody));
        JsonNode firstEntry = allBody.get(0);
        assertEquals(Set.of("service", "checks"),
                firstEntry.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        JsonNode check = firstEntry.get("checks").get(0);
        assertEquals(Set.of("checkId", "status", "required", "sequenceNumber", "observedAt",
                        "acceptedAt", "deadline", "expired", "output", "deregisterAfterMillis"),
                check.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        assertEquals("http", check.get("checkId").textValue());
        assertEquals("PASSING", check.get("status").textValue());
        assertEquals("2026-09-26T09:59:59Z", check.get("observedAt").textValue());
        assertEquals("2026-09-26T10:00:00Z", check.get("acceptedAt").textValue());
        assertEquals("2026-09-26T10:00:10Z", check.get("deadline").textValue());
        assertEquals(false, check.get("expired").booleanValue());
        assertEquals("ok", check.get("output").textValue());
        assertEquals(0, allBody.get(3).get("checks").size());

        assertEquals(List.of("web-1"), serviceIds(new ObjectMapper().readTree(passing.body())));
        assertEquals(List.of("web-1"), serviceIds(new ObjectMapper().readTree(passingTrue.body())));
        assertEquals(serviceIds(allBody), serviceIds(new ObjectMapper().readTree(passingFalse.body())));
        assertEquals(400, invalidFilter.statusCode(), invalidFilter.body());
        assertErrorEnvelope(invalidFilter, "invalid_query", false);
        assertEquals(400, unknownQuery.statusCode(), unknownQuery.body());
        assertErrorEnvelope(unknownQuery, "invalid_query", false);
        for (HttpResponse<String> read : List.of(all, passing, passingTrue, passingFalse, catalog)) {
            assertEquals(index, Long.parseLong(read.headers().firstValue("X-Qraft-Index").orElseThrow()));
        }
        assertEquals(4, new ObjectMapper().readTree(catalog.body()).size());
        assertEquals(index, store.getLastAppliedIndex());
        assertEquals(3, store.healthChecks().size());
    }

    @Test
    void observationCarriesTheDeregistrationDelayIntoReplicatedStateAndDiscovery() throws Exception {
        QraftStateStore store = startHealthApi(new MutableClockHelper(Instant.parse("2026-09-26T10:00:00Z")));
        HttpClient client = HttpClient.newHttpClient();
        registerService(client, "web", "frontend", "node-a");
        Map<String, String> identity = Map.of("X-Qraft-Node", "node-a");

        HttpResponse<String> withDelay = request(client, "/v1/agent/check/observe", "PUT",
                observation("web", "ttl", "passing", 1, 10_000, null).replace("}", ",\"deregisterAfterMillis\":60000}"),
                identity);
        HttpResponse<String> withoutDelay = request(client, "/v1/agent/check/observe", "PUT",
                observation("web", "http", "passing", 1, 10_000, null), identity);

        assertEquals(200, withDelay.statusCode(), withDelay.body());
        assertEquals(200, withoutDelay.statusCode(), withoutDelay.body());
        ServiceInstanceId instance = new ServiceInstanceId("default", "default", "node-a", "web");
        assertEquals(60_000, store.findHealthCheck(new ServiceCheckId(instance, "ttl"))
                .orElseThrow().observation().deregisterAfterMillis());
        assertEquals(0, store.findHealthCheck(new ServiceCheckId(instance, "http"))
                .orElseThrow().observation().deregisterAfterMillis());
        JsonNode checks = new ObjectMapper().readTree(
                request(client, "/v1/health/service/frontend", "GET").body()).get(0).get("checks");
        assertEquals(0, checks.get(0).get("deregisterAfterMillis").longValue());
        assertEquals(60_000, checks.get(1).get("deregisterAfterMillis").longValue());
    }

    @Test
    void registrationDeclaringChecksPrunesOthersAndRejectsLaterUndeclaredObservations() throws Exception {
        QraftStateStore store = startHealthApi(new MutableClockHelper(Instant.parse("2026-09-26T10:00:00Z")));
        HttpClient client = HttpClient.newHttpClient();
        Map<String, String> identity = Map.of("X-Qraft-Node", "node-a");
        registerService(client, "web", "frontend", "node-a");
        assertEquals(200, request(client, "/v1/agent/check/observe", "PUT",
                observation("web", "http", "passing", 1, 10_000, null), identity).statusCode());

        HttpResponse<String> reRegistered = request(client, "/v1/agent/service/register", "PUT", """
                {"serviceId":"web","serviceName":"frontend","address":"127.0.0.1","port":8080,
                 "checks":["tcp"]}
                """, identity);
        HttpResponse<String> late = request(client, "/v1/agent/check/observe", "PUT",
                observation("web", "http", "critical", 2, 10_000, null), identity);
        HttpResponse<String> declared = request(client, "/v1/agent/check/observe", "PUT",
                observation("web", "tcp", "passing", 1, 10_000, null), identity);

        assertEquals(200, reRegistered.statusCode(), reRegistered.body());
        assertEquals(404, late.statusCode(), late.body());
        JsonNode lateError = assertErrorEnvelope(late, "check_not_declared", false);
        assertEquals("http", lateError.get("checkId").textValue());
        assertEquals(200, declared.statusCode(), declared.body());
        ServiceInstanceId instance = new ServiceInstanceId("default", "default", "node-a", "web");
        assertTrue(store.findHealthCheck(new ServiceCheckId(instance, "http")).isEmpty());
        assertTrue(store.findHealthCheck(new ServiceCheckId(instance, "tcp")).isPresent());

        for (String invalid : List.of("\"tcp\"", "[1]", "[\" \"]", "[null]")) {
            HttpResponse<String> rejected = request(client, "/v1/agent/service/register", "PUT", """
                    {"serviceId":"web","serviceName":"frontend","address":"127.0.0.1","port":8080,
                     "checks":%s}
                    """.formatted(invalid), identity);
            assertEquals(400, rejected.statusCode(), invalid + " -> " + rejected.body());
            assertErrorEnvelope(rejected, "invalid_registration", false);
        }
    }

    @Test
    void membershipTimesAreStampedWithTheServerClockNotTheAgentClock() throws Exception {
        MutableClockHelper clock = new MutableClockHelper(Instant.parse("2026-09-26T10:00:00Z"));
        QraftStateStore store = startHealthApi(clock);
        HttpClient client = HttpClient.newHttpClient();

        assertEquals(201, request(client, "/api/v1/agents/register", "POST", agentRegistration()).statusCode());
        assertEquals(Instant.parse("2026-09-26T10:00:00Z"),
                store.findAgent("agent-1").orElseThrow().getRegistrationTime());

        clock.advance(Duration.ofSeconds(5));
        assertEquals(204, request(client, "/api/v1/agents/heartbeat", "POST", """
                {"agentId":"agent-1","timestamp":"2000-01-01T00:00:00Z","sequenceNumber":1,"status":"passing"}
                """).statusCode());
        assertEquals(Instant.parse("2026-09-26T10:00:05Z"), store.findAgent("agent-1").orElseThrow().getLastHeartbeat(),
                "an agent clock that is wrong cannot move its own membership deadline");
    }

    private QraftStateStore startHealthApi(Clock clock) throws Exception {
        QraftStateStore store = startSingleNode();
        server = new HttpApiServer(0, node, store, clock);
        server.start().join();
        return store;
    }

    private void registerService(HttpClient client, String serviceId, String serviceName,
                                 String nodeId) throws Exception {
        registerService(client, serviceId, serviceName, nodeId, "default", "default");
    }

    private void registerService(HttpClient client, String serviceId, String serviceName, String nodeId,
                                 String tenantId, String namespace) throws Exception {
        String registration = """
                {"serviceId":"%s","serviceName":"%s","address":"127.0.0.1","port":8080}
                """.formatted(serviceId, serviceName);
        HttpResponse<String> response = request(client, "/v1/agent/service/register", "PUT", registration,
                Map.of("X-Qraft-Node", nodeId, "X-Qraft-Tenant", tenantId, "X-Qraft-Namespace", namespace));
        assertEquals(200, response.statusCode(), response.body());
    }

    private static String observation(String serviceId, String checkId, String status, long sequenceNumber,
                                      long ttlMillis, String output) {
        String outputField = output == null ? "" : ",\"output\":\"" + output + "\"";
        return """
                {"serviceId":"%s","checkId":"%s","status":"%s","sequenceNumber":%d,\
                "observedAt":"2026-09-26T09:59:59Z","ttlMillis":%d%s}"""
                .formatted(serviceId, checkId, status, sequenceNumber, ttlMillis, outputField);
    }

    private static List<String> serviceIds(JsonNode entries) {
        List<String> ids = new java.util.ArrayList<>();
        entries.forEach(entry -> ids.add(entry.get("service").get("serviceId").textValue()));
        return ids;
    }

    private static List<String> healthStates(JsonNode entries) {
        List<String> states = new java.util.ArrayList<>();
        entries.forEach(entry -> states.add(entry.get("service").get("health").textValue()));
        return states;
    }

    /** Test clock helper that lets the enclosing tests advance time explicitly. */
    private static final class MutableClockHelper extends Clock {
        private final AtomicReference<Instant> now;

        MutableClockHelper(Instant start) {
            now = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    /** Test storage fixture that persists an append but reports failure to exercise uncertain command outcomes. */
    private static final class AmbiguousAppendStorageFixture implements RaftStorage {
        private final RaftStorage delegate;

        private AmbiguousAppendStorageFixture(RaftStorage delegate) {
            this.delegate = delegate;
        }

        @Override public CompletableFuture<Void> open(Path dataDir) { return delegate.open(dataDir); }
        @Override public CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor) {
            return delegate.updateMetadata(term, votedFor);
        }
        @Override public CompletableFuture<PersistentMeta> loadMetadata() { return delegate.loadMetadata(); }
        /**
         * Persists every append, but reports each one after the bootstrap configuration at index 1 as failed,
         * so the node starts normally and its first uncertain append is the event that fences it.
         */
        @Override public CompletableFuture<Void> appendEntries(List<LogEntryData> entries) {
            if (!entries.isEmpty() && entries.getFirst().index() == 1) return delegate.appendEntries(entries);
            return delegate.appendEntries(entries).thenCompose(ignored -> CompletableFuture.failedFuture(
                    new IllegalStateException("append persisted before completion failed",
                            new InjectedFaultFixture(
                                    dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_WAL_TRANSITION_FAILURE,
                                    "append persisted before completion failed"))));
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

    /** Test storage fixture that holds append completion until the test releases it. */
    private static final class GatedAppendStorageFixture implements RaftStorage {
        private final RaftStorage delegate;
        private final AtomicReference<CompletableFuture<Void>> nextGate = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<Void>> blockedGate = new AtomicReference<>();
        private final CountDownLatch appendBlocked = new CountDownLatch(1);

        private GatedAppendStorageFixture(RaftStorage delegate) {
            this.delegate = delegate;
        }

        void blockNextAppendCompletion() {
            nextGate.set(new CompletableFuture<>());
        }

        void awaitBlockedAppend() throws InterruptedException {
            assertTrue(appendBlocked.await(10, TimeUnit.SECONDS), "append did not reach its gate");
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
        QraftStateStore store = new QraftStateStore();
        startSingleNode(store);
        return store;
    }

    private void startSingleNode(RaftLogApplicator store) throws Exception {
        runtime = JavaRuntime.create();
        InMemoryTransportSimulatorFixture.clearAllTransports();
        InMemoryTransportSimulatorFixture transport = new InMemoryTransportSimulatorFixture("catalog-node");
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
        node.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(node.isLeader());
    }

    /**
     * Test state-machine fixture that declines service registrations while applying other commands.
     *
     * <p>Applies every command to the store except service registrations, which it declines.
     */
    private record RejectingRegistrationsFixture(QraftStateStore store) implements RaftLogApplicator {
        @Override public RaftCommandResult<?> apply(RaftCommand command) {
            return command instanceof CatalogCommand.Register
                    ? new RaftCommandResult.NoOp<>() : store.apply(command);
        }
        @Override public byte[] takeSnapshot() { return store.takeSnapshot(); }
        @Override public void restoreSnapshot(byte[] snapshot) { store.restoreSnapshot(snapshot); }
        @Override public long getLastAppliedIndex() { return store.getLastAppliedIndex(); }
        @Override public void setLastAppliedIndex(long index) { store.setLastAppliedIndex(index); }
        @Override public void reset() { store.reset(); }
    }

    private GatedAppendStorageFixture startGatedHttpNode() throws Exception {
        return startGatedHttpNode(HttpApiServer.DEFAULT_RAFT_TIMEOUT);
    }

    private GatedAppendStorageFixture startGatedHttpNode(Duration raftTimeout) throws Exception {
        runtime = JavaRuntime.create();
        QraftStateStore store = new QraftStateStore();
        RaftStorageFactory.DurableStorage durable = RaftStorageFactory
                .createDurable(directory, true).toCompletionStage().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        GatedAppendStorageFixture gatedWal = new GatedAppendStorageFixture(durable.wal());
        node = RaftNode.builder()
                .runtime(runtime).nodeId("pending-http-node").clusterNodes(Set.of("pending-http-node"))
                .transport(new InMemoryTransportSimulatorFixture("pending-http-node"))
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(gatedWal, durable.snapshots()))
                .snapshotEnabled(false).electionTimeout(25).heartbeatInterval(10_000)
                .build();
        node.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(node.isLeader());
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null, raftTimeout);
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

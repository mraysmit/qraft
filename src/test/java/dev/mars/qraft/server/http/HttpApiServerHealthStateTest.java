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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.InMemoryTransportSimulatorFixture;
import dev.mars.qraft.raft.ManualRaftClusterFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the health-check listing across services that the administrative interface's Health Checks page reads,
 * {@code GET /v1/health/state/{state}} (UI-2 task list, Step 2): each accepted check in the read's scope in the state
 * asked, or all for {@code any}, with its instance, service and node. ACL filtering is tested in
 * {@link HttpApiServerAclEnforcementTest}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-14
 * @version 1.0
 */
class HttpApiServerHealthStateTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> ACME_PROD = Map.of("X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "prod");

    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private HttpApiServer server;
    private QraftStateStore store;
    private RaftNode leader;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void startAServerWithChecksInTwoScopes() throws Exception {
        runtime = RaftExecutorsFixture.create();
        cluster = new ManualRaftClusterFixture(runtime);
        InMemoryTransportSimulatorFixture.clearAllTransports();
        store = new QraftStateStore();
        RaftNode node = cluster.add(cluster.builder("a", Set.of("a"), new InMemoryTransportSimulatorFixture("a"),
                store, RaftNodeMode.volatileMode()));
        ManualRaftClusterFixture.startAll(node);
        leader = cluster.elect(node);
        server = new HttpApiServer(0, leader, store);
        server.start().join();

        ok(send("PUT", "/v1/tenant", "{\"Name\":\"acme\"}", Map.of()));
        ok(send("PUT", "/v1/namespace", "{\"Name\":\"prod\"}", Map.of("X-Qraft-Tenant", "acme")));
        ok(send("PUT", "/v1/catalog/register", "{\"node\":{\"address\":\"10.0.0.1\"}}", Map.of("X-Qraft-Node", "node-1")));
        register("node-1", Map.of(), "web-1", "web", "[\"http\",\"tcp\"]");
        register("node-2", Map.of(), "web-2", "web", "[\"http\"]");
        register("node-2", Map.of(), "db-1", "db", "[\"tcp\"]");
        register("node-3", ACME_PROD, "pay-1", "payments", "[\"http\"]");
        observe("node-1", Map.of(), "web-1", "http", "passing");
        observe("node-1", Map.of(), "web-1", "tcp", "warning");
        observe("node-2", Map.of(), "web-2", "http", "critical");
        observe("node-2", Map.of(), "db-1", "tcp", "passing");
        observe("node-3", ACME_PROD, "pay-1", "http", "critical");
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (server != null) server.close();
            cluster.close();
        } finally {
            runtime.close().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
        }
    }

    @Test
    void anyListsEveryCheckInTheScopeByServiceInstanceAndCheck() throws Exception {
        HttpResponse<String> response = send("GET", "/v1/health/state/any", null, Map.of());

        JsonNode checks = ok(response);
        assertEquals(List.of("db/db-1/tcp", "web/web-1/http", "web/web-1/tcp", "web/web-2/http"), keys(checks));
        assertTrue(response.headers().firstValue("X-Qraft-Index").orElse("").matches("\\d+"));
        JsonNode first = checks.get(1);
        assertEquals("web-1", first.path("serviceId").asText());
        assertEquals("web", first.path("serviceName").asText());
        assertEquals("node-1", first.path("nodeId").asText());
        assertEquals("registering", first.path("nodeStatus").asText(), "node-1 is registered and has not beaten");
        assertEquals("default", first.path("tenantId").asText());
        assertEquals("default", first.path("namespace").asText());
        assertEquals("http", first.path("checkId").asText());
        assertEquals("PASSING", first.path("status").asText());
        assertEquals("2026-11-14T09:59:59Z", first.path("observedAt").asText());
        assertTrue(first.path("acceptedAt").isTextual() && first.path("deadline").isTextual(), first.toString());
        assertFalse(first.path("expired").asBoolean());
        assertEquals("out", first.path("output").asText());
        assertTrue(checks.get(3).path("nodeStatus").isNull(), "node-2 has no entry");
    }

    @Test
    void aStateListsOnlyTheChecksInIt() throws Exception {
        assertEquals(List.of("web/web-2/http"), keys(ok(send("GET", "/v1/health/state/critical", null, Map.of()))));
        assertEquals(List.of("web/web-1/tcp"), keys(ok(send("GET", "/v1/health/state/warning", null, Map.of()))));
        assertEquals(List.of("db/db-1/tcp", "web/web-1/http"),
                keys(ok(send("GET", "/v1/health/state/passing", null, Map.of()))));
        assertEquals(List.of(), keys(ok(send("GET", "/v1/health/state/maintenance", null, Map.of()))));
        assertEquals(List.of(), keys(ok(send("GET", "/v1/health/state/unknown", null, Map.of()))));
    }

    @Test
    void anInstanceHeldAsLegacyFailingIsCountedAsCritical() throws Exception {
        // FAILING is kept only so an older snapshot loads; no check result can hold it (HealthObservation refuses it),
        // and the aggregate counts it as critical, so the summary does too rather than leave the instance uncounted.
        assertTrue(leader.submitCommand(dev.mars.qraft.state.CatalogCommand.register(
                new dev.mars.qraft.state.catalog.ServiceInstance("old-1", "old", "node-9", "10.0.0.9", 80, List.of(),
                        Map.of(), dev.mars.qraft.state.catalog.ServiceHealth.FAILING)))
                .get(10, TimeUnit.SECONDS) instanceof dev.mars.qraft.raft.RaftCommandResult.Success<?>);
        assertEquals(dev.mars.qraft.state.catalog.ServiceHealth.FAILING, store.getServiceCatalog().instances().stream()
                .filter(instance -> instance.serviceId().equals("old-1")).findFirst().orElseThrow().health(),
                "the state holds the legacy health as given");

        JsonNode old = ok(send("GET", "/v1/internal/ui/services", null, Map.of())).get(1);

        assertEquals("old", old.path("name").asText());
        assertEquals(1, old.path("health").path("critical").asInt(), old.toString());
    }

    @Test
    void theStatesAndCountsAreTheFiveDocumentedOnes() throws Exception {
        JsonNode health = ok(send("GET", "/v1/internal/ui/services", null, Map.of())).get(0).path("health");
        List<String> keys = new ArrayList<>();
        health.fieldNames().forEachRemaining(keys::add);

        assertEquals(List.of("unknown", "passing", "warning", "critical", "maintenance"), keys);
    }

    @Test
    void theScopeTheHeadersNameIsTheOneRead() throws Exception {
        JsonNode checks = ok(send("GET", "/v1/health/state/any", null, ACME_PROD));

        assertEquals(List.of("payments/pay-1/http"), keys(checks));
        assertEquals("acme", checks.get(0).path("tenantId").asText());
        assertEquals("prod", checks.get(0).path("namespace").asText());
    }

    @Test
    void anUnknownStateOrParameterIsRefused() throws Exception {
        for (String path : List.of("/v1/health/state/failing", "/v1/health/state/", "/v1/health/state")) {
            HttpResponse<String> refused = send("GET", path, null, Map.of());
            assertEquals(400, refused.statusCode(), path + " " + refused.body());
            assertEquals("invalid_state", JSON.readTree(refused.body()).path("code").asText(), path);
        }
        HttpResponse<String> tagged = send("GET", "/v1/health/state/any?service=web", null, Map.of());
        assertEquals(400, tagged.statusCode());
        assertEquals("invalid_query", JSON.readTree(tagged.body()).path("code").asText());
        assertEquals(200, send("GET", "/v1/health/state/any?stale", null, Map.of()).statusCode());
    }

    private void register(String node, Map<String, String> scope, String id, String name, String checks)
            throws Exception {
        Map<String, String> headers = new LinkedHashMap<>(scope);
        headers.put("X-Qraft-Node", node);
        ok(send("PUT", "/v1/catalog/register", """
                {"service":{"serviceId":"%s","serviceName":"%s","address":"10.0.0.5","port":8080,"checks":%s}}"""
                .formatted(id, name, checks), headers));
    }

    private void observe(String node, Map<String, String> scope, String serviceId, String checkId, String status)
            throws Exception {
        Map<String, String> headers = new LinkedHashMap<>(scope);
        headers.put("X-Qraft-Node", node);
        ok(send("PUT", "/v1/catalog/check/observe", """
                {"serviceId":"%s","checkId":"%s","status":"%s","sequenceNumber":1,\
                "observedAt":"2026-11-14T09:59:59Z","ttlMillis":600000,"output":"out"}"""
                .formatted(serviceId, checkId, status), headers));
    }

    private HttpResponse<String> send(String method, String path, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.request().uri() + " " + response.body());
        return JSON.readTree(response.body());
    }

    /** Each check as {@code service/instance/check}. */
    private static List<String> keys(JsonNode checks) {
        List<String> keys = new ArrayList<>();
        checks.forEach(check -> keys.add(check.path("serviceName").asText() + "/" + check.path("serviceId").asText()
                + "/" + check.path("checkId").asText()));
        return keys;
    }
}

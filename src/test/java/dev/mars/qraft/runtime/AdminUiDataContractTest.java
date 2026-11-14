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
import dev.mars.qraft.client.catalog.CatalogOutcome;
import dev.mars.qraft.client.catalog.HttpCatalogClient;
import dev.mars.qraft.client.health.CheckObservation;
import dev.mars.qraft.client.health.CheckStatus;
import dev.mars.qraft.client.health.ObservationOutcome;
import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.ServiceDefinition;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.http.HttpApiServer;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.server.ui.ClasspathUiAssets;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The administrative interface's Stage 1 views read a server in server mode through the same public API this test
 * reads: below the base path the page's bootstrap object names, the reads of {@code src/main/ui/src/api/client.ts},
 * answered in the fields and types its zod schemas ({@code schemas.ts}) require. A field renamed or retyped on the
 * server fails here, and not only in a browser.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-14
 * @version 1.0
 */
class AdminUiDataContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern BOOTSTRAP = Pattern.compile(
            "<script id=\"qraft-bootstrap\" type=\"application/json\" nonce=\"[^\"]+\">(.*?)</script>");
    private static final Set<String> HEALTH = Set.of("UNKNOWN", "PASSING", "WARNING", "CRITICAL", "MAINTENANCE");
    private static final Set<String> NODE_STATUS = Set.of("registering", "healthy", "unreachable");

    /** The JSON types of schemas.ts. */
    private enum Type { TEXT, TEXT_OR_NULL, INTEGER, BOOLEAN, TEXT_MAP, TEXT_LIST, LIST, OBJECT }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private HttpApiServer server;
    private HttpCatalogClient catalog;

    @AfterEach
    void stop() throws Exception {
        new CleanupHelper()
                .run(() -> { if (catalog != null) catalog.close(); })
                .run(() -> { if (server != null) server.close(); })
                .run(() -> { if (node != null) node.stop().get(10, TimeUnit.SECONDS); })
                .run(() -> { if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS); })
                .run(http::close)
                .rethrow();
    }

    @Test
    void theViewsDataIsAnsweredBelowTheBootstrapsBasePathInTheFieldsTheInterfaceReads() throws Exception {
        startServer();
        URI origin = URI.create("http://127.0.0.1:" + server.port());
        catalog = new HttpCatalogClient(HttpClient.newHttpClient(), JSON, List.of(origin), "client-a", "default",
                "default", "dc1", "eu-west", Duration.ofSeconds(2));
        assertInstanceOf(CatalogOutcome.Success.class, catalog.registerNode(Node.of("client-a", "127.0.0.1", "dc1",
                "eu-west", Map.of("qraft.version", "1.0-test"))).get(10, TimeUnit.SECONDS));
        assertInstanceOf(CatalogOutcome.Success.class, catalog.register(new ServiceDefinition("web-1", "web",
                "127.0.0.1", 8081, List.of("http"), Map.of("owner", "platform"), true).withCheckIds(List.of("http")))
                .get(10, TimeUnit.SECONDS));
        assertInstanceOf(ObservationOutcome.Accepted.class, catalog.observe(new CheckObservation("web-1", "http",
                CheckStatus.PASSING, 1, Instant.now(), Duration.ofSeconds(30), true, "200 OK", Duration.ZERO))
                .get(10, TimeUnit.SECONDS));

        String apiBase = bootstrap(get(origin, "/ui/")).path("apiBasePath").asText();
        URI api = origin.resolve(apiBase);

        JsonNode status = read(api, "raft/status", false);
        fields(status, "raft status", Map.ofEntries(Map.entry("nodeId", Type.TEXT),
                Map.entry("serverId", Type.TEXT_OR_NULL), Map.entry("state", Type.TEXT), Map.entry("term", Type.INTEGER),
                Map.entry("leaderId", Type.TEXT_OR_NULL), Map.entry("commitIndex", Type.INTEGER),
                Map.entry("lastApplied", Type.INTEGER), Map.entry("lastLogIndex", Type.INTEGER),
                Map.entry("snapshotLastIndex", Type.INTEGER), Map.entry("fenced", Type.BOOLEAN),
                Map.entry("fenceReason", Type.TEXT_OR_NULL), Map.entry("removed", Type.BOOLEAN)));
        assertTrue(Set.of("FOLLOWER", "CANDIDATE", "LEADER").contains(status.path("state").asText()), status.toString());

        assertEquals("ready", read(api, "health/ready", false).path("status").asText());

        JsonNode services = read(api, "v1/catalog/services", true);
        assertTrue(services.isObject(), services.toString());
        assertEquals(List.of("http"), JSON.convertValue(services.path("web"), List.class));

        JsonNode entries = read(api, "v1/health/service/web", true);
        assertEquals(1, entries.size(), entries.toString());
        JsonNode entry = entries.get(0);
        fields(entry, "health entry", Map.of("service", Type.OBJECT, "checks", Type.LIST));
        assertTrue(NODE_STATUS.contains(entry.path("nodeStatus").asText()), entry.toString());
        fields(entry.path("service"), "service instance", Map.ofEntries(Map.entry("serviceId", Type.TEXT),
                Map.entry("serviceName", Type.TEXT), Map.entry("nodeId", Type.TEXT_OR_NULL),
                Map.entry("address", Type.TEXT), Map.entry("port", Type.INTEGER), Map.entry("tags", Type.TEXT_LIST),
                Map.entry("metadata", Type.TEXT_MAP), Map.entry("health", Type.TEXT), Map.entry("tenantId", Type.TEXT),
                Map.entry("namespace", Type.TEXT), Map.entry("datacenter", Type.TEXT_OR_NULL),
                Map.entry("region", Type.TEXT_OR_NULL), Map.entry("enabled", Type.BOOLEAN),
                Map.entry("createIndex", Type.INTEGER), Map.entry("modifyIndex", Type.INTEGER)));
        assertTrue(HEALTH.contains(entry.path("service").path("health").asText()), entry.toString());
        JsonNode check = entry.path("checks").path(0);
        fields(check, "check", Map.ofEntries(Map.entry("checkId", Type.TEXT), Map.entry("status", Type.TEXT),
                Map.entry("required", Type.BOOLEAN), Map.entry("sequenceNumber", Type.INTEGER),
                Map.entry("observedAt", Type.TEXT), Map.entry("acceptedAt", Type.TEXT), Map.entry("deadline", Type.TEXT),
                Map.entry("expired", Type.BOOLEAN), Map.entry("output", Type.TEXT_OR_NULL),
                Map.entry("deregisterAfterMillis", Type.INTEGER)));
        assertEquals("PASSING", check.path("status").asText());

        JsonNode nodes = read(api, "v1/catalog/nodes", true);
        assertEquals(1, nodes.size(), nodes.toString());
        fields(nodes.get(0), "node", Map.ofEntries(Map.entry("name", Type.TEXT), Map.entry("id", Type.TEXT_OR_NULL),
                Map.entry("address", Type.TEXT), Map.entry("datacenter", Type.TEXT_OR_NULL),
                Map.entry("region", Type.TEXT_OR_NULL), Map.entry("metadata", Type.TEXT_MAP),
                Map.entry("status", Type.TEXT), Map.entry("registrationTime", Type.TEXT_OR_NULL),
                Map.entry("lastHeartbeat", Type.TEXT_OR_NULL)));
        assertTrue(NODE_STATUS.contains(nodes.get(0).path("status").asText()), nodes.toString());
        assertEquals("1.0-test", nodes.get(0).path("metadata").path("qraft.version").asText());
    }

    @Test
    void aRefusedReadAnswersTheErrorEnvelopeTheInterfaceReads() throws Exception {
        startServer();
        URI origin = URI.create("http://127.0.0.1:" + server.port());

        HttpResponse<String> refused = get(origin, "/v1/catalog/services?dc=dc-2");

        assertEquals(400, refused.statusCode());
        JsonNode envelope = JSON.readTree(refused.body());
        fields(envelope, "error envelope", Map.of("code", Type.TEXT, "message", Type.TEXT, "retryable", Type.BOOLEAN,
                "requestId", Type.TEXT_OR_NULL));
        assertEquals(refused.headers().firstValue("X-Request-Id").orElse(""), envelope.path("requestId").asText());
    }

    @Test
    void theScopedReadsAreAnsweredInTheScopeTheHeadersNameInTheFieldsTheInterfaceReads() throws Exception {
        // UI-2 task list, Step 4: the scope selector's tenancy reads, the services summary and the check listing, as
        // client.ts sends them and schemas.ts reads them.
        startServer();
        URI origin = URI.create("http://127.0.0.1:" + server.port());
        assertEquals(200, send(origin, "PUT", "/v1/tenant", "{\"Name\":\"acme\"}", Map.of()).statusCode());
        assertEquals(200, send(origin, "PUT", "/v1/namespace", "{\"Name\":\"prod\"}", Map.of("X-Qraft-Tenant", "acme"))
                .statusCode());
        catalog = new HttpCatalogClient(HttpClient.newHttpClient(), JSON, List.of(origin), "client-b", "acme", "prod",
                "dc1", "eu-west", Duration.ofSeconds(2));
        assertInstanceOf(CatalogOutcome.Success.class, catalog.register(new ServiceDefinition("pay-1", "payments",
                "127.0.0.1", 8082, List.of("v2"), Map.of(), true).withCheckIds(List.of("http")))
                .get(10, TimeUnit.SECONDS));
        assertInstanceOf(ObservationOutcome.Accepted.class, catalog.observe(new CheckObservation("pay-1", "http",
                CheckStatus.WARNING, 1, Instant.now(), Duration.ofSeconds(30), true, "slow", Duration.ZERO))
                .get(10, TimeUnit.SECONDS));
        URI api = origin.resolve(bootstrap(get(origin, "/ui/")).path("apiBasePath").asText());
        Map<String, String> acmeProd = Map.of("X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "prod");

        JsonNode tenants = read(api, "v1/tenants", false, Map.of());
        for (JsonNode tenant : tenants) fields(tenant, "tenant", Map.of("Name", Type.TEXT, "Description", Type.TEXT));
        assertTrue(tenants.findValuesAsText("Name").contains("acme"), tenants.toString());
        JsonNode namespaces = read(api, "v1/namespaces", false, Map.of("X-Qraft-Tenant", "acme"));
        for (JsonNode namespace : namespaces) {
            fields(namespace, "namespace", Map.of("Name", Type.TEXT, "Tenant", Type.TEXT, "Description", Type.TEXT));
        }
        assertEquals(List.of("default", "prod"), namespaces.findValuesAsText("Name"));

        JsonNode summary = read(api, "v1/internal/ui/services", true, acmeProd);
        assertEquals(1, summary.size(), summary.toString());
        fields(summary.get(0), "service summary", Map.ofEntries(Map.entry("name", Type.TEXT),
                Map.entry("tenantId", Type.TEXT), Map.entry("namespace", Type.TEXT), Map.entry("tags", Type.TEXT_LIST),
                Map.entry("instanceCount", Type.INTEGER), Map.entry("health", Type.OBJECT),
                Map.entry("datacenters", Type.TEXT_LIST), Map.entry("regions", Type.TEXT_LIST),
                Map.entry("modifyIndex", Type.INTEGER)));
        fields(summary.get(0).path("health"), "health counts", Map.of("unknown", Type.INTEGER, "passing", Type.INTEGER,
                "warning", Type.INTEGER, "critical", Type.INTEGER, "maintenance", Type.INTEGER));
        assertEquals("prod", summary.get(0).path("namespace").asText());
        assertEquals(1, summary.get(0).path("health").path("warning").asInt());

        JsonNode checks = read(api, "v1/health/state/any", true, acmeProd);
        assertEquals(1, checks.size(), checks.toString());
        fields(checks.get(0), "check entry", Map.ofEntries(Map.entry("serviceId", Type.TEXT),
                Map.entry("serviceName", Type.TEXT), Map.entry("nodeId", Type.TEXT_OR_NULL),
                Map.entry("tenantId", Type.TEXT), Map.entry("namespace", Type.TEXT), Map.entry("checkId", Type.TEXT),
                Map.entry("status", Type.TEXT), Map.entry("required", Type.BOOLEAN),
                Map.entry("sequenceNumber", Type.INTEGER), Map.entry("observedAt", Type.TEXT),
                Map.entry("acceptedAt", Type.TEXT), Map.entry("deadline", Type.TEXT), Map.entry("expired", Type.BOOLEAN),
                Map.entry("output", Type.TEXT_OR_NULL), Map.entry("deregisterAfterMillis", Type.INTEGER)));
        assertTrue(checks.get(0).has("nodeStatus"), "nodeStatus is present, null for a node with no entry");
        assertTrue(HEALTH.contains(checks.get(0).path("status").asText()), checks.toString());
        assertEquals(0, read(api, "v1/health/state/any", true, Map.of()).size(), "the default scope holds no check");
    }

    @Test
    void theOperatorReadsAreAnsweredInTheFieldsTheOperateViewsRead() throws Exception {
        // UI-3 task list, Phase 2: the membership and storage reads, as client.ts sends them and schemas.ts reads them.
        startServer();
        URI origin = URI.create("http://127.0.0.1:" + server.port());
        URI api = origin.resolve(bootstrap(get(origin, "/ui/")).path("apiBasePath").asText());

        JsonNode members = read(api, "v1/operator/raft/members", false);
        fields(members, "membership", Map.of("view", Type.TEXT, "answeredBy", Type.TEXT, "leaderId", Type.TEXT_OR_NULL,
                "term", Type.INTEGER, "commitIndex", Type.INTEGER, "lastLogIndex", Type.INTEGER, "quorum", Type.OBJECT,
                "servers", Type.LIST));
        assertEquals("leader", members.path("view").asText(), "the single server leads");
        fields(members.path("quorum"), "quorum", Map.of("voters", Type.INTEGER, "quorumSize", Type.INTEGER,
                "reachableVoters", Type.INTEGER, "failureTolerance", Type.INTEGER, "healthy", Type.BOOLEAN));
        fields(members.path("servers").get(0), "member", Map.ofEntries(Map.entry("serverId", Type.TEXT),
                Map.entry("name", Type.TEXT), Map.entry("address", Type.TEXT), Map.entry("voter", Type.BOOLEAN),
                Map.entry("leader", Type.BOOLEAN), Map.entry("self", Type.BOOLEAN), Map.entry("matchIndex", Type.INTEGER),
                Map.entry("nextIndex", Type.INTEGER), Map.entry("lag", Type.INTEGER),
                Map.entry("lastContactMillis", Type.INTEGER), Map.entry("reachable", Type.BOOLEAN),
                Map.entry("healthy", Type.BOOLEAN)));

        JsonNode storage = read(api, "v1/operator/raft/storage", false);
        fields(storage, "storage", Map.ofEntries(Map.entry("answeredBy", Type.TEXT),
                Map.entry("snapshotLastIndex", Type.INTEGER), Map.entry("snapshotLastTerm", Type.INTEGER),
                Map.entry("firstLogIndex", Type.INTEGER), Map.entry("lastLogIndex", Type.INTEGER),
                Map.entry("commitIndex", Type.INTEGER), Map.entry("lastApplied", Type.INTEGER),
                Map.entry("fenced", Type.BOOLEAN), Map.entry("fenceReason", Type.TEXT_OR_NULL),
                Map.entry("transitionQueueDepth", Type.INTEGER)));
        for (String nullable : List.of("storageBytes", "snapshotAgeSeconds", "lastSnapshotFailure")) {
            assertTrue(storage.has(nullable), nullable + " is present, null where unknown: " + storage);
        }
    }

    private HttpResponse<String> send(URI origin, String method, String path, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A read below the API base path: 200, JSON, and, for a catalog read, the applied index the views show. */
    private JsonNode read(URI api, String path, boolean indexed) throws Exception {
        return read(api, path, indexed, Map.of());
    }

    private JsonNode read(URI api, String path, boolean indexed, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(api.resolve(path))
                .header("Accept", "application/json").timeout(Duration.ofSeconds(5)).GET();
        headers.forEach(request::header);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " " + response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), path);
        if (indexed) {
            assertTrue(response.headers().firstValue("X-Qraft-Index").orElse("").matches("\\d+"),
                    path + " carries its applied index");
        }
        return JSON.readTree(response.body());
    }

    private static void fields(JsonNode object, String what, Map<String, Type> expected) {
        Map<String, String> wrong = new LinkedHashMap<>();
        expected.forEach((name, type) -> {
            JsonNode value = object.get(name);
            if (value == null || !is(value, type)) wrong.put(name, type + " but was " + value);
        });
        if (!wrong.isEmpty()) fail(what + " fields not as the interface reads them: " + wrong + " in " + object);
    }

    private static boolean is(JsonNode value, Type type) {
        return switch (type) {
            case TEXT -> value.isTextual();
            case TEXT_OR_NULL -> value.isTextual() || value.isNull();
            case INTEGER -> value.isIntegralNumber();
            case BOOLEAN -> value.isBoolean();
            case OBJECT -> value.isObject();
            case LIST -> value.isArray();
            case TEXT_LIST -> value.isArray() && allMatch(value.elements(), JsonNode::isTextual);
            case TEXT_MAP -> value.isObject() && allMatch(value.elements(), JsonNode::isTextual);
        };
    }

    private static boolean allMatch(java.util.Iterator<JsonNode> values, java.util.function.Predicate<JsonNode> test) {
        while (values.hasNext()) {
            if (!test.test(values.next())) return false;
        }
        return true;
    }

    private static JsonNode bootstrap(HttpResponse<String> index) throws Exception {
        assertEquals(200, index.statusCode());
        Matcher element = BOOTSTRAP.matcher(index.body());
        assertTrue(element.find(), "no bootstrap element in " + index.body());
        return JSON.readTree(element.group(1));
    }

    private HttpResponse<String> get(URI origin, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void startServer() throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder()
                .stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("server-1")
                .clusterNodes(Set.of("server-1"))
                .transport(new PeerlessTransportFixture())
                .stateMachine(store)
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode())
                .electionTimeout(25)
                .heartbeatInterval(10_000)
                .build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(node.isLeader(), "the single server did not become leader");
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), new AdminUiConfig(true, "/ui/", Optional.empty()),
                new ClasspathUiAssets(AdminUiDataContractTest.class.getClassLoader(), "ui-fixture/"));
        server.start().get(10, TimeUnit.SECONDS);
    }
}

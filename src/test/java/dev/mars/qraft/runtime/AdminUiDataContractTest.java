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
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.common.events.EventJournal;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.config.AppConfig;
import dev.mars.qraft.server.config.SecurityProfile;
import dev.mars.qraft.server.http.AclHttpSettings;
import dev.mars.qraft.server.http.HttpApiServer;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.server.ui.ClasspathUiAssets;
import dev.mars.qraft.state.NodeCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private QraftStateStore store;
    private HttpApiServer server;
    private HttpCatalogClient catalog;
    private final EventJournal journal = EventJournal.forBoot("server-1", Clock.systemUTC());

    @TempDir
    Path dataDirectory;

    @AfterEach
    void stop() throws Exception {
        new CleanupHelper()
                .run(() -> { if (catalog != null) catalog.close(); })
                .run(() -> { if (server != null) server.close(); })
                .run(() -> { if (node != null) node.stop().get(10, TimeUnit.SECONDS); })
                .run(() -> { if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS); })
                .run(journal::close)
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

    @Test
    void theEventConfigurationAndReadMetadataAreAnsweredInTheFieldsTheInterfaceReads() throws Exception {
        // UI-4 task list, Phase 0: the event query, the configuration read, and the headers naming the answering
        // server and the consistency, as client.ts sends them and schemas.ts reads them.
        startServer();
        URI origin = URI.create("http://127.0.0.1:" + server.port());
        catalog = new HttpCatalogClient(HttpClient.newHttpClient(), JSON, List.of(origin), "client-c", "default",
                "default", "dc1", "eu-west", Duration.ofSeconds(2));
        assertInstanceOf(CatalogOutcome.Success.class, catalog.register(new ServiceDefinition("web-1", "web",
                "127.0.0.1", 8081, List.of(), Map.of(), true)).get(10, TimeUnit.SECONDS));
        URI api = origin.resolve(bootstrap(get(origin, "/ui/")).path("apiBasePath").asText());

        JsonNode page = read(api, "v1/operator/events?service=web&tenant=default&namespace=default&limit=1000", false);
        fields(page, "event page", Map.of("events", Type.LIST, "next", Type.TEXT, "status", Type.TEXT,
                "capacity", Type.INTEGER));
        assertEquals(1, page.path("events").size(), page.toString());
        JsonNode event = page.path("events").get(0);
        fields(event, "event", Map.ofEntries(Map.entry("cursor", Type.TEXT), Map.entry("node", Type.TEXT),
                Map.entry("observedAt", Type.TEXT), Map.entry("category", Type.TEXT), Map.entry("severity", Type.TEXT),
                Map.entry("term", Type.INTEGER), Map.entry("committedIndex", Type.INTEGER),
                Map.entry("tenant", Type.TEXT), Map.entry("namespace", Type.TEXT), Map.entry("type", Type.TEXT),
                Map.entry("event", Type.OBJECT)));
        assertEquals("web", event.path("event").path("serviceName").asText());
        JsonNode system = read(api, "v1/operator/events?category=system", false).path("events").get(0);
        fields(system, "system event", Map.of("cursor", Type.TEXT, "severity", Type.TEXT, "type", Type.TEXT));
        assertTrue(!system.has("tenant") && !system.has("committedIndex"), "absent, not null: " + system);

        JsonNode configuration = read(api, "v1/operator/configuration", false);
        fields(configuration, "configuration", Map.of("server", Type.TEXT_OR_NULL, "file", Type.TEXT_OR_NULL,
                "readAt", Type.TEXT, "settings", Type.LIST));
        for (JsonNode setting : configuration.path("settings")) {
            fields(setting, "setting", Map.of("key", Type.TEXT, "source", Type.TEXT, "redacted", Type.BOOLEAN,
                    "restartRequired", Type.BOOLEAN));
            assertTrue(setting.has("value"), "value is present, null where none: " + setting);
        }

        for (String query : new String[] {"", "?stale", "?consistent"}) {
            HttpResponse<String> nodes = http.send(HttpRequest.newBuilder(api.resolve("v1/catalog/nodes" + query))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("server-1", nodes.headers().firstValue("X-Qraft-Answered-By").orElse(null), query);
            assertEquals(query.isEmpty() ? "default" : query.substring(1),
                    nodes.headers().firstValue("X-Qraft-Consistency").orElse(null), query);
        }
    }

    @Test
    void theAccessAnswersAndTheWritesAreAnsweredInTheFieldsTheInterfaceReads() throws Exception {
        // UI-5 task list, Phase 0: sign-in by self, the token menu, permission-shaped navigation, the security status,
        // Tokens, Policies, explanations, and a service's registration and deregistration, as client.ts sends them.
        startServer(new AclHttpSettings(true, dataDirectory, AclDefaultPolicy.DENY), SecurityProfile.PRODUCTION);
        URI origin = URI.create("http://127.0.0.1:" + server.port());
        assertEquals("token", bootstrap(get(origin, "/ui/")).path("authMode").asText());
        HttpResponse<String> bootstrapped = send(origin, "PUT", "/v1/acl/bootstrap", "", Map.of());
        assertEquals(200, bootstrapped.statusCode(), bootstrapped.body());
        Map<String, String> management = Map.of("X-Qraft-Token",
                JSON.readTree(bootstrapped.body()).path("secretId").asText());

        HttpResponse<String> unknown = send(origin, "GET", "/v1/acl/token/self", "",
                Map.of("X-Qraft-Token", "00000000-0000-4000-8000-00000000abcd"));
        assertEquals(403, unknown.statusCode());
        assertEquals("token_not_found", JSON.readTree(unknown.body()).path("code").asText(),
                "sign-in tells an unknown secret by its code");

        JsonNode self = written(origin, "GET", "/v1/acl/token/self", "", management);
        token(self, "self");
        assertEquals("<hidden>", self.path("secretId").asText());
        assertEquals("global-management", self.path("policies").path(0).path("name").asText());

        JsonNode authorized = written(origin, "POST", "/v1/internal/acl/authorize",
                "[{\"resource\":\"operator\",\"access\":\"write\"},{\"resource\":\"acl\",\"access\":\"read\"},"
                        + "{\"resource\":\"service\",\"segment\":\"web\",\"access\":\"write\","
                        + "\"tenant\":\"default\",\"namespace\":\"default\"}]", management);
        assertEquals(3, authorized.size(), authorized.toString());
        authorized.forEach(check -> fields(check, "authorization", Map.of("resource", Type.TEXT, "access", Type.TEXT,
                "allow", Type.BOOLEAN)));

        JsonNode policy = written(origin, "PUT", "/v1/acl/policy",
                "{\"name\":\"web-writer\",\"description\":\"writes web\",\"rules\":"
                        + "{\"service\":{\"web\":{\"policy\":\"write\"}}}}", management);
        fields(policy, "policy", Map.of("id", Type.TEXT, "name", Type.TEXT, "description", Type.TEXT_OR_NULL,
                "rules", Type.OBJECT));
        JsonNode policies = written(origin, "GET", "/v1/acl/policies", "", management);
        assertTrue(policies.size() >= 3, "the two built-ins and the new one: " + policies);

        JsonNode created = written(origin, "PUT", "/v1/acl/token", "{\"description\":\"web deployer\","
                + "\"policies\":[{\"name\":\"web-writer\"}],\"nodeIdentities\":[{\"nodeName\":\"node-a\"}],"
                + "\"serviceIdentities\":[{\"serviceName\":\"api\",\"tenant\":\"default\","
                + "\"namespace\":\"default\"}],\"expirationTtl\":\"30m\"}", management);
        token(created, "created token");
        assertFalse(created.path("secretId").asText().equals("<hidden>"), "the create answers the secret once");
        assertTrue(created.path("expirationTime").isTextual());
        String accessor = created.path("accessorId").asText();
        JsonNode linked = written(origin, "GET", "/v1/acl/tokens?policy=" + policy.path("id").asText(), "", management);
        assertEquals(accessor, linked.path(0).path("accessorId").asText());
        assertEquals("<hidden>", linked.path(0).path("secretId").asText());
        token(written(origin, "PUT", "/v1/acl/token/" + accessor, "{\"description\":\"web deployer, renamed\","
                + "\"policies\":[{\"name\":\"web-writer\"}]}", management), "updated token");

        JsonNode explained = written(origin, "POST", "/v1/acl/explain", "{\"accessorId\":\"" + accessor + "\","
                + "\"resource\":\"service\",\"name\":\"web\",\"access\":\"write\"}", management);
        fields(explained, "explanation", Map.of("accessorId", Type.TEXT, "allowed", Type.BOOLEAN,
                "defaultPolicy", Type.TEXT, "decidedBy", Type.TEXT, "policy", Type.OBJECT, "rule", Type.OBJECT));
        fields(explained.path("rule"), "explained rule", Map.of("resource", Type.TEXT, "prefix", Type.BOOLEAN,
                "access", Type.TEXT));

        JsonNode status = written(origin, "GET", "/v1/internal/ui/acl/status", "", management);
        fields(status, "security status", Map.ofEntries(Map.entry("enabled", Type.BOOLEAN),
                Map.entry("defaultPolicy", Type.TEXT), Map.entry("securityProfile", Type.TEXT),
                Map.entry("transport", Type.OBJECT), Map.entry("readAt", Type.TEXT),
                Map.entry("expiringWithinMinutes", Type.INTEGER), Map.entry("managementTokens", Type.LIST),
                Map.entry("anonymous", Type.OBJECT), Map.entry("aclWriters", Type.LIST),
                Map.entry("expiringSoon", Type.LIST)));
        fields(status.path("transport"), "transport", Map.of("https", Type.BOOLEAN,
                "clientCertificateRequired", Type.BOOLEAN));
        fields(status.path("managementTokens").path(0), "management token", Map.of("accessorId", Type.TEXT,
                "description", Type.TEXT_OR_NULL, "installedFromFile", Type.BOOLEAN, "expirationTime",
                Type.TEXT_OR_NULL));
        fields(status.path("anonymous"), "anonymous", Map.of("accessorId", Type.TEXT, "policies", Type.LIST,
                "writes", Type.TEXT_LIST));
        fields(status.path("aclWriters").path(0), "acl writer", Map.of("accessorId", Type.TEXT,
                "description", Type.TEXT_OR_NULL));
        JsonNode expiring = status.path("expiringSoon").path(0);
        fields(expiring, "expiring token", Map.of("accessorId", Type.TEXT, "description", Type.TEXT_OR_NULL,
                "expirationTime", Type.TEXT, "nodeIdentities", Type.LIST, "serviceIdentities", Type.LIST,
                "holdsAclWrite", Type.BOOLEAN));
        assertEquals(accessor, expiring.path("accessorId").asText());

        Map<String, String> onNode = new LinkedHashMap<>(management);
        onNode.put("X-Qraft-Node", "node-a");
        onNode.put("X-Qraft-Tenant", "default");
        onNode.put("X-Qraft-Namespace", "default");
        JsonNode registered = written(origin, "PUT", "/v1/catalog/register", "{\"node\":{\"address\":\"127.0.0.1\"},"
                + "\"service\":{\"serviceId\":\"web-1\",\"serviceName\":\"web\",\"address\":\"127.0.0.1\","
                + "\"port\":8081,\"tags\":[\"http\"],\"metadata\":{},\"enabled\":false}}", onNode);
        fields(registered, "registration", Map.of("node", Type.TEXT, "service", Type.OBJECT));
        fields(registered.path("service"), "registered service", Map.of("serviceId", Type.TEXT,
                "serviceName", Type.TEXT, "tenantId", Type.TEXT, "namespace", Type.TEXT, "registered", Type.BOOLEAN));
        JsonNode deregistered = written(origin, "PUT", "/v1/catalog/deregister", "{\"serviceId\":\"web-1\"}", onNode);
        fields(deregistered, "deregistration", Map.of("node", Type.TEXT, "serviceId", Type.TEXT,
                "deregistered", Type.BOOLEAN));

        assertTrue(written(origin, "DELETE", "/v1/acl/token/" + accessor, "", management).asBoolean(),
                "a deletion answers true");
    }

    /** A token's answer in the fields the token menu, the token pages and sign-in read. */
    @Test
    void theKeyValueAnswersAreAnsweredInTheFieldsTheInterfaceReads() throws Exception {
        // UI-6 Key/Value task list, Phase 0: a folder's keys, an entry, a held entry, a filtered listing, a write's
        // true and a stale compare-and-set's false, and a key's events, as client.ts will send and read them.
        startServer(new AclHttpSettings(true, dataDirectory, AclDefaultPolicy.DENY), SecurityProfile.PRODUCTION);
        URI origin = URI.create("http://127.0.0.1:" + server.port());
        URI api = origin.resolve(bootstrap(get(origin, "/ui/")).path("apiBasePath").asText());
        HttpResponse<String> bootstrapped = send(origin, "PUT", "/v1/acl/bootstrap", "", Map.of());
        assertEquals(200, bootstrapped.statusCode(), bootstrapped.body());
        Map<String, String> management = Map.of("X-Qraft-Token",
                JSON.readTree(bootstrapped.body()).path("secretId").asText());

        assertEquals(JSON.readTree("true"), written(origin, "PUT", "/v1/kv/app/config?flags=3", "{\"a\":1}", management));
        assertEquals(JSON.readTree("true"), written(origin, "PUT", "/v1/kv/app/db/url", "jdbc", management));
        assertEquals(JSON.readTree("true"), written(origin, "PUT", "/v1/kv/other", "x", management));
        assertEquals(JSON.readTree("false"), written(origin, "PUT", "/v1/kv/app/config?cas=0", "{}", management),
                "a stale compare-and-set is answered false, 200");

        JsonNode level = read(api, "v1/kv/app/?keys&separator=/", true, management);
        assertEquals(JSON.readTree("[\"app/config\",\"app/db/\"]"), level, "a folder's keys, a folder ending in /");
        JsonNode entry = read(api, "v1/kv/app/config", true, management).get(0);
        fields(entry, "entry", Map.of("Key", Type.TEXT, "Value", Type.TEXT, "Flags", Type.INTEGER,
                "CreateIndex", Type.INTEGER, "ModifyIndex", Type.INTEGER, "LockIndex", Type.INTEGER));
        assertFalse(entry.has("Session"), "absent while no session holds it: " + entry);

        assertInstanceOf(RaftCommandResult.Success.class, node.submitCommand(NodeCommand.register(
                Node.of("node-a", "10.0.0.1", null, null, Map.of()), Instant.now())).get(10, TimeUnit.SECONDS));
        String session = written(origin, "PUT", "/v1/session/create", "{\"Node\":\"node-a\"}", management).path("ID")
                .asText();
        assertEquals(JSON.readTree("true"), written(origin, "PUT", "/v1/kv/app/lock?acquire=" + session, "", management));
        JsonNode held = read(api, "v1/kv/app/lock", true, management).get(0);
        fields(held, "held entry", Map.of("Session", Type.TEXT, "LockIndex", Type.INTEGER));

        written(origin, "PUT", "/v1/acl/policy", "{\"name\":\"app-reader\",\"rules\":"
                + "{\"key_prefix\":{\"app/\":{\"policy\":\"read\"}}}}", management);
        String reader = written(origin, "PUT", "/v1/acl/token", "{\"policies\":[{\"name\":\"app-reader\"}]}",
                management).path("secretId").asText();
        HttpResponse<String> filtered = http.send(HttpRequest.newBuilder(api.resolve("v1/kv/?keys&separator=/"))
                .header("X-Qraft-Token", reader).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, filtered.statusCode(), filtered.body());
        assertEquals(JSON.readTree("[\"app/\"]"), JSON.readTree(filtered.body()));
        assertEquals("true", filtered.headers().firstValue("X-Qraft-Results-Filtered-By-ACLs").orElse(null),
                "the browser says the server left keys out");

        JsonNode events = read(api, "v1/operator/events?key=app/config&tenant=default&namespace=default", false,
                management).path("events");
        assertEquals(1, events.size(), events.toString());
        fields(events.get(0), "key event", Map.of("type", Type.TEXT, "committedIndex", Type.INTEGER,
                "event", Type.OBJECT));
        assertEquals("app/config", events.get(0).path("event").path("key").asText());
        assertFalse(events.toString().contains("{\\\"a\\\":1}") || events.toString().contains("eyJhIjoxfQ"),
                "an event never carries the value");
    }

    @Test
    void theCoordinationAnswersAreAnsweredInTheFieldsTheInterfaceReads() throws Exception {
        // UI-6 Sessions, Locks and Watches task list, Phase 0: a session with the leader's timer, a renewal, a held key,
        // an acquisition's true and a refused one's false, a running lock delay, and a session's events.
        startServer(new AclHttpSettings(true, dataDirectory, AclDefaultPolicy.DENY), SecurityProfile.PRODUCTION);
        java.util.concurrent.ScheduledExecutorService timers = java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor();
        try (dev.mars.qraft.server.session.SessionTtlExpiry ttl = dev.mars.qraft.server.session.SessionTtlExpiry.attach(
                node, store.getSessions(), node::submitCommand,
                dev.mars.qraft.server.health.ExpiryScheduler.of(timers), Clock.systemUTC(), Duration.ofSeconds(1))) {
            server.useSessionTtl(ttl);
            URI origin = URI.create("http://127.0.0.1:" + server.port());
            URI api = origin.resolve(bootstrap(get(origin, "/ui/")).path("apiBasePath").asText());
            HttpResponse<String> bootstrapped = send(origin, "PUT", "/v1/acl/bootstrap", "", Map.of());
            assertEquals(200, bootstrapped.statusCode(), bootstrapped.body());
            Map<String, String> management = Map.of("X-Qraft-Token",
                    JSON.readTree(bootstrapped.body()).path("secretId").asText());
            assertInstanceOf(RaftCommandResult.Success.class, node.submitCommand(NodeCommand.register(
                    Node.of("node-a", "10.0.0.1", null, null, Map.of()), Instant.now())).get(10, TimeUnit.SECONDS));

            String holder = written(origin, "PUT", "/v1/session/create",
                    "{\"Node\":\"node-a\",\"Name\":\"worker\",\"TTL\":\"30s\",\"LockDelay\":\"5s\"}", management)
                    .path("ID").asText();
            String other = written(origin, "PUT", "/v1/session/create", "{\"Node\":\"node-a\"}", management).path("ID")
                    .asText();
            JsonNode sessions = read(api, "v1/internal/ui/sessions", true, management);
            fields(sessions, "sessions read", Map.of("Sessions", Type.LIST, "TtlKnown", Type.BOOLEAN));
            assertTrue(sessions.path("TtlKnown").asBoolean(), "the leader keeps the timers");
            Map<String, Type> session = Map.of("ID", Type.TEXT, "Name", Type.TEXT, "Node", Type.TEXT,
                    "LockDelay", Type.INTEGER, "Behavior", Type.TEXT, "TTL", Type.TEXT, "CreateIndex", Type.INTEGER,
                    "ModifyIndex", Type.INTEGER, "RenewedAt", Type.TEXT_OR_NULL, "ExpiresAt", Type.TEXT_OR_NULL);
            sessions.path("Sessions").forEach(entry -> fields(entry, "session", session));
            JsonNode timed = sessions.path("Sessions").get(holder.compareTo(other) < 0 ? 0 : 1);
            assertEquals(holder, timed.path("ID").asText());
            assertTrue(timed.path("ExpiresAt").isTextual(), "a session with a TTL lapses: " + timed);
            JsonNode renewed = written(origin, "PUT", "/v1/session/renew/" + holder, "", management);
            fields(renewed.get(0), "renewed session", Map.of("ID", Type.TEXT, "TTL", Type.TEXT));
            fields(read(api, "v1/session/info/" + holder, true, management).get(0), "session info",
                    Map.of("ID", Type.TEXT, "Node", Type.TEXT, "Behavior", Type.TEXT, "LockDelay", Type.INTEGER));

            assertEquals(JSON.readTree("true"), written(origin, "PUT", "/v1/kv/app/lock?acquire=" + holder, "",
                    management), "an acquisition answers true");
            assertEquals(JSON.readTree("false"), written(origin, "PUT", "/v1/kv/app/lock?acquire=" + other, "",
                    management), "a key another session holds is refused false, 200");
            JsonNode locks = read(api, "v1/internal/ui/locks", true, management);
            fields(locks, "locks read", Map.of("Held", Type.LIST, "Delays", Type.LIST));
            fields(locks.path("Held").get(0), "held key", Map.of("Tenant", Type.TEXT, "Namespace", Type.TEXT,
                    "Key", Type.TEXT, "Session", Type.TEXT, "LockIndex", Type.INTEGER, "CreateIndex", Type.INTEGER,
                    "ModifyIndex", Type.INTEGER));

            assertEquals(JSON.readTree("true"), written(origin, "PUT", "/v1/session/destroy/" + holder, "", management));
            JsonNode delayed = read(api, "v1/internal/ui/locks", true, management);
            assertEquals(0, delayed.path("Held").size(), delayed.toString());
            fields(delayed.path("Delays").get(0), "lock delay", Map.of("Tenant", Type.TEXT, "Namespace", Type.TEXT,
                    "Key", Type.TEXT, "Until", Type.TEXT));
            Instant.parse(delayed.path("Delays").get(0).path("Until").asText());

            JsonNode events = read(api, "v1/operator/events?session=" + holder, false, management).path("events");
            List<String> types = new java.util.ArrayList<>();
            events.forEach(event -> types.add(event.path("type").asText()));
            assertEquals(List.of("session.created", "lock.acquired", "session.destroyed"), types);
            fields(events.get(2).path("event"), "session event", Map.of("id", Type.TEXT, "reason", Type.TEXT));
            assertEquals("destroyed", events.get(2).path("event").path("reason").asText());
        } finally {
            timers.shutdownNow();
        }
    }

    @Test
    void theGovernanceAnswersAreAnsweredInTheFieldsTheInterfaceReads() throws Exception {
        // UI-6 Tenants, Namespaces and Audit task list, Phase 0: the tenancy reads and writes, what each scope holds,
        // a scope's events, and the audit read of a file this server writes.
        startServer(new AclHttpSettings(true, dataDirectory, AclDefaultPolicy.DENY), SecurityProfile.PRODUCTION);
        Path auditFile = dataDirectory.resolve("audit").resolve("qraft-audit.json");
        try (dev.mars.qraft.server.http.AuditFileSink sink = dev.mars.qraft.server.http.AuditFileSink.start(journal,
                auditFile)) {
            server.useAuditFile(auditFile);
            URI origin = URI.create("http://127.0.0.1:" + server.port());
            URI api = origin.resolve(bootstrap(get(origin, "/ui/")).path("apiBasePath").asText());
            HttpResponse<String> bootstrapped = send(origin, "PUT", "/v1/acl/bootstrap", "", Map.of());
            assertEquals(200, bootstrapped.statusCode(), bootstrapped.body());
            Map<String, String> management = Map.of("X-Qraft-Token",
                    JSON.readTree(bootstrapped.body()).path("secretId").asText());

            fields(written(origin, "PUT", "/v1/tenant", "{\"Name\":\"acme\",\"Description\":\"Acme Ltd\"}", management),
                    "created tenant", Map.of("Name", Type.TEXT, "Description", Type.TEXT, "CreateIndex", Type.INTEGER,
                            "ModifyIndex", Type.INTEGER));
            fields(written(origin, "PUT", "/v1/namespace", "{\"Name\":\"prod\",\"Tenant\":\"acme\",\"Description\":\"\"}",
                    management), "created namespace", Map.of("Name", Type.TEXT, "Tenant", Type.TEXT,
                    "Description", Type.TEXT, "CreateIndex", Type.INTEGER, "ModifyIndex", Type.INTEGER));
            assertEquals(JSON.readTree("true"), written(origin, "PUT", "/v1/kv/app/a", "x", Map.of("X-Qraft-Token",
                    management.get("X-Qraft-Token"), "X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "prod")));

            JsonNode scopes = read(api, "v1/internal/ui/scopes", true, management);
            JsonNode acme = scopes.path("Tenants").get(0);
            fields(acme, "tenant's holdings", Map.of("Name", Type.TEXT, "Description", Type.TEXT,
                    "CreateIndex", Type.INTEGER, "ModifyIndex", Type.INTEGER, "Namespaces", Type.LIST));
            fields(acme.path("Namespaces").get(1), "namespace's holdings", Map.of("Name", Type.TEXT,
                    "Description", Type.TEXT, "CreateIndex", Type.INTEGER, "ModifyIndex", Type.INTEGER,
                    "Services", Type.INTEGER, "Instances", Type.INTEGER, "Keys", Type.INTEGER, "HeldKeys", Type.INTEGER,
                    "Sessions", Type.TEXT_LIST));
            assertEquals(1, acme.path("Namespaces").get(1).path("Keys").asInt(), scopes.toString());

            JsonNode events = read(api, "v1/operator/events?tenant=acme&namespace=prod&category=domain", false, management)
                    .path("events");
            fields(events.get(0), "tenancy event", Map.of("type", Type.TEXT, "event", Type.OBJECT, "tenant", Type.TEXT,
                    "namespace", Type.TEXT));
            assertEquals("namespace.created", events.get(0).path("type").asText(), events.toString());

            assertEquals(JSON.readTree("true"), written(origin, "DELETE", "/v1/namespace/prod", "",
                    Map.of("X-Qraft-Token", management.get("X-Qraft-Token"), "X-Qraft-Tenant", "acme")),
                    "a deletion answers true");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            JsonNode audit = read(api, "v1/internal/ui/audit?tenant=acme", false, management);
            while (audit.path("Records").isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(50);
                audit = read(api, "v1/internal/ui/audit?tenant=acme", false, management);
            }
            fields(audit, "audit read", Map.of("Configured", Type.BOOLEAN, "File", Type.TEXT, "Size", Type.INTEGER,
                    "Scanned", Type.INTEGER, "Complete", Type.BOOLEAN, "Records", Type.LIST));
            fields(audit.path("Records").get(0), "audit record", Map.of("Type", Type.TEXT, "Timestamp", Type.TEXT,
                    "Node", Type.TEXT, "AccessorId", Type.TEXT, "Method", Type.TEXT, "Route", Type.TEXT,
                    "Status", Type.INTEGER, "Tenant", Type.TEXT, "Namespace", Type.TEXT, "RequestId", Type.TEXT));
        }
    }

    private static void token(JsonNode token, String what) {
        fields(token, what, Map.ofEntries(Map.entry("accessorId", Type.TEXT), Map.entry("secretId", Type.TEXT),
                Map.entry("description", Type.TEXT_OR_NULL), Map.entry("policies", Type.LIST),
                Map.entry("roles", Type.LIST), Map.entry("nodeIdentities", Type.LIST),
                Map.entry("serviceIdentities", Type.LIST), Map.entry("createTime", Type.TEXT),
                Map.entry("expirationTime", Type.TEXT_OR_NULL), Map.entry("installedFromFile", Type.BOOLEAN)));
    }

    /** A request the interface sends, answered 200 with JSON and the answering server named. */
    private JsonNode written(URI origin, String method, String path, String body, Map<String, String> headers)
            throws Exception {
        HttpResponse<String> response = send(origin, method, path, body, headers);
        assertEquals(200, response.statusCode(), method + " " + path + ": " + response.body());
        assertEquals("server-1", response.headers().firstValue("X-Qraft-Answered-By").orElse(null), path);
        return JSON.readTree(response.body());
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
        startServer(AclHttpSettings.disabled(), SecurityProfile.DEVELOPMENT);
    }

    private void startServer(AclHttpSettings acl, SecurityProfile profile) throws Exception {
        runtime = RaftExecutorsFixture.create();
        store = new QraftStateStore();
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
                .events(journal)
                .build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(node.isLeader(), "the single server did not become leader");
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), new AdminUiConfig(true, "/ui/", Optional.empty()),
                new ClasspathUiAssets(AdminUiDataContractTest.class.getClassLoader(), "ui-fixture/"),
                HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, acl, Optional.empty(), profile);
        server.useEventJournal(journal);
        server.useConfiguration(AppConfig.fromJson("{\"version\":1,\"server\":{\"id\":\"server-1\"}}").effective());
        server.start().get(10, TimeUnit.SECONDS);
    }
}

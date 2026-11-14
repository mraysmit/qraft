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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.acl.AclRulesJson;
import dev.mars.qraft.common.acl.NodeIdentity;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.InMemoryTransportSimulatorFixture;
import dev.mars.qraft.raft.ManualRaftClusterFixture;
import dev.mars.qraft.raft.MembershipService;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.acl.AclRemovalAuthorizer;
import dev.mars.qraft.server.acl.AclResolver;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.AclCommand;
import dev.mars.qraft.state.CatalogCommand;
import dev.mars.qraft.state.NodeCommand;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.acl.AclBuiltins;
import dev.mars.qraft.state.acl.AclPolicy;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests enforcement on a server's HTTP API (ACL list, Phase 4), one test for each row of proposal 11's server table,
 * after Consul's {@code vetRegisterWithACL}, {@code vetDeregisterWithACL} and its read filters: a write is authorized
 * before it is proposed and refused with 403 {@code permission_denied}; a read is filtered to what the token may see,
 * with {@code X-Qraft-Results-Filtered-By-ACLs} when anything was removed; an unknown secret is refused with 403
 * {@code token_not_found} on every route that takes a token; a request without one acts as the anonymous token; and
 * health and the bootstrap need no token. Every refusal is in the standard envelope with the request ID, and no answer
 * or log line holds a secret.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-06
 * @version 1.0
 */
class HttpApiServerAclEnforcementTest {
    private static final Set<String> MEMBERS = Set.of("a", "b", "c");
    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final String FILTERED = "X-Qraft-Results-Filtered-By-ACLs";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dataDirectory;

    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private final Map<String, RaftNode> nodes = new HashMap<>();
    private final Map<String, QraftStateStore> stores = new HashMap<>();
    private final List<String> secretsUsed = new ArrayList<>();
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private RaftNode leader;
    private HttpApiServer server;
    private final HttpClient client = HttpClient.newHttpClient();
    private int policies;

    @BeforeEach
    void setUp() throws Exception {
        captured.start();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(captured);
        runtime = RaftExecutorsFixture.create();
        cluster = new ManualRaftClusterFixture(runtime);
        InMemoryTransportSimulatorFixture.clearAllTransports();
        for (String name : MEMBERS) {
            stores.put(name, new QraftStateStore());
            nodes.put(name, cluster.add(cluster.builder(name, MEMBERS, new InMemoryTransportSimulatorFixture(name),
                    stores.get(name), RaftNodeMode.volatileMode())));
        }
        ManualRaftClusterFixture.startAll(nodes.values().toArray(RaftNode[]::new));
        leader = cluster.elect(nodes.get("a"));
        await(leader.submitCommand(new dev.mars.qraft.state.DistributedStateRaftCommand(
                dev.mars.qraft.state.distributed.DistributedStateCommand.put("k", "v"))));
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (server != null) server.close();
            cluster.close();
        } finally {
            runtime.close().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
            ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(captured);
        }
        for (String secret : secretsUsed) {
            for (ILoggingEvent event : captured.list) {
                assertFalse(event.getFormattedMessage().contains(secret), "a log line holds a secret: " + event);
            }
        }
    }

    // ---- Tokens, the envelope, and the routes that need none ----

    @Test
    void anUnknownSecretIsRefusedAsNotFoundOnEveryRouteThatTakesAToken() throws Exception {
        serveDenying();
        String unknown = secret();
        Map<String, String> asWeb = Map.of("X-Qraft-Node", "web-1");
        List<Route> routes = List.of(
                new Route("PUT", "/v1/catalog/register", "{\"node\":{\"address\":\"10.0.0.1\"}}", asWeb),
                new Route("PUT", "/v1/catalog/deregister", "{}", asWeb),
                new Route("PUT", "/v1/catalog/node/heartbeat", "{\"sequenceNumber\":1}", asWeb),
                new Route("PUT", "/v1/catalog/check/observe", observation("api-1", "http"), asWeb),
                new Route("GET", "/v1/catalog/nodes", "", Map.of()),
                new Route("GET", "/v1/catalog/node-services/web-1", "", Map.of()),
                new Route("GET", "/v1/catalog/services", "", Map.of()),
                new Route("GET", "/v1/catalog/service/api", "", Map.of()),
                new Route("GET", "/v1/health/service/api", "", Map.of()),
                new Route("GET", "/v1/internal/ui/services", "", Map.of()),
                new Route("GET", "/v1/health/state/any", "", Map.of()),
                new Route("GET", "/v1/operator/raft/configuration", "", Map.of()),
                new Route("GET", "/raft/status", "", Map.of()));

        for (Route route : routes) {
            Map<String, String> headers = new LinkedHashMap<>(route.headers());
            headers.put("X-Qraft-Token", unknown);
            headers.put("X-Request-Id", "acl-request-1");
            HttpResponse<String> response = send(route.method(), route.path(), route.body(), headers);

            assertEquals(403, response.statusCode(), route + ": " + response.body());
            JsonNode body = JSON.readTree(response.body());
            assertEquals("token_not_found", body.get("code").asText(), route.toString());
            assertEquals("ACL not found", body.get("message").asText(), route.toString());
            assertEquals("acl-request-1", body.get("requestId").asText(), route.toString());
            assertFalse(response.body().contains(unknown), route + " answers the secret");
        }
        assertTrue(stores.get("a").getNodes().isEmpty(), "nothing was registered");
    }

    @Test
    void aRequestWithoutATokenActsAsTheAnonymousToken() throws Exception {
        serveDenying();

        HttpResponse<String> refused = registerNode("web-1", null, null);
        assertEquals(403, refused.statusCode(), refused.body());
        assertEquals("permission_denied", code(refused));
        assertEquals("Permission denied: token with AccessorID '" + AclBuiltins.ANONYMOUS_TOKEN_ID
                + "' lacks permission 'node:write' on \"web-1\"", message(refused));

        // The anonymous token can be given policies, as in Consul.
        String policy = policy("{\"node\":{\"web-1\":{\"policy\":\"write\"}}}");
        AclToken anonymous = stores.get("a").getAcl().token(AclBuiltins.ANONYMOUS_TOKEN_ID).orElseThrow();
        assertSuccess(await(leader.submitCommand(AclCommand.updateToken(new AclToken(anonymous.accessorId(),
                anonymous.secretHash(), anonymous.description(), List.of(policy), List.of(), List.of(), List.of(),
                anonymous.createTime(), anonymous.expirationTime(), false)))));

        assertEquals(200, registerNode("web-1", null, null).statusCode());
    }

    @Test
    void theTokenIsReadFromXQraftTokenAndOtherwiseFromABearerAuthorization() throws Exception {
        serveDenying();
        String web = token("{\"node\":{\"web-1\":{\"policy\":\"write\"}}}");
        String body = "{\"node\":{\"address\":\"10.0.0.1\"}}";

        assertEquals(200, send("PUT", "/v1/catalog/register", body,
                Map.of("X-Qraft-Node", "web-1", "Authorization", "Bearer " + web)).statusCode());
        HttpResponse<String> unknownBearer = send("PUT", "/v1/catalog/register", body,
                Map.of("X-Qraft-Node", "web-1", "Authorization", "Bearer " + secret()));
        assertEquals(403, unknownBearer.statusCode(), unknownBearer.body());
        assertEquals("token_not_found", code(unknownBearer));
        HttpResponse<String> both = send("PUT", "/v1/catalog/register", body,
                Map.of("X-Qraft-Node", "web-1", "X-Qraft-Token", web, "Authorization", "Bearer " + secret()));
        assertEquals(200, both.statusCode(), "X-Qraft-Token comes first, as X-Consul-Token does: " + both.body());
    }

    @Test
    void withAclsDisabledATokenIsIgnored() throws Exception {
        serve(AclHttpSettings.disabled());

        assertEquals(200, registerNode("web-1", null, secret()).statusCode());
        HttpResponse<String> nodesList = send("GET", "/v1/catalog/nodes", "", Map.of("X-Qraft-Token", secret()));
        assertEquals(200, nodesList.statusCode());
        assertTrue(nodesList.headers().firstValue(FILTERED).isEmpty());
    }

    @Test
    void healthNeedsNoTokenAndIgnoresAnUnknownOne() throws Exception {
        serveDenying();

        for (Map<String, String> headers : List.of(Map.<String, String>of(), Map.of("X-Qraft-Token", secret()))) {
            assertEquals(200, send("GET", "/health/live", "", headers).statusCode());
            HttpResponse<String> ready = send("GET", "/health/ready", "", headers);
            assertNotEquals(403, ready.statusCode(), ready.body());
        }
    }

    @Test
    void theBootstrapNeedsNoTokenAndIgnoresAnUnknownOne() throws Exception {
        serveDenying();

        HttpResponse<String> response = send("PUT", "/v1/acl/bootstrap", "", Map.of("X-Qraft-Token", secret()));

        assertEquals(200, response.statusCode(), response.body());
        secretsUsed.add(JSON.readTree(response.body()).get("secretId").asText());
    }

    @Test
    void aRefusalIsLoggedBelowErrorWithTheAccessorAndNeverTheSecret() throws Exception {
        serveDenying();
        String reader = token("{\"node\":{\"web-1\":{\"policy\":\"read\"}}}");
        String accessor = accessorOf(reader);

        assertEquals(403, registerNode("web-1", null, reader).statusCode());

        List<ILoggingEvent> refusals = captured.list.stream()
                .filter(event -> event.getFormattedMessage().contains(accessor)).toList();
        assertFalse(refusals.isEmpty(), "the refusal is logged with the accessor ID");
        for (ILoggingEvent event : refusals) {
            assertTrue(event.getLevel().isGreaterOrEqual(Level.DEBUG) && !event.getLevel().isGreaterOrEqual(Level.ERROR),
                    "a denial is not an error: " + event);
            assertTrue(event.getThrowableProxy() == null, "a denial carries no exception: " + event);
        }
    }

    // ---- Writes ----

    @Test
    void registeringANodeNeedsNodeWriteOnItsName() throws Exception {
        serveDenying();
        String reader = token("{\"node\":{\"web-1\":{\"policy\":\"read\"}}}");
        String writer = token("{\"node\":{\"web-1\":{\"policy\":\"write\"}}}");

        HttpResponse<String> refused = registerNode("web-1", null, reader);
        assertEquals(403, refused.statusCode(), refused.body());
        assertEquals("permission_denied", code(refused));
        assertEquals("Permission denied: token with AccessorID '" + accessorOf(reader)
                + "' lacks permission 'node:write' on \"web-1\"", message(refused));
        assertTrue(stores.get("a").findNode("web-1").isEmpty(), "a refused registration is not proposed");

        assertEquals(200, registerNode("web-1", null, writer).statusCode());
        assertTrue(stores.get("a").findNode("web-1").isPresent());
    }

    @Test
    void aRegistrationThatLeavesTheNodeAsRecordedNeedsNoNodeWrite() throws Exception {
        serveDenying();
        String nodeId = UUID.randomUUID().toString();
        arrangeNode("web-1", nodeId);
        String api = token("{\"service\":{\"api\":{\"policy\":\"write\"}}}");

        HttpResponse<String> response = send("PUT", "/v1/catalog/register",
                "{\"node\":{\"address\":\"10.0.0.1\"}," + serviceBody("api-1", "api").substring(1),
                Map.of("X-Qraft-Node", "web-1", "X-Qraft-Node-ID", nodeId, "X-Qraft-Token", api));

        assertEquals(200, response.statusCode(), "the node is unchanged, so service:write is enough, as in Consul: "
                + response.body());
        HttpResponse<String> changed = send("PUT", "/v1/catalog/register",
                "{\"node\":{\"address\":\"10.0.0.2\"}," + serviceBody("api-1", "api").substring(1),
                Map.of("X-Qraft-Node", "web-1", "X-Qraft-Node-ID", nodeId, "X-Qraft-Token", api));
        assertEquals(403, changed.statusCode(), changed.body());
        assertTrue(message(changed).contains("'node:write' on \"web-1\""), changed.body());
    }

    @Test
    void registeringAServiceNeedsServiceWriteOnItsNameInItsScope() throws Exception {
        serveDenying();
        String api = token("{\"service\":{\"api\":{\"policy\":\"write\"}}}");

        HttpResponse<String> elsewhere = send("PUT", "/v1/catalog/register", serviceBody("api-1", "api"),
                Map.of("X-Qraft-Node", "web-1", "X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "prod",
                        "X-Qraft-Token", api));
        assertEquals(403, elsewhere.statusCode(), elsewhere.body());
        assertEquals("Permission denied: token with AccessorID '" + accessorOf(api)
                + "' lacks permission 'service:write' on \"api\" in tenant \"acme\" namespace \"prod\"",
                message(elsewhere));

        HttpResponse<String> inDefault = send("PUT", "/v1/catalog/register", serviceBody("api-1", "api"),
                Map.of("X-Qraft-Node", "web-1", "X-Qraft-Token", api));
        assertEquals(200, inDefault.statusCode(), inDefault.body());
    }

    @Test
    void replacingAnInstanceNeedsServiceWriteOnTheNameItReplaces() throws Exception {
        serveDenying();
        arrangeService("api-1", "api", "web-1");
        String billing = token("{\"service\":{\"billing\":{\"policy\":\"write\"}}}");
        String both = token("{\"service\":{\"billing\":{\"policy\":\"write\"},\"api\":{\"policy\":\"write\"}}}");

        HttpResponse<String> refused = send("PUT", "/v1/catalog/register", serviceBody("api-1", "billing"),
                Map.of("X-Qraft-Node", "web-1", "X-Qraft-Token", billing));
        assertEquals(403, refused.statusCode(), refused.body());
        assertTrue(message(refused).contains("'service:write' on \"api\""), refused.body());
        assertEquals("api", stores.get("a").getServiceCatalog().instances().getFirst().serviceName());

        assertEquals(200, send("PUT", "/v1/catalog/register", serviceBody("api-1", "billing"),
                Map.of("X-Qraft-Node", "web-1", "X-Qraft-Token", both)).statusCode());
    }

    @Test
    void aClaimToANameAnotherNodeHoldsIsAuthorizedBeforeItIsJudged() throws Exception {
        serveDenying();
        arrangeNode("web-1", UUID.randomUUID().toString());
        String claimant = UUID.randomUUID().toString();
        String api = token("{\"service\":{\"api\":{\"policy\":\"write\"}}}");
        String web = token("{\"node\":{\"web-1\":{\"policy\":\"write\"}}}");

        HttpResponse<String> refused = registerNode("web-1", claimant, api);
        assertEquals(403, refused.statusCode(), refused.body());
        assertEquals("permission_denied", code(refused));

        HttpResponse<String> judged = registerNode("web-1", claimant, web);
        assertEquals(409, judged.statusCode(), "with node:write the store judges the claim: " + judged.body());
        assertEquals("node_name_reserved", code(judged));
    }

    @Test
    void aRenameNeedsNodeWriteOnTheOldNameAndTheNew() throws Exception {
        serveDenying();
        String nodeId = UUID.randomUUID().toString();
        arrangeNode("old-name", nodeId);
        String newOnly = token("{\"node\":{\"new-name\":{\"policy\":\"write\"}}}");
        String both = token("{\"node\":{\"new-name\":{\"policy\":\"write\"},\"old-name\":{\"policy\":\"write\"}}}");

        HttpResponse<String> refused = registerNode("new-name", nodeId, newOnly);
        assertEquals(403, refused.statusCode(), refused.body());
        assertTrue(message(refused).contains("'node:write' on \"old-name\""), refused.body());
        assertTrue(stores.get("a").findNode("old-name").isPresent());

        assertEquals(200, registerNode("new-name", nodeId, both).statusCode());
    }

    @Test
    void aHeartbeatNeedsNodeWriteOnItsName() throws Exception {
        serveDenying();
        arrangeNode("web-1", null);
        String reader = token("{\"node\":{\"web-1\":{\"policy\":\"read\"}}}");
        String writer = token("{\"node\":{\"web-1\":{\"policy\":\"write\"}}}");

        HttpResponse<String> refused = send("PUT", "/v1/catalog/node/heartbeat", "{\"sequenceNumber\":1}",
                Map.of("X-Qraft-Node", "web-1", "X-Qraft-Token", reader));
        assertEquals(403, refused.statusCode(), refused.body());
        assertTrue(message(refused).contains("'node:write' on \"web-1\""), refused.body());

        HttpResponse<String> accepted = send("PUT", "/v1/catalog/node/heartbeat", "{\"sequenceNumber\":1}",
                Map.of("X-Qraft-Node", "web-1", "X-Qraft-Token", writer));
        assertEquals(200, accepted.statusCode(), accepted.body());
    }

    @Test
    void anObservationNeedsServiceWriteOnItsService() throws Exception {
        serveDenying();
        arrangeService("api-1", "api", "web-1");
        String other = token("{\"service\":{\"other\":{\"policy\":\"write\"}}}");
        String api = token("{\"service\":{\"api\":{\"policy\":\"write\"}}}");

        HttpResponse<String> refused = send("PUT", "/v1/catalog/check/observe", observation("api-1", "http"),
                Map.of("X-Qraft-Node", "web-1", "X-Qraft-Token", other));
        assertEquals(403, refused.statusCode(), refused.body());
        assertTrue(message(refused).contains("'service:write' on \"api\""), refused.body());
        assertTrue(stores.get("a").healthChecks().isEmpty(), "a refused observation is not proposed");

        HttpResponse<String> accepted = send("PUT", "/v1/catalog/check/observe", observation("api-1", "http"),
                Map.of("X-Qraft-Node", "web-1", "X-Qraft-Token", api));
        assertEquals(200, accepted.statusCode(), accepted.body());
    }

    @Test
    void nodeWriteDeregistersAnythingOnTheNode() throws Exception {
        serveDenying();
        arrangeService("api-1", "api", "web-1");
        String client = identityToken("web-1");
        String anotherNode = identityToken("db-1");

        HttpResponse<String> refused = send("PUT", "/v1/catalog/deregister", "{\"serviceId\":\"api-1\"}",
                Map.of("X-Qraft-Node", "web-1", "X-Qraft-Token", anotherNode));
        assertEquals(403, refused.statusCode(), "another node's identity reads services but writes none: "
                + refused.body());

        HttpResponse<String> response = send("PUT", "/v1/catalog/deregister", "{\"serviceId\":\"api-1\"}",
                Map.of("X-Qraft-Node", "web-1", "X-Qraft-Token", client));

        assertEquals(200, response.statusCode(), "a node identity removes what is on its node: " + response.body());
        assertTrue(JSON.readTree(response.body()).get("deregistered").asBoolean());
    }

    @Test
    void withoutNodeWriteAServiceIsDeregisteredWithServiceWriteOnItsName() throws Exception {
        serveDenying();
        arrangeService("api-1", "api", "web-1");
        String other = token("{\"service\":{\"other\":{\"policy\":\"write\"}}}");
        String api = token("{\"service\":{\"api\":{\"policy\":\"write\"}}}");
        Map<String, String> asOther = Map.of("X-Qraft-Node", "web-1", "X-Qraft-Token", other);
        Map<String, String> asApi = Map.of("X-Qraft-Node", "web-1", "X-Qraft-Token", api);

        HttpResponse<String> refused = send("PUT", "/v1/catalog/deregister", "{\"serviceId\":\"api-1\"}", asOther);
        assertEquals(403, refused.statusCode(), refused.body());
        assertTrue(message(refused).contains("'service:write' on \"api\""), refused.body());

        HttpResponse<String> node = send("PUT", "/v1/catalog/deregister", "{}", asApi);
        assertEquals(403, node.statusCode(), "a node's removal needs node:write: " + node.body());
        assertTrue(message(node).contains("'node:write' on \"web-1\""), node.body());

        HttpResponse<String> unknown = send("PUT", "/v1/catalog/deregister", "{\"serviceId\":\"api-9\"}", asApi);
        assertEquals(404, unknown.statusCode(), "Consul's \"Unknown service ID\": " + unknown.body());
        assertEquals("service_not_found", code(unknown));

        HttpResponse<String> removed = send("PUT", "/v1/catalog/deregister", "{\"serviceId\":\"api-1\"}", asApi);
        assertEquals(200, removed.statusCode(), removed.body());
        assertTrue(stores.get("a").getServiceCatalog().instances().isEmpty());
    }

    // ---- Reads ----

    @Test
    void theNodeListShowsOnlyTheNodesTheTokenMayRead() throws Exception {
        serveDenying();
        arrangeNode("web-1", null);
        arrangeNode("db-1", null);
        String web = token("{\"node_prefix\":{\"web-\":{\"policy\":\"read\"}}}");
        String every = token("{\"node_prefix\":{\"\":{\"policy\":\"read\"}}}");

        HttpResponse<String> filtered = send("GET", "/v1/catalog/nodes", "", Map.of("X-Qraft-Token", web));
        assertEquals(200, filtered.statusCode(), filtered.body());
        assertEquals(List.of("web-1"), names(filtered, "name"));
        assertEquals("true", filtered.headers().firstValue(FILTERED).orElse(""));

        HttpResponse<String> whole = send("GET", "/v1/catalog/nodes", "", Map.of("X-Qraft-Token", every));
        assertEquals(List.of("db-1", "web-1"), names(whole, "name"));
        assertTrue(whole.headers().firstValue(FILTERED).isEmpty(), "nothing was removed");
    }

    @Test
    void aNodesServicesNeedNodeReadAndAreFilteredByServiceRead() throws Exception {
        serveDenying();
        arrangeService("api-1", "api", "web-1");
        arrangeService("db-1", "db", "web-1");
        String servicesOnly = token("{\"service_prefix\":{\"\":{\"policy\":\"read\"}}}");
        String apiOnWeb = token("{\"node\":{\"web-1\":{\"policy\":\"read\"}},\"service\":{\"api\":{\"policy\":\"read\"}}}");

        HttpResponse<String> noNode = send("GET", "/v1/catalog/node-services/web-1", "",
                Map.of("X-Qraft-Token", servicesOnly));
        assertEquals(200, noNode.statusCode(), noNode.body());
        assertEquals(List.of(), names(noNode, "serviceId"));
        assertEquals("true", noNode.headers().firstValue(FILTERED).orElse(""));

        HttpResponse<String> api = send("GET", "/v1/catalog/node-services/web-1", "",
                Map.of("X-Qraft-Token", apiOnWeb));
        assertEquals(List.of("api-1"), names(api, "serviceId"));
        assertEquals("true", api.headers().firstValue(FILTERED).orElse(""));
    }

    @Test
    void theServiceListShowsOnlyTheServicesTheTokenMayRead() throws Exception {
        serveDenying();
        arrangeService("api-1", "api", "web-1");
        arrangeService("db-1", "db", "web-1");
        String api = token("{\"service\":{\"api\":{\"policy\":\"read\"}}}");

        HttpResponse<String> response = send("GET", "/v1/catalog/services", "", Map.of("X-Qraft-Token", api));

        assertEquals(200, response.statusCode(), response.body());
        List<String> listed = new ArrayList<>();
        JSON.readTree(response.body()).fieldNames().forEachRemaining(listed::add);
        assertEquals(List.of("api"), listed);
        assertEquals("true", response.headers().firstValue(FILTERED).orElse(""));
    }

    @Test
    void aServicesInstancesNeedNodeReadAndServiceRead() throws Exception {
        serveDenying();
        arrangeService("api-1", "api", "web-1");
        arrangeService("api-2", "api", "db-1");
        String apiOnWeb = token("{\"node\":{\"web-1\":{\"policy\":\"read\"}},\"service\":{\"api\":{\"policy\":\"read\"}}}");
        String nodesOnly = token("{\"node_prefix\":{\"\":{\"policy\":\"read\"}}}");

        HttpResponse<String> response = send("GET", "/v1/catalog/service/api", "",
                Map.of("X-Qraft-Token", apiOnWeb));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("api-1"), names(response, "serviceId"));
        assertEquals("true", response.headers().firstValue(FILTERED).orElse(""));

        assertEquals(List.of(), names(send("GET", "/v1/catalog/service/api", "",
                Map.of("X-Qraft-Token", nodesOnly)), "serviceId"), "an instance needs service:read too");
    }

    @Test
    void aServicesHealthNeedsNodeReadAndServiceRead() throws Exception {
        serveDenying();
        arrangeService("api-1", "api", "web-1");
        arrangeService("api-2", "api", "db-1");
        String apiOnWeb = token("{\"node\":{\"web-1\":{\"policy\":\"read\"}},\"service\":{\"api\":{\"policy\":\"read\"}}}");

        HttpResponse<String> response = send("GET", "/v1/health/service/api", "",
                Map.of("X-Qraft-Token", apiOnWeb));

        assertEquals(200, response.statusCode(), response.body());
        JsonNode entries = JSON.readTree(response.body());
        assertEquals(1, entries.size(), response.body());
        assertEquals("api-1", entries.get(0).get("service").get("serviceId").asText());
        assertEquals("true", response.headers().firstValue(FILTERED).orElse(""));
    }

    @Test
    void theServicesSummaryCountsOnlyWhatTheTokenMayRead() throws Exception {
        // UI-2 task list, Step 1: a service needs service:read, and an instance is counted only with node:read too.
        serveDenying();
        arrangeService("api-1", "api", "web-1");
        arrangeService("api-2", "api", "db-1");
        arrangeService("db-1", "db", "db-1");
        String apiOnWeb = token("{\"node\":{\"web-1\":{\"policy\":\"read\"}},\"service\":{\"api\":{\"policy\":\"read\"}}}");

        HttpResponse<String> response = send("GET", "/v1/internal/ui/services", "", Map.of("X-Qraft-Token", apiOnWeb));

        assertEquals(200, response.statusCode(), response.body());
        JsonNode summary = JSON.readTree(response.body());
        assertEquals(List.of("api"), names(response, "name"));
        assertEquals(1, summary.get(0).get("instanceCount").asInt(), "the instance on db-1 is not counted");
        assertEquals("true", response.headers().firstValue(FILTERED).orElse(""));
    }

    @Test
    void theCheckListingListsOnlyTheChecksOfInstancesTheTokenMayRead() throws Exception {
        // UI-2 task list, Step 2: a check is listed only when its instance could be read, node:read and service:read.
        serveDenying();
        arrangeService("api-1", "api", "web-1");
        arrangeService("api-2", "api", "db-1");
        String writer = token("{\"service_prefix\":{\"\":{\"policy\":\"write\"}},\"node_prefix\":{\"\":{\"policy\":\"write\"}}}");
        for (String[] instance : new String[][] {{"api-1", "web-1"}, {"api-2", "db-1"}}) {
            HttpResponse<String> observed = send("PUT", "/v1/catalog/check/observe", observation(instance[0], "http"),
                    Map.of("X-Qraft-Token", writer, "X-Qraft-Node", instance[1]));
            assertEquals(200, observed.statusCode(), observed.body());
        }
        String apiOnWeb = token("{\"node\":{\"web-1\":{\"policy\":\"read\"}},\"service\":{\"api\":{\"policy\":\"read\"}}}");

        HttpResponse<String> response = send("GET", "/v1/health/state/any", "", Map.of("X-Qraft-Token", apiOnWeb));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("api-1"), names(response, "serviceId"));
        assertEquals("true", response.headers().firstValue(FILTERED).orElse(""));
        assertEquals(2, names(send("GET", "/v1/health/state/any", "", Map.of("X-Qraft-Token", writer)), "serviceId")
                .size(), "a token that may read both sees both");
    }

    // ---- Operator ----

    @Test
    void theRaftConfigurationAndStatusNeedOperatorRead() throws Exception {
        serveDenying();
        String reader = token("{\"operator\":\"read\"}");
        String nodes = token("{\"node_prefix\":{\"\":{\"policy\":\"write\"}}}");

        for (String path : List.of("/v1/operator/raft/configuration", "/raft/status")) {
            HttpResponse<String> refused = send("GET", path, "", Map.of("X-Qraft-Token", nodes));
            assertEquals(403, refused.statusCode(), path + ": " + refused.body());
            assertEquals("Permission denied: token with AccessorID '" + accessorOf(nodes)
                    + "' lacks permission 'operator:read'", message(refused), path);
            assertEquals(200, send("GET", path, "", Map.of("X-Qraft-Token", reader)).statusCode(), path);
        }
    }

    @Test
    void removingAServerNeedsOperatorWrite() throws Exception {
        AclResolver leaders = AclResolver.enabled(AclDefaultPolicy.DENY, stores.get("a").getAcl());
        serve(new AclHttpSettings(true, dataDirectory, AclDefaultPolicy.DENY), MembershipService.authorizedBy(leader,
                InMemoryTransportSimulatorFixture.getAllTransports().get("a"), AclRemovalAuthorizer.of(leaders)));
        String reader = token("{\"operator\":\"read\"}");
        String writer = token("{\"operator\":\"write\"}");

        HttpResponse<String> refused = send("DELETE", "/v1/operator/raft/peer?name=c", "",
                Map.of("X-Qraft-Token", reader));
        assertEquals(403, refused.statusCode(), refused.body());
        assertEquals("permission_denied", code(refused));
        assertTrue(message(refused).contains("'operator:write'"), refused.body());
        assertTrue(leader.getConfiguration().orElseThrow().serverNamed("c").isPresent());

        HttpResponse<String> removed = send("DELETE", "/v1/operator/raft/peer?name=c", "",
                Map.of("X-Qraft-Token", writer));
        assertEquals(200, removed.statusCode(), removed.body());
        assertTrue(leader.getConfiguration().orElseThrow().serverNamed("c").isEmpty());
    }

    // ---- Helpers ----

    private record Route(String method, String path, String body, Map<String, String> headers) {
    }

    private void serveDenying() throws Exception {
        serve(new AclHttpSettings(true, dataDirectory, AclDefaultPolicy.DENY));
    }

    private void serve(AclHttpSettings acl) throws Exception {
        serve(acl, null);
    }

    private void serve(AclHttpSettings acl, MembershipService membership) throws Exception {
        server = new HttpApiServer(0, leader, stores.get("a"), Clock.fixed(NOW, ZoneOffset.UTC),
                AdminUiConfig.disabled(), null, HttpApiServer.DEFAULT_RAFT_TIMEOUT, membership, acl);
        server.start().join();
    }

    private String secret() {
        String secret = UUID.randomUUID().toString();
        secretsUsed.add(secret);
        return secret;
    }

    /** A policy with {@code rules}, created through the leader; returns its ID. */
    private String policy(String rules) throws Exception {
        String id = UUID.randomUUID().toString();
        assertSuccess(await(leader.submitCommand(AclCommand.createPolicy(
                new AclPolicy(id, "policy-" + ++policies, "", AclRulesJson.parse(rules))))));
        return id;
    }

    /** A token linked to a new policy with {@code rules}; returns its secret. */
    private String token(String rules) throws Exception {
        return createToken(List.of(policy(rules)), List.of());
    }

    /** A token holding a node identity for {@code nodeName}; returns its secret. */
    private String identityToken(String nodeName) throws Exception {
        return createToken(List.of(), List.of(new NodeIdentity(nodeName)));
    }

    private String createToken(List<String> policyIds, List<NodeIdentity> nodeIdentities) throws Exception {
        String secret = secret();
        assertSuccess(await(leader.submitCommand(AclCommand.createToken(new AclToken(UUID.randomUUID().toString(),
                AclSecrets.hash(secret), "", policyIds, List.of(), nodeIdentities, List.of(), NOW, null, false)))));
        return secret;
    }

    private String accessorOf(String secret) {
        return stores.get("a").getAcl().tokenBySecret(secret).orElseThrow().accessorId();
    }

    private void arrangeNode(String name, String nodeId) throws Exception {
        assertSuccess(await(leader.submitCommand(NodeCommand.register(
                Node.of(name, "10.0.0.1", null, null, Map.of()).withId(nodeId), NOW))));
    }

    /** A service on {@code node}, in the default scope, declaring the check {@code http}. */
    private void arrangeService(String serviceId, String serviceName, String node) throws Exception {
        assertSuccess(await(leader.submitCommand(CatalogCommand.register(new ServiceInstance(serviceId,
                serviceName, node, "10.0.0.1", 8080, List.of(), Map.of(), ServiceHealth.UNKNOWN), List.of("http")))));
    }

    private HttpResponse<String> registerNode(String name, String nodeId, String secret) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Qraft-Node", name);
        if (nodeId != null) headers.put("X-Qraft-Node-ID", nodeId);
        if (secret != null) headers.put("X-Qraft-Token", secret);
        return send("PUT", "/v1/catalog/register", "{\"node\":{\"address\":\"10.0.0.1\"}}", headers);
    }

    private static String serviceBody(String serviceId, String serviceName) {
        return "{\"service\":{\"serviceId\":\"" + serviceId + "\",\"serviceName\":\"" + serviceName
                + "\",\"address\":\"10.0.0.1\",\"port\":8080,\"checks\":[\"http\"]}}";
    }

    private static String observation(String serviceId, String checkId) {
        return """
                {"serviceId":"%s","checkId":"%s","status":"passing","sequenceNumber":1,\
                "observedAt":"2026-11-06T09:59:59Z","ttlMillis":30000}""".formatted(serviceId, checkId);
    }

    private HttpResponse<String> send(String method, String path, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.port() + path))
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String code(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).get("code").asText();
    }

    private static String message(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).get("message").asText();
    }

    private static List<String> names(HttpResponse<String> response, String field) throws Exception {
        List<String> names = new ArrayList<>();
        JSON.readTree(response.body()).forEach(entry -> names.add(entry.get(field).asText()));
        return names;
    }

    private static void assertSuccess(RaftCommandResult<?> result) {
        assertInstanceOf(RaftCommandResult.Success.class, result, String.valueOf(result));
    }
}

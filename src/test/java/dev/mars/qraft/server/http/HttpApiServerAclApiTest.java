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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.acl.AclRulesJson;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.InMemoryTransportSimulatorFixture;
import dev.mars.qraft.raft.ManualRaftClusterFixture;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.AclCommand;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.acl.AclBuiltins;
import dev.mars.qraft.state.acl.AclPolicy;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;
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
 * Tests the ACL API of a server (ACL list, Phase 5), after Consul's {@code agent/acl_endpoint.go} in camel case: tokens,
 * with their list filters, clone and {@code self}; policies and roles, by ID and by name; the internal
 * {@code authorize} and {@code resolve} routes, which answer only for the presenting token; and {@code explain}
 * (proposal 19). Every route but {@code self}, {@code authorize} and {@code resolve} needs {@code acl:read} or
 * {@code acl:write}; a secret is answered only by the request that made it, and never its hash (proposal 3); with ACLs
 * disabled every route answers 401 {@code acl_disabled}; and a change that would leave no token holding
 * {@code acl:write} is refused with 409 {@code acl_lockout} (proposal 17). No answer after the first, and no log line,
 * holds a secret.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class HttpApiServerAclApiTest {
    private static final Set<String> MEMBERS = Set.of("a", "b", "c");
    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final String HIDDEN = "<hidden>";
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
    private String management;
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
        management = secret();
        assertSuccess(await(leader.submitCommand(AclCommand.bootstrap(new AclToken(UUID.randomUUID().toString(),
                AclSecrets.hash(management), "management", List.of(AclBuiltins.GLOBAL_MANAGEMENT_ID), List.of(),
                List.of(), List.of(), NOW, null, false), 0))));
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

    // ---- ACLs disabled, and who may call ----

    @Test
    void withAclsDisabledEveryRouteAnswers401() throws Exception {
        serve(AclHttpSettings.disabled());
        List<String[]> routes = List.of(
                new String[] {"PUT", "/v1/acl/token", "{}"}, new String[] {"GET", "/v1/acl/tokens", ""},
                new String[] {"GET", "/v1/acl/token/self", ""},
                new String[] {"GET", "/v1/acl/token/" + UUID.randomUUID(), ""},
                new String[] {"PUT", "/v1/acl/token/" + UUID.randomUUID() + "/clone", ""},
                new String[] {"DELETE", "/v1/acl/token/" + UUID.randomUUID(), ""},
                new String[] {"PUT", "/v1/acl/policy", "{}"}, new String[] {"GET", "/v1/acl/policies", ""},
                new String[] {"GET", "/v1/acl/policy/name/x", ""},
                new String[] {"PUT", "/v1/acl/role", "{}"}, new String[] {"GET", "/v1/acl/roles", ""},
                new String[] {"POST", "/v1/internal/acl/authorize", "[]"},
                new String[] {"POST", "/v1/internal/acl/resolve", ""},
                new String[] {"POST", "/v1/acl/explain", "{}"});

        for (String[] route : routes) {
            HttpResponse<String> response = send(route[0], route[1], route[2], management);
            assertEquals(401, response.statusCode(), String.join(" ", route) + ": " + response.body());
            assertEquals("acl_disabled", code(response), String.join(" ", route));
            assertEquals("ACL support disabled", message(response));
        }
    }

    @Test
    void readingNeedsAclReadAndChangingNeedsAclWrite() throws Exception {
        serveDenying();
        String reader = tokenWith("{\"acl\":\"read\"}");
        String nobody = tokenWith("{\"node_prefix\":{\"\":{\"policy\":\"read\"}}}");

        HttpResponse<String> list = send("GET", "/v1/acl/tokens", "", reader);
        assertEquals(200, list.statusCode(), list.body());
        HttpResponse<String> create = send("PUT", "/v1/acl/policy", policyBody("p-reader", "{}"), reader);
        assertEquals(403, create.statusCode(), create.body());
        assertEquals("permission_denied", code(create));
        assertTrue(message(create).endsWith("lacks permission 'acl:write'"), message(create));

        HttpResponse<String> refused = send("GET", "/v1/acl/policies", "", nobody);
        assertEquals(403, refused.statusCode(), refused.body());
        assertTrue(message(refused).endsWith("lacks permission 'acl:read'"), message(refused));
        assertEquals("token_not_found", code(send("GET", "/v1/acl/roles", "", secret())));
        // No default policy grants acl, so even allow leaves the ACL API closed to the anonymous token.
        server.close();
        serve(new AclHttpSettings(true, dataDirectory, AclDefaultPolicy.ALLOW));
        assertEquals(403, send("GET", "/v1/acl/tokens", "", null).statusCode());
    }

    // ---- Tokens ----

    @Test
    void creatingATokenAnswersItsSecretOnceAndLaterReadsHideIt() throws Exception {
        serveDenying();
        String policy = policy("{\"node_prefix\":{\"\":{\"policy\":\"read\"}}}");

        HttpResponse<String> created = send("PUT", "/v1/acl/token", """
                {"description":"reader","policies":[{"id":"%s"}],"nodeIdentities":[{"nodeName":"web-1"}],
                 "serviceIdentities":[{"serviceName":"api","tenant":"acme","namespace":"prod"}]}
                """.formatted(policy), management);

        assertEquals(200, created.statusCode(), created.body());
        JsonNode token = JSON.readTree(created.body());
        String secret = token.get("secretId").asText();
        secretsUsed.add(secret);
        String accessor = token.get("accessorId").asText();
        assertTrue(AclSecrets.isUuid(secret) && AclSecrets.isUuid(accessor), created.body());
        assertEquals("reader", token.get("description").asText());
        assertEquals(policy, token.get("policies").get(0).get("id").asText());
        assertEquals("policy-1", token.get("policies").get(0).get("name").asText());
        assertEquals("web-1", token.get("nodeIdentities").get(0).get("nodeName").asText());
        assertEquals("acme", token.get("serviceIdentities").get(0).get("tenant").asText());
        assertEquals(NOW.toString(), token.get("createTime").asText(), "the leader stamps the creation time");
        AclToken stored = stores.get("a").getAcl().tokenBySecret(secret).orElseThrow();
        assertEquals(accessor, stored.accessorId());
        assertFalse(created.body().contains(stored.secretHash()), "an answer never holds the hash");

        for (String path : List.of("/v1/acl/token/" + accessor, "/v1/acl/tokens")) {
            HttpResponse<String> read = send("GET", path, "", management);
            assertEquals(200, read.statusCode(), read.body());
            assertFalse(read.body().contains(secret), path + " answers the secret");
            assertFalse(read.body().contains(stored.secretHash()), path + " answers the hash");
            assertTrue(read.body().contains(HIDDEN), path);
        }
        assertEquals("reader", JSON.readTree(send("GET", "/v1/acl/token/" + accessor, "", management).body())
                .get("description").asText());
    }

    @Test
    void aTokenLinksToPoliciesAndRolesByIdOrByName() throws Exception {
        serveDenying();
        String policy = policy("{\"node_prefix\":{\"\":{\"policy\":\"read\"}}}");
        HttpResponse<String> role = send("PUT", "/v1/acl/role",
                "{\"name\":\"readers\",\"policies\":[{\"name\":\"policy-1\"}]}", management);
        assertEquals(200, role.statusCode(), role.body());

        HttpResponse<String> created = send("PUT", "/v1/acl/token",
                "{\"policies\":[{\"name\":\"policy-1\"}],\"roles\":[{\"name\":\"readers\"}]}", management);

        assertEquals(200, created.statusCode(), created.body());
        secretsUsed.add(JSON.readTree(created.body()).get("secretId").asText());
        JsonNode token = JSON.readTree(created.body());
        assertEquals(policy, token.get("policies").get(0).get("id").asText());
        assertEquals(JSON.readTree(role.body()).get("id").asText(), token.get("roles").get(0).get("id").asText());

        HttpResponse<String> missing = send("PUT", "/v1/acl/token", "{\"policies\":[{\"name\":\"absent\"}]}",
                management);
        assertEquals(400, missing.statusCode(), missing.body());
        assertEquals("policy_not_found", code(missing));
    }

    @Test
    void aSuppliedAccessorAndSecretAreUsedAndAnExpiryIsBounded() throws Exception {
        serveDenying();
        String accessor = UUID.randomUUID().toString();
        String supplied = secret();

        HttpResponse<String> created = send("PUT", "/v1/acl/token",
                "{\"accessorId\":\"" + accessor + "\",\"secretId\":\"" + supplied + "\",\"expirationTtl\":\"1h\"}",
                management);

        assertEquals(200, created.statusCode(), created.body());
        assertEquals(accessor, JSON.readTree(created.body()).get("accessorId").asText());
        assertEquals(NOW.plusSeconds(3600).toString(), JSON.readTree(created.body()).get("expirationTime").asText());
        assertEquals(accessor, stores.get("a").getAcl().tokenBySecret(supplied).orElseThrow().accessorId());

        for (String body : List.of("{\"expirationTtl\":\"30s\"}", "{\"expirationTtl\":\"25h\"}",
                "{\"expirationTtl\":\"soon\"}", "{\"expirationTime\":\"" + NOW.plusSeconds(10) + "\"}",
                "{\"expirationTime\":\"" + NOW.plusSeconds(600) + "\",\"expirationTtl\":\"10m\"}",
                "{\"secretId\":\"not-a-uuid\"}", "{\"accessorId\":\"00000000-0000-0000-0000-000000000042\"}",
                "{\"unknown\":true}", "[]")) {
            HttpResponse<String> refused = send("PUT", "/v1/acl/token", body, management);
            assertEquals(400, refused.statusCode(), body + ": " + refused.body());
            assertEquals("invalid_token", code(refused), body);
        }
        HttpResponse<String> taken = send("PUT", "/v1/acl/token", "{\"accessorId\":\"" + accessor + "\"}",
                management);
        assertEquals(409, taken.statusCode(), taken.body());
        assertEquals("accessor_in_use", code(taken));
    }

    @Test
    void updatingATokenReplacesItsLinksAndKeepsItsSecretAndExpiry() throws Exception {
        serveDenying();
        String policy = policy("{\"node_prefix\":{\"\":{\"policy\":\"read\"}}}");
        JsonNode created = createToken("{\"description\":\"before\",\"expirationTtl\":\"2h\"}");
        String accessor = created.get("accessorId").asText();
        String secret = created.get("secretId").asText();

        HttpResponse<String> updated = send("PUT", "/v1/acl/token/" + accessor,
                "{\"description\":\"after\",\"policies\":[{\"id\":\"" + policy + "\"}]}", management);

        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals(HIDDEN, JSON.readTree(updated.body()).get("secretId").asText());
        AclToken stored = stores.get("a").getAcl().tokenBySecret(secret).orElseThrow();
        assertEquals("after", stored.description());
        assertEquals(List.of(policy), stored.policyIds());
        assertEquals(NOW.plusSeconds(7200), stored.expirationTime(), "an update keeps the expiry");

        HttpResponse<String> newSecret = send("PUT", "/v1/acl/token/" + accessor,
                "{\"secretId\":\"" + secret() + "\"}", management);
        assertEquals(400, newSecret.statusCode(), newSecret.body());
        assertEquals("secret_immutable", code(newSecret));
        HttpResponse<String> newExpiry = send("PUT", "/v1/acl/token/" + accessor,
                "{\"expirationTime\":\"" + NOW.plusSeconds(600) + "\"}", management);
        assertEquals("expiry_immutable", code(newExpiry));
        HttpResponse<String> otherAccessor = send("PUT", "/v1/acl/token/" + accessor,
                "{\"accessorId\":\"" + UUID.randomUUID() + "\"}", management);
        assertEquals(400, otherAccessor.statusCode());
        assertEquals("invalid_token", code(otherAccessor));
        HttpResponse<String> absent = send("PUT", "/v1/acl/token/" + UUID.randomUUID(), "{}", management);
        assertEquals(404, absent.statusCode(), absent.body());
        assertEquals("acl_not_found", code(absent));
    }

    @Test
    void cloningMakesANewSecretWithTheSameLinks() throws Exception {
        serveDenying();
        String policy = policy("{\"node_prefix\":{\"\":{\"policy\":\"read\"}}}");
        JsonNode original = createToken("{\"description\":\"original\",\"policies\":[{\"id\":\"" + policy + "\"}]}");

        HttpResponse<String> cloned = send("PUT", "/v1/acl/token/" + original.get("accessorId").asText() + "/clone",
                "{\"description\":\"copy\"}", management);

        assertEquals(200, cloned.statusCode(), cloned.body());
        JsonNode clone = JSON.readTree(cloned.body());
        String secret = clone.get("secretId").asText();
        secretsUsed.add(secret);
        assertNotEquals(original.get("accessorId").asText(), clone.get("accessorId").asText());
        assertNotEquals(original.get("secretId").asText(), secret);
        assertEquals("copy", clone.get("description").asText());
        assertEquals(List.of(policy), stores.get("a").getAcl().tokenBySecret(secret).orElseThrow().policyIds());
        assertEquals(404, send("PUT", "/v1/acl/token/" + UUID.randomUUID() + "/clone", "", management)
                .statusCode());
    }

    @Test
    void deletingATokenRefusesTheCallersOwnTheAnonymousTokenAndALockOut() throws Exception {
        serveDenying();
        JsonNode reader = createToken("{}");

        HttpResponse<String> deleted = send("DELETE", "/v1/acl/token/" + reader.get("accessorId").asText(), "",
                management);
        assertEquals(200, deleted.statusCode(), deleted.body());
        assertEquals("true", deleted.body());
        assertTrue(stores.get("a").getAcl().token(reader.get("accessorId").asText()).isEmpty());

        String managementAccessor = stores.get("a").getAcl().tokenBySecret(management).orElseThrow().accessorId();
        HttpResponse<String> own = send("DELETE", "/v1/acl/token/" + managementAccessor, "", management);
        assertEquals(400, own.statusCode(), own.body());
        assertEquals("self_deletion", code(own));
        assertEquals("Deletion of the request's authorization token is not permitted", message(own));

        HttpResponse<String> anonymous = send("DELETE", "/v1/acl/token/" + AclBuiltins.ANONYMOUS_TOKEN_ID, "",
                management);
        assertEquals(400, anonymous.statusCode(), anonymous.body());
        assertEquals("builtin_immutable", code(anonymous));

        // Another holder of acl:write may not delete the last token that holds it without an expiry.
        String expiring = expiringManagementToken();
        HttpResponse<String> lockout = send("DELETE", "/v1/acl/token/" + managementAccessor, "", expiring);
        assertEquals(409, lockout.statusCode(), lockout.body());
        assertEquals("acl_lockout", code(lockout));
        assertEquals(404, send("DELETE", "/v1/acl/token/" + UUID.randomUUID(), "", management).statusCode());
    }

    @Test
    void selfAnswersThePresentingTokenWithoutAGrant() throws Exception {
        serveDenying();
        String nobody = tokenWith("{\"node\":{\"x\":{\"policy\":\"deny\"}}}");

        HttpResponse<String> self = send("GET", "/v1/acl/token/self", "", nobody);
        assertEquals(200, self.statusCode(), self.body());
        assertEquals(stores.get("a").getAcl().tokenBySecret(nobody).orElseThrow().accessorId(),
                JSON.readTree(self.body()).get("accessorId").asText());
        assertEquals(HIDDEN, JSON.readTree(self.body()).get("secretId").asText());
        assertFalse(self.body().contains(nobody));

        HttpResponse<String> anonymous = send("GET", "/v1/acl/token/self", "", null);
        assertEquals(AclBuiltins.ANONYMOUS_TOKEN_ID, JSON.readTree(anonymous.body()).get("accessorId").asText());
        assertEquals("token_not_found", code(send("GET", "/v1/acl/token/self", "", secret())));
    }

    @Test
    void theTokenListFiltersByPolicyRoleAndServiceName() throws Exception {
        serveDenying();
        String policy = policy("{\"node_prefix\":{\"\":{\"policy\":\"read\"}}}");
        String role = JSON.readTree(send("PUT", "/v1/acl/role", "{\"name\":\"r\",\"policies\":[{\"id\":\"" + policy
                + "\"}]}", management).body()).get("id").asText();
        String byPolicy = createToken("{\"policies\":[{\"id\":\"" + policy + "\"}]}").get("accessorId").asText();
        String byRole = createToken("{\"roles\":[{\"id\":\"" + role + "\"}]}").get("accessorId").asText();
        String byService = createToken("{\"serviceIdentities\":[{\"serviceName\":\"api\"}]}").get("accessorId")
                .asText();

        assertEquals(List.of(byPolicy), accessors(send("GET", "/v1/acl/tokens?policy=" + policy, "", management)));
        assertEquals(List.of(byRole), accessors(send("GET", "/v1/acl/tokens?role=" + role, "", management)));
        assertEquals(List.of(byService), accessors(send("GET", "/v1/acl/tokens?servicename=api", "", management)));
        assertEquals(5, accessors(send("GET", "/v1/acl/tokens", "", management)).size(),
                "the anonymous token, the management token and three more");
        HttpResponse<String> unknown = send("GET", "/v1/acl/tokens?owner=me", "", management);
        assertEquals(400, unknown.statusCode());
        assertEquals("invalid_query", code(unknown));
    }

    // ---- Policies and roles ----

    @Test
    void aPolicyIsCreatedReadUpdatedAndDeletedWithItsRulesAsJson() throws Exception {
        serveDenying();

        HttpResponse<String> created = send("PUT", "/v1/acl/policy",
                policyBody("web-writers", "{\"service\":{\"web\":{\"policy\":\"write\"}}}"), management);
        assertEquals(200, created.statusCode(), created.body());
        JsonNode policy = JSON.readTree(created.body());
        String id = policy.get("id").asText();
        assertEquals("web-writers", policy.get("name").asText());
        assertEquals(JSON.readTree("{\"service\":{\"web\":{\"policy\":\"write\"}}}"), policy.get("rules"));

        assertEquals(id, JSON.readTree(send("GET", "/v1/acl/policy/" + id, "", management).body())
                .get("id").asText());
        assertEquals(id, JSON.readTree(send("GET", "/v1/acl/policy/name/web-writers", "", management).body())
                .get("id").asText());
        List<String> names = new ArrayList<>();
        JSON.readTree(send("GET", "/v1/acl/policies", "", management).body())
                .forEach(entry -> names.add(entry.get("name").asText()));
        assertEquals(List.of("builtin/global-read-only", "global-management", "web-writers"),
                names.stream().sorted().toList());

        HttpResponse<String> updated = send("PUT", "/v1/acl/policy/" + id, "{\"name\":\"web-readers\","
                + "\"description\":\"reads\",\"rules\":\"{\\\"service\\\":{\\\"web\\\":{\\\"policy\\\":\\\"read\\\"}}}\"}",
                management);
        assertEquals(200, updated.statusCode(), updated.body());
        AclPolicy stored = stores.get("a").getAcl().policy(id).orElseThrow();
        assertEquals("web-readers", stored.name());
        assertEquals(AclRulesJson.parse("{\"service\":{\"web\":{\"policy\":\"read\"}}}"), stored.rules());

        HttpResponse<String> deleted = send("DELETE", "/v1/acl/policy/" + id, "", management);
        assertEquals(200, deleted.statusCode(), deleted.body());
        assertTrue(stores.get("a").getAcl().policy(id).isEmpty());
        assertEquals(404, send("GET", "/v1/acl/policy/" + id, "", management).statusCode());
        assertEquals(404, send("GET", "/v1/acl/policy/name/web-readers", "", management).statusCode());
    }

    @Test
    void aPolicyIsRefusedForInvalidRulesATakenNameOrABuiltIn() throws Exception {
        serveDenying();
        send("PUT", "/v1/acl/policy", policyBody("taken", "{}"), management);

        HttpResponse<String> invalid = send("PUT", "/v1/acl/policy", policyBody("bad", "{\"servcie\":{}}"),
                management);
        assertEquals(400, invalid.statusCode(), invalid.body());
        assertEquals("invalid_policy", code(invalid));
        assertTrue(message(invalid).contains("servcie"), message(invalid));
        HttpResponse<String> taken = send("PUT", "/v1/acl/policy", policyBody("TAKEN", "{}"), management);
        assertEquals(409, taken.statusCode(), taken.body());
        assertEquals("name_in_use", code(taken));
        HttpResponse<String> builtIn = send("PUT", "/v1/acl/policy/" + AclBuiltins.GLOBAL_MANAGEMENT_ID,
                policyBody("global-management", "{}"), management);
        assertEquals(400, builtIn.statusCode(), builtIn.body());
        assertEquals("builtin_immutable", code(builtIn));
        HttpResponse<String> deleteBuiltIn = send("DELETE", "/v1/acl/policy/" + AclBuiltins.GLOBAL_READ_ONLY_ID, "",
                management);
        assertEquals("builtin_immutable", code(deleteBuiltIn));
        HttpResponse<String> renamed = send("PUT", "/v1/acl/policy/" + AclBuiltins.GLOBAL_MANAGEMENT_ID,
                "{\"name\":\"superuser\",\"rules\":" + AclRulesJson.write(
                        stores.get("a").getAcl().policy(AclBuiltins.GLOBAL_MANAGEMENT_ID).orElseThrow().rules())
                        + "}", management);
        assertEquals(200, renamed.statusCode(), "a built-in may be renamed: " + renamed.body());
    }

    @Test
    void deletingThePolicyThatHoldsTheLastAclWriteIsALockOut() throws Exception {
        serveDenying();
        String policy = JSON.readTree(send("PUT", "/v1/acl/policy", policyBody("admins", "{\"acl\":\"write\"}"),
                management).body()).get("id").asText();
        String admin = createToken("{\"policies\":[{\"id\":\"" + policy + "\"}]}").get("secretId").asText();
        String managementAccessor = stores.get("a").getAcl().tokenBySecret(management).orElseThrow().accessorId();
        assertEquals(200, send("DELETE", "/v1/acl/token/" + managementAccessor, "", admin).statusCode());

        HttpResponse<String> lockout = send("DELETE", "/v1/acl/policy/" + policy, "", admin);

        assertEquals(409, lockout.statusCode(), lockout.body());
        assertEquals("acl_lockout", code(lockout));
        assertTrue(message(lockout).contains("acl:write"), message(lockout));
    }

    @Test
    void aRoleIsCreatedReadUpdatedAndDeleted() throws Exception {
        serveDenying();
        String policy = policy("{\"node_prefix\":{\"\":{\"policy\":\"read\"}}}");

        HttpResponse<String> created = send("PUT", "/v1/acl/role", """
                {"name":"readers","description":"read nodes","policies":[{"id":"%s"}],
                 "nodeIdentities":[{"nodeName":"web-1"}]}""".formatted(policy), management);
        assertEquals(200, created.statusCode(), created.body());
        JsonNode role = JSON.readTree(created.body());
        String id = role.get("id").asText();
        assertEquals("policy-1", role.get("policies").get(0).get("name").asText());
        assertEquals("web-1", role.get("nodeIdentities").get(0).get("nodeName").asText());

        assertEquals(id, JSON.readTree(send("GET", "/v1/acl/role/" + id, "", management).body()).get("id").asText());
        assertEquals(id, JSON.readTree(send("GET", "/v1/acl/role/name/readers", "", management).body())
                .get("id").asText());
        assertEquals(1, JSON.readTree(send("GET", "/v1/acl/roles", "", management).body()).size());
        assertEquals(1, JSON.readTree(send("GET", "/v1/acl/roles?policy=" + policy, "", management).body()).size());

        HttpResponse<String> updated = send("PUT", "/v1/acl/role/" + id, "{\"name\":\"node-readers\"}", management);
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals("node-readers", stores.get("a").getAcl().role(id).orElseThrow().name());
        assertEquals(List.of(), stores.get("a").getAcl().role(id).orElseThrow().policyIds(), "links are replaced");

        assertEquals(200, send("DELETE", "/v1/acl/role/" + id, "", management).statusCode());
        assertEquals(404, send("GET", "/v1/acl/role/" + id, "", management).statusCode());
        HttpResponse<String> invalid = send("PUT", "/v1/acl/role", "{\"name\":\"has space\"}", management);
        assertEquals(400, invalid.statusCode(), invalid.body());
        assertEquals("invalid_role", code(invalid));
    }

    // ---- Authorize, resolve and explain ----

    @Test
    void authorizeAnswersOnlyForThePresentingToken() throws Exception {
        serveDenying();
        String webWriter = tokenWith("{\"service\":{\"web\":{\"policy\":\"write\"}}}");

        HttpResponse<String> answer = send("POST", "/v1/internal/acl/authorize", """
                [{"resource":"service","segment":"web","access":"write"},
                 {"resource":"service","segment":"db","access":"read"},
                 {"resource":"service","segment":"web","access":"read","tenant":"acme","namespace":"prod"},
                 {"resource":"operator","access":"read"}]""", webWriter);

        assertEquals(200, answer.statusCode(), answer.body());
        List<Boolean> allowed = new ArrayList<>();
        JSON.readTree(answer.body()).forEach(check -> allowed.add(check.get("allow").asBoolean()));
        assertEquals(List.of(true, false, false, false), allowed);
        assertEquals("web", JSON.readTree(answer.body()).get(0).get("segment").asText());

        StringBuilder tooMany = new StringBuilder("[");
        for (int i = 0; i < 65; i++) tooMany.append(i == 0 ? "" : ",").append("{\"resource\":\"operator\",\"access\":\"read\"}");
        HttpResponse<String> refused = send("POST", "/v1/internal/acl/authorize", tooMany + "]", webWriter);
        assertEquals(400, refused.statusCode(), refused.body());
        assertEquals("invalid_authorization", code(refused));
        assertEquals("invalid_authorization", code(send("POST", "/v1/internal/acl/authorize",
                "[{\"resource\":\"keyring\",\"segment\":\"x\",\"access\":\"read\"}]", webWriter)));
        HttpResponse<String> session = send("POST", "/v1/internal/acl/authorize",
                "[{\"resource\":\"session\",\"segment\":\"x\",\"access\":\"read\"}]", webWriter);
        assertEquals(200, session.statusCode(), "session is a resource since the sessions list: " + session.body());
        assertEquals(false, JSON.readTree(session.body()).get(0).get("allow").asBoolean());
        HttpResponse<String> key = send("POST", "/v1/internal/acl/authorize",
                "[{\"resource\":\"key\",\"segment\":\"x\",\"access\":\"read\"}]", webWriter);
        assertEquals(200, key.statusCode(), "key is a resource since the key/value list: " + key.body());
        assertEquals(false, JSON.readTree(key.body()).get(0).get("allow").asBoolean());
        assertEquals("token_not_found", code(send("POST", "/v1/internal/acl/authorize", "[]", secret())));
    }

    @Test
    void resolveAnswersThePresentingTokenWithThePoliciesAndRolesItLinksTo() throws Exception {
        serveDenying();
        String direct = policy("{\"node_prefix\":{\"\":{\"policy\":\"read\"}}}");
        String throughRole = policy("{\"service_prefix\":{\"\":{\"policy\":\"read\"}}}");
        policy("{\"operator\":\"read\"}");
        String role = JSON.readTree(send("PUT", "/v1/acl/role", "{\"name\":\"r\",\"policies\":[{\"id\":\""
                + throughRole + "\"}]}", management).body()).get("id").asText();
        String secret = createToken("{\"policies\":[{\"id\":\"" + direct + "\"}],\"roles\":[{\"id\":\"" + role
                + "\"}]}").get("secretId").asText();

        HttpResponse<String> resolved = send("POST", "/v1/internal/acl/resolve", "", secret);

        assertEquals(200, resolved.statusCode(), resolved.body());
        JsonNode body = JSON.readTree(resolved.body());
        assertEquals(HIDDEN, body.get("token").get("secretId").asText());
        List<String> policyIds = new ArrayList<>();
        body.get("policies").forEach(policy -> policyIds.add(policy.get("id").asText()));
        assertEquals(List.of(direct, throughRole).stream().sorted().toList(), policyIds.stream().sorted().toList(),
                "only what the token links to, directly or through its roles");
        assertTrue(body.get("policies").get(0).has("rules"));
        assertEquals(role, body.get("roles").get(0).get("id").asText());
        assertFalse(resolved.body().contains(secret));
    }

    @Test
    void explainSaysWhatDecidedARequest() throws Exception {
        serveDenying();
        String policy = policy("{\"service_prefix\":{\"web\":{\"policy\":\"write\"}}}");
        String accessor = createToken("{\"policies\":[{\"id\":\"" + policy + "\"}],"
                + "\"nodeIdentities\":[{\"nodeName\":\"web-1\"}]}").get("accessorId").asText();
        String reader = tokenWith("{\"acl\":\"read\"}");

        JsonNode byRule = JSON.readTree(send("POST", "/v1/acl/explain", "{\"accessorId\":\"" + accessor
                + "\",\"resource\":\"service\",\"name\":\"web-api\",\"access\":\"write\"}", reader).body());
        assertTrue(byRule.get("allowed").asBoolean(), byRule.toString());
        assertEquals("rule", byRule.get("decidedBy").asText());
        assertEquals(policy, byRule.get("policy").get("id").asText());
        assertEquals("web", byRule.get("rule").get("name").asText());
        assertTrue(byRule.get("rule").get("prefix").asBoolean());

        JsonNode byIdentity = JSON.readTree(send("POST", "/v1/acl/explain", "{\"accessorId\":\"" + accessor
                + "\",\"resource\":\"node\",\"name\":\"web-1\",\"access\":\"write\"}", reader).body());
        assertEquals("web-1", byIdentity.get("nodeIdentity").get("nodeName").asText(), byIdentity.toString());

        JsonNode byDefault = JSON.readTree(send("POST", "/v1/acl/explain", "{\"accessorId\":\"" + accessor
                + "\",\"resource\":\"operator\",\"access\":\"read\"}", reader).body());
        assertFalse(byDefault.get("allowed").asBoolean());
        assertEquals("default", byDefault.get("decidedBy").asText());
        assertEquals("deny", byDefault.get("defaultPolicy").asText());

        assertEquals(403, send("POST", "/v1/acl/explain", "{\"accessorId\":\"" + accessor
                + "\",\"resource\":\"operator\",\"access\":\"read\"}", tokenWith("{}")).statusCode());
        assertEquals(404, send("POST", "/v1/acl/explain", "{\"accessorId\":\"" + UUID.randomUUID()
                + "\",\"resource\":\"operator\",\"access\":\"read\"}", reader).statusCode());
        assertEquals("invalid_explanation", code(send("POST", "/v1/acl/explain", "{\"accessorId\":\"" + accessor
                + "\",\"resource\":\"service\",\"access\":\"read\"}", reader)));
    }

    @Test
    void aFollowerAnswersReadsAndSendsChangesToTheLeader() throws Exception {
        // A follower answers from its applied state, so it must first hear of the management token.
        cluster.heartbeatUntil(leader, () -> stores.get("b").getAcl().tokenBySecret(management).isPresent(),
                "the follower applies the bootstrap");
        server = new HttpApiServer(0, nodes.get("b"), stores.get("b"), Clock.fixed(NOW, ZoneOffset.UTC),
                AdminUiConfig.disabled(), null, HttpApiServer.DEFAULT_RAFT_TIMEOUT, null,
                new AclHttpSettings(true, dataDirectory, AclDefaultPolicy.DENY));
        server.start().join();

        assertEquals(200, send("GET", "/v1/acl/policies", "", management).statusCode());
        HttpResponse<String> write = send("PUT", "/v1/acl/policy", policyBody("p", "{}"), management);
        assertEquals(503, write.statusCode(), write.body());
        assertEquals("leader_unavailable", code(write));
    }

    // ---- Helpers ----

    private void serveDenying() throws Exception {
        serve(new AclHttpSettings(true, dataDirectory, AclDefaultPolicy.DENY));
    }

    private void serve(AclHttpSettings acl) throws Exception {
        server = new HttpApiServer(0, leader, stores.get("a"), Clock.fixed(NOW, ZoneOffset.UTC),
                AdminUiConfig.disabled(), null, HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, acl);
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

    /** A token linked to a new policy with {@code rules}, created through the leader; returns its secret. */
    private String tokenWith(String rules) throws Exception {
        String policy = policy(rules);
        String secret = secret();
        assertSuccess(await(leader.submitCommand(AclCommand.createToken(new AclToken(UUID.randomUUID().toString(),
                AclSecrets.hash(secret), "", List.of(policy), List.of(), List.of(), List.of(), NOW, null, false)))));
        return secret;
    }

    /** A token holding the management policy that expires in an hour; returns its secret. */
    private String expiringManagementToken() throws Exception {
        String secret = secret();
        assertSuccess(await(leader.submitCommand(AclCommand.createToken(new AclToken(UUID.randomUUID().toString(),
                AclSecrets.hash(secret), "", List.of(AclBuiltins.GLOBAL_MANAGEMENT_ID), List.of(), List.of(),
                List.of(), NOW, NOW.plusSeconds(3600), false)))));
        return secret;
    }

    /** Creates a token through the API with {@code body}; returns its answer, remembering its secret. */
    private JsonNode createToken(String body) throws Exception {
        HttpResponse<String> created = send("PUT", "/v1/acl/token", body, management);
        assertEquals(200, created.statusCode(), created.body());
        JsonNode token = JSON.readTree(created.body());
        secretsUsed.add(token.get("secretId").asText());
        return token;
    }

    private static String policyBody(String name, String rules) {
        return "{\"name\":\"" + name + "\",\"rules\":" + rules + "}";
    }

    private static List<String> accessors(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        List<String> accessors = new ArrayList<>();
        JSON.readTree(response.body()).forEach(token -> accessors.add(token.get("accessorId").asText()));
        return accessors;
    }

    private HttpResponse<String> send(String method, String path, String body, String secret) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.port() + path))
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (secret != null) request.header("X-Qraft-Token", secret);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String code(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("code").asText();
    }

    private static String message(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("message").asText();
    }

    private static void assertSuccess(RaftCommandResult<?> result) {
        assertInstanceOf(RaftCommandResult.Success.class, result, String.valueOf(result));
    }
}

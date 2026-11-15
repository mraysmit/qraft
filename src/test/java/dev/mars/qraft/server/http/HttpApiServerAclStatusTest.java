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
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.acl.AclRulesJson;
import dev.mars.qraft.common.acl.NodeIdentity;
import dev.mars.qraft.common.acl.ServiceIdentity;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.common.tls.ServerTls;
import dev.mars.qraft.common.tls.TlsMaterial;
import dev.mars.qraft.raft.InMemoryTransportSimulatorFixture;
import dev.mars.qraft.raft.ManualRaftClusterFixture;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.config.SecurityProfile;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the administrative interface's security status read, {@code GET /v1/internal/ui/acl/status} (UI-5 task list,
 * decision 4): whether ACLs are on and the default policy, the security profile, whether the listener serves HTTPS and
 * asks for client certificates, the management tokens, the anonymous token's policies and what they let it write, the
 * tokens holding {@code acl:write} without an expiry as the lock-out guard decides them, and the tokens that expire
 * within an hour with what they hold. It needs {@code acl:read}, and with ACLs disabled answers 401
 * {@code acl_disabled}, as every ACL route. No answer holds a secret.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-15
 * @version 1.0
 */
class HttpApiServerAclStatusTest {
    private static final Set<String> MEMBERS = Set.of("a", "b", "c");
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final String STATUS = "/v1/internal/ui/acl/status";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dataDirectory;

    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private final Map<String, RaftNode> nodes = new HashMap<>();
    private final Map<String, QraftStateStore> stores = new HashMap<>();
    private final List<String> secretsUsed = new ArrayList<>();
    private RaftNode leader;
    private HttpApiServer server;
    private HttpClient client = HttpClient.newHttpClient();
    private String scheme = "http";
    private String management;
    private String managementAccessor;
    private int policies;

    @BeforeEach
    void setUp() throws Exception {
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
        managementAccessor = UUID.randomUUID().toString();
        assertSuccess(await(leader.submitCommand(AclCommand.bootstrap(new AclToken(managementAccessor,
                AclSecrets.hash(management), "bootstrap", List.of(AclBuiltins.GLOBAL_MANAGEMENT_ID), List.of(),
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
        }
    }

    @Test
    void theStatusNamesTheSettingsTheManagementTokensAndTheTokensHoldingAclWriteWithoutAnExpiry() throws Exception {
        serve(Optional.empty(), SecurityProfile.PRODUCTION);
        String fromFile = token("from the file", List.of(AclBuiltins.GLOBAL_MANAGEMENT_ID), List.of(), List.of(),
                null, true);
        String writer = token("acl writer", List.of(policy("{\"acl\":\"write\"}")), List.of(), List.of(), null, false);
        String expiringWriter = token("expiring writer", List.of(policy("{\"acl\":\"write\"}")), List.of(), List.of(),
                NOW.plus(Duration.ofHours(12)), false);
        String reader = token("acl reader", List.of(policy("{\"acl\":\"read\"}")), List.of(), List.of(), null, false);

        JsonNode status = ok(STATUS, management);

        assertTrue(status.path("enabled").asBoolean(), status.toString());
        assertEquals("deny", status.path("defaultPolicy").asText());
        assertEquals("production", status.path("securityProfile").asText());
        assertFalse(status.path("transport").path("https").asBoolean());
        assertFalse(status.path("transport").path("clientCertificateRequired").asBoolean());
        assertEquals(NOW.toString(), status.path("readAt").asText());
        assertEquals(60, status.path("expiringWithinMinutes").asInt());

        Map<String, JsonNode> managementTokens = byAccessor(status.path("managementTokens"));
        assertEquals(Set.of(managementAccessor, fromFile), managementTokens.keySet(),
                "the management tokens are those linked to global-management");
        assertFalse(managementTokens.get(managementAccessor).path("installedFromFile").asBoolean());
        assertTrue(managementTokens.get(fromFile).path("installedFromFile").asBoolean());
        assertEquals("from the file", managementTokens.get(fromFile).path("description").asText());

        assertEquals(Set.of(managementAccessor, fromFile, writer), byAccessor(status.path("aclWriters")).keySet(),
                "a token with an expiry, and one holding only acl:read, are not counted");
        assertFalse(byAccessor(status.path("aclWriters")).containsKey(expiringWriter));
        assertFalse(byAccessor(status.path("aclWriters")).containsKey(reader));
        assertNoSecret(status);
    }

    @Test
    void theAnonymousTokensPoliciesAreListedWithWhatTheyLetItWrite() throws Exception {
        serve(Optional.empty(), SecurityProfile.DEVELOPMENT);
        JsonNode untouched = ok(STATUS, management).path("anonymous");
        assertEquals(AclBuiltins.ANONYMOUS_TOKEN_ID, untouched.path("accessorId").asText());
        assertEquals(0, untouched.path("policies").size(), untouched.toString());
        assertEquals(0, untouched.path("writes").size());

        String readsNodes = policy("{\"node_prefix\":{\"\":{\"policy\":\"read\"}}}");
        String writesServices = policy("{\"service_prefix\":{\"\":{\"policy\":\"write\"}},"
                + "\"key_prefix\":{\"app/\":{\"policy\":\"write\"}}}");
        HttpResponse<String> update = send("PUT", "/v1/acl/token/" + AclBuiltins.ANONYMOUS_TOKEN_ID,
                "{\"description\":\"anonymous\",\"policies\":[{\"id\":\"" + readsNodes + "\"},{\"id\":\""
                        + writesServices + "\"}]}", management);
        assertEquals(200, update.statusCode(), update.body());

        JsonNode anonymous = ok(STATUS, management).path("anonymous");
        Map<String, String> linked = new HashMap<>();
        anonymous.path("policies").forEach(policy -> linked.put(policy.path("id").asText(),
                policy.path("name").asText()));
        assertEquals(Map.of(readsNodes, "policy-1", writesServices, "policy-2"), linked,
                "each linked policy by its ID and name");
        List<String> writes = new ArrayList<>();
        anonymous.path("writes").forEach(resource -> writes.add(resource.asText()));
        assertEquals(List.of("key", "service"), writes, "the resources its rules let it write, in name order");
    }

    @Test
    void theTokensThatExpireWithinAnHourAreListedWithWhatTheyHold() throws Exception {
        serve(Optional.empty(), SecurityProfile.DEVELOPMENT);
        String soon = token("soon", List.of(policy("{\"acl\":\"write\"}")), List.of(new NodeIdentity("web-1")),
                List.of(new ServiceIdentity("api", "acme", "prod")), NOW.plus(Duration.ofMinutes(30)), false);
        String plain = token("plain", List.of(), List.of(), List.of(new ServiceIdentity("db", "default", "default")),
                NOW.plus(Duration.ofHours(1)), false);
        token("later", List.of(policy("{\"acl\":\"write\"}")), List.of(), List.of(),
                NOW.plus(Duration.ofHours(2)), false);

        Map<String, JsonNode> expiring = byAccessor(ok(STATUS, management).path("expiringSoon"));

        assertEquals(Set.of(soon, plain), expiring.keySet(), "a token expiring after an hour is not listed; one expiring at the hour is");
        JsonNode first = expiring.get(soon);
        assertEquals("soon", first.path("description").asText());
        assertEquals(NOW.plus(Duration.ofMinutes(30)).toString(), first.path("expirationTime").asText());
        assertTrue(first.path("holdsAclWrite").asBoolean());
        assertEquals("web-1", first.path("nodeIdentities").get(0).path("nodeName").asText());
        JsonNode service = first.path("serviceIdentities").get(0);
        assertEquals("api", service.path("serviceName").asText());
        assertEquals("acme", service.path("tenant").asText());
        assertEquals("prod", service.path("namespace").asText());
        assertFalse(expiring.get(plain).path("holdsAclWrite").asBoolean());
    }

    @Test
    void theStatusNeedsAclReadAndIsRefusedWithAclsDisabled() throws Exception {
        serve(Optional.empty(), SecurityProfile.DEVELOPMENT);
        String reader = tokenSecret(token("reader", List.of(policy("{\"acl\":\"read\"}")), List.of(), List.of(), null,
                false));
        String nobody = tokenSecret(token("nobody", List.of(policy("{\"operator\":\"read\"}")), List.of(), List.of(),
                null, false));

        assertEquals(200, send("GET", STATUS, "", reader).statusCode());
        HttpResponse<String> refused = send("GET", STATUS, "", nobody);
        assertEquals(403, refused.statusCode(), refused.body());
        assertEquals("permission_denied", JSON.readTree(refused.body()).path("code").asText());
        assertEquals(403, send("GET", STATUS, "", null).statusCode(), "the anonymous token holds no acl:read");
        assertEquals("token_not_found", JSON.readTree(send("GET", STATUS, "", secret()).body()).path("code").asText());
        HttpResponse<String> query = send("GET", STATUS + "?stale", "", reader);
        assertEquals(400, query.statusCode(), query.body());
        assertEquals(405, send("PUT", STATUS, "{}", reader).statusCode());

        server.close();
        server = new HttpApiServer(0, leader, stores.get("a"), Clock.fixed(NOW, ZoneOffset.UTC),
                AdminUiConfig.disabled(), null, HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, AclHttpSettings.disabled());
        server.start().join();
        HttpResponse<String> disabled = send("GET", STATUS, "", null);
        assertEquals(401, disabled.statusCode(), disabled.body());
        assertEquals("acl_disabled", JSON.readTree(disabled.body()).path("code").asText());
    }

    @Test
    void overHttpsTheStatusSaysSoAndWhetherAClientCertificateIsRequired() throws Exception {
        TlsMaterial caller = HttpApiServerTlsTest.material("ca.pem", "client.pem", "client.key");
        client = HttpClient.newBuilder().sslContext(caller.clientContext()).sslParameters(caller.clientParameters(true))
                .connectTimeout(Duration.ofSeconds(5)).build();
        scheme = "https";
        serve(Optional.of(new ServerTls(HttpApiServerTlsTest.material("ca.pem", "server.pem", "server.key"), true)),
                SecurityProfile.DEVELOPMENT);

        JsonNode transport = ok(STATUS, management).path("transport");

        assertTrue(transport.path("https").asBoolean(), transport.toString());
        assertTrue(transport.path("clientCertificateRequired").asBoolean());
    }

    private void serve(Optional<ServerTls> tls, SecurityProfile profile) throws Exception {
        server = new HttpApiServer(0, leader, stores.get("a"), Clock.fixed(NOW, ZoneOffset.UTC),
                AdminUiConfig.disabled(), null, HttpApiServer.DEFAULT_RAFT_TIMEOUT, null,
                new AclHttpSettings(true, dataDirectory, AclDefaultPolicy.DENY), tls, profile);
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

    /** A token created through the leader; returns its accessor ID, and keeps its secret for {@link #tokenSecret}. */
    private String token(String description, List<String> policyIds, List<NodeIdentity> nodeIdentities,
                         List<ServiceIdentity> serviceIdentities, Instant expiry, boolean fromFile) throws Exception {
        String accessor = UUID.randomUUID().toString();
        String secret = secret();
        assertSuccess(await(leader.submitCommand(AclCommand.createToken(new AclToken(accessor, AclSecrets.hash(secret),
                description, policyIds, List.of(), nodeIdentities, serviceIdentities, NOW, expiry, fromFile)))));
        secrets.put(accessor, secret);
        return accessor;
    }

    private final Map<String, String> secrets = new HashMap<>();

    /** The secret of the token {@link #token} made under {@code accessor}. */
    private String tokenSecret(String accessor) {
        return secrets.get(accessor);
    }

    private JsonNode ok(String path, String secret) throws Exception {
        HttpResponse<String> response = send("GET", path, "", secret);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String secret) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(scheme + "://localhost:" + server.port() + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (secret != null) request.header("X-Qraft-Token", secret);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, JsonNode> byAccessor(JsonNode tokens) {
        Map<String, JsonNode> found = new HashMap<>();
        tokens.forEach(token -> found.put(token.path("accessorId").asText(), token));
        return found;
    }

    private void assertNoSecret(JsonNode answer) {
        for (String secret : secretsUsed) {
            assertFalse(answer.toString().contains(secret), "the status holds a secret");
        }
    }

    private static void assertSuccess(RaftCommandResult<?> result) {
        assertInstanceOf(RaftCommandResult.Success.class, result, String.valueOf(result));
    }
}

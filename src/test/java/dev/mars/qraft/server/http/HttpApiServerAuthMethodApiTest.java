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
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.AclCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.acl.AclPolicy;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests the auth method and binding rule API (authentication list, Phase 1), on Consul's: a {@code jwt} method with a
 * PEM public key is created, read, listed, updated and deleted, with its rules; a rule gets an ID and is listed by its
 * method; a key that is not one, a selector this server cannot read, and a missing method are refused; and with ACLs a
 * change needs {@code acl:write} and a read {@code acl:read}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class HttpApiServerAuthMethodApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dataRoot;

    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private HttpApiServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() throws Exception {
        try {
            if (server != null) server.close();
            if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        } finally {
            if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
            http.close();
        }
    }

    private void start(boolean acl) throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("auth-node").clusterNodes(Set.of("auth-node")).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null,
                HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, acl
                ? new AclHttpSettings(true, dataRoot.resolve("server"), AclDefaultPolicy.DENY)
                : AclHttpSettings.disabled());
        server.start().get(10, TimeUnit.SECONDS);
    }

    static String publicKeyPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(
                generator.generateKeyPair().getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----";
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static void refused(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        if (code != null) assertEquals(code, JSON.readTree(response.body()).path("code").asText(), response.body());
    }

    private static String methodBody(String name, String key) throws Exception {
        return JSON.writeValueAsString(java.util.Map.of("Name", name, "Type", "jwt", "Description", "Workloads",
                "MaxTokenTTL", "30m", "Config", java.util.Map.of("JWTValidationPubKeys", List.of(key),
                        "BoundIssuer", "https://issuer.example", "BoundAudiences", List.of("qraft"),
                        "ClaimMappings", java.util.Map.of("sub", "name"), "ExpirationLeeway", "5s")));
    }

    @Test
    void methodsAndRulesAreWrittenReadListedAndDeleted() throws Exception {
        start(false);
        String key = publicKeyPem();

        JsonNode created = ok(send("PUT", "/v1/acl/auth-method", methodBody("workloads", key)));
        assertEquals("workloads", created.path("Name").asText());
        assertEquals("30m0s", created.path("MaxTokenTTL").asText());
        assertEquals("https://issuer.example", created.path("Config").path("BoundIssuer").asText());
        assertEquals(List.of("workloads"), names(ok(send("GET", "/v1/acl/auth-methods", null)), "Name"));
        assertEquals("Workloads", ok(send("GET", "/v1/acl/auth-method/workloads", null)).path("Description").asText());

        JsonNode rule = ok(send("PUT", "/v1/acl/binding-rule", "{\"AuthMethod\":\"workloads\",\"Selector\":"
                + "\"value.name == \\\"web\\\"\",\"BindType\":\"service\",\"BindName\":\"${value.name}\"}"));
        String id = rule.path("ID").asText();
        UUID.fromString(id);
        assertEquals("service", rule.path("BindType").asText());
        assertEquals(List.of(id), names(ok(send("GET", "/v1/acl/binding-rules?authmethod=workloads", null)), "ID"));
        assertEquals("role", ok(send("PUT", "/v1/acl/binding-rule/" + id, "{\"AuthMethod\":\"workloads\","
                + "\"BindType\":\"role\",\"BindName\":\"web-role\"}")).path("BindType").asText());

        assertEquals("true", send("DELETE", "/v1/acl/auth-method/workloads", null).body());
        refused(send("GET", "/v1/acl/auth-method/workloads", null), 404, "auth_method_not_found");
        refused(send("GET", "/v1/acl/binding-rule/" + id, null), 404, "binding_rule_not_found");
    }

    @Test
    void whatCannotBeWrittenIsRefused() throws Exception {
        start(false);
        String key = publicKeyPem();
        ok(send("PUT", "/v1/acl/auth-method", methodBody("workloads", key)));

        refused(send("PUT", "/v1/acl/auth-method", methodBody("workloads", key)), 409, "auth_method_exists");
        refused(send("PUT", "/v1/acl/auth-method", methodBody("bad-key", "-----BEGIN PUBLIC KEY-----\nAAAA\n"
                + "-----END PUBLIC KEY-----")), 400, "invalid_auth_method");
        refused(send("PUT", "/v1/acl/auth-method", "{\"Name\":\"k8s\",\"Type\":\"kubernetes\",\"Config\":{}}"), 400,
                "invalid_auth_method");
        refused(send("PUT", "/v1/acl/binding-rule", "{\"AuthMethod\":\"workloads\",\"Selector\":\"value.a != \\\"b\\\"\","
                + "\"BindType\":\"service\",\"BindName\":\"x\"}"), 400, "invalid_binding_rule");
        refused(send("PUT", "/v1/acl/binding-rule", "{\"AuthMethod\":\"absent\",\"BindType\":\"service\","
                + "\"BindName\":\"x\"}"), 404, "auth_method_not_found");
        refused(send("PUT", "/v1/acl/binding-rule", "{\"AuthMethod\":\"workloads\",\"BindType\":\"group\","
                + "\"BindName\":\"x\"}"), 400, "invalid_binding_rule");
        refused(send("DELETE", "/v1/acl/auth-method/absent", null), 404, "auth_method_not_found");
    }

    @Test
    void aclsNeedAclWriteToChangeAndAclReadToRead() throws Exception {
        start(true);
        String writer = token("{\"acl\":\"write\"}");
        String reader = token("{\"acl\":\"read\"}");
        String key = publicKeyPem();

        refused(send("PUT", "/v1/acl/auth-method", methodBody("workloads", key), "X-Qraft-Token", reader), 403,
                "permission_denied");
        ok(send("PUT", "/v1/acl/auth-method", methodBody("workloads", key), "X-Qraft-Token", writer));
        assertFalse(names(ok(send("GET", "/v1/acl/auth-methods", null, "X-Qraft-Token", reader)), "Name").isEmpty());
        refused(send("GET", "/v1/acl/auth-methods", null), 403, "permission_denied");
    }

    private static List<String> names(JsonNode list, String field) {
        List<String> names = new java.util.ArrayList<>();
        list.forEach(item -> names.add(item.path(field).asText()));
        return names;
    }

    private void submit(RaftCommand command) throws Exception {
        assertEquals(RaftCommandResult.Success.class, node.submitCommand(command).get(10, TimeUnit.SECONDS).getClass());
    }

    private String token(String rules) throws Exception {
        String policy = UUID.randomUUID().toString();
        submit(AclCommand.createPolicy(new AclPolicy(policy, "p-" + policy.substring(0, 8), "",
                AclRulesJson.parse(rules))));
        String secret = UUID.randomUUID().toString();
        submit(AclCommand.createToken(new AclToken(UUID.randomUUID().toString(), AclSecrets.hash(secret), "",
                List.of(policy), List.of(), List.of(), List.of(), Instant.now(), null, false)));
        return secret;
    }
}

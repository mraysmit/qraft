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
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code POST /v1/acl/login} and {@code /v1/acl/logout} (authentication list, Phase 2, decisions 2 and 3): a valid
 * JWT is exchanged for a token bound by every matching rule, a service identity named from a claim and a policy by
 * name, which expires at the method's limit and works as any token; a JWT that matches no rule, or that is not valid, is
 * refused; and logout deletes the login token, and only a login token.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class HttpApiServerLoginTest {
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

    private void start() throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("login-node").clusterNodes(Set.of("login-node")).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null,
                HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, new AclHttpSettings(true, dataRoot.resolve("server"),
                AclDefaultPolicy.DENY));
        server.start().get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void refused(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        if (code != null) assertEquals(code, JSON.readTree(response.body()).path("code").asText(), response.body());
    }

    @Test
    void aValidJwtIsExchangedForABoundTokenThatWorksAndLogoutDeletesIt() throws Exception {
        start();
        String admin = token("{\"acl\":\"write\"}");
        submit(AclCommand.createPolicy(new AclPolicy(UUID.randomUUID().toString(), "kv-reader", "",
                AclRulesJson.parse("{\"key_prefix\":{\"\":{\"policy\":\"read\"}}}"))));
        KeyPair keys = JwtValidatorFixture.ecKeys();
        assertEquals(200, send("PUT", "/v1/acl/auth-method", JSON.writeValueAsString(Map.of("name", "workloads",
                "type", "jwt", "maxTokenTtl", "10m", "config", Map.of("jwtValidationPubKeys",
                        List.of(JwtValidatorFixture.pem(keys)), "boundIssuer", "https://issuer.example",
                        "boundAudiences", List.of("qraft"), "claimMappings", Map.of("sub", "name"),
                        "listClaimMappings", Map.of("groups", "groups")))), "X-Qraft-Token", admin).statusCode());
        assertEquals(200, send("PUT", "/v1/acl/binding-rule", "{\"authMethod\":\"workloads\",\"selector\":"
                + "\"value.name == \\\"web\\\"\",\"bindType\":\"service\",\"bindName\":\"${value.name}\"}",
                "X-Qraft-Token", admin).statusCode());
        assertEquals(200, send("PUT", "/v1/acl/binding-rule", "{\"authMethod\":\"workloads\",\"selector\":"
                + "\"\\\"dev\\\" in list.groups\",\"bindType\":\"policy\",\"bindName\":\"kv-reader\"}",
                "X-Qraft-Token", admin).statusCode());

        String bearer = JwtValidatorFixture.jwt(keys, Map.of("iss", "https://issuer.example", "aud", "qraft",
                "sub", "web", "groups", List.of("dev"), "exp", Instant.now().plusSeconds(300).getEpochSecond()));
        HttpResponse<String> login = send("POST", "/v1/acl/login", JSON.writeValueAsString(Map.of(
                "authMethod", "workloads", "bearerToken", bearer)));
        assertEquals(200, login.statusCode(), login.body());
        JsonNode token = JSON.readTree(login.body());
        assertEquals("web", token.path("serviceIdentities").get(0).path("serviceName").asText());
        assertEquals("kv-reader", token.path("policies").get(0).path("name").asText());
        assertEquals("workloads", token.path("authMethod").asText());
        Instant expires = Instant.parse(token.path("expirationTime").asText());
        assertTrue(expires.isBefore(Instant.now().plusSeconds(601)) && expires.isAfter(Instant.now().plusSeconds(540)),
                "the method's MaxTokenTTL: " + expires);
        String secret = token.path("secretId").asText();
        refused(send("GET", "/v1/kv/absent", null, "X-Qraft-Token", secret), 404, "key_not_found");

        assertEquals("true", send("POST", "/v1/acl/logout", null, "X-Qraft-Token", secret).body());
        refused(send("GET", "/v1/kv/absent", null, "X-Qraft-Token", secret), 403, "token_not_found");
        refused(send("POST", "/v1/acl/logout", null, "X-Qraft-Token", admin), 403, "permission_denied");

        String noRule = JwtValidatorFixture.jwt(keys, Map.of("iss", "https://issuer.example", "aud", "qraft",
                "sub", "db", "exp", Instant.now().plusSeconds(300).getEpochSecond()));
        refused(send("POST", "/v1/acl/login", JSON.writeValueAsString(Map.of("authMethod", "workloads",
                "bearerToken", noRule))), 403, "permission_denied");
        refused(send("POST", "/v1/acl/login", JSON.writeValueAsString(Map.of("authMethod", "workloads",
                "bearerToken", "not.a.jwt"))), 403, "permission_denied");
        refused(send("POST", "/v1/acl/login", JSON.writeValueAsString(Map.of("authMethod", "absent",
                "bearerToken", bearer))), 404, "auth_method_not_found");
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

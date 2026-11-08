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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the tenancy API on the server (tenancy list, Phase 3), on Consul's partition and namespace APIs:
 * {@code /v1/tenant} and {@code /v1/tenants}, {@code /v1/namespace} and {@code /v1/namespaces} create, read, update,
 * list and delete, with Consul's refusals; deleting a namespace through the API removes its keys; and with ACLs a change
 * needs {@code operator:write} and a read {@code operator:read}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class HttpApiServerTenancyApiTest {
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
                .nodeId("tenancy-node").clusterNodes(Set.of("tenancy-node")).transport(new PeerlessTransportFixture())
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

    private static List<String> names(JsonNode list) {
        List<String> names = new ArrayList<>();
        list.forEach(item -> names.add(item.path("Name").asText()));
        return names;
    }

    @Test
    void tenantsAreCreatedReadUpdatedListedAndDeleted() throws Exception {
        start(false);

        JsonNode created = ok(send("PUT", "/v1/tenant", "{\"Name\":\"acme\",\"Description\":\"Acme Ltd\"}"));
        assertEquals("acme", created.path("Name").asText());
        assertEquals("Acme Ltd", created.path("Description").asText());
        assertTrue(created.path("CreateIndex").asLong() > 0);
        assertEquals(List.of("acme", "default"), names(ok(send("GET", "/v1/tenants", null))));
        assertEquals("Acme Ltd", ok(send("GET", "/v1/tenant/acme", null)).path("Description").asText());

        JsonNode updated = ok(send("PUT", "/v1/tenant/acme", "{\"Description\":\"Acme\"}"));
        assertEquals("Acme", updated.path("Description").asText());
        assertTrue(updated.path("ModifyIndex").asLong() > created.path("ModifyIndex").asLong());

        assertEquals("true", send("DELETE", "/v1/tenant/acme", null).body());
        refused(send("GET", "/v1/tenant/acme", null), 404, "tenant_not_found");
        assertEquals(List.of("default"), names(ok(send("GET", "/v1/tenants", null))));
    }

    @Test
    void namespacesLiveInTheTenantTheBodyOrHeaderNames() throws Exception {
        start(false);
        ok(send("PUT", "/v1/tenant", "{\"Name\":\"acme\"}"));

        JsonNode prod = ok(send("PUT", "/v1/namespace", "{\"Name\":\"prod\",\"Tenant\":\"acme\",\"Description\":\"P\"}"));
        assertEquals("acme", prod.path("Tenant").asText());
        ok(send("PUT", "/v1/namespace", "{\"Name\":\"staging\"}", "X-Qraft-Tenant", "acme"));
        ok(send("PUT", "/v1/namespace", "{\"Name\":\"team-a\"}"));
        assertEquals(List.of("default", "prod", "staging"),
                names(ok(send("GET", "/v1/namespaces", null, "X-Qraft-Tenant", "acme"))));
        assertEquals(List.of("default", "team-a"), names(ok(send("GET", "/v1/namespaces", null))),
                "without a tenant, the default tenant's");
        assertEquals("P", ok(send("GET", "/v1/namespace/prod", null, "X-Qraft-Tenant", "acme"))
                .path("Description").asText());
        assertEquals("Q", ok(send("PUT", "/v1/namespace/prod", "{\"Description\":\"Q\"}", "X-Qraft-Tenant", "acme"))
                .path("Description").asText());

        assertEquals("true", send("PUT", "/v1/kv/config", "v", "X-Qraft-Tenant", "acme", "X-Qraft-Namespace",
                "prod").body());
        assertEquals("true", send("DELETE", "/v1/namespace/prod", null, "X-Qraft-Tenant", "acme").body());
        refused(send("GET", "/v1/namespace/prod", null, "X-Qraft-Tenant", "acme"), 404, "namespace_not_found");
        refused(send("GET", "/v1/kv/config", null, "X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "prod"), 404,
                "key_not_found");
    }

    @Test
    void whatCannotBeDoneIsRefusedAsConsulRefusesIt() throws Exception {
        start(false);
        ok(send("PUT", "/v1/tenant", "{\"Name\":\"acme\"}"));

        refused(send("PUT", "/v1/tenant", "{\"Name\":\"acme\"}"), 409, "tenant_exists");
        refused(send("PUT", "/v1/tenant", "{\"Name\":\"Acme Ltd\"}"), 400, "invalid_name");
        refused(send("PUT", "/v1/tenant", "{}"), 400, "invalid_name");
        refused(send("PUT", "/v1/tenant", "[1]"), 400, null);
        refused(send("DELETE", "/v1/tenant/default", null), 400, "tenant_reserved");
        refused(send("DELETE", "/v1/tenant/absent", null), 404, "tenant_not_found");
        refused(send("PUT", "/v1/tenant/absent", "{\"Description\":\"x\"}"), 404, "tenant_not_found");
        refused(send("PUT", "/v1/namespace", "{\"Name\":\"prod\",\"Tenant\":\"absent\"}"), 404, "tenant_not_found");
        refused(send("PUT", "/v1/namespace", "{\"Name\":\"default\",\"Tenant\":\"acme\"}"), 409, "namespace_exists");
        refused(send("DELETE", "/v1/namespace/default", null, "X-Qraft-Tenant", "acme"), 400, "namespace_reserved");
        refused(send("GET", "/v1/namespaces", null, "X-Qraft-Tenant", "absent"), 404, "tenant_not_found");
        refused(send("POST", "/v1/tenant", "{\"Name\":\"x\"}"), 405, "method_not_allowed");
        refused(send("GET", "/v1/tenant", null), 405, "method_not_allowed");
        refused(send("GET", "/v1/tenant/a/b", null), 404, "not_found");
        refused(send("GET", "/v1/tenants?filter=x", null), 400, "invalid_query");
    }

    @Test
    void aclsNeedOperatorWriteToChangeAndOperatorReadToRead() throws Exception {
        start(true);
        String writer = token("{\"operator\":\"write\"}");
        String reader = token("{\"operator\":\"read\"}");

        refused(send("PUT", "/v1/tenant", "{\"Name\":\"acme\"}", "X-Qraft-Token", reader), 403, "permission_denied");
        ok(send("PUT", "/v1/tenant", "{\"Name\":\"acme\"}", "X-Qraft-Token", writer));
        refused(send("PUT", "/v1/namespace", "{\"Name\":\"prod\",\"Tenant\":\"acme\"}", "X-Qraft-Token", reader), 403,
                "permission_denied");
        ok(send("PUT", "/v1/namespace", "{\"Name\":\"prod\",\"Tenant\":\"acme\"}", "X-Qraft-Token", writer));
        assertEquals(List.of("acme", "default"), names(ok(send("GET", "/v1/tenants", null, "X-Qraft-Token", reader))));
        refused(send("GET", "/v1/tenants", null), 403, "permission_denied");
        refused(send("DELETE", "/v1/tenant/acme", null, "X-Qraft-Token", reader), 403, "permission_denied");
    }

    private void submit(RaftCommand command) throws Exception {
        assertInstanceOf(RaftCommandResult.Success.class, node.submitCommand(command).get(10, TimeUnit.SECONDS));
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

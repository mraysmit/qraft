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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code /v1/kv} on the server (key/value list, Phase 3), on Consul's KV API: set, read, raw, recursive and key
 * listings with a separator, flags, compare-and-set, deletes, the index header, the scope headers, refused queries,
 * a follower's answer to a write, and the ACL checks: read, write, a filtered recursive read, and write on the whole
 * prefix for a recursive delete.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class HttpApiServerKvTest {
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

    private void start(boolean acl, Set<String> members) throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("kv-node").clusterNodes(members).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        if (members.size() == 1) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        }
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null,
                HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, acl
                ? new AclHttpSettings(true, dataRoot.resolve("server"), AclDefaultPolicy.DENY)
                : AclHttpSettings.disabled());
        server.start().get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<byte[]> send(String method, String pathAndQuery, byte[] body, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                + pathAndQuery)).method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private JsonNode get(String pathAndQuery, String... headers) throws Exception {
        HttpResponse<byte[]> answer = send("GET", pathAndQuery, null, headers);
        assertEquals(200, answer.statusCode(), text(answer));
        return JSON.readTree(answer.body());
    }

    @Test
    void aValueIsSetAndReadBackWithItsFlagsAndIndexes() throws Exception {
        start(false, Set.of("kv-node"));

        HttpResponse<byte[]> set = send("PUT", "/v1/kv/app/config?flags=7", bytes("hello"));
        assertEquals(200, set.statusCode(), text(set));
        assertEquals("true", text(set));

        HttpResponse<byte[]> read = send("GET", "/v1/kv/app/config", null);
        assertEquals(200, read.statusCode(), text(read));
        JsonNode entry = JSON.readTree(read.body()).get(0);
        assertEquals("app/config", entry.path("Key").asText());
        assertArrayEquals(bytes("hello"), Base64.getDecoder().decode(entry.path("Value").asText()));
        assertEquals(7, entry.path("Flags").asLong());
        assertEquals(0, entry.path("LockIndex").asLong());
        assertTrue(entry.path("CreateIndex").asLong() > 0);
        assertEquals(entry.path("CreateIndex").asLong(), entry.path("ModifyIndex").asLong());
        assertEquals(entry.path("ModifyIndex").asText(), read.headers().firstValue("X-Qraft-Index").orElseThrow());

        HttpResponse<byte[]> raw = send("GET", "/v1/kv/app/config?raw", null);
        assertEquals(200, raw.statusCode());
        assertArrayEquals(bytes("hello"), raw.body());
        assertEquals(404, send("GET", "/v1/kv/app/absent", null).statusCode());
    }

    @Test
    void aPrefixIsListedInKeyOrderAndItsKeysStopAtTheSeparator() throws Exception {
        start(false, Set.of("kv-node"));
        for (String key : List.of("web/name", "web/config/b", "web/config/a", "worker/x"))
            send("PUT", "/v1/kv/" + key, bytes(key));

        List<String> recursed = new ArrayList<>();
        get("/v1/kv/web/?recurse").forEach(entry -> recursed.add(entry.path("Key").asText()));
        assertEquals(List.of("web/config/a", "web/config/b", "web/name"), recursed);

        assertEquals(JSON.readTree("[\"web/\",\"worker/\"]"), get("/v1/kv/?keys&separator=/"));
        assertEquals(JSON.readTree("[\"web/config/a\",\"web/config/b\",\"web/name\"]"), get("/v1/kv/web/?keys"));
        assertEquals(404, send("GET", "/v1/kv/none/?recurse", null).statusCode(), "an empty listing is not found");
    }

    @Test
    void compareAndSetAndDeletesAnswerTrueOrFalse() throws Exception {
        start(false, Set.of("kv-node"));
        assertEquals("true", text(send("PUT", "/v1/kv/lock?cas=0", bytes("a"))));
        assertEquals("false", text(send("PUT", "/v1/kv/lock?cas=0", bytes("b"))), "cas 0 needs an absent key");
        long index = get("/v1/kv/lock").get(0).path("ModifyIndex").asLong();
        assertEquals("false", text(send("PUT", "/v1/kv/lock?cas=" + (index - 1), bytes("b"))));
        assertEquals("true", text(send("PUT", "/v1/kv/lock?cas=" + index, bytes("b"))));

        long current = get("/v1/kv/lock").get(0).path("ModifyIndex").asLong();
        assertEquals("false", text(send("DELETE", "/v1/kv/lock?cas=" + (current - 1), null)));
        assertEquals("true", text(send("DELETE", "/v1/kv/lock?cas=" + current, null)));
        assertEquals(404, send("GET", "/v1/kv/lock", null).statusCode());

        send("PUT", "/v1/kv/tree/a", bytes("1"));
        send("PUT", "/v1/kv/tree/b/c", bytes("2"));
        send("PUT", "/v1/kv/treetop", bytes("3"));
        assertEquals("true", text(send("DELETE", "/v1/kv/tree/?recurse", null)));
        assertEquals(JSON.readTree("[\"treetop\"]"), get("/v1/kv/?keys"));
    }

    @Test
    void eachTenantAndNamespaceHoldsItsOwnKeys() throws Exception {
        start(false, Set.of("kv-node"));
        send("PUT", "/v1/kv/shared", bytes("default"));
        node.submitCommand(dev.mars.qraft.state.tenancy.TenancyCommand.createTenant("acme", "")).get(10, TimeUnit.SECONDS);
        node.submitCommand(dev.mars.qraft.state.tenancy.TenancyCommand.createNamespace("acme", "prod", "")).get(10, TimeUnit.SECONDS);
        send("PUT", "/v1/kv/shared", bytes("acme"), "X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "prod");

        assertArrayEquals(bytes("default"), send("GET", "/v1/kv/shared?raw", null).body());
        assertArrayEquals(bytes("acme"), send("GET", "/v1/kv/shared?raw", null,
                "X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "prod").body());
        assertEquals(404, send("GET", "/v1/kv/shared", null, "X-Qraft-Tenant", "acme").statusCode(),
                "the acme tenant's default namespace holds none");
    }

    @Test
    void whatItDoesNotTakeIsRefused() throws Exception {
        start(false, Set.of("kv-node"));

        assertRefused(send("GET", "/v1/kv/a?acquire=s", null), 400, "invalid_query");
        assertRefused(send("PUT", "/v1/kv/a?acquire=s&release=s", bytes("x")), 400, "invalid_query");
        assertRefused(send("PUT", "/v1/kv/a?acquire=s&cas=0", bytes("x")), 400, "invalid_query");
        assertRefused(send("PUT", "/v1/kv/a?acquire=s", bytes("x")), 400, "invalid_session");
        assertRefused(send("GET", "/v1/kv/a?cached", null), 400, "invalid_query");
        assertRefused(send("GET", "/v1/kv/a?raw&recurse", null), 400, "invalid_query");
        assertRefused(send("PUT", "/v1/kv/a?flags=x", bytes("x")), 400, "invalid_query");
        assertRefused(send("PUT", "/v1/kv/a?cas=-1", bytes("x")), 400, "invalid_query");
        assertRefused(send("PUT", "/v1/kv/", bytes("x")), 400, "key_required");
        assertRefused(send("DELETE", "/v1/kv/", null), 400, "key_required");
        assertRefused(send("POST", "/v1/kv/a", bytes("x")), 405, "method_not_allowed");
    }

    @Test
    void aFollowerAnswersAWriteAsTheCatalogDoes() throws Exception {
        start(false, Set.of("kv-node", "absent-peer"));

        HttpResponse<byte[]> refused = send("PUT", "/v1/kv/a", bytes("x"));
        assertEquals(503, refused.statusCode(), text(refused));
        assertEquals("leader_unavailable", JSON.readTree(refused.body()).path("code").asText());
        assertTrue(JSON.readTree(refused.body()).path("retryable").asBoolean());
    }

    @Test
    void aclsDecideReadsWritesAndRecursiveDeletes() throws Exception {
        start(true, Set.of("kv-node"));
        String admin = token("{\"key_prefix\":{\"\":{\"policy\":\"write\"}}}");
        String reader = token("{\"key_prefix\":{\"app/\":{\"policy\":\"read\"}},\"key\":{\"app/secret\":{\"policy\":\"deny\"}}}");
        String writer = token("{\"key_prefix\":{\"app/\":{\"policy\":\"write\"}},\"key\":{\"app/secret\":{\"policy\":\"deny\"}}}");
        for (String key : List.of("app/a", "app/b", "app/secret", "other"))
            assertEquals(200, send("PUT", "/v1/kv/" + key, bytes(key), "X-Qraft-Token", admin).statusCode());

        assertEquals(200, send("GET", "/v1/kv/app/a", null, "X-Qraft-Token", reader).statusCode());
        assertRefused(send("GET", "/v1/kv/app/secret", null, "X-Qraft-Token", reader), 403, "permission_denied");
        assertRefused(send("PUT", "/v1/kv/app/a", bytes("x"), "X-Qraft-Token", reader), 403, "permission_denied");
        assertRefused(send("GET", "/v1/kv/other", null), 403, "permission_denied");

        HttpResponse<byte[]> recursed = send("GET", "/v1/kv/app/?recurse", null, "X-Qraft-Token", reader);
        List<String> keys = new ArrayList<>();
        JSON.readTree(recursed.body()).forEach(entry -> keys.add(entry.path("Key").asText()));
        assertEquals(List.of("app/a", "app/b"), keys, "what the token cannot read is left out");
        assertEquals("true", recursed.headers().firstValue(HttpApiServer.RESULTS_FILTERED_HEADER).orElseThrow());

        assertRefused(send("DELETE", "/v1/kv/app/?recurse", null, "X-Qraft-Token", writer), 403, "permission_denied");
        assertEquals("true", text(send("DELETE", "/v1/kv/app/a", null, "X-Qraft-Token", writer)));
        assertEquals("true", text(send("DELETE", "/v1/kv/?recurse", null, "X-Qraft-Token", admin)));
        assertRefused(send("GET", "/v1/kv/?keys", null, "X-Qraft-Token", admin), 404, null);
    }

    private static void assertRefused(HttpResponse<byte[]> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), text(response));
        if (code != null) assertEquals(code, JSON.readTree(response.body()).path("code").asText(), text(response));
    }

    private String token(String rules) throws Exception {
        String policy = UUID.randomUUID().toString();
        assertInstanceOf(RaftCommandResult.Success.class, node.submitCommand(AclCommand.createPolicy(new AclPolicy(
                policy, "p-" + policy.substring(0, 8), "", AclRulesJson.parse(rules)))).get(10, TimeUnit.SECONDS));
        String secret = UUID.randomUUID().toString();
        assertInstanceOf(RaftCommandResult.Success.class, node.submitCommand(AclCommand.createToken(new AclToken(
                UUID.randomUUID().toString(), AclSecrets.hash(secret), "", List.of(policy), List.of(), List.of(),
                List.of(), Instant.now(), null, false))).get(10, TimeUnit.SECONDS));
        return secret;
    }
}

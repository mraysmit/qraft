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
import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.acl.AclRulesJson;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.health.ExpiryScheduler;
import dev.mars.qraft.server.session.SessionTtlExpiry;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.AclCommand;
import dev.mars.qraft.state.NodeCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.acl.AclPolicy;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;
import dev.mars.qraft.state.kv.KvScope;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code /v1/session} and {@code ?acquire} and {@code ?release} on {@code /v1/kv} on the server (sessions list,
 * Phase 3), on Consul's session API: creation with its defaults and refusals, the session in Consul's form, listings by
 * node and in all, renewal on the leader alone, destruction, a lock that only its session releases, the lock delay
 * after an invalidation, and the ACL checks: {@code session:write} on the node to create, renew or destroy, a filtered
 * {@code session:read}, and {@code key:write} to acquire.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class HttpApiServerSessionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dataRoot;

    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private QraftStateStore store;
    private HttpApiServer server;
    private SessionTtlExpiry ttl;
    private final ScheduledExecutorService ttlExecutor = Executors.newSingleThreadScheduledExecutor();
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() throws Exception {
        try {
            if (ttl != null) ttl.close();
            if (server != null) server.close();
            if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        } finally {
            ttlExecutor.shutdownNow();
            if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
            http.close();
        }
    }

    private void start(boolean acl, Set<String> members) throws Exception {
        runtime = RaftExecutorsFixture.create();
        store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("session-node").clusterNodes(members).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        if (members.size() == 1) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
            for (String name : List.of("host-a", "host-b")) {
                submit(NodeCommand.register(Node.of(name, "10.0.0.1", null, null, Map.of()), Instant.now()));
            }
        }
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null,
                HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, acl
                ? new AclHttpSettings(true, dataRoot.resolve("server"), AclDefaultPolicy.DENY)
                : AclHttpSettings.disabled());
        ttl = SessionTtlExpiry.attach(node, store.getSessions(), node::submitCommand,
                ExpiryScheduler.of(ttlExecutor), Clock.systemUTC(), Duration.ofSeconds(1));
        server.useSessionTtl(ttl);
        server.start().get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<byte[]> send(String method, String pathAndQuery, String body, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                + pathAndQuery)).method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private JsonNode ok(HttpResponse<byte[]> response) throws Exception {
        assertEquals(200, response.statusCode(), text(response));
        return JSON.readTree(response.body());
    }

    private String create(String body, String... headers) throws Exception {
        return ok(send("PUT", "/v1/session/create", body, headers)).path("ID").asText();
    }

    @Test
    void aSessionIsCreatedWithConsulsDefaultsAndReadBackInConsulsForm() throws Exception {
        start(false, Set.of("session-node"));

        String id = create("{\"Node\":\"host-a\",\"Name\":\"leader\",\"TTL\":\"15s\",\"Behavior\":\"delete\","
                + "\"LockDelay\":\"2s\"}");
        String plain = create("{\"Node\":\"host-b\"}");
        UUID.fromString(id);

        HttpResponse<byte[]> info = send("GET", "/v1/session/info/" + id, null);
        JsonNode session = ok(info).get(0);
        assertEquals(id, session.path("ID").asText());
        assertEquals("leader", session.path("Name").asText());
        assertEquals("host-a", session.path("Node").asText());
        assertEquals("delete", session.path("Behavior").asText());
        assertEquals("15s", session.path("TTL").asText());
        assertEquals(Duration.ofSeconds(2).toNanos(), session.path("LockDelay").asLong());
        assertTrue(session.path("CreateIndex").asLong() > 0);
        assertEquals(session.path("CreateIndex").asLong(), session.path("ModifyIndex").asLong());
        assertTrue(info.headers().firstValue("X-Qraft-Index").isPresent());

        JsonNode defaults = ok(send("GET", "/v1/session/info/" + plain, null)).get(0);
        assertEquals("release", defaults.path("Behavior").asText(), "Consul's default behaviour");
        assertEquals("", defaults.path("TTL").asText(), "no TTL");
        assertEquals(Duration.ofSeconds(15).toNanos(), defaults.path("LockDelay").asLong(), "Consul's default delay");
        assertEquals(0, ok(send("GET", "/v1/session/info/" + UUID.randomUUID(), null)).size(),
                "an unknown session answers an empty list, as Consul's does");

        assertEquals(List.of(id), ids(ok(send("GET", "/v1/session/node/host-a", null))));
        assertEquals(List.of(id, plain).stream().sorted().toList(), ids(ok(send("GET", "/v1/session/list", null))));

        assertEquals("true", text(send("PUT", "/v1/session/destroy/" + id, null)));
        assertEquals(List.of(plain), ids(ok(send("GET", "/v1/session/list", null))));
    }

    @Test
    void whatASessionCannotBeIsRefused() throws Exception {
        start(false, Set.of("session-node"));

        assertRefused(send("PUT", "/v1/session/create", "{}"), 400, "node_required");
        assertRefused(send("PUT", "/v1/session/create", "{\"Node\":\"absent\"}"), 400, "node_not_found");
        assertRefused(send("PUT", "/v1/session/create", "{\"Node\":\"host-a\",\"TTL\":\"5s\"}"), 400, "invalid_session");
        assertRefused(send("PUT", "/v1/session/create", "{\"Node\":\"host-a\",\"TTL\":\"25h\"}"), 400, "invalid_session");
        assertRefused(send("PUT", "/v1/session/create", "{\"Node\":\"host-a\",\"Behavior\":\"keep\"}"), 400,
                "invalid_session");
        assertRefused(send("PUT", "/v1/session/create", "{\"Node\":\"host-a\",\"NodeChecks\":[\"serfHealth\"]}"), 400,
                "invalid_session");
        assertRefused(send("PUT", "/v1/session/create", "not json"), 400, null);
        assertRefused(send("GET", "/v1/session/create", null), 405, "method_not_allowed");
        assertRefused(send("PUT", "/v1/session/list", null), 405, "method_not_allowed");
        assertRefused(send("GET", "/v1/session/elsewhere", null), 404, "not_found");
        assertRefused(send("PUT", "/v1/session/renew/" + UUID.randomUUID(), null), 404, "session_not_found");
    }

    @Test
    void aLockIsHeldByItsSessionAloneAndAfterAnInvalidationWaitsOutTheLockDelay() throws Exception {
        start(false, Set.of("session-node"));
        String holder = create("{\"Node\":\"host-a\",\"LockDelay\":\"1m\"}");
        String other = create("{\"Node\":\"host-b\",\"LockDelay\":\"0s\"}");

        assertEquals("true", text(send("PUT", "/v1/kv/locks/leader?acquire=" + holder, "me")));
        assertEquals("true", text(send("PUT", "/v1/kv/locks/leader?acquire=" + holder, "me again")),
                "the holder acquires again");
        assertEquals("false", text(send("PUT", "/v1/kv/locks/leader?acquire=" + other, "them")));
        assertEquals("false", text(send("PUT", "/v1/kv/locks/leader?release=" + other, "")));
        JsonNode held = ok(send("GET", "/v1/kv/locks/leader", null)).get(0);
        assertEquals(1, held.path("LockIndex").asLong());
        assertEquals(holder, held.path("Session").asText());

        assertEquals("true", text(send("PUT", "/v1/session/destroy/" + holder, null)));
        JsonNode released = ok(send("GET", "/v1/kv/locks/leader", null)).get(0);
        assertFalse(released.has("Session"), "the release behaviour freed the key");
        assertEquals(1, released.path("LockIndex").asLong());
        assertEquals("false", text(send("PUT", "/v1/kv/locks/leader?acquire=" + other, "them")),
                "the destroyed session's lock delay has not passed");
        assertTrue(store.getKv().get(KvScope.DEFAULT, "locks/leader").orElseThrow().session() == null);

        assertEquals("true", text(send("PUT", "/v1/kv/locks/free?acquire=" + other, "them")));
        assertEquals("true", text(send("PUT", "/v1/kv/locks/free?release=" + other, "done")));
        assertFalse(ok(send("GET", "/v1/kv/locks/free", null)).get(0).has("Session"));
    }

    @Test
    void theLeaderRenews() throws Exception {
        start(false, Set.of("session-node"));
        String id = create("{\"Node\":\"host-a\",\"TTL\":\"10s\"}");

        JsonNode renewed = ok(send("PUT", "/v1/session/renew/" + id, null));
        assertEquals(id, renewed.get(0).path("ID").asText());
        assertEquals("10s", renewed.get(0).path("TTL").asText());
    }

    @Test
    void aFollowerAnswersThatItCannotRenewSoTheClientTriesItsNextServer() throws Exception {
        start(false, Set.of("session-node", "absent-peer"));

        HttpResponse<byte[]> refused = send("PUT", "/v1/session/renew/" + UUID.randomUUID(), null);
        assertRefused(refused, 503, "leader_unavailable");
        assertTrue(JSON.readTree(refused.body()).path("retryable").asBoolean());
        assertRefused(send("PUT", "/v1/session/create", "{\"Node\":\"host-a\"}"), 503, "leader_unavailable");
    }

    @Test
    void aclsDecideOnTheSessionsNode() throws Exception {
        start(true, Set.of("session-node"));
        String admin = token("{\"session_prefix\":{\"\":{\"policy\":\"write\"}},\"key_prefix\":{\"\":{\"policy\":\"write\"}}}");
        String hostA = token("{\"session\":{\"host-a\":{\"policy\":\"write\"}},\"key_prefix\":{\"locks/\":{\"policy\":\"write\"}}}");
        String readerB = token("{\"session_prefix\":{\"host-b\":{\"policy\":\"read\"}}}");
        String b = create("{\"Node\":\"host-b\"}", "X-Qraft-Token", admin);

        String a = create("{\"Node\":\"host-a\"}", "X-Qraft-Token", hostA);
        assertRefused(send("PUT", "/v1/session/create", "{\"Node\":\"host-b\"}", "X-Qraft-Token", hostA), 403,
                "permission_denied");
        assertRefused(send("PUT", "/v1/session/destroy/" + b, null, "X-Qraft-Token", hostA), 403, "permission_denied");
        assertRefused(send("PUT", "/v1/session/renew/" + b, null, "X-Qraft-Token", hostA), 403, "permission_denied");
        assertEquals(200, send("PUT", "/v1/session/renew/" + a, null, "X-Qraft-Token", hostA).statusCode());

        HttpResponse<byte[]> listed = send("GET", "/v1/session/list", null, "X-Qraft-Token", readerB);
        assertEquals(List.of(b), ids(ok(listed)), "what the token cannot read is left out");
        assertEquals("true", listed.headers().firstValue(HttpApiServer.RESULTS_FILTERED_HEADER).orElseThrow());
        assertEquals(0, ok(send("GET", "/v1/session/info/" + a, null, "X-Qraft-Token", readerB)).size());

        assertEquals("true", text(send("PUT", "/v1/kv/locks/x?acquire=" + a, "", "X-Qraft-Token", hostA)));
        assertRefused(send("PUT", "/v1/kv/other?acquire=" + a, "", "X-Qraft-Token", hostA), 403, "permission_denied");
    }

    private static List<String> ids(JsonNode sessions) {
        List<String> ids = new ArrayList<>();
        sessions.forEach(session -> ids.add(session.path("ID").asText()));
        return ids;
    }

    private static void assertRefused(HttpResponse<byte[]> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), text(response));
        if (code != null) assertEquals(code, JSON.readTree(response.body()).path("code").asText(), text(response));
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

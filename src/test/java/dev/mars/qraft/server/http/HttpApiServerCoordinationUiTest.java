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
import dev.mars.qraft.state.tenancy.TenancyCommand;
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
 * Tests the administrative interface's coordination reads (UI-6 Sessions, Locks and Watches list, Phase 0):
 * {@code GET /v1/internal/ui/locks}, every held key across scopes and the lock delays still running, narrowed to one
 * session and filtered by {@code key:read} in each key's scope (decision 4); and {@code GET /v1/internal/ui/sessions},
 * the sessions in Consul's form with the leader's timers, which a follower says it does not know (decision 2).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-17
 * @version 1.0
 */
class HttpApiServerCoordinationUiTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] ACME = {"X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "prod"};

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
                .nodeId("ui-node").clusterNodes(members).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        if (members.size() == 1) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
            for (String name : List.of("host-a", "host-b")) {
                submit(NodeCommand.register(Node.of(name, "10.0.0.1", null, null, Map.of()), Instant.now()));
            }
            submit(TenancyCommand.createTenant("acme", ""));
            submit(TenancyCommand.createNamespace("acme", "prod", ""));
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

    @Test
    void theLocksReadAnswersTheHeldKeysAndTheRunningLockDelaysAcrossScopes() throws Exception {
        start(false, Set.of("ui-node"));
        String first = create("{\"Node\":\"host-a\",\"LockDelay\":\"10s\"}");
        String second = create("{\"Node\":\"host-b\"}");
        assertEquals("true", text(send("PUT", "/v1/kv/app/leader?acquire=" + first, "a")));
        assertEquals("true", text(send("PUT", "/v1/kv/app/leader?acquire=" + second, "b", ACME)));
        assertEquals("true", text(send("PUT", "/v1/kv/other?acquire=" + first, "c", ACME)));
        assertEquals("true", text(send("PUT", "/v1/kv/free", "f")));

        HttpResponse<byte[]> read = send("GET", "/v1/internal/ui/locks", null);
        JsonNode locks = ok(read);
        assertEquals(List.of("acme/prod/app/leader:" + second, "acme/prod/other:" + first,
                "default/default/app/leader:" + first), held(locks), "every scope's, in key order, and no free key");
        JsonNode one = locks.path("Held").get(2);
        assertEquals(1, one.path("LockIndex").asLong());
        assertTrue(one.path("ModifyIndex").asLong() > 0);
        assertFalse(one.has("Value"), "a value is never answered here");
        assertEquals(0, locks.path("Delays").size());
        assertEquals(Long.toString(store.getKv().lastIndex()), read.headers().firstValue("X-Qraft-Index").orElseThrow());

        assertEquals(List.of("acme/prod/other:" + first, "default/default/app/leader:" + first),
                held(ok(send("GET", "/v1/internal/ui/locks?session=" + first, null))), "one session's");

        Instant destroyed = Instant.now();
        assertEquals("true", text(send("PUT", "/v1/session/destroy/" + first, null)));
        JsonNode after = ok(send("GET", "/v1/internal/ui/locks?consistent", null));
        assertEquals(List.of("acme/prod/app/leader:" + second), held(after));
        List<String> delayed = new ArrayList<>();
        after.path("Delays").forEach(delay -> {
            delayed.add(delay.path("Tenant").asText() + "/" + delay.path("Namespace").asText() + "/"
                    + delay.path("Key").asText());
            Instant until = Instant.parse(delay.path("Until").asText());
            assertFalse(until.isBefore(destroyed.plusSeconds(9)), "the session's ten-second lock delay: " + until);
        });
        assertEquals(List.of("acme/prod/other", "default/default/app/leader"), delayed);

        assertRefused(send("GET", "/v1/internal/ui/locks?recurse", null), 400, "invalid_query");
        assertRefused(send("GET", "/v1/internal/ui/locks?session=", null), 400, "invalid_query");
        assertRefused(send("PUT", "/v1/internal/ui/locks", null), 405, "method_not_allowed");
    }

    @Test
    void theLocksReadLeavesOutTheKeysATokenCannotReadInTheirScope() throws Exception {
        start(true, Set.of("ui-node"));
        String admin = token("{\"session_prefix\":{\"\":{\"policy\":\"write\"}},\"key_prefix\":{\"\":{\"policy\":\"write\"}},"
                + "\"tenant\":{\"acme\":{\"namespace\":{\"prod\":{\"key_prefix\":{\"\":{\"policy\":\"write\"}}}}}}}");
        String reader = token("{\"key_prefix\":{\"app/\":{\"policy\":\"read\"}}}");
        String session = create("{\"Node\":\"host-a\"}", "X-Qraft-Token", admin);
        assertEquals("true", text(send("PUT", "/v1/kv/app/leader?acquire=" + session, "a", "X-Qraft-Token", admin)));
        assertEquals("true", text(send("PUT", "/v1/kv/other?acquire=" + session, "b", "X-Qraft-Token", admin)));
        assertEquals("true", text(send("PUT", "/v1/kv/app/leader?acquire=" + session, "c", "X-Qraft-Token", admin,
                ACME[0], ACME[1], ACME[2], ACME[3])));

        assertEquals(3, held(ok(send("GET", "/v1/internal/ui/locks", null, "X-Qraft-Token", admin))).size());
        HttpResponse<byte[]> filtered = send("GET", "/v1/internal/ui/locks", null, "X-Qraft-Token", reader);
        assertEquals(List.of("default/default/app/leader:" + session), held(ok(filtered)),
                "a top-level key rule is the default scope's, and another key is left out");
        assertEquals("true", filtered.headers().firstValue(HttpApiServer.RESULTS_FILTERED_HEADER).orElseThrow());
        assertFalse(send("GET", "/v1/internal/ui/locks", null, "X-Qraft-Token", admin).headers()
                .firstValue(HttpApiServer.RESULTS_FILTERED_HEADER).isPresent());
        assertRefused(send("GET", "/v1/internal/ui/locks", null, "X-Qraft-Token", "not-a-token"), 403,
                "token_not_found");
    }

    @Test
    void theSessionsReadAnswersTheLeadersTimersInConsulsForm() throws Exception {
        start(false, Set.of("ui-node"));
        Instant before = Instant.now();
        String timed = create("{\"Node\":\"host-a\",\"Name\":\"leader\",\"TTL\":\"15s\"}");
        Instant after = Instant.now();
        String untimed = create("{\"Node\":\"host-b\"}");

        HttpResponse<byte[]> read = send("GET", "/v1/internal/ui/sessions", null);
        JsonNode answer = ok(read);
        assertTrue(answer.path("TtlKnown").asBoolean(), "the leader keeps the timers");
        assertTrue(read.headers().firstValue("X-Qraft-Index").isPresent());
        JsonNode first = session(answer, timed);
        assertEquals("leader", first.path("Name").asText());
        assertEquals("host-a", first.path("Node").asText());
        assertEquals("15s", first.path("TTL").asText());
        assertTrue(first.path("RenewedAt").isNull(), "not renewed");
        Instant expires = Instant.parse(first.path("ExpiresAt").asText());
        assertFalse(expires.isBefore(before.plusSeconds(30).minusMillis(1)), "twice the TTL from its creation");
        assertFalse(expires.isAfter(after.plusSeconds(30).plusMillis(1)), "twice the TTL from its creation");
        assertTrue(session(answer, untimed).path("ExpiresAt").isNull(), "a session without a TTL never lapses");
        assertTrue(session(answer, untimed).path("RenewedAt").isNull());

        Instant renewing = Instant.now();
        assertEquals(200, send("PUT", "/v1/session/renew/" + timed, null).statusCode());
        JsonNode renewed = session(ok(send("GET", "/v1/internal/ui/sessions", null)), timed);
        assertFalse(Instant.parse(renewed.path("RenewedAt").asText()).isBefore(renewing.minusMillis(1)));
        assertFalse(Instant.parse(renewed.path("ExpiresAt").asText()).isBefore(renewing.plusSeconds(30).minusMillis(1)));

        assertRefused(send("GET", "/v1/internal/ui/sessions?node=host-a", null), 400, "invalid_query");
    }

    @Test
    void theSessionsReadLeavesOutWhatTheTokenCannotRead() throws Exception {
        start(true, Set.of("ui-node"));
        String admin = token("{\"session_prefix\":{\"\":{\"policy\":\"write\"}}}");
        String readerB = token("{\"session_prefix\":{\"host-b\":{\"policy\":\"read\"}}}");
        create("{\"Node\":\"host-a\"}", "X-Qraft-Token", admin);
        String b = create("{\"Node\":\"host-b\"}", "X-Qraft-Token", admin);

        HttpResponse<byte[]> read = send("GET", "/v1/internal/ui/sessions", null, "X-Qraft-Token", readerB);
        List<String> ids = new ArrayList<>();
        ok(read).path("Sessions").forEach(session -> ids.add(session.path("ID").asText()));
        assertEquals(List.of(b), ids);
        assertEquals("true", read.headers().firstValue(HttpApiServer.RESULTS_FILTERED_HEADER).orElseThrow());
    }

    @Test
    void aFollowerSaysItDoesNotKnowTheTimers() throws Exception {
        start(false, Set.of("ui-node", "absent-peer"));

        JsonNode answer = ok(send("GET", "/v1/internal/ui/sessions?stale", null));
        assertFalse(answer.path("TtlKnown").asBoolean(), "only the leader keeps the timers");
        assertEquals(0, answer.path("Sessions").size());
    }

    private static List<String> held(JsonNode locks) {
        List<String> held = new ArrayList<>();
        locks.path("Held").forEach(entry -> held.add(entry.path("Tenant").asText() + "/"
                + entry.path("Namespace").asText() + "/" + entry.path("Key").asText() + ":"
                + entry.path("Session").asText()));
        return held;
    }

    private static JsonNode session(JsonNode answer, String id) {
        for (JsonNode session : answer.path("Sessions")) {
            if (session.path("ID").asText().equals(id)) return session;
        }
        throw new AssertionError("no session " + id + " in " + answer);
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

    private static JsonNode ok(HttpResponse<byte[]> response) throws Exception {
        assertEquals(200, response.statusCode(), text(response));
        return JSON.readTree(response.body());
    }

    private String create(String body, String... headers) throws Exception {
        return ok(send("PUT", "/v1/session/create", body, headers)).path("ID").asText();
    }

    private static void assertRefused(HttpResponse<byte[]> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), text(response));
        assertEquals(code, JSON.readTree(response.body()).path("code").asText(), text(response));
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

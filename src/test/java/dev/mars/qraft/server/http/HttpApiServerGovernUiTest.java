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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the administrative interface's governance reads (UI-6 Tenants, Namespaces and Audit task list, Phase 0):
 * {@code GET /v1/internal/ui/scopes}, what every tenant and namespace holds (decision 2); and
 * {@code GET /v1/internal/ui/audit}, the answering server's audit file, newest first and filtered (decision 5).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-17
 * @version 1.0
 */
class HttpApiServerGovernUiTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] PROD = {"X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "prod"};

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
                .nodeId("govern-node").clusterNodes(Set.of("govern-node")).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        submit(TenancyCommand.createTenant("acme", "Acme Ltd"));
        submit(TenancyCommand.createNamespace("acme", "prod", "production"));
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null,
                HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, acl
                ? new AclHttpSettings(true, dataRoot.resolve("server"), AclDefaultPolicy.DENY)
                : AclHttpSettings.disabled());
        server.start().get(10, TimeUnit.SECONDS);
    }

    @Test
    void theScopesReadCountsWhatEachTenantAndNamespaceHolds() throws Exception {
        start(false);
        register("node-a", "web-1", "web", PROD);
        register("node-b", "web-2", "web", PROD);
        register("node-a", "api-1", "api", PROD);
        register("node-a", "home-1", "home");
        for (String key : List.of("app/a", "app/b", "lock")) {
            assertEquals("true", text(send("PUT", "/v1/kv/" + key, "v", PROD)));
        }
        assertEquals("true", text(send("PUT", "/v1/kv/readme", "v")));
        String session = JSON.readTree(text(send("PUT", "/v1/session/create", "{\"Node\":\"node-a\"}"))).path("ID")
                .asText();
        assertEquals("true", text(send("PUT", "/v1/kv/lock?acquire=" + session, "v", PROD)));

        HttpResponse<byte[]> read = send("GET", "/v1/internal/ui/scopes", null);
        JsonNode tenants = ok(read).path("Tenants");
        assertTrue(read.headers().firstValue("X-Qraft-Index").orElse("").matches("\\d+"));
        assertEquals(List.of("acme", "default"), names(tenants));
        JsonNode acme = tenants.get(0);
        assertEquals("Acme Ltd", acme.path("Description").asText());
        assertTrue(acme.path("CreateIndex").asLong() > 0);
        assertEquals(List.of("default", "prod"), names(acme.path("Namespaces")));
        JsonNode prod = acme.path("Namespaces").get(1);
        assertEquals("production", prod.path("Description").asText());
        assertEquals(2, prod.path("Services").asInt(), prod.toString());
        assertEquals(3, prod.path("Instances").asInt());
        assertEquals(3, prod.path("Keys").asInt());
        assertEquals(1, prod.path("HeldKeys").asInt());
        assertEquals(List.of(session), texts(prod.path("Sessions")));
        JsonNode empty = acme.path("Namespaces").get(0);
        assertEquals(List.of(0, 0, 0, 0), List.of(empty.path("Services").asInt(), empty.path("Instances").asInt(),
                empty.path("Keys").asInt(), empty.path("HeldKeys").asInt()));
        JsonNode home = tenants.get(1).path("Namespaces").get(0);
        assertEquals(1, home.path("Services").asInt());
        assertEquals(1, home.path("Keys").asInt());

        assertRefused(send("GET", "/v1/internal/ui/scopes?tenant=acme", null), 400, "invalid_query");
        assertRefused(send("PUT", "/v1/internal/ui/scopes", null), 405, "method_not_allowed");
        assertEquals(200, send("GET", "/v1/internal/ui/scopes?consistent", null).statusCode());
    }

    @Test
    void theScopesReadNeedsOperatorRead() throws Exception {
        start(true);
        String keysOnly = token("{\"key_prefix\":{\"\":{\"policy\":\"read\"}}}");
        String operator = token("{\"operator\":\"read\"}");

        assertRefused(send("GET", "/v1/internal/ui/scopes", null, "X-Qraft-Token", keysOnly), 403, "permission_denied");
        assertEquals(List.of("acme", "default"), names(ok(send("GET", "/v1/internal/ui/scopes", null,
                "X-Qraft-Token", operator)).path("Tenants")));
    }

    /** Audit lines as {@code AuditFileSink} writes them, oldest first, with an older line, a gap and a broken line. */
    private Path auditFile() throws Exception {
        List<String> lines = List.of(
                "{\"type\":\"audit.http_request\",\"timestamp\":\"2026-11-17T10:00:00Z\",\"node\":\"s1\",\"accessorId\":\"a1\","
                        + "\"method\":\"GET\",\"route\":\"/v1/kv\",\"status\":200,\"requestId\":\"r1\"}",
                "{\"type\":\"audit.events_lost\"}",
                line("2026-11-17T10:00:02Z", "a2", "PUT", "/v1/acl/token", 403, "default", "default", "r2"),
                "not json",
                line("2026-11-17T10:00:03Z", "a1", "PUT", "/v1/kv", 200, "acme", "prod", "r3"),
                line("2026-11-17T10:00:04Z", "a1", "DELETE", "/v1/tenant", 500, "acme", "default", "r4"),
                line("2026-11-17T10:00:05Z", "a2", "GET", "/v1/acl/tokens", 200, "default", "default", "r5"));
        Path file = dataRoot.resolve("audit").resolve("qraft-audit.json");
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    private static String line(String at, String accessor, String method, String route, int status, String tenant,
                               String namespace, String requestId) {
        return ("{\"type\":\"audit.http_request\",\"timestamp\":\"%s\",\"node\":\"s1\",\"accessorId\":\"%s\","
                + "\"method\":\"%s\",\"route\":\"%s\",\"status\":%d,\"tenant\":\"%s\",\"namespace\":\"%s\","
                + "\"requestId\":\"%s\"}").formatted(at, accessor, method, route, status, tenant, namespace, requestId);
    }

    private static List<String> requests(JsonNode audit) {
        List<String> ids = new ArrayList<>();
        audit.path("Records").forEach(record -> ids.add(record.path("Type").asText().equals("audit.http_request")
                ? record.path("RequestId").asText() : record.path("Type").asText()));
        return ids;
    }

    @Test
    void theAuditReadAnswersTheFileNewestFirstFilteredAndSaysWhatItRead() throws Exception {
        start(false);
        Path file = auditFile();
        server.useAuditFile(file);

        JsonNode all = ok(send("GET", "/v1/internal/ui/audit", null));
        assertTrue(all.path("Configured").asBoolean());
        assertEquals(file.toString(), all.path("File").asText());
        assertEquals(java.nio.file.Files.size(file), all.path("Size").asLong());
        assertTrue(all.path("Complete").asBoolean(), "the whole file was read");
        assertEquals(all.path("Size").asLong(), all.path("Scanned").asLong());
        assertEquals(List.of("r5", "r4", "r3", "audit.unreadable_line", "r2", "audit.events_lost", "r1"), requests(all));
        JsonNode newest = all.path("Records").get(0);
        assertEquals("2026-11-17T10:00:05Z", newest.path("Timestamp").asText());
        assertEquals("s1", newest.path("Node").asText());
        assertEquals("a2", newest.path("AccessorId").asText());
        assertEquals("GET", newest.path("Method").asText());
        assertEquals("/v1/acl/tokens", newest.path("Route").asText());
        assertEquals(200, newest.path("Status").asInt());
        assertEquals("default", newest.path("Tenant").asText());
        assertTrue(all.path("Records").get(6).path("Tenant").isNull(), "a line from before the scope was recorded");

        // A lost stretch and a broken line stay in every answer: what they hid may have matched.
        assertEquals(List.of("r4", "r3", "audit.unreadable_line", "audit.events_lost", "r1"),
                requests(ok(send("GET", "/v1/internal/ui/audit?accessor=a1", null))));
        assertEquals(List.of("audit.unreadable_line", "r2", "audit.events_lost"),
                requests(ok(send("GET", "/v1/internal/ui/audit?result=denied", null))));
        assertEquals(List.of("r4", "audit.unreadable_line", "audit.events_lost"),
                requests(ok(send("GET", "/v1/internal/ui/audit?result=failed", null))));
        assertEquals(List.of("r5", "audit.unreadable_line", "r2", "audit.events_lost"),
                requests(ok(send("GET", "/v1/internal/ui/audit?route=/v1/acl", null))));
        assertEquals(List.of("r3", "audit.unreadable_line", "audit.events_lost"),
                requests(ok(send("GET", "/v1/internal/ui/audit?tenant=acme&namespace=prod", null))));
        assertEquals(List.of("r3", "audit.unreadable_line", "audit.events_lost"),
                requests(ok(send("GET", "/v1/internal/ui/audit?method=put&accessor=a1", null))));
        assertEquals(List.of("audit.unreadable_line", "r2", "audit.events_lost"),
                requests(ok(send("GET", "/v1/internal/ui/audit?requestId=r2", null))));
        assertEquals(List.of("audit.unreadable_line", "r2", "audit.events_lost", "r1"),
                requests(ok(send("GET", "/v1/internal/ui/audit?before=2026-11-17T10:00:03Z", null))));

        JsonNode page = ok(send("GET", "/v1/internal/ui/audit?limit=2", null));
        assertEquals(List.of("r5", "r4"), requests(page));
        assertFalse(page.path("Complete").asBoolean(), "the read stopped at its limit, before the file's start");
        assertTrue(all.path("Next").isNull(), "a read that reached the start has no next page");
        // The next page goes on from the byte where the read stopped (Phase 3: a time would rescan what was read).
        JsonNode next = ok(send("GET", "/v1/internal/ui/audit?limit=2&from=" + page.path("Next").asLong(), null));
        assertEquals(List.of("r3", "audit.unreadable_line"), requests(next));
        JsonNode last = ok(send("GET", "/v1/internal/ui/audit?limit=5&from=" + next.path("Next").asLong(), null));
        assertEquals(List.of("r2", "audit.events_lost", "r1"), requests(last));
        assertTrue(last.path("Complete").asBoolean());
        assertEquals(List.of("r5", "r4", "r3", "audit.unreadable_line", "r2", "audit.events_lost", "r1"),
                requests(ok(send("GET", "/v1/internal/ui/audit?from=" + (all.path("Size").asLong() + 100), null))),
                "an offset past the end, as after a rotation, reads from the end");

        for (String refused : List.of("limit=0", "limit=1001", "result=other", "before=yesterday", "accessor=", "kind=x",
                "from=-1", "from=end")) {
            assertRefused(send("GET", "/v1/internal/ui/audit?" + refused, null), 400, "invalid_query");
        }
    }

    @Test
    void theAuditReadStopsAtItsScanLimitAndSaysSo() throws Exception {
        start(false);
        Path file = auditFile();
        server.useAuditFile(file);
        server.useAuditScanLimit(400);

        JsonNode scanned = ok(send("GET", "/v1/internal/ui/audit", null));
        assertFalse(scanned.path("Complete").asBoolean());
        assertTrue(scanned.path("Scanned").asLong() <= 400 && scanned.path("Scanned").asLong() > 0, scanned.toString());
        assertEquals("r5", requests(scanned).getFirst());
        assertFalse(requests(scanned).contains("r1"), "a line before the limit is not read");
        // The pages after it go on from where each stopped, its cut line read whole, until the start.
        List<String> seen = new ArrayList<>(requests(scanned));
        JsonNode page = scanned;
        while (!page.path("Complete").asBoolean()) {
            page = ok(send("GET", "/v1/internal/ui/audit?from=" + page.path("Next").asLong(), null));
            seen.addAll(requests(page));
        }
        assertEquals(List.of("r5", "r4", "r3", "audit.unreadable_line", "r2", "audit.events_lost", "r1"), seen);
    }

    @Test
    void theAuditReadSaysWhenNoFileIsConfiguredOrWrittenYet() throws Exception {
        start(false);
        JsonNode none = ok(send("GET", "/v1/internal/ui/audit", null));
        assertFalse(none.path("Configured").asBoolean());
        assertTrue(none.path("File").isNull());
        assertEquals(0, none.path("Records").size());

        server.useAuditFile(dataRoot.resolve("not-yet").resolve("qraft-audit.json"));
        JsonNode empty = ok(send("GET", "/v1/internal/ui/audit", null));
        assertTrue(empty.path("Configured").asBoolean());
        assertEquals(0, empty.path("Size").asLong());
        assertTrue(empty.path("Complete").asBoolean());
    }

    @Test
    void theAuditReadNeedsOperatorRead() throws Exception {
        start(true);
        server.useAuditFile(auditFile());
        String keysOnly = token("{\"key_prefix\":{\"\":{\"policy\":\"read\"}}}");
        String operator = token("{\"operator\":\"read\"}");

        assertRefused(send("GET", "/v1/internal/ui/audit", null, "X-Qraft-Token", keysOnly), 403, "permission_denied");
        assertEquals(7, ok(send("GET", "/v1/internal/ui/audit", null, "X-Qraft-Token", operator)).path("Records").size());
    }

    private void register(String nodeName, String id, String name, String... scope) throws Exception {
        String[] headers = new String[scope.length + 2];
        headers[0] = "X-Qraft-Node";
        headers[1] = nodeName;
        System.arraycopy(scope, 0, headers, 2, scope.length);
        HttpResponse<byte[]> answer = send("PUT", "/v1/catalog/register", """
                {"node":{"address":"10.0.0.1"},"service":{"serviceId":"%s","serviceName":"%s","address":"10.0.0.5",\
                "port":8080}}""".formatted(id, name), headers);
        assertEquals(200, answer.statusCode(), text(answer));
    }

    private static List<String> names(JsonNode list) {
        List<String> names = new ArrayList<>();
        list.forEach(item -> names.add(item.path("Name").asText()));
        return names;
    }

    private static List<String> texts(JsonNode list) {
        List<String> texts = new ArrayList<>();
        list.forEach(item -> texts.add(item.asText()));
        return texts;
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

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

package dev.mars.qraft.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.raft.SharedDockerClusterFixture;
import dev.mars.qraft.testing.fault.SubprocessOutputAuditHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shaded runtime JAR, started with {@code java -jar} from a directory that holds only its configuration, serves the
 * administrative interface from the resources packaged in it; a server with the interface disabled, and a client, serve
 * none of its routes (design section 19.6; the interface's task list, Step 5); and the packaged server answers the
 * configuration it was started from with its secret withheld (UI-4 task list, Phase 3); and, with ACLs on, answers the
 * calls the interface makes once signed in with the management token from its file (UI-5 task list, Phase 4), and,
 * with an audit file, the Tenants, Namespaces and Audit pages' calls (UI-6 Tenants, Namespaces and Audit task list,
 * Phase 4). Run after {@code mvn install}, as the end-to-end stage is, against the {@code target/qraft.jar} that build
 * packaged.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-14
 * @version 1.0
 */
@Tag("e2e")
class PackagedAdminUiEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REPOSITORY = Path.of("").toAbsolutePath();
    private static final Path JAR = REPOSITORY.resolve("target").resolve("qraft.jar");
    private static final String UI_ROOT = "META-INF/qraft/ui/";
    private static final String OPERATOR_SECRET = "packaged-operator-secret-keep-out-of-answers";

    @TempDir
    Path directory;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Process server;
    private Process client;

    @BeforeEach
    void theJarIsTheOneThisBuildPackaged() {
        SharedDockerClusterFixture.assertRuntimeJarIsCurrent(REPOSITORY, JAR);
    }

    @AfterEach
    void stop() throws Exception {
        for (Process process : new Process[] {client, server}) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(30, TimeUnit.SECONDS);
            }
        }
        http.close();
    }

    @Test
    void thePackagedJarServesTheInterfaceFromItsOwnResources() throws Exception {
        Path run = Files.createDirectories(directory.resolve("server-run"));
        int port = freePort();
        server = start("server", run, serverConfig(run, port, ""));
        URI origin = URI.create("http://127.0.0.1:" + port);
        awaitReady(server, origin, "/health/ready", run.resolve("server.out"));
        try (Stream<Path> files = Files.walk(run)) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().equals("index.html")),
                    "no interface file on disk where the server runs");
        }

        HttpResponse<String> index = get(origin, "/ui/");
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("id=\"qraft-bootstrap\""), "the index is rendered with its bootstrap object");
        HttpResponse<String> manifest = get(origin, "/ui/asset-manifest.json");
        assertEquals(200, manifest.statusCode());
        String entry = entryFile(JSON.readTree(manifest.body()));
        assertTrue(index.body().contains("\"./" + entry + "\""), "the index links the manifest's entry " + entry);
        HttpResponse<byte[]> asset = http.send(HttpRequest.newBuilder(origin.resolve("/ui/" + entry))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, asset.statusCode(), entry);
        assertEquals("public, max-age=31536000, immutable", asset.headers().firstValue("Cache-Control").orElse(""));
        assertArrayEquals(packaged(entry), asset.body(), "the served bundle is the one packaged in the jar");
        assertEquals(200, get(origin, "/ui/services/web").statusCode(), "a view's address is answered with the index");

        server.destroyForcibly();
        server.waitFor(30, TimeUnit.SECONDS);
        SubprocessOutputAuditHelper.requireNoErrors("the packaged server", Files.readString(run.resolve("server.out")));
    }

    @Test
    void thePackagedServerAnswersTheConfigurationItWasStartedFromWithItsSecretWithheld() throws Exception {
        Path run = Files.createDirectories(directory.resolve("configured-run"));
        int port = freePort();
        server = start("server", run, serverConfig(run, port,
                ", \"operator\": {\"token\": \"" + OPERATOR_SECRET + "\"}"));
        URI origin = URI.create("http://127.0.0.1:" + port);
        awaitReady(server, origin, "/health/ready", run.resolve("server.out"));

        HttpResponse<String> answer = get(origin, "/v1/operator/configuration");
        assertEquals(200, answer.statusCode(), answer.body());
        assertTrue(!answer.body().contains(OPERATOR_SECRET), "the operator token is never answered: " + answer.body());
        JsonNode configuration = JSON.readTree(answer.body());
        assertEquals("packaged-server", configuration.path("server").asText());
        assertEquals(run.resolve("server.json").toString(), configuration.path("file").asText());
        boolean redacted = false;
        for (JsonNode setting : configuration.path("settings")) {
            if (setting.path("key").asText().equals("server.operator.token")) {
                redacted = setting.path("redacted").asBoolean() && setting.path("value").isNull();
            }
        }
        assertTrue(redacted, "the token's setting is listed and withheld: " + configuration);
        assertEquals("packaged-server", answer.headers().firstValue("X-Qraft-Answered-By").orElse(null));

        server.destroyForcibly();
        server.waitFor(30, TimeUnit.SECONDS);
        SubprocessOutputAuditHelper.requireNoErrors("the packaged server", Files.readString(run.resolve("server.out")));
    }

    @Test
    void aPackagedServerWithAclsOnAnswersTheInterfacesCallsUnderTheTokenSignedInWith() throws Exception {
        Path run = Files.createDirectories(directory.resolve("acl-run"));
        String management = UUID.randomUUID().toString();
        Path secretFile = run.resolve("management-token");
        Files.writeString(secretFile, management);
        int port = freePort();
        server = start("server", run, serverConfig(run, port, ", \"acl\": {\"enabled\": true, \"defaultPolicy\": \"deny\","
                + " \"initialManagementTokenFile\": " + path(secretFile) + "}"));
        URI origin = URI.create("http://127.0.0.1:" + port);
        awaitReady(server, origin, "/health/ready", run.resolve("server.out"));
        assertTrue(get(origin, "/ui/").body().contains("\"authMode\":\"token\""), "the interface asks for sign-in");
        Map<String, String> signedIn = Map.of("X-Qraft-Token", management);

        // Sign-in: self, once the leader has installed the token from the file; an unknown secret is refused.
        JsonNode self = awaitOk(origin, "GET", "/v1/acl/token/self", "", signedIn);
        assertTrue(self.path("installedFromFile").asBoolean(), self.toString());
        assertEquals("<hidden>", self.path("secretId").asText());
        assertEquals("token_not_found", JSON.readTree(send(origin, "GET", "/v1/acl/token/self", "",
                Map.of("X-Qraft-Token", UUID.randomUUID().toString())).body()).path("code").asText());
        JsonNode allowed = ok(origin, "POST", "/v1/internal/acl/authorize",
                "[{\"resource\":\"operator\",\"access\":\"write\"},{\"resource\":\"acl\",\"access\":\"write\"}]", signedIn);
        assertTrue(allowed.path(0).path("allow").asBoolean() && allowed.path(1).path("allow").asBoolean(), allowed.toString());
        JsonNode status = ok(origin, "GET", "/v1/internal/ui/acl/status", "", signedIn);
        assertTrue(status.path("managementTokens").path(0).path("installedFromFile").asBoolean(), status.toString());
        assertEquals(403, send(origin, "GET", "/v1/operator/configuration", "", Map.of()).statusCode(),
                "a request without the token acts as the anonymous token, which reads nothing");

        // Tokens and policies, as the Access pages change them.
        String policy = ok(origin, "PUT", "/v1/acl/policy", "{\"name\":\"web-writer\",\"rules\":"
                + "{\"service\":{\"web\":{\"policy\":\"write\"}},\"node\":{\"node-a\":{\"policy\":\"write\"}}}}", signedIn)
                .path("id").asText();
        JsonNode created = ok(origin, "PUT", "/v1/acl/token",
                "{\"description\":\"web deployer\",\"policies\":[{\"id\":\"" + policy + "\"}]}", signedIn);
        String secret = created.path("secretId").asText();
        String accessor = created.path("accessorId").asText();
        assertTrue(!secret.equals("<hidden>"), "the create answers the secret once");
        assertEquals("web deployer, renamed", ok(origin, "PUT", "/v1/acl/token/" + accessor,
                "{\"description\":\"web deployer, renamed\",\"policies\":[{\"id\":\"" + policy + "\"}]}", signedIn)
                .path("description").asText());
        assertTrue(ok(origin, "POST", "/v1/acl/explain", "{\"accessorId\":\"" + accessor + "\",\"resource\":\"service\","
                + "\"name\":\"web\",\"access\":\"write\"}", signedIn).path("allowed").asBoolean());

        // A service registered and deregistered under the new token, as the Changes tab does.
        Map<String, String> deployer = Map.of("X-Qraft-Token", secret, "X-Qraft-Node", "node-a",
                "X-Qraft-Tenant", "default", "X-Qraft-Namespace", "default");
        JsonNode registered = ok(origin, "PUT", "/v1/catalog/register", "{\"node\":{\"address\":\"127.0.0.1\"},"
                + "\"service\":{\"serviceId\":\"web-1\",\"serviceName\":\"web\",\"address\":\"127.0.0.1\",\"port\":8081,"
                + "\"tags\":[],\"metadata\":{},\"enabled\":true}}", deployer);
        assertTrue(registered.path("service").path("registered").asBoolean(), registered.toString());
        assertTrue(ok(origin, "PUT", "/v1/catalog/deregister", "{\"serviceId\":\"web-1\"}", deployer)
                .path("deregistered").asBoolean());

        assertTrue(ok(origin, "DELETE", "/v1/acl/token/" + accessor, "", signedIn).asBoolean());
        assertTrue(ok(origin, "DELETE", "/v1/acl/policy/" + policy, "", signedIn).asBoolean());
        assertEquals("token_not_found", JSON.readTree(send(origin, "GET", "/v1/acl/token/self", "",
                Map.of("X-Qraft-Token", secret)).body()).path("code").asText(), "a deleted token ends its session");

        server.destroyForcibly();
        server.waitFor(30, TimeUnit.SECONDS);
        String output = Files.readString(run.resolve("server.out"));
        assertTrue(!output.contains(management) && !output.contains(secret), "no secret reaches the server's output");
        SubprocessOutputAuditHelper.requireNoErrors("the packaged server with ACLs on", output);
    }

    @Test
    void aPackagedServerWithAclsOnAnswersTheKeyValuePagesCallsUnderAKeyPrefixToken() throws Exception {
        // UI-6 Key/Value task list, Phase 3: what the browser and the editor send, under a token that may write app/.
        Path run = Files.createDirectories(directory.resolve("kv-run"));
        String management = UUID.randomUUID().toString();
        Path secretFile = run.resolve("management-token");
        Files.writeString(secretFile, management);
        int port = freePort();
        server = start("server", run, serverConfig(run, port, ", \"acl\": {\"enabled\": true, \"defaultPolicy\": \"deny\","
                + " \"initialManagementTokenFile\": " + path(secretFile) + "}"));
        URI origin = URI.create("http://127.0.0.1:" + port);
        awaitReady(server, origin, "/health/ready", run.resolve("server.out"));
        Map<String, String> signedIn = Map.of("X-Qraft-Token", management);
        awaitOk(origin, "GET", "/v1/acl/token/self", "", signedIn);
        String policy = ok(origin, "PUT", "/v1/acl/policy", "{\"name\":\"app-keys\",\"rules\":"
                + "{\"key_prefix\":{\"app/\":{\"policy\":\"write\"}}}}", signedIn).path("id").asText();
        String secret = ok(origin, "PUT", "/v1/acl/token", "{\"policies\":[{\"id\":\"" + policy + "\"}]}", signedIn)
                .path("secretId").asText();
        Map<String, String> writer = Map.of("X-Qraft-Token", secret, "X-Qraft-Tenant", "default",
                "X-Qraft-Namespace", "default");
        assertTrue(ok(origin, "PUT", "/v1/kv/other?flags=0&cas=0", "kv-e2e-other", signedIn).asBoolean());

        // The editor: a creation, refused once the key exists; an update at the index read, refused once stale.
        assertTrue(ok(origin, "PUT", "/v1/kv/app/config?flags=0&cas=0", "kv-e2e-first", writer).asBoolean());
        assertFalse(ok(origin, "PUT", "/v1/kv/app/config?flags=0&cas=0", "kv-e2e-again", writer).asBoolean());
        long read = ok(origin, "GET", "/v1/kv/app/config", "", writer).path(0).path("ModifyIndex").asLong();
        assertTrue(ok(origin, "PUT", "/v1/kv/app/config?flags=1&cas=" + read, "kv-e2e-second", writer).asBoolean());
        assertFalse(ok(origin, "PUT", "/v1/kv/app/config?flags=1&cas=" + read, "kv-e2e-stale", writer).asBoolean(),
                "a write at an index the entry has moved past is refused, false");
        JsonNode entry = ok(origin, "GET", "/v1/kv/app/config", "", writer).path(0);
        assertEquals("kv-e2e-second", new String(Base64.getDecoder().decode(entry.path("Value").asText()),
                StandardCharsets.UTF_8));
        assertEquals(1, entry.path("Flags").asLong());

        // The browser: a level, filtered to what the token may read, and the authorize answers its controls follow.
        HttpResponse<String> level = send(origin, "GET", "/v1/kv/?keys&separator=/", "", writer);
        assertEquals(200, level.statusCode(), level.body());
        assertEquals(JSON.readTree("[\"app/\"]"), JSON.readTree(level.body()));
        assertEquals("true", level.headers().firstValue("X-Qraft-Results-Filtered-By-ACLs").orElse(null));
        JsonNode allowed = ok(origin, "POST", "/v1/internal/acl/authorize",
                "[{\"resource\":\"key\",\"segment\":\"app/config\",\"access\":\"write\"},"
                        + "{\"resource\":\"key\",\"segment\":\"other\",\"access\":\"write\"}]", writer);
        assertTrue(allowed.path(0).path("allow").asBoolean() && !allowed.path(1).path("allow").asBoolean(),
                allowed.toString());

        // An entry's recent events, read with operator:read, never carry its value.
        JsonNode events = ok(origin, "GET", "/v1/operator/events?key=app/config&tenant=default&namespace=default", "",
                signedIn).path("events");
        assertEquals(2, events.size(), events.toString());
        assertFalse(events.toString().contains("kv-e2e") || events.toString().contains(Base64.getEncoder()
                .encodeToString("kv-e2e-second".getBytes(StandardCharsets.UTF_8))), events.toString());

        // A deletion at the index read, and a folder's.
        long modified = entry.path("ModifyIndex").asLong();
        assertTrue(ok(origin, "DELETE", "/v1/kv/app/config?cas=" + modified, "", writer).asBoolean());
        assertTrue(ok(origin, "PUT", "/v1/kv/app/db/url?flags=0&cas=0", "kv-e2e-url", writer).asBoolean());
        assertTrue(ok(origin, "DELETE", "/v1/kv/app/?recurse", "", writer).asBoolean());
        assertEquals(404, send(origin, "GET", "/v1/kv/app/db/url", "", writer).statusCode());
        assertEquals(403, send(origin, "DELETE", "/v1/kv/?recurse", "", writer).statusCode(),
                "the whole store needs write on every key");

        server.destroyForcibly();
        server.waitFor(30, TimeUnit.SECONDS);
        String output = Files.readString(run.resolve("server.out"));
        assertFalse(output.contains("kv-e2e") || output.contains(management) || output.contains(secret),
                "no value and no secret reaches the server's output");
        SubprocessOutputAuditHelper.requireNoErrors("the packaged server serving key/value", output);
    }

    @Test
    void aPackagedServerWithAclsOnAnswersTheSessionsAndLocksPagesCallsUnderASessionToken() throws Exception {
        // UI-6 Sessions, Locks and Watches task list, Phase 5: what the Sessions, Locks and Key/Value pages send, under a
        // token that may write sessions on one node and keys under jobs/.
        Path run = Files.createDirectories(directory.resolve("sessions-run"));
        String management = UUID.randomUUID().toString();
        Path secretFile = run.resolve("management-token");
        Files.writeString(secretFile, management);
        int port = freePort();
        server = start("server", run, serverConfig(run, port, ", \"acl\": {\"enabled\": true, \"defaultPolicy\": \"deny\","
                + " \"initialManagementTokenFile\": " + path(secretFile) + "}"));
        URI origin = URI.create("http://127.0.0.1:" + port);
        awaitReady(server, origin, "/health/ready", run.resolve("server.out"));
        Map<String, String> signedIn = Map.of("X-Qraft-Token", management);
        awaitOk(origin, "GET", "/v1/acl/token/self", "", signedIn);
        for (String node : List.of("lock-node", "other-node")) {
            assertTrue(ok(origin, "PUT", "/v1/catalog/register", "{\"node\":{\"address\":\"127.0.0.1\"},\"service\":"
                    + "{\"serviceId\":\"svc-" + node + "\",\"serviceName\":\"svc\",\"address\":\"127.0.0.1\",\"port\":8081,"
                    + "\"tags\":[],\"metadata\":{},\"enabled\":true}}", Map.of("X-Qraft-Token", management,
                    "X-Qraft-Node", node, "X-Qraft-Tenant", "default", "X-Qraft-Namespace", "default"))
                    .path("service").path("registered").asBoolean(), node);
        }
        String policy = ok(origin, "PUT", "/v1/acl/policy", "{\"name\":\"locker\",\"rules\":{\"session\":{\"lock-node\":"
                + "{\"policy\":\"write\"}},\"key_prefix\":{\"jobs/\":{\"policy\":\"write\"}}}}", signedIn).path("id").asText();
        String secret = ok(origin, "PUT", "/v1/acl/token", "{\"policies\":[{\"id\":\"" + policy + "\"}]}", signedIn)
                .path("secretId").asText();
        Map<String, String> locker = Map.of("X-Qraft-Token", secret, "X-Qraft-Tenant", "default",
                "X-Qraft-Namespace", "default");

        // The Sessions pages: a creation, the sessions with the leader's deadline, filtered, and a renewal.
        String holder = ok(origin, "PUT", "/v1/session/create",
                "{\"Node\":\"lock-node\",\"Name\":\"sl-e2e\",\"TTL\":\"30s\",\"LockDelay\":\"5s\"}", locker).path("ID").asText();
        String contender = ok(origin, "PUT", "/v1/session/create", "{\"Node\":\"lock-node\"}", locker).path("ID").asText();
        assertEquals(403, send(origin, "PUT", "/v1/session/create", "{\"Node\":\"other-node\"}", locker).statusCode(),
                "a session on another node needs session:write on it");
        String outsider = ok(origin, "PUT", "/v1/session/create", "{\"Node\":\"other-node\"}", signedIn).path("ID").asText();
        HttpResponse<String> listed = send(origin, "GET", "/v1/internal/ui/sessions", "", locker);
        assertEquals(200, listed.statusCode(), listed.body());
        JsonNode sessions = JSON.readTree(listed.body());
        assertTrue(sessions.path("TtlKnown").asBoolean(), sessions.toString());
        assertEquals("true", listed.headers().firstValue("X-Qraft-Results-Filtered-By-ACLs").orElse(null));
        assertFalse(sessions.toString().contains(outsider), "a session on a node the token cannot read is left out");
        JsonNode timed = null;
        for (JsonNode session : sessions.path("Sessions")) {
            if (session.path("ID").asText().equals(holder)) timed = session;
        }
        assertTrue(timed != null && timed.path("ExpiresAt").isTextual() && timed.path("RenewedAt").isNull(),
                sessions.toString());
        assertEquals(holder, ok(origin, "PUT", "/v1/session/renew/" + holder, "", locker).path(0).path("ID").asText());

        // The Key/Value page's lock: acquired, refused to another session, released only by its holder.
        assertTrue(ok(origin, "PUT", "/v1/kv/jobs/leader?flags=0&acquire=" + holder, "sl-e2e-value", locker).asBoolean());
        assertFalse(ok(origin, "PUT", "/v1/kv/jobs/leader?flags=0&acquire=" + contender, "sl-e2e-value", locker)
                .asBoolean(), "a key another session holds");
        assertFalse(ok(origin, "PUT", "/v1/kv/jobs/leader?flags=0&release=" + contender, "sl-e2e-value", locker)
                .asBoolean(), "a session that does not hold the key cannot release it");
        assertTrue(ok(origin, "PUT", "/v1/kv/outside?flags=0&acquire=" + outsider, "sl-e2e-outside", signedIn).asBoolean());

        // The Locks page: the held keys the token may read, filtered, and a session's own.
        HttpResponse<String> locks = send(origin, "GET", "/v1/internal/ui/locks", "", locker);
        assertEquals(200, locks.statusCode(), locks.body());
        JsonNode held = JSON.readTree(locks.body()).path("Held");
        assertEquals(1, held.size(), held.toString());
        assertEquals("jobs/leader", held.path(0).path("Key").asText());
        assertEquals(holder, held.path(0).path("Session").asText());
        assertEquals("true", locks.headers().firstValue("X-Qraft-Results-Filtered-By-ACLs").orElse(null));
        assertEquals(1, ok(origin, "GET", "/v1/internal/ui/locks?session=" + holder, "", locker).path("Held").size());
        assertTrue(ok(origin, "PUT", "/v1/kv/jobs/leader?flags=0&release=" + holder, "sl-e2e-value", locker).asBoolean());

        // A destroy releases what its session holds and starts the lock delay, and its event says why it ended.
        assertTrue(ok(origin, "PUT", "/v1/kv/jobs/leader?flags=0&acquire=" + holder, "sl-e2e-value", locker).asBoolean());
        assertTrue(ok(origin, "PUT", "/v1/session/destroy/" + holder, "", locker).asBoolean());
        JsonNode after = ok(origin, "GET", "/v1/internal/ui/locks?consistent", "", locker);
        assertEquals(0, after.path("Held").size(), after.toString());
        assertEquals("jobs/leader", after.path("Delays").path(0).path("Key").asText(), after.toString());
        JsonNode events = ok(origin, "GET", "/v1/operator/events?session=" + holder, "", signedIn).path("events");
        JsonNode last = events.path(events.size() - 1);
        assertEquals("session.destroyed", last.path("type").asText(), events.toString());
        assertEquals("destroyed", last.path("event").path("reason").asText());
        assertFalse(events.toString().contains("sl-e2e-value"), "no event carries a value");

        server.destroyForcibly();
        server.waitFor(30, TimeUnit.SECONDS);
        String output = Files.readString(run.resolve("server.out"));
        assertFalse(output.contains("sl-e2e-value") || output.contains("sl-e2e-outside") || output.contains(management)
                || output.contains(secret), "no value and no secret reaches the server's output");
        SubprocessOutputAuditHelper.requireNoErrors("the packaged server serving sessions and locks", output);
    }

    @Test
    void aPackagedServerWithAclsOnAndAnAuditFileAnswersTheTenancyAndAuditPagesCalls() throws Exception {
        // UI-6 Tenants, Namespaces and Audit task list, Phase 4: what the Tenants, Namespaces and Audit pages send, with
        // ACLs on and an audit file: a tenant and a namespace created, counted, described and deleted, the deletion
        // removing what the count named, and the audit read finding those requests by principal, scope and result.
        Path run = Files.createDirectories(directory.resolve("govern-run"));
        String management = UUID.randomUUID().toString();
        Path secretFile = run.resolve("management-token");
        Files.writeString(secretFile, management);
        Path auditFile = run.resolve("audit").resolve("qraft-audit.json");
        int port = freePort();
        server = start("server", run, serverConfig(run, port, ", \"acl\": {\"enabled\": true, \"defaultPolicy\": \"deny\","
                + " \"initialManagementTokenFile\": " + path(secretFile) + "}, \"audit\": {\"path\": " + path(auditFile)
                + "}"));
        URI origin = URI.create("http://127.0.0.1:" + port);
        awaitReady(server, origin, "/health/ready", run.resolve("server.out"));
        Map<String, String> signedIn = Map.of("X-Qraft-Token", management);
        String operator = awaitOk(origin, "GET", "/v1/acl/token/self", "", signedIn).path("accessorId").asText();
        Map<String, String> inTenant = Map.of("X-Qraft-Token", management, "X-Qraft-Tenant", "gov-e2e");
        Map<String, String> prod = Map.of("X-Qraft-Token", management, "X-Qraft-Tenant", "gov-e2e",
                "X-Qraft-Namespace", "prod");

        // Created: a tenant, with its default namespace, and a namespace in it; a second creation is a conflict.
        String tenant = "{\"Name\":\"gov-e2e\",\"Description\":\"Governed\"}";
        assertEquals("gov-e2e", ok(origin, "PUT", "/v1/tenant", tenant, signedIn).path("Name").asText());
        assertEquals(409, send(origin, "PUT", "/v1/tenant", tenant, signedIn).statusCode(), "a tenant that exists");
        assertEquals("prod", ok(origin, "PUT", "/v1/namespace",
                "{\"Name\":\"prod\",\"Tenant\":\"gov-e2e\",\"Description\":\"production\"}", signedIn).path("Name").asText());

        // Counted: one service with two instances, and two keys, one of them held by a session.
        for (String node : List.of("gov-node-a", "gov-node-b")) {
            Map<String, String> onNode = new HashMap<>(prod);
            onNode.put("X-Qraft-Node", node);
            assertTrue(ok(origin, "PUT", "/v1/catalog/register", "{\"node\":{\"address\":\"127.0.0.1\"},\"service\":"
                    + "{\"serviceId\":\"web-" + node + "\",\"serviceName\":\"web\",\"address\":\"127.0.0.1\",\"port\":8081,"
                    + "\"tags\":[],\"metadata\":{},\"enabled\":true}}", onNode)
                    .path("service").path("registered").asBoolean(), node);
        }
        assertTrue(ok(origin, "PUT", "/v1/kv/app/config?flags=0", "gov-e2e-value", prod).asBoolean());
        String session = ok(origin, "PUT", "/v1/session/create", "{\"Node\":\"gov-node-a\",\"Name\":\"gov-e2e\"}", prod)
                .path("ID").asText();
        assertTrue(ok(origin, "PUT", "/v1/kv/app/leader?flags=0&acquire=" + session, "gov-e2e-value", prod).asBoolean());
        JsonNode counted = namespaceIn(ok(origin, "GET", "/v1/internal/ui/scopes?consistent", "", signedIn), "gov-e2e", "prod");
        assertEquals(List.of(1, 2, 2, 1), List.of(counted.path("Services").asInt(), counted.path("Instances").asInt(),
                counted.path("Keys").asInt(), counted.path("HeldKeys").asInt()), counted.toString());
        assertEquals(session, counted.path("Sessions").path(0).asText(), counted.toString());

        // Described.
        assertEquals("production, renamed", ok(origin, "PUT", "/v1/namespace/prod",
                "{\"Description\":\"production, renamed\"}", inTenant).path("Description").asText());
        assertEquals("Governed, renamed", ok(origin, "PUT", "/v1/tenant/gov-e2e", "{\"Description\":\"Governed, renamed\"}",
                signedIn).path("Description").asText());

        // Deleted: the namespace with what was counted, its session keeping on and holding nothing there; a second
        // deletion answers 404, a deletion already made. Then the tenant.
        assertTrue(ok(origin, "DELETE", "/v1/namespace/prod", "", inTenant).asBoolean());
        assertEquals(404, send(origin, "DELETE", "/v1/namespace/prod", "", inTenant).statusCode());
        JsonNode remaining = ok(origin, "GET", "/v1/internal/ui/scopes?consistent", "", signedIn);
        assertEquals(List.of("default"), names(tenantIn(remaining, "gov-e2e").path("Namespaces")), remaining.toString());
        assertFalse(ok(origin, "GET", "/v1/internal/ui/locks?consistent", "", signedIn).toString().contains("gov-e2e"),
                "no key is held in the deleted namespace");
        assertEquals(session, ok(origin, "GET", "/v1/session/info/" + session + "?consistent", "", signedIn)
                .path(0).path("ID").asText(), "the session keeps on");
        assertTrue(ok(origin, "DELETE", "/v1/tenant/gov-e2e", "", signedIn).asBoolean());
        assertEquals(404, send(origin, "DELETE", "/v1/tenant/gov-e2e", "", signedIn).statusCode());

        // A refused request: a token with no policy may not read the tenants.
        JsonNode bare = ok(origin, "PUT", "/v1/acl/token", "{\"description\":\"gov-e2e bare\"}", signedIn);
        String bareSecret = bare.path("secretId").asText();
        assertEquals(403, send(origin, "GET", "/v1/tenants", "", Map.of("X-Qraft-Token", bareSecret)).statusCode());

        // The audit read finds those requests by principal and result, and by principal, scope and method.
        List<JsonNode> denied = awaitAudit(origin, "accessor=" + bare.path("accessorId").asText() + "&result=denied",
                signedIn, 1);
        assertEquals("GET 403", denied.get(0).path("Method").asText() + " " + denied.get(0).path("Status").asInt());
        List<JsonNode> deletions = awaitAudit(origin, "accessor=" + operator + "&tenant=gov-e2e&method=DELETE"
                + "&route=/v1/namespace", signedIn, 2);
        assertEquals(List.of(404, 200), deletions.stream().map(record -> record.path("Status").asInt()).toList(),
                "the namespace's deletions, newest first");
        List<JsonNode> writes = awaitAudit(origin, "tenant=gov-e2e&namespace=prod&route=/v1/kv", signedIn, 2);
        assertTrue(writes.stream().allMatch(record -> record.path("AccessorId").asText().equals(operator)), writes.toString());
        JsonNode answer = ok(origin, "GET", "/v1/internal/ui/audit?limit=1", "", signedIn);
        assertTrue(answer.path("Configured").asBoolean());
        assertEquals(auditFile.toString(), answer.path("File").asText());

        server.destroyForcibly();
        server.waitFor(30, TimeUnit.SECONDS);
        String audit = Files.readString(auditFile);
        String output = Files.readString(run.resolve("server.out"));
        for (String written : List.of(audit, output)) {
            assertFalse(written.contains("gov-e2e-value") || written.contains(management) || written.contains(bareSecret),
                    "no value and no secret reaches the audit file or the server's output");
        }
        SubprocessOutputAuditHelper.requireNoErrors("the packaged server serving tenancy and audit", output);
    }

    /**
     * The requests the audit read answers for {@code query}, once it answers at least {@code count} within 30 s: the
     * audit file is written after each request is answered.
     */
    private List<JsonNode> awaitAudit(URI origin, String query, Map<String, String> headers, int count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        List<JsonNode> requests = List.of();
        while (System.nanoTime() < deadline) {
            JsonNode answer = ok(origin, "GET", "/v1/internal/ui/audit?" + query, "", headers);
            List<JsonNode> found = new ArrayList<>();
            answer.path("Records").forEach(record -> {
                if (record.path("Type").asText().equals("audit.http_request")) found.add(record);
            });
            requests = found;
            if (requests.size() >= count) return requests;
            Thread.sleep(100);
        }
        throw new AssertionError("the audit read for " + query + " answered " + requests + ", not " + count + " requests");
    }

    private static JsonNode tenantIn(JsonNode scopes, String name) {
        for (JsonNode tenant : scopes.path("Tenants")) {
            if (tenant.path("Name").asText().equals(name)) return tenant;
        }
        throw new AssertionError("no tenant " + name + " in " + scopes);
    }

    private static JsonNode namespaceIn(JsonNode scopes, String tenant, String name) {
        for (JsonNode namespace : tenantIn(scopes, tenant).path("Namespaces")) {
            if (namespace.path("Name").asText().equals(name)) return namespace;
        }
        throw new AssertionError("no namespace " + tenant + "/" + name + " in " + scopes);
    }

    private static List<String> names(JsonNode values) {
        List<String> names = new ArrayList<>();
        values.forEach(value -> names.add(value.path("Name").asText()));
        return names;
    }

    @Test
    void aServerWithTheInterfaceDisabledAndAClientServeNoneOfItsRoutes() throws Exception {
        String entry = entryFile(JSON.readTree(packaged("asset-manifest.json")));
        Path serverRun = Files.createDirectories(directory.resolve("server-run"));
        int serverPort = freePort();
        server = start("server", serverRun, serverConfig(serverRun, serverPort, ", \"ui\": {\"enabled\": false}"));
        URI serverOrigin = URI.create("http://127.0.0.1:" + serverPort);
        awaitReady(server, serverOrigin, "/health/ready", serverRun.resolve("server.out"));
        Path clientRun = Files.createDirectories(directory.resolve("client-run"));
        int clientPort = freePort();
        client = start("client", clientRun, clientConfig(clientRun, clientPort, serverPort));
        URI clientOrigin = URI.create("http://127.0.0.1:" + clientPort);
        awaitReady(client, clientOrigin, "/health/live", clientRun.resolve("client.out"));

        for (URI origin : List.of(serverOrigin, clientOrigin)) {
            for (String path : List.of("/ui/", "/ui/index.html", "/ui/asset-manifest.json", "/ui/" + entry,
                    "/ui/services/web")) {
                HttpResponse<String> response = get(origin, path);
                assertEquals(404, response.statusCode(), origin + path);
                assertTrue(!response.body().contains("qraft-bootstrap"), origin + path);
            }
        }
        assertEquals(200, get(serverOrigin, "/v1/catalog/services").statusCode(), "the server's API is served");

        client.destroyForcibly();
        client.waitFor(30, TimeUnit.SECONDS);
        server.destroyForcibly();
        server.waitFor(30, TimeUnit.SECONDS);
        SubprocessOutputAuditHelper.requireNoErrors("the client", Files.readString(clientRun.resolve("client.out")));
        SubprocessOutputAuditHelper.requireNoErrors("the server without the interface",
                Files.readString(serverRun.resolve("server.out")));
    }

    /** Starts the packaged jar in {@code mode} with {@code run} as its working directory and its output in a file. */
    private Process start(String mode, Path run, String config) throws IOException {
        Path file = run.resolve(mode + ".json");
        Files.writeString(file, config);
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", JAR.toString(), mode, "--config", file.toString())
                .directory(run.toFile())
                .redirectErrorStream(true)
                .redirectOutput(run.resolve(mode + ".out").toFile())
                .start();
    }

    private static String serverConfig(Path run, int httpPort, String ui) throws IOException {
        return """
                {"version": 1,
                 "server": {"id": "packaged-server", "http": {"host": "127.0.0.1", "port": %d}, "apiGrpcPort": %d,
                            "raft": {"port": %d, "nodes": {"packaged-server": "127.0.0.1:%3$d"},
                                     "electionTimeoutMs": 1500, "heartbeatIntervalMs": 300,
                                     "storage": {"type": "raftlog", "path": %s, "fsync": true}},
                            "telemetry": {"enabled": false}%s},
                 "logging": {"directory": %s}}
                """.formatted(httpPort, freePort(), freePort(), path(run.resolve("data")), ui,
                path(run.resolve("logs")));
    }

    private static String clientConfig(Path run, int httpPort, int serverPort) throws IOException {
        return """
                {"version": 1,
                 "client": {"nodeName": "packaged-client", "dataDirectory": %s, "address": "127.0.0.1",
                            "httpPort": %d, "heartbeatIntervalMs": 500, "shutdownTimeoutMs": 3000},
                 "servers": {"urls": ["http://127.0.0.1:%d"], "requestTimeoutMs": 2000},
                 "catalog": {"services": []},
                 "logging": {"directory": %s}}
                """.formatted(path(run.resolve("data")), httpPort, serverPort, path(run.resolve("logs")));
    }

    private void awaitReady(Process process, URI origin, String health, Path output) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            assertTrue(process.isAlive(), () -> "the process stopped before it was ready:\n" + read(output));
            if (status(origin, health) == 200) return;
            Thread.sleep(100);
        }
        throw new AssertionError(origin + health + " did not answer 200 within 60 s:\n" + read(output));
    }

    private int status(URI origin, String path) {
        try {
            return get(origin, path).statusCode();
        } catch (IOException | InterruptedException notYet) {
            if (notYet instanceof InterruptedException) Thread.currentThread().interrupt();
            return -1;
        }
    }

    private HttpResponse<String> send(URI origin, String method, String path, String body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A request answered 200, its body read. */
    private JsonNode ok(URI origin, String method, String path, String body, Map<String, String> headers) throws Exception {
        HttpResponse<String> answer = send(origin, method, path, body, headers);
        assertEquals(200, answer.statusCode(), method + " " + path + ": " + answer.body());
        return JSON.readTree(answer.body());
    }

    /**
     * A request answered 200 within 30 s: the leader installs the management token from its file once it leads, after
     * the server reports ready; until then the secret is unknown.
     */
    private JsonNode awaitOk(URI origin, String method, String path, String body, Map<String, String> headers)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        HttpResponse<String> answer = send(origin, method, path, body, headers);
        while (answer.statusCode() != 200 && System.nanoTime() < deadline) {
            Thread.sleep(100);
            answer = send(origin, method, path, body, headers);
        }
        assertEquals(200, answer.statusCode(), method + " " + path + ": " + answer.body());
        return JSON.readTree(answer.body());
    }

    private HttpResponse<String> get(URI origin, String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A file below the interface's root, as the jar holds it. */
    private static byte[] packaged(String file) throws IOException {
        try (JarFile jar = new JarFile(JAR.toFile())) {
            var entry = jar.getJarEntry(UI_ROOT + file);
            assertNotNull(entry, UI_ROOT + file + " is not in " + JAR);
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static String entryFile(JsonNode manifest) {
        for (JsonNode chunk : manifest) {
            if (chunk.path("isEntry").asBoolean()) return chunk.path("file").asText();
        }
        throw new AssertionError("the manifest names no entry: " + manifest);
    }

    private static String path(Path file) throws IOException {
        return JSON.writeValueAsString(file.toString());
    }

    private static String read(Path output) {
        try {
            return Files.exists(output) ? Files.readString(output) : "(no output)";
        } catch (IOException unreadable) {
            return "(output unreadable: " + unreadable.getMessage() + ")";
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link AclCommandLine}, the {@code qraft acl} commands (ACL list, Phase 5), after Consul's {@code consul acl},
 * against a server that answers as a Qraft server does: {@code bootstrap}; {@code token}, {@code policy} and
 * {@code role} with {@code create}, {@code list}, {@code read} and {@code delete}; and {@code explain}. Each sends the
 * caller's token from {@code --token} or {@code --token-file}, as {@code qraft operator raft} does; a refusal is
 * printed as {@code Error <status>: <code>: <message>} with exit code 1, and a misuse prints the usage with exit
 * code 2.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class AclCommandLineTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = """
            {"accessorId":"11111111-1111-4111-8111-111111111111","secretId":"22222222-2222-4222-8222-222222222222",
             "description":"web","policies":[{"id":"33333333-3333-4333-8333-333333333333","name":"readers"}],
             "roles":[],"nodeIdentities":[{"nodeName":"web-1"}],
             "serviceIdentities":[{"serviceName":"api","tenant":"acme","namespace":"prod"}],
             "createTime":"2026-11-07T10:00:00Z","expirationTime":null,"installedFromFile":false}""";
    private static final String POLICY = """
            {"id":"33333333-3333-4333-8333-333333333333","name":"readers","description":"read",
             "rules":{"node_prefix":{"":{"policy":"read"}}}}""";

    @TempDir
    Path directory;
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private volatile int status = 200;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String request = exchange.getRequestMethod() + " " + exchange.getRequestURI();
            requests.add(request);
            tokens.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Qraft-Token")));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = answers.getOrDefault(request, "true").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void bootstrapPrintsTheManagementTokenAndSendsNoToken() {
        answers.put("PUT /v1/acl/bootstrap", TOKEN);

        int exit = run("bootstrap", "--http-addr", address());

        assertEquals(0, exit, err());
        assertEquals(List.of("PUT /v1/acl/bootstrap"), requests);
        assertEquals(List.of("null"), tokens);
        assertTrue(out().contains("AccessorID:") && out().contains("11111111-1111-4111-8111-111111111111"), out());
        assertTrue(out().contains("SecretID:") && out().contains("22222222-2222-4222-8222-222222222222"), out());
    }

    @Test
    void tokenCreateSendsItsLinksIdentitiesAndExpiryUnderTheTokenFromAFile() throws Exception {
        answers.put("PUT /v1/acl/token", TOKEN);
        Path tokenFile = Files.writeString(directory.resolve("token"), "the-callers-secret\n");

        int exit = run("token", "create", "--http-addr", address(), "--token-file", tokenFile.toString(),
                "--description", "web", "--policy-name", "readers", "--policy-id",
                "44444444-4444-4444-8444-444444444444", "--role-name", "ops", "--node-identity", "web-1",
                "--service-identity", "api:acme:prod", "--service-identity", "db", "--expiration-ttl", "1h");

        assertEquals(0, exit, err());
        assertEquals(List.of("PUT /v1/acl/token"), requests);
        assertEquals(List.of("the-callers-secret"), tokens);
        assertEquals(JSON.readTree("""
                {"description":"web","policies":[{"name":"readers"},{"id":"44444444-4444-4444-8444-444444444444"}],
                 "roles":[{"name":"ops"}],"nodeIdentities":[{"nodeName":"web-1"}],
                 "serviceIdentities":[{"serviceName":"api","tenant":"acme","namespace":"prod"},{"serviceName":"db"}],
                 "expirationTtl":"1h"}"""), JSON.readTree(bodies.getFirst()));
        assertTrue(out().contains("22222222-2222-4222-8222-222222222222"), out());
        assertTrue(out().contains("readers (33333333-3333-4333-8333-333333333333)"), out());
        assertTrue(out().contains("api (acme/prod)"), out());
    }

    @Test
    void tokenListReadAndDelete() {
        answers.put("GET /v1/acl/tokens", "[" + TOKEN.replace("22222222-2222-4222-8222-222222222222", "<hidden>")
                + "]");
        answers.put("GET /v1/acl/token/11111111-1111-4111-8111-111111111111", TOKEN);
        answers.put("GET /v1/acl/token/self", TOKEN);

        assertEquals(0, run("token", "list", "--http-addr", address(), "--token", "t"), err());
        List<String> lines = out().lines().toList();
        assertEquals(List.of("AccessorID", "Description", "Policies"), List.of(lines.get(0).split("\\s+")));
        assertTrue(lines.get(1).startsWith("11111111-1111-4111-8111-111111111111"), out());
        assertTrue(lines.get(1).contains("readers"), out());
        out.reset();

        assertEquals(0, run("token", "read", "--http-addr", address(), "--token", "t", "--id",
                "11111111-1111-4111-8111-111111111111"), err());
        assertTrue(out().contains("web-1"), out());
        assertEquals(0, run("token", "read", "--http-addr", address(), "--token", "t", "--self"), err());
        assertEquals(0, run("token", "delete", "--http-addr", address(), "--token", "t", "--id",
                "11111111-1111-4111-8111-111111111111"), err());

        assertEquals(List.of("GET /v1/acl/tokens", "GET /v1/acl/token/11111111-1111-4111-8111-111111111111",
                "GET /v1/acl/token/self", "DELETE /v1/acl/token/11111111-1111-4111-8111-111111111111"), requests);
        assertTrue(out().contains("Deleted token 11111111-1111-4111-8111-111111111111"), out());
    }

    @Test
    void policyCreateReadsItsRulesFromAFileAndPolicyReadTakesAName() throws Exception {
        answers.put("PUT /v1/acl/policy", POLICY);
        answers.put("GET /v1/acl/policy/name/readers", POLICY);
        answers.put("GET /v1/acl/policies", "[" + POLICY + "]");
        Path rules = Files.writeString(directory.resolve("rules.json"), "{\"node_prefix\":{\"\":{\"policy\":\"read\"}}}");

        assertEquals(0, run("policy", "create", "--http-addr", address(), "--token", "t", "--name", "readers",
                "--description", "read", "--rules-file", rules.toString()), err());
        assertEquals(JSON.readTree("{\"name\":\"readers\",\"description\":\"read\","
                + "\"rules\":{\"node_prefix\":{\"\":{\"policy\":\"read\"}}}}"), JSON.readTree(bodies.getFirst()));
        assertTrue(out().contains("readers") && out().contains("node_prefix"), out());

        assertEquals(0, run("policy", "read", "--http-addr", address(), "--token", "t", "--name", "readers"), err());
        assertEquals(0, run("policy", "list", "--http-addr", address(), "--token", "t"), err());
        assertEquals(0, run("policy", "delete", "--http-addr", address(), "--token", "t", "--id",
                "33333333-3333-4333-8333-333333333333"), err());
        assertEquals(List.of("PUT /v1/acl/policy", "GET /v1/acl/policy/name/readers", "GET /v1/acl/policies",
                "DELETE /v1/acl/policy/33333333-3333-4333-8333-333333333333"), requests);
    }

    @Test
    void roleCreateSendsItsPoliciesAndIdentities() throws Exception {
        answers.put("PUT /v1/acl/role", """
                {"id":"55555555-5555-4555-8555-555555555555","name":"ops","description":"",
                 "policies":[{"id":"33333333-3333-4333-8333-333333333333","name":"readers"}],
                 "nodeIdentities":[],"serviceIdentities":[]}""");

        assertEquals(0, run("role", "create", "--http-addr", address(), "--token", "t", "--name", "ops",
                "--policy-name", "readers"), err());

        assertEquals(JSON.readTree("{\"name\":\"ops\",\"policies\":[{\"name\":\"readers\"}]}"),
                JSON.readTree(bodies.getFirst()));
        assertTrue(out().contains("55555555-5555-4555-8555-555555555555"), out());
        assertEquals(0, run("role", "read", "--http-addr", address(), "--token", "t", "--name", "ops"), err());
        assertEquals("GET /v1/acl/role/name/ops", requests.get(1));
    }

    @Test
    void explainSendsTheQuestionAndPrintsWhatDecided() throws Exception {
        answers.put("POST /v1/acl/explain", """
                {"accessorId":"11111111-1111-4111-8111-111111111111","allowed":true,"defaultPolicy":"deny",
                 "decidedBy":"rule","policy":{"id":"33333333-3333-4333-8333-333333333333","name":"readers"},
                 "rule":{"resource":"node","name":"","prefix":true,"access":"read"}}""");

        assertEquals(0, run("explain", "--http-addr", address(), "--token", "t", "--accessor-id",
                "11111111-1111-4111-8111-111111111111", "--resource", "node", "--name", "web-1", "--access", "read"),
                err());

        assertEquals(JSON.readTree("{\"accessorId\":\"11111111-1111-4111-8111-111111111111\",\"resource\":\"node\","
                + "\"name\":\"web-1\",\"access\":\"read\"}"), JSON.readTree(bodies.getFirst()));
        assertTrue(out().contains("Allowed") && out().contains("true"), out());
        assertTrue(out().contains("readers (33333333-3333-4333-8333-333333333333)"), out());
    }

    @Test
    void aRefusalIsPrintedWithExitCodeOne() {
        status = 403;
        answers.put("GET /v1/acl/tokens", "{\"code\":\"permission_denied\",\"message\":\"Permission denied\"}");

        assertEquals(1, run("token", "list", "--http-addr", address(), "--token", "t"));
        assertEquals("Error 403: permission_denied: Permission denied", err().strip());
    }

    @Test
    void aMisusePrintsTheUsageWithExitCodeTwo() {
        List<String[]> misuses = List.of(new String[] {"tokens", "list"}, new String[] {"token", "frobnicate"},
                new String[] {"token", "read"}, new String[] {"token", "delete"},
                new String[] {"token", "list", "--token", "a", "--token-file", "b"},
                new String[] {"policy", "create", "--rules", "{}", "--rules-file", "f", "--name", "p"},
                new String[] {"policy", "create", "--name", "p"},
                new String[] {"explain", "--resource", "node"}, new String[] {"token", "create", "--colour", "red"},
                new String[] {});
        for (String[] misuse : misuses) {
            err.reset();
            assertEquals(2, run(misuse), String.join(" ", misuse));
            assertTrue(err().contains("Usage: qraft acl"), String.join(" ", misuse) + ": " + err());
        }
        assertTrue(requests.isEmpty(), "a misuse calls no server");
        assertFalse(out().contains("Error"));
    }

    private int run(String... args) {
        return AclCommandLine.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String address() {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }
}

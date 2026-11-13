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
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.server.QraftServerService;
import dev.mars.qraft.server.config.AppConfig;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of login and audit (authentication list, Phase 4): three servers with ACLs enabled and an audit
 * file each. An operator creates a {@code jwt} auth method and a binding rule on the leader; a workload exchanges its
 * JWT for a token there; every server enforces that token from the replicated state; its requests are written to the
 * leader's audit file under its accessor ID; and after logout no server accepts it.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
@Tag("e2e")
class AuthEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MANAGEMENT = "6b1f0c2e-8d3a-4e5b-9c7d-1a2b3c4d5e6f";

    @TempDir
    Path root;

    private final List<RaftExecutorsFixture> executors = new ArrayList<>();
    private final List<QraftServerService> servers = new ArrayList<>();
    private final List<Integer> httpPorts = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() throws Exception {
        CleanupHelper cleanup = new CleanupHelper();
        // Each server is stopped on purpose while the others may still send to it: a leader that sends a heartbeat
        // to one already stopped logs it as unreachable (AuthEndToEndTest, 2026-11-13).
        for (int i = servers.size() - 1; i >= 0; i--) {
            QraftServerService server = servers.get(i);
            IntentionalErrorsHelper.expectPeerShutdownDuringCleanup("server" + (i + 1));
            cleanup.run(() -> server.stop().get(30, TimeUnit.SECONDS));
        }
        for (RaftExecutorsFixture executor : executors) cleanup.run(() -> executor.close().get(30, TimeUnit.SECONDS));
        cleanup.run(http::close);
        cleanup.rethrow();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void aWorkloadLogsInWithItsJwtUsesItsTokenOnEveryServerIsAuditedAndLogsOut() throws Exception {
        startCluster();
        waitUntil(() -> leader() >= 0, "the management token is installed on the leader");
        int leader = leader();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keys = generator.generateKeyPair();
        String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(
                keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";

        assertEquals(200, send(leader, "PUT", "/v1/acl/policy", "{\"name\":\"kv-writer\",\"rules\":"
                + "{\"key_prefix\":{\"\":{\"policy\":\"write\"}}}}", MANAGEMENT).statusCode());
        assertEquals(200, send(leader, "PUT", "/v1/acl/auth-method", JSON.writeValueAsString(Map.of("name", "ci",
                "type", "jwt", "config", Map.of("jwtValidationPubKeys", List.of(pem), "boundIssuer", "https://ci.example",
                        "claimMappings", Map.of("job", "job")))), MANAGEMENT).statusCode());
        assertEquals(200, send(leader, "PUT", "/v1/acl/binding-rule", "{\"authMethod\":\"ci\",\"selector\":"
                + "\"value.job == \\\"deploy\\\"\",\"bindType\":\"policy\",\"bindName\":\"kv-writer\"}", MANAGEMENT)
                .statusCode());

        HttpResponse<String> login = send(leader, "POST", "/v1/acl/login", JSON.writeValueAsString(Map.of(
                "authMethod", "ci", "bearerToken", jwt(keys, Map.of("iss", "https://ci.example", "job", "deploy",
                        "exp", Instant.now().plusSeconds(300).getEpochSecond())))), null);
        assertEquals(200, login.statusCode(), login.body());
        JsonNode token = JSON.readTree(login.body());
        String secret = token.path("secretId").asText();
        String accessor = token.path("accessorId").asText();

        assertEquals("true", send(leader, "PUT", "/v1/kv/deploy/version", "42", secret).body());
        for (int server = 0; server < 3; server++) {
            int at = server;
            waitUntil(() -> status(at, "/v1/kv/deploy/version", secret) == 200,
                    "server " + (at + 1) + " enforces the login token from the replicated state");
        }
        Path audit = root.resolve("audit-server" + (leader + 1) + ".json");
        waitUntil(() -> auditLines(audit).stream().anyMatch(line -> line.contains(accessor)
                && line.contains("\"route\":\"/v1/kv\"")), "the leader audited the workload's write");
        assertTrue(auditLines(audit).stream().noneMatch(line -> line.contains(secret)), "no secret is audited");

        assertEquals("true", send(leader, "POST", "/v1/acl/logout", null, secret).body());
        for (int server = 0; server < 3; server++) {
            int at = server;
            waitUntil(() -> status(at, "/v1/kv/deploy/version", secret) == 403,
                    "server " + (at + 1) + " refuses the token after logout");
        }
    }

    private int leader() {
        for (int i = 0; i < httpPorts.size(); i++) {
            if (status(i, "/v1/acl/token/self", MANAGEMENT) == 200 && status(i, "/v1/kv/probe?consistent",
                    MANAGEMENT) != 503) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> auditLines(Path file) {
        try {
            return Files.exists(file) ? Files.readAllLines(file) : List.of();
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    private static String jwt(KeyPair keys, Map<String, Object> claims) throws Exception {
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String input = url.encodeToString(JSON.writeValueAsBytes(Map.of("alg", "ES256"))) + "."
                + url.encodeToString(JSON.writeValueAsBytes(claims));
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(keys.getPrivate());
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + url.encodeToString(signer.sign());
    }

    private int status(int server, String path, String token) {
        try {
            return send(server, "GET", path, null, token).statusCode();
        } catch (Exception unavailable) {
            return -1;
        }
    }

    private HttpResponse<String> send(int server, String method, String path, String body, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + httpPorts.get(server)
                + path)).method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("X-Qraft-Token", token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void startCluster() throws Exception {
        Path managementFile = Files.writeString(root.resolve("management"), MANAGEMENT);
        Map<String, Integer> raftPorts = new LinkedHashMap<>();
        Map<String, String> nodes = new LinkedHashMap<>();
        for (String id : List.of("server1", "server2", "server3")) {
            int raft = freePort();
            raftPorts.put(id, raft);
            nodes.put(id, "localhost:" + raft);
        }
        for (String id : nodes.keySet()) {
            int port = freePort();
            httpPorts.add(port);
            Path configuration = root.resolve(id + ".json");
            JSON.writeValue(configuration.toFile(), Map.of("version", 1, "server", Map.of(
                    "id", id, "http", Map.of("port", port), "apiGrpcPort", freePort(),
                    "raft", Map.of("port", raftPorts.get(id), "nodes", nodes, "electionTimeoutMs", 600,
                            "heartbeatIntervalMs", 150, "storage", Map.of("path", root.resolve(id).toString())),
                    "acl", Map.of("enabled", true, "defaultPolicy", "deny",
                            "initialManagementTokenFile", managementFile.toString()),
                    "audit", Map.of("path", root.resolve("audit-" + id + ".json").toString()),
                    "telemetry", Map.of("enabled", false))));
            AppConfig config = AppConfig.fromFile(configuration);
            config.validate();
            RaftExecutorsFixture executor = RaftExecutorsFixture.create();
            executors.add(executor);
            servers.add(new QraftServerService(executor.stateLoop(), executor.blockingTasks(), config));
        }
        for (QraftServerService server : servers) server.start().get(30, TimeUnit.SECONDS);
    }

    private static void waitUntil(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(), description);
    }
}

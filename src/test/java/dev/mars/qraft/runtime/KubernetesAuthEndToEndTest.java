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
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.common.tls.TlsMaterial;
import dev.mars.qraft.common.tls.TlsSettings;
import dev.mars.qraft.server.QraftServerService;
import dev.mars.qraft.server.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the {@code kubernetes} auth method (Kubernetes auth method list, Phase 4): three servers with ACLs
 * enabled, and a stub Kubernetes API over HTTPS. An operator creates the method and a binding rule on the service
 * account's namespace; a pod's service account token is exchanged for a token that every server enforces from the
 * replicated state; and no answer holds the reviewer's token.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
@Tag("e2e")
class KubernetesAuthEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MANAGEMENT = "7c2e1d3f-9e4b-4f6c-8d8e-2b3c4d5e6f70";

    @TempDir
    Path root;

    private final List<RaftExecutorsFixture> executors = new ArrayList<>();
    private final List<QraftServerService> servers = new ArrayList<>();
    private final List<Integer> httpPorts = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private HttpsServer kubernetes;

    @AfterEach
    void stop() throws Exception {
        CleanupHelper cleanup = new CleanupHelper();
        for (QraftServerService server : servers.reversed()) cleanup.run(() -> server.stop().get(30, TimeUnit.SECONDS));
        for (RaftExecutorsFixture executor : executors) cleanup.run(() -> executor.close().get(30, TimeUnit.SECONDS));
        if (kubernetes != null) cleanup.run(() -> kubernetes.stop(0));
        cleanup.run(http::close);
        cleanup.rethrow();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** A Kubernetes API whose token review authenticates {@code pod-token} as the service account billing/deployer. */
    private void startKubernetes() throws Exception {
        TlsMaterial material = TlsMaterial.load(new TlsSettings("kubernetes", TlsEndToEndTest.fixture("ca.pem"),
                Optional.of(TlsEndToEndTest.fixture("server.pem")), Optional.of(TlsEndToEndTest.fixture("server.key")),
                List.of("TLSv1.3", "TLSv1.2")));
        kubernetes = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        kubernetes.setHttpsConfigurator(new HttpsConfigurator(material.serverContext()));
        kubernetes.createContext("/apis/authentication.k8s.io/v1/tokenreviews", exchange -> {
            JsonNode review = JSON.readTree(exchange.getRequestBody());
            boolean reviewer = "Bearer reviewer-token".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            boolean pod = "pod-token".equals(review.path("spec").path("token").asText());
            Map<String, Object> status = reviewer && pod
                    ? Map.of("authenticated", true, "user", Map.of("username",
                    "system:serviceaccount:billing:deployer", "uid", "uid-1"))
                    : Map.of("authenticated", false);
            byte[] answer = JSON.writeValueAsBytes(Map.of("kind", "TokenReview", "status", status));
            exchange.sendResponseHeaders(201, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        kubernetes.start();
    }

    @Test
    void aPodLogsInWithItsServiceAccountTokenAndEveryServerEnforcesItsToken() throws Exception {
        startKubernetes();
        startCluster();
        waitUntil(() -> leader() >= 0, "the management token is installed on the leader");
        int leader = leader();

        assertEquals(200, send(leader, "PUT", "/v1/acl/policy", "{\"name\":\"kv-writer\",\"rules\":"
                + "{\"key_prefix\":{\"billing/\":{\"policy\":\"write\"}}}}", MANAGEMENT).statusCode());
        HttpResponse<String> method = send(leader, "PUT", "/v1/acl/auth-method", JSON.writeValueAsString(Map.of(
                "name", "cluster", "type", "kubernetes", "config", Map.of(
                        "host", "https://localhost:" + kubernetes.getAddress().getPort(),
                        "caCert", Files.readString(TlsEndToEndTest.fixture("ca.pem"), StandardCharsets.UTF_8),
                        "serviceAccountJwt", "reviewer-token"))), MANAGEMENT);
        assertEquals(200, method.statusCode(), method.body());
        assertEquals(200, send(leader, "PUT", "/v1/acl/binding-rule", "{\"authMethod\":\"cluster\",\"selector\":"
                + "\"serviceaccount.namespace == \\\"billing\\\"\",\"bindType\":\"policy\",\"bindName\":\"kv-writer\"}",
                MANAGEMENT).statusCode());
        for (int server = 0; server < 3; server++) {
            int at = server;
            waitUntil(() -> {
                try {
                    HttpResponse<String> read = send(at, "GET", "/v1/acl/auth-method/cluster?stale", null, MANAGEMENT);
                    return read.statusCode() == 200 && !read.body().contains("reviewer-token");
                } catch (Exception unanswered) {
                    return false;
                }
            }, "server " + (at + 1) + " holds the method, without its reviewer token");
        }

        HttpResponse<String> login = send(leader, "POST", "/v1/acl/login", JSON.writeValueAsString(Map.of(
                "authMethod", "cluster", "bearerToken", "pod-token")), null);
        assertEquals(200, login.statusCode(), login.body());
        assertFalse(login.body().contains("reviewer-token"));
        String secret = JSON.readTree(login.body()).path("secretId").asText();
        assertEquals("true", send(leader, "PUT", "/v1/kv/billing/leader", "pod", secret).body());
        assertEquals(403, send(leader, "PUT", "/v1/kv/payroll/leader", "pod", secret).statusCode(),
                "only what its policy grants");
        for (int server = 0; server < 3; server++) {
            int at = server;
            waitUntil(() -> status(at, "/v1/kv/billing/leader?stale", secret) == 200,
                    "server " + (at + 1) + " enforces the login token from the replicated state");
        }
        assertEquals(403, send(leader, "POST", "/v1/acl/login", JSON.writeValueAsString(Map.of(
                "authMethod", "cluster", "bearerToken", "someone-else")), null).statusCode());
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

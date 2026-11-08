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
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.common.tls.TlsMaterial;
import dev.mars.qraft.common.tls.TlsSettings;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests a login through a {@code kubernetes} auth method (Kubernetes auth method list, Phase 3, decisions 3 and 4)
 * against a stub Kubernetes API over HTTPS: the server reviews the bearer token under the method's reviewer token and
 * CA; an authenticated service account binds what the rules on {@code serviceaccount.namespace} and
 * {@code serviceaccount.name} give it; a token the review does not authenticate is refused 403; and a cluster API that
 * cannot be reached, or whose certificate the method's CA did not sign, is refused 503.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class HttpApiServerKubernetesLoginTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private HttpApiServer server;
    private HttpsServer kubernetes;
    private final ConcurrentLinkedQueue<String> reviewerTokens = new ConcurrentLinkedQueue<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() throws Exception {
        try {
            if (kubernetes != null) kubernetes.stop(0);
            if (server != null) server.close();
            if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        } finally {
            if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
            http.close();
        }
    }

    private static Path fixture(String name) throws Exception {
        return Path.of(HttpApiServerKubernetesLoginTest.class.getResource("/tls/" + name).toURI());
    }

    /** A Kubernetes API that answers token reviews: {@code good-token} is the service account billing/web. */
    private void startKubernetes() throws Exception {
        TlsMaterial material = TlsMaterial.load(new TlsSettings("kubernetes", fixture("ca.pem"),
                Optional.of(fixture("server.pem")), Optional.of(fixture("server.key")), List.of("TLSv1.3", "TLSv1.2")));
        kubernetes = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        kubernetes.setHttpsConfigurator(new HttpsConfigurator(material.serverContext()));
        kubernetes.createContext("/apis/authentication.k8s.io/v1/tokenreviews", exchange -> {
            reviewerTokens.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            JsonNode review = JSON.readTree(exchange.getRequestBody());
            boolean good = "good-token".equals(review.path("spec").path("token").asText())
                    && "TokenReview".equals(review.path("kind").asText());
            Map<String, Object> status = good
                    ? Map.of("authenticated", true, "user", Map.of("username", "system:serviceaccount:billing:web",
                    "uid", "6a1f-uid"))
                    : Map.of("authenticated", false, "error", "the token is not valid");
            byte[] answer = JSON.writeValueAsBytes(Map.of("apiVersion", "authentication.k8s.io/v1",
                    "kind", "TokenReview", "status", status));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        kubernetes.start();
    }

    private void start() throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("k8s-auth-node").clusterNodes(Set.of("k8s-auth-node")).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null);
        server.start().get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<String> send(String method, String path, Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void method(String name, String ca) throws Exception {
        HttpResponse<String> created = send("PUT", "/v1/acl/auth-method", Map.of("name", name, "type", "kubernetes",
                "config", Map.of("host", "https://localhost:" + kubernetes.getAddress().getPort(),
                        "caCert", Files.readString(fixture(ca), StandardCharsets.UTF_8),
                        "serviceAccountJwt", "reviewer-token")));
        assertEquals(200, created.statusCode(), created.body());
        HttpResponse<String> rule = send("PUT", "/v1/acl/binding-rule", Map.of("authMethod", name,
                "selector", "serviceaccount.namespace == \"billing\"", "bindType", "service",
                "bindName", "${serviceaccount.name}"));
        assertEquals(200, rule.statusCode(), rule.body());
    }

    private static String code(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("code").asText();
    }

    @Test
    void aServiceAccountTheClusterAuthenticatesLogsInWithItsNamespaceAndName() throws Exception {
        startKubernetes();
        start();
        method("pods", "ca.pem");

        HttpResponse<String> login = send("POST", "/v1/acl/login", Map.of("authMethod", "pods",
                "bearerToken", "good-token"));
        assertEquals(200, login.statusCode(), login.body());
        JsonNode token = JSON.readTree(login.body());
        assertEquals("web", token.path("serviceIdentities").get(0).path("serviceName").asText());
        assertEquals("pods", token.path("authMethod").asText());
        assertEquals(List.of("Bearer reviewer-token"), List.copyOf(reviewerTokens), "reviewed under the reviewer");
        assertTrue(!login.body().contains("reviewer-token"));

        HttpResponse<String> refused = send("POST", "/v1/acl/login", Map.of("authMethod", "pods",
                "bearerToken", "bad-token"));
        assertEquals(403, refused.statusCode(), refused.body());
        assertEquals("permission_denied", code(refused));
    }

    @Test
    void aClusterApiThatCannotBeReachedOrTrustedIsUnavailable() throws Exception {
        startKubernetes();
        start();
        method("untrusted", "other-ca.pem");
        HttpResponse<String> untrusted = send("POST", "/v1/acl/login", Map.of("authMethod", "untrusted",
                "bearerToken", "good-token"));
        assertEquals(503, untrusted.statusCode(), untrusted.body());
        assertEquals("auth_method_unavailable", code(untrusted));
        assertTrue(reviewerTokens.isEmpty(), "no review is sent to a server the CA did not sign");

        method("pods", "ca.pem");
        kubernetes.stop(0);
        kubernetes = null;
        HttpResponse<String> unreachable = send("POST", "/v1/acl/login", Map.of("authMethod", "pods",
                "bearerToken", "good-token"));
        assertEquals(503, unreachable.statusCode(), unreachable.body());
        assertEquals("auth_method_unavailable", code(unreachable));
        assertTrue(JSON.readTree(unreachable.body()).path("retryable").asBoolean());
    }
}

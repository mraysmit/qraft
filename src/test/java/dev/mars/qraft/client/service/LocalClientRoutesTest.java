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

package dev.mars.qraft.client.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.client.catalog.HttpCatalogClient;
import dev.mars.qraft.client.catalog.ServerContactTracker;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.common.QraftVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the local API's client information routes over real HTTP, with a real listener and a real client calling a real
 * JDK server, after Consul's {@code AgentSelf}, {@code AgentMembers} and {@code AgentLeave} (read in
 * {@code agent/agent_endpoint.go} on 2026-10-31).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-31
 * @version 1.0
 */
class LocalClientRoutesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NODE_ID = "1f6c2a5e-8f0b-4a3e-9c11-2d7b8e4f6a10";
    private static final Instant NOW = Instant.parse("2026-10-31T01:02:03Z");

    @TempDir
    Path directory;
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final ServerContactTracker contact = new ServerContactTracker(Clock.fixed(NOW, ZoneOffset.UTC));
    private final AtomicReference<ReadinessPolicy.Conditions> conditions = new AtomicReference<>(
            new ReadinessPolicy.Conditions(true, true, false, true, false));
    private final List<HttpServer> servers = new ArrayList<>();
    private CompletableFuture<Boolean> departure = CompletableFuture.completedFuture(true);
    private HttpCatalogClient catalogClient;
    private LocalApiServer api;

    @BeforeEach
    void startApi() throws Exception {
        start(refusedServer());
    }

    @AfterEach
    void stop() {
        api.shutdown();
        catalogClient.close();
        servers.forEach(server -> server.stop(0));
    }

    /** Starts the API against {@code server}, replacing any API a test started before. */
    private void start(URI server) {
        if (api != null) api.shutdown();
        if (catalogClient != null) catalogClient.close();
        ClientConfiguration config = ClientConfiguration.builder()
                .nodeName("client-1").dataDirectory(directory).address("10.0.0.5").clientPort(0)
                .datacenter("dc-1").region("eu-west").serverUrl(server.toString()).build();
        catalogClient = new HttpCatalogClient(HttpClient.newHttpClient(), JSON, List.of(server), "client-1", "default",
                "default", "dc-1", "eu-west", Duration.ofSeconds(5));
        api = new LocalApiServer(config, () -> true);
        new LocalClientRoutes(config, NODE_ID, conditions::get, contact, catalogClient::forwardRead,
                () -> {
                    events.add("depart");
                    return departure;
                },
                () -> events.add("left")).install(api);
        api.start();
    }

    private static URI refusedServer() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
    }

    private URI stubServer(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        servers.add(server);
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private HttpResponse<String> send(String method, String path, String requestId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (requestId != null) request.header("X-Request-Id", requestId);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        return send(method, path, null);
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private static void assertRefused(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json(response).path("code").asText(), response.body());
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> names = new TreeSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void selfNamesTheNodeAndItsVersionAsConsulsAgentSelfDoes() throws Exception {
        HttpResponse<String> self = send("GET", "/v1/client/self");

        assertEquals(200, self.statusCode(), self.body());
        JsonNode body = json(self);
        assertEquals(Set.of("node", "version", "config", "serverContact", "readiness"), fieldNames(body));
        assertEquals("client-1", body.path("node").path("name").asText());
        assertEquals(NODE_ID, body.path("node").path("id").asText());
        assertEquals("10.0.0.5", body.path("node").path("address").asText());
        assertEquals("dc-1", body.path("node").path("datacenter").asText());
        assertEquals("eu-west", body.path("node").path("region").asText());
        assertEquals(QraftVersion.current(), body.path("version").asText());
    }

    @Test
    void selfShowsOnlyTheSettingsItNamesSoThatASecretAddedLaterIsNotShownByDefault() throws Exception {
        JsonNode config = json(send("GET", "/v1/client/self")).path("config");

        assertEquals(Set.of("nodeName", "dataDirectory", "address", "clientPort", "bindAddress", "datacenter", "region",
                "tenant", "namespace", "servers", "heartbeatIntervalMs", "shutdownTimeoutMs", "requestTimeoutMs",
                "registrationRetryMinMs", "registrationRetryMaxMs", "contactFreshnessMs", "loggingDirectory"),
                fieldNames(config));
        assertEquals(directory.toString(), config.path("dataDirectory").asText());
        assertEquals("127.0.0.1", config.path("bindAddress").asText());
        assertTrue(config.path("servers").isArray());
    }

    @Test
    void selfSaysWhichReadinessConditionsAreMetAndWhenAServerWasLastHeardFrom() throws Exception {
        JsonNode before = json(send("GET", "/v1/client/self"));
        assertFalse(before.path("readiness").path("ready").asBoolean());
        assertTrue(before.path("readiness").path("running").asBoolean());
        assertTrue(before.path("readiness").path("nodeRegistered").asBoolean());
        assertFalse(before.path("readiness").path("servicesConverged").asBoolean());
        assertTrue(before.path("readiness").path("requiredChecksSatisfied").asBoolean());
        assertFalse(before.path("readiness").path("serverContactFresh").asBoolean());
        assertTrue(before.path("serverContact").path("lastSuccessfulContact").isNull(), "no server has answered yet");

        contact.recordSuccessfulContact();
        conditions.set(new ReadinessPolicy.Conditions(true, true, true, true, true));

        JsonNode after = json(send("GET", "/v1/client/self"));
        assertTrue(after.path("readiness").path("ready").asBoolean());
        assertEquals(NOW.toString(), after.path("serverContact").path("lastSuccessfulContact").asText());
    }

    @Test
    void membersAreTheServersCatalogPassedOnAsItCameBecauseThereIsNoGossipPool() throws Exception {
        AtomicReference<String> asked = new AtomicReference<>();
        AtomicReference<String> requestId = new AtomicReference<>();
        start(stubServer(exchange -> {
            asked.set(exchange.getRequestURI().getPath());
            requestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            exchange.getResponseHeaders().set("X-Qraft-Index", "42");
            byte[] body = "[{\"name\":\"client-1\",\"status\":\"HEALTHY\"}]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        }));

        HttpResponse<String> members = send("GET", "/v1/client/members", "request-7");

        assertEquals(200, members.statusCode(), members.body());
        assertEquals("[{\"name\":\"client-1\",\"status\":\"HEALTHY\"}]", members.body());
        assertEquals("42", members.headers().firstValue("X-Qraft-Index").orElseThrow());
        assertEquals("request-7", members.headers().firstValue("X-Request-Id").orElseThrow());
        assertEquals("application/json", members.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("/v1/catalog/nodes", asked.get());
        assertEquals("request-7", requestId.get(), "the servers' logs can be followed back to the request");
    }

    @Test
    void aServersRefusalOfMembersIsPassedOnWithItsLeaderHint() throws Exception {
        start(stubServer(exchange -> {
            exchange.getResponseHeaders().set("X-Qraft-Leader-Id", "server-2");
            byte[] body = "{\"code\":\"leader_unavailable\",\"retryable\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        }));

        HttpResponse<String> members = send("GET", "/v1/client/members");

        assertRefused(members, 503, "leader_unavailable");
        assertEquals("server-2", members.headers().firstValue("X-Qraft-Leader-Id").orElseThrow());
    }

    @Test
    void membersWithNoServerToAskAreARetryableFailureAndNeverStaleData() throws Exception {
        HttpResponse<String> members = send("GET", "/v1/client/members");

        assertRefused(members, 503, "no_server_reachable");
        assertTrue(json(members).path("retryable").asBoolean());
    }

    @Test
    void leavingDeregistersAnswersAndOnlyThenSaysTheClientHasLeft() throws Exception {
        HttpResponse<String> left = send("PUT", "/v1/client/leave");

        assertEquals(200, left.statusCode(), left.body());
        assertTrue(json(left).path("deregistered").asBoolean());
        awaitLeft();
        assertEquals(List.of("depart", "left"), events);
    }

    /** The answer reaches the caller just before the client is told it has left; the bound only diagnoses a hang. */
    private void awaitLeft() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (events.size() < 2 && System.nanoTime() < deadline) Thread.onSpinWait();
    }

    @Test
    void leavingWaitsForTheDeparture() throws Exception {
        departure = new CompletableFuture<>();
        CompletableFuture<HttpResponse<String>> answer = http.sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + "/v1/client/leave"))
                        .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (events.isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(List.of("depart"), events);
        assertFalse(answer.isDone(), "the answer says whether the node left, so it waits to know");

        departure.complete(false);

        HttpResponse<String> left = answer.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(200, left.statusCode(), left.body());
        assertFalse(json(left).path("deregistered").asBoolean(), "the client still leaves if it could not deregister");
        awaitLeft();
        assertEquals(List.of("depart", "left"), events);
    }

    @Test
    void theRoutesRefuseOtherMethodsAndPaths() throws Exception {
        for (String[] route : List.of(new String[] {"PUT", "/v1/client/self"}, new String[] {"PUT", "/v1/client/members"},
                new String[] {"GET", "/v1/client/leave"}, new String[] {"POST", "/v1/client/leave"})) {
            assertRefused(send(route[0], route[1]), 405, "method_not_allowed");
        }
        for (String[] route : List.of(new String[] {"GET", "/v1/client/self/x"},
                new String[] {"GET", "/v1/client/members/x"}, new String[] {"PUT", "/v1/client/leave/x"})) {
            assertRefused(send(route[0], route[1]), 404, "not_found");
        }
        assertEquals(List.of(), events);
    }

    @Test
    void onceShutdownBeginsALeaveIsRefusedAndSelfIsStillServed() throws Exception {
        api.beginShutdown();

        assertRefused(send("PUT", "/v1/client/leave"), 503, "shutting_down");
        assertEquals(200, send("GET", "/v1/client/self").statusCode());
        assertEquals(List.of(), events);
    }
}

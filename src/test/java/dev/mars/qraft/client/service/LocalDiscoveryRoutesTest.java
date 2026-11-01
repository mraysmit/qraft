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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.client.catalog.HttpCatalogClient;
import dev.mars.qraft.client.config.ClientConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the local API's forwarded discovery reads over real HTTP, with a real listener and a real client calling a real
 * JDK server standing in for the servers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-01
 * @version 1.0
 */
class LocalDiscoveryRoutesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<HttpServer> servers = new ArrayList<>();
    private final List<Asked> asked = new CopyOnWriteArrayList<>();
    private HttpCatalogClient catalogClient;
    private LocalApiServer api;

    /** What a server was asked. */
    private record Asked(String method, String uri, String requestId, String tenant, String namespace, String node) {
    }

    @AfterEach
    void stop() {
        if (api != null) api.shutdown();
        if (catalogClient != null) catalogClient.close();
        servers.forEach(server -> server.stop(0));
    }

    /** Starts the API with {@code seeds}, for a client of tenant {@code acme} and namespace {@code prod}. */
    private void start(URI... seeds) {
        ClientConfiguration config = ClientConfiguration.builder()
                .nodeName("client-1").dataDirectory(directory).address("10.0.0.5").clientPort(0)
                .serverUrl(seeds[0].toString()).build();
        catalogClient = new HttpCatalogClient(HttpClient.newHttpClient(), JSON, List.of(seeds), "client-1", "acme",
                "prod", "dc-1", "eu-west", Duration.ofSeconds(5));
        api = new LocalApiServer(config, () -> true);
        new LocalDiscoveryRoutes(catalogClient::forwardRead).install(api);
        api.start();
    }

    private static URI refusedServer() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
    }

    /** A server that records what it is asked and answers {@code status} and {@code body}, with {@code headers}. */
    private URI server(int status, String body, Map<String, String> headers) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            record(exchange);
            headers.forEach(exchange.getResponseHeaders()::set);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        servers.add(server);
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void record(HttpExchange exchange) {
        var headers = exchange.getRequestHeaders();
        asked.add(new Asked(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                headers.getFirst("X-Request-Id"), headers.getFirst("X-Qraft-Tenant"),
                headers.getFirst("X-Qraft-Namespace"), headers.getFirst("X-Qraft-Node")));
    }

    private HttpResponse<String> send(String method, String pathAndQuery, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + pathAndQuery))
                .method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return send("GET", pathAndQuery, Map.of());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private static void assertRefused(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json(response).path("code").asText(), response.body());
    }

    @Test
    void everyDiscoveryReadReachesTheServersWithItsPathAndQueryAsTheCallerWroteThem() throws Exception {
        start(server(200, "[]", Map.of()));

        for (String read : List.of("/v1/catalog/services", "/v1/catalog/service/web",
                "/v1/catalog/service/a%2Fb", "/v1/catalog/nodes", "/v1/health/service/web",
                "/v1/health/service/web?passing", "/v1/health/service/web?passing=true&note=a%20b",
                "/v1/catalog/nodes?wan=1")) {
            asked.clear();
            HttpResponse<String> answer = get(read);

            assertEquals(200, answer.statusCode(), read + " " + answer.body());
            assertEquals(List.of("GET " + read), asked.stream().map(a -> a.method() + " " + a.uri()).toList());
        }
    }

    @Test
    void theAnswerComesBackAsItCameWithTheIndexTheLeaderHintAndTheCallersRequestId() throws Exception {
        String body = "[{\"service\":{\"serviceId\":\"web-1\"},\"checks\":[],\"nodeStatus\":\"healthy\"}]";
        start(server(200, body, Map.of("X-Qraft-Index", "42", "X-Qraft-Leader-Id", "server-2")));

        HttpResponse<String> answer = send("GET", "/v1/health/service/web", Map.of("X-Request-Id", "request-7"));

        assertEquals(200, answer.statusCode(), answer.body());
        assertEquals(body, answer.body());
        assertEquals("42", answer.headers().firstValue("X-Qraft-Index").orElseThrow());
        assertEquals("server-2", answer.headers().firstValue("X-Qraft-Leader-Id").orElseThrow());
        assertEquals("request-7", answer.headers().firstValue("X-Request-Id").orElseThrow());
        assertEquals("application/json", answer.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("request-7", asked.getFirst().requestId(), "the servers' logs can be followed back to the request");
    }

    @Test
    void aServersRefusalIsPassedOnUnchanged() throws Exception {
        String body = "{\"code\":\"invalid_query\",\"error\":\"invalid_query\",\"message\":\"No\","
                + "\"retryable\":false,\"requestId\":\"theirs\"}";
        start(server(400, body, Map.of("X-Qraft-Leader-Id", "server-2")));

        HttpResponse<String> answer = get("/v1/health/service/web?stale");

        assertEquals(400, answer.statusCode(), answer.body());
        assertEquals(body, answer.body());
        assertEquals("server-2", answer.headers().firstValue("X-Qraft-Leader-Id").orElseThrow());
    }

    @Test
    void theCallersScopeReachesTheServersUnchanged() throws Exception {
        start(server(200, "[]", Map.of()));

        assertEquals(200, send("GET", "/v1/catalog/services",
                Map.of("X-Qraft-Tenant", "other", "X-Qraft-Namespace", "staging")).statusCode());

        assertEquals("other", asked.getFirst().tenant());
        assertEquals("staging", asked.getFirst().namespace());
    }

    @Test
    void aReadWithoutAScopeIsInTheClientsOwnWhereItsApplicationsRegister() throws Exception {
        start(server(200, "[]", Map.of()));

        assertEquals(200, get("/v1/catalog/services").statusCode());
        assertEquals(200, send("GET", "/v1/catalog/services", Map.of("X-Qraft-Namespace", "staging")).statusCode());

        assertEquals("acme", asked.getFirst().tenant());
        assertEquals("prod", asked.getFirst().namespace());
        assertEquals("acme", asked.get(1).tenant(), "each header is read alone");
        assertEquals("staging", asked.get(1).namespace());
    }

    @Test
    void aReadSpeaksAsThisNodeWhateverTheCallerSays() throws Exception {
        start(server(200, "[]", Map.of()));

        assertEquals(200, send("GET", "/v1/catalog/nodes", Map.of("X-Qraft-Node", "another")).statusCode());

        assertEquals("client-1", asked.getFirst().node());
    }

    @Test
    void aScopeThatCannotBeSentOnIsRefusedWithoutAskingTheServers() throws Exception {
        start(server(200, "[]", Map.of()));

        try (Socket socket = new Socket("127.0.0.1", api.port())) {
            socket.getOutputStream().write(("GET /v1/catalog/services HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "X-Qraft-Tenant: a\u0001b\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            String answer = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);

            assertTrue(answer.startsWith("HTTP/1.1 400"), answer);
            assertTrue(answer.contains("\"invalid_scope\""), answer);
        }
        assertEquals(List.of(), asked);
    }

    @Test
    void aRequestIdThatCannotBeSentOnIsRefusedWithoutAskingTheServers() throws Exception {
        start(server(200, "[]", Map.of()));

        try (Socket socket = new Socket("127.0.0.1", api.port())) {
            socket.getOutputStream().write(("GET /v1/catalog/services HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "X-Request-Id: a\u0001b\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            String answer = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);

            assertTrue(answer.startsWith("HTTP/1.1 400"), answer);
            assertTrue(answer.contains("\"invalid_request_id\""), answer);
        }
        assertEquals(List.of(), asked);
    }

    @Test
    void aSeedThatIsDownOrBusyIsPassedOverForTheNext() throws Exception {
        start(refusedServer(), server(503, "{\"code\":\"draining\",\"retryable\":true}", Map.of()),
                server(200, "[1]", Map.of()));

        HttpResponse<String> answer = get("/v1/catalog/nodes");

        assertEquals(200, answer.statusCode(), answer.body());
        assertEquals("[1]", answer.body());
    }

    @Test
    void withNoServerToAskAReadIsARetryableFailureAndNeverStaleData() throws Exception {
        start(refusedServer());

        for (String read : List.of("/v1/catalog/services", "/v1/catalog/service/web", "/v1/catalog/nodes",
                "/v1/health/service/web?passing")) {
            HttpResponse<String> answer = get(read);

            assertRefused(answer, 503, "no_server_reachable");
            assertTrue(json(answer).path("retryable").asBoolean(), read);
        }
    }

    @Test
    void whenEverySeedIsBusyTheLastAnswerIsPassedOn() throws Exception {
        start(server(503, "{\"code\":\"draining\",\"retryable\":true}", Map.of()));

        assertRefused(get("/v1/catalog/services"), 503, "draining");
    }

    @Test
    void theRoutesOnlyReadAndNeverAskTheServersToWrite() throws Exception {
        start(server(200, "[]", Map.of()));

        for (String method : List.of("PUT", "POST", "DELETE")) {
            for (String route : List.of("/v1/catalog/services", "/v1/catalog/service/web", "/v1/catalog/nodes",
                    "/v1/health/service/web")) {
                assertRefused(send(method, route, Map.of()), 405, "method_not_allowed");
            }
        }
        assertEquals(List.of(), asked);
    }

    @Test
    void onceShutdownBeginsReadsAreStillForwarded() throws Exception {
        start(server(200, "[]", Map.of()));

        api.beginShutdown();

        assertEquals(200, get("/v1/catalog/services").statusCode());
        assertRefused(send("PUT", "/v1/catalog/services", Map.of()), 503, "shutting_down");
        assertFalse(asked.isEmpty());
    }
}

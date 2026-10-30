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

package dev.mars.qraft.common.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link JsonApi}, the request handling the server's HTTP API and the client's local API share, against a real
 * JDK HTTP server: the request ID, the body limit, the method check and the error envelope.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-30
 * @version 1.0
 */
class JsonApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JsonApi api = new JsonApi(JSON);
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicInteger handled = new AtomicInteger();
    private final AtomicReference<String> handledBody = new AtomicReference<>();
    private final AtomicReference<String> handledRequestId = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startAServerWithOneRoute() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/echo", api.handle(exchange -> {
            if (!api.requireMethod(exchange, "PUT")) return;
            handled.incrementAndGet();
            handledRequestId.set(MDC.get("requestId"));
            handledBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            api.json(exchange, 200, Map.of("ok", true));
        }));
        server.createContext("/refuse", api.handle(exchange ->
                api.error(exchange, 409, "taken", "The name is taken", false, Map.of("node", "web-01"))));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void aRequestIdIsEchoedOrMadeAndIsTheLoggingRequestIdWhileTheRequestIsHandled() throws Exception {
        HttpResponse<String> given = send(put("/echo", "{}").header("X-Request-Id", "req-7"));
        assertEquals("req-7", given.headers().firstValue("X-Request-Id").orElseThrow());
        assertEquals("req-7", handledRequestId.get());

        HttpResponse<String> made = send(put("/echo", "{}"));
        String madeId = made.headers().firstValue("X-Request-Id").orElseThrow();
        assertFalse(madeId.isBlank(), "a request without an ID is given one");
        assertEquals(madeId, handledRequestId.get());
    }

    @Test
    void aBodyAtTheLimitReachesTheRouteIntactAndOneOverItIsRefusedBeforeTheRouteRuns() throws Exception {
        String atLimit = "x".repeat(JsonApi.MAX_BODY_BYTES);
        assertEquals(200, send(put("/echo", atLimit)).statusCode());
        assertEquals(atLimit, handledBody.get());

        HttpResponse<String> declared = send(put("/echo", atLimit + "x").header("X-Request-Id", "req-big"));
        assertEnvelope(declared, 413, "request_too_large", false);
        assertEquals("req-big", JSON.readTree(declared.body()).path("requestId").asText());

        // A body sent in chunks declares no length, so it is counted as it is read.
        HttpResponse<String> chunked = send(HttpRequest.newBuilder(uri("/echo")).PUT(HttpRequest.BodyPublishers
                .ofInputStream(() -> new ByteArrayInputStream((atLimit + "x").getBytes(StandardCharsets.UTF_8)))));
        assertEnvelope(chunked, 413, "request_too_large", false);
        assertEquals(1, handled.get(), "neither body over the limit reached the route");
    }

    @Test
    void aMethodTheRouteDoesNotServeIsRefusedWith405InTheEnvelope() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/echo")).GET());

        assertEnvelope(response, 405, "method_not_allowed", false);
        assertEquals(0, handled.get());
    }

    @Test
    void anErrorIsTheEnvelopeTheServersApiAnswersWith() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/refuse")).header("X-Request-Id", "req-9")
                .GET());

        JsonNode body = assertEnvelope(response, 409, "taken", false);
        assertEquals("The name is taken", body.path("message").asText());
        assertEquals("req-9", body.path("requestId").asText());
        assertEquals("web-01", body.path("node").asText(), "details follow the envelope's fields");
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
    }

    private JsonNode assertEnvelope(HttpResponse<String> response, int status, String code, boolean retryable)
            throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals(code, body.path("code").asText(), response.body());
        assertEquals(code, body.path("error").asText(), response.body());
        assertTrue(body.path("message").isTextual(), response.body());
        assertEquals(retryable, body.path("retryable").asBoolean(), response.body());
        return body;
    }

    private HttpRequest.Builder put(String path, String body) {
        return HttpRequest.newBuilder(uri(path)).PUT(HttpRequest.BodyPublishers.ofString(body));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

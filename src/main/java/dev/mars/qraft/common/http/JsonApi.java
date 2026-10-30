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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.MDC;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The request handling that the server's HTTP API and the client's local API share:
 * <ul>
 *   <li>each request has an ID, the one its {@code X-Request-Id} header gives or a new one, which the answer carries
 *       back and which is the logging context's {@code requestId} while the request is handled;</li>
 *   <li>a request body longer than {@link #MAX_BODY_BYTES} is refused with 413 {@code request_too_large} before the
 *       route runs, whether or not the request declares its length;</li>
 *   <li>a route refuses a method it does not serve with 405 {@code method_not_allowed};</li>
 *   <li>an error is one JSON envelope: {@code code}, {@code error} (the same code), {@code message},
 *       {@code retryable} and {@code requestId}, then any details.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-30
 * @version 1.0
 */
public final class JsonApi {
    /** The longest request body accepted, in bytes: 512 KiB, as Consul limits its request bodies. */
    public static final int MAX_BODY_BYTES = 512 * 1024;
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String REQUEST_ID = "requestId";

    private final ObjectMapper json;

    public JsonApi(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * {@code route} with its request's ID set and its body read and limited. The route reads the body as usual, from
     * the exchange's request body.
     */
    public HttpHandler handle(HttpHandler route) {
        Objects.requireNonNull(route, "route");
        return exchange -> {
            String requestId = exchange.getRequestHeaders().getFirst(REQUEST_ID_HEADER);
            if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
            exchange.getResponseHeaders().set(REQUEST_ID_HEADER, requestId);
            try (MDC.MDCCloseable ignored = MDC.putCloseable(REQUEST_ID, requestId)) {
                byte[] body;
                try (InputStream input = exchange.getRequestBody()) {
                    body = input.readNBytes(MAX_BODY_BYTES + 1);
                }
                if (body.length > MAX_BODY_BYTES) {
                    error(exchange, 413, "request_too_large",
                            "The request body is longer than " + MAX_BODY_BYTES + " bytes", false, Map.of());
                    return;
                }
                exchange.setStreams(new ByteArrayInputStream(body), null);
                route.handle(exchange);
            }
        };
    }

    /** Whether the request uses {@code method}; when it does not, it is answered 405 {@code method_not_allowed}. */
    public boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (method.equalsIgnoreCase(exchange.getRequestMethod())) return true;
        error(exchange, 405, "method_not_allowed", "Method not allowed", false, Map.of());
        return false;
    }

    /** Answers {@code body} as JSON with {@code status}. */
    public void json(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    /** Answers the error envelope with {@code status}, followed by {@code details}. */
    public void error(HttpExchange exchange, int status, String code, String message, boolean retryable,
                      Map<String, ?> details) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("error", code);
        body.put("message", message);
        body.put("retryable", retryable);
        body.put(REQUEST_ID, MDC.get(REQUEST_ID));
        body.putAll(details);
        json(exchange, status, body);
    }
}

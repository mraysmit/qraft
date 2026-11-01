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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import dev.mars.qraft.client.catalog.ForwardedRead;
import dev.mars.qraft.common.http.JsonApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The local API's discovery reads, which the client forwards to the servers, as Consul's agent does:
 * <pre>
 * GET /v1/catalog/services
 * GET /v1/catalog/service/{name}
 * GET /v1/catalog/nodes
 * GET /v1/health/service/{name}[?passing]
 * </pre>
 *
 * <p>Each goes to the servers with its path and query as the caller wrote them, and the servers' answer comes back as
 * it came, so that what a read means is decided in one place. A caller's scope headers go with it. A caller that sends
 * none reads the client's own scope, where its registrations through this API are. With no server to ask a read fails
 * rather than answer from old data; the client keeps no copy.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-01
 * @version 1.0
 */
public final class LocalDiscoveryRoutes {
    private static final List<String> ROUTES = List.of("/v1/catalog/services", "/v1/catalog/service",
            "/v1/catalog/nodes", "/v1/health/service");
    private static final List<String> SCOPE_HEADERS = List.of("X-Qraft-Tenant", "X-Qraft-Namespace");

    /** Forwards a read to the servers under the caller's scope headers; empty when no server could be asked. */
    @FunctionalInterface
    public interface ForwardedReads {
        CompletableFuture<Optional<ForwardedRead>> read(String pathAndQuery, String requestId, Map<String, String> scope);
    }

    private final JsonApi api = new JsonApi(new ObjectMapper());
    private final ForwardedReads reads;

    public LocalDiscoveryRoutes(ForwardedReads reads) {
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    /** Serves the routes on {@code localApi}. */
    public void install(LocalApiServer localApi) {
        ROUTES.forEach(route -> localApi.route(route, this::read));
    }

    private void read(HttpExchange exchange) throws IOException {
        if (api.requireMethod(exchange, "GET")) forward(exchange, exchange.getRequestURI().getRawPath());
    }

    /** Answers {@code exchange} with what the servers answer at {@code serverPath}, under the caller's query. */
    void forward(HttpExchange exchange, String serverPath) throws IOException {
        Map<String, String> scope = new LinkedHashMap<>();
        for (String header : SCOPE_HEADERS) {
            String value = exchange.getRequestHeaders().getFirst(header);
            if (value == null) continue;
            if (!sendable(value)) {
                api.error(exchange, 400, "invalid_scope", header + " holds a character that cannot be sent on", false,
                        Map.of());
                return;
            }
            scope.put(header, value);
        }
        String query = exchange.getRequestURI().getRawQuery();
        String requestId = exchange.getResponseHeaders().getFirst(JsonApi.REQUEST_ID_HEADER);
        if (!sendable(requestId)) {
            api.error(exchange, 400, "invalid_request_id",
                    JsonApi.REQUEST_ID_HEADER + " holds a character that cannot be sent on", false, Map.of());
            return;
        }
        Optional<ForwardedRead> read = reads.read(query == null ? serverPath : serverPath + "?" + query, requestId, scope)
                .join();
        if (read.isEmpty()) {
            api.error(exchange, 503, "no_server_reachable", "No server could be reached", true, Map.of());
            return;
        }
        passOn(exchange, read.get());
    }

    /** The listener takes control characters in a header value, which the client sending it on refuses. */
    private static boolean sendable(String value) {
        return value.chars().allMatch(character -> character >= 0x20 && character < 0x7f);
    }

    private static void passOn(HttpExchange exchange, ForwardedRead read) throws IOException {
        read.index().ifPresent(index -> exchange.getResponseHeaders().set("X-Qraft-Index", index));
        read.leaderId().ifPresent(leader -> exchange.getResponseHeaders().set("X-Qraft-Leader-Id", leader));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] body = read.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(read.status(), body.length == 0 ? -1 : body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }
}

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
import dev.mars.qraft.client.acl.ClientAuthorizer;
import dev.mars.qraft.client.catalog.ForwardedResponse;
import dev.mars.qraft.common.http.JsonApi;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /v1/kv}, {@code /v1/session} and the tenancy routes on the client's local API (key/value list, Phase 4;
 * sessions list, Phase 3; tenancy list, Phase 3): every read and write is forwarded to the servers, as Consul's agent
 * forwards its KV, session, partition and namespace requests, with the caller's scope headers, else the client's own
 * tenant and namespace (tenancy list, decision 6), and the caller's token, else the client's default token; the servers
 * decide and filter, and their answer is passed on as it came. A session created without a {@code Node} is bound to the
 * client's own node, as Consul's agent binds it (sessions list, decision 5).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class LocalKvRoutes {
    private static final String ROUTE = "/v1/kv";
    private static final String SESSION_ROUTE = "/v1/session";
    private static final String SESSION_CREATE = SESSION_ROUTE + "/create";
    private static final List<String> TENANCY_ROUTES = List.of("/v1/tenant", "/v1/tenants", "/v1/namespace",
            "/v1/namespaces");
    private static final List<String> SCOPE_HEADERS = List.of("X-Qraft-Tenant", "X-Qraft-Namespace");
    private static final Set<String> METHODS = Set.of("GET", "PUT", "DELETE");

    /** Forwards one request to the servers; empty when no server could be asked. */
    @FunctionalInterface
    public interface Forwarder {
        CompletableFuture<Optional<ForwardedResponse>> forward(String method, String pathAndQuery, byte[] body,
                                                               String requestId, Map<String, String> headers);
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonApi api = new JsonApi(mapper);
    private final Forwarder forwarder;
    private final ClientAuthorizer acl;
    private final String nodeName;

    /**
     * @param acl      gives the token each request is sent on under: the caller's, else the client's default token
     * @param nodeName the client's node, which a session created without one is bound to
     */
    public LocalKvRoutes(Forwarder forwarder, ClientAuthorizer acl, String nodeName) {
        this.forwarder = Objects.requireNonNull(forwarder, "forwarder");
        this.acl = Objects.requireNonNull(acl, "acl");
        this.nodeName = Objects.requireNonNull(nodeName, "nodeName");
    }

    /** Serves the routes on {@code localApi}. */
    public void install(LocalApiServer localApi) {
        localApi.route(ROUTE, this::forward);
        localApi.route(SESSION_ROUTE, this::forward);
        TENANCY_ROUTES.forEach(route -> localApi.route(route, this::forward));
    }

    /**
     * The body sent on: a session created without a node, or with an empty one, names the client's; any other body as
     * it came, the servers refusing what is not a session.
     */
    private byte[] sendable(String method, String path, byte[] body) {
        if (!method.equals("PUT") || !path.equals(SESSION_CREATE)) return body;
        com.fasterxml.jackson.databind.JsonNode session;
        try {
            session = body.length == 0 ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (IOException notJson) {
            return body;
        }
        if (!(session instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) return body;
        com.fasterxml.jackson.databind.JsonNode node = object.get("Node");
        if (node != null && !node.isNull() && !(node.isTextual() && node.asText().isEmpty())) return body;
        object.put("Node", nodeName);
        try {
            return mapper.writeValueAsBytes(object);
        } catch (IOException unwritable) {
            throw new IllegalStateException("A session could not be written", unwritable);
        }
    }

    private void forward(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(java.util.Locale.ROOT);
        if (!METHODS.contains(method)) {
            api.error(exchange, 405, "method_not_allowed", "Method not allowed", false, Map.of());
            return;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String header : SCOPE_HEADERS) {
            String value = exchange.getRequestHeaders().getFirst(header);
            if (value == null) continue;
            if (!sendable(value)) {
                api.error(exchange, 400, "invalid_scope", header + " holds a character that cannot be sent on", false,
                        Map.of());
                return;
            }
            headers.put(header, value);
        }
        try {
            acl.forwarding(exchange).ifPresent(token -> headers.put("X-Qraft-Token", token));
        } catch (IllegalArgumentException unsendable) {
            api.error(exchange, 400, "invalid_token", unsendable.getMessage(), false, Map.of());
            return;
        }
        String requestId = exchange.getResponseHeaders().getFirst(JsonApi.REQUEST_ID_HEADER);
        if (requestId == null || !sendable(requestId)) {
            api.error(exchange, 400, "invalid_request_id",
                    JsonApi.REQUEST_ID_HEADER + " holds a character that cannot be sent on", false, Map.of());
            return;
        }
        byte[] body = sendable(method, exchange.getRequestURI().getPath(), exchange.getRequestBody().readAllBytes());
        String query = exchange.getRequestURI().getRawQuery();
        String path = exchange.getRequestURI().getRawPath() + (query == null ? "" : "?" + query);
        Optional<ForwardedResponse> answer = forwarder.forward(method, path, body, requestId, headers).join();
        if (answer.isEmpty()) {
            api.error(exchange, 503, "no_server_reachable", "No server could be reached", true, Map.of());
            return;
        }
        passOn(exchange, answer.get());
    }

    /** The listener takes control characters in a header value, which the client sending it on refuses. */
    private static boolean sendable(String value) {
        return value.chars().allMatch(character -> character >= 0x20 && character < 0x7f);
    }

    private static void passOn(HttpExchange exchange, ForwardedResponse answer) throws IOException {
        answer.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        byte[] body = answer.body();
        exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}

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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import dev.mars.qraft.common.acl.AclAccess;
import dev.mars.qraft.common.acl.AclRequest;
import dev.mars.qraft.common.acl.AclResource;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.server.acl.AclPrincipal;
import dev.mars.qraft.state.tenancy.Namespace;
import dev.mars.qraft.state.tenancy.TenancyCommand;
import dev.mars.qraft.state.tenancy.TenancyState;
import dev.mars.qraft.state.tenancy.Tenant;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

/**
 * The tenancy API on the server (tenancy list, Phase 3), on Consul's partition and namespace APIs:
 *
 * <ul>
 *   <li>{@code PUT /v1/tenant} with {@code Name} and {@code Description} creates a tenant, with its {@code default}
 *       namespace; {@code GET}, {@code PUT} (its description) and {@code DELETE /v1/tenant/{name}}; and
 *       {@code GET /v1/tenants} lists them;</li>
 *   <li>{@code PUT /v1/namespace} with {@code Name}, {@code Description} and {@code Tenant}, which is otherwise the
 *       request's {@code X-Qraft-Tenant} header, else {@code default}; {@code GET}, {@code PUT} and
 *       {@code DELETE /v1/namespace/{name}} in the header's tenant; and {@code GET /v1/namespaces} lists that tenant's.</li>
 * </ul>
 *
 * <p>Deleting a namespace removes its services, checks and keys, and deleting a tenant its namespaces (decision 5).
 * Reads take the consistency list's options. With ACLs enabled a change needs {@code operator:write} and a read
 * {@code operator:read}, as Consul's partition and namespace management does.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
final class TenancyApiRoutes {
    static final List<String> CONTEXTS = List.of("/v1/tenant", "/v1/tenants", "/v1/namespace", "/v1/namespaces");
    private static final AclRequest OPERATOR_READ = AclRequest.single(AclResource.OPERATOR, AclAccess.READ);
    private static final AclRequest OPERATOR_WRITE = AclRequest.single(AclResource.OPERATOR, AclAccess.WRITE);

    private enum Kind { TENANT, NAMESPACE }

    /** What a path names: a kind, and a name, or a listing, or neither for a creation. */
    private record Route(Kind kind, String name, boolean list) { }

    private final HttpApiServer server;

    TenancyApiRoutes(HttpApiServer server) {
        this.server = server;
    }

    void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Route route = route(path);
        if (route == null) {
            server.respondError(exchange, 404, "not_found", "No such route: " + path, false);
            return;
        }
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        Set<String> allowed = route.list() ? Set.of("GET") : route.name() == null ? Set.of("PUT")
                : Set.of("GET", "PUT", "DELETE");
        if (!allowed.contains(method)) {
            server.respondError(exchange, 405, "method_not_allowed", "Method not allowed", false);
            return;
        }
        if (!server.prepareStateRequest(exchange, method)) return;
        boolean read = method.equals("GET");
        if (read) {
            if (server.refusedForItsQuery(exchange)) return;
        } else {
            String query = exchange.getRequestURI().getRawQuery();
            if (query != null && !query.isEmpty()) {
                server.respondError(exchange, 400, "invalid_query", "A tenancy change takes no query", false);
                return;
            }
        }
        AclPrincipal principal = server.principal(exchange);
        if (principal == null) return;
        if (server.refused(exchange, principal, read ? OPERATOR_READ : OPERATOR_WRITE)) return;
        String headerTenant;
        try {
            headerTenant = TokenRequestContext.optionalScope(exchange, TokenRequestContext.TENANT_HEADER);
        } catch (IllegalArgumentException invalid) {
            server.respondError(exchange, 400, "invalid_scope", HttpApiServer.safeMessage(invalid), false);
            return;
        }
        try {
            if (route.kind() == Kind.TENANT) {
                tenant(exchange, method, route);
            } else {
                namespace(exchange, method, route, headerTenant);
            }
        } catch (JsonProcessingException notJson) {
            server.respondError(exchange, 400, "invalid_json", "The body is not JSON: " + notJson.getOriginalMessage(),
                    false);
        } catch (IllegalArgumentException invalid) {
            server.respondError(exchange, 400, "invalid_request", HttpApiServer.safeMessage(invalid), false);
        }
    }

    private static Route route(String path) {
        return switch (path) {
            case "/v1/tenants" -> new Route(Kind.TENANT, null, true);
            case "/v1/namespaces" -> new Route(Kind.NAMESPACE, null, true);
            case "/v1/tenant" -> new Route(Kind.TENANT, null, false);
            case "/v1/namespace" -> new Route(Kind.NAMESPACE, null, false);
            default -> {
                for (Kind kind : Kind.values()) {
                    String prefix = "/v1/" + kind.name().toLowerCase(Locale.ROOT) + "/";
                    if (!path.startsWith(prefix)) continue;
                    String name = path.substring(prefix.length());
                    yield name.isEmpty() || name.contains("/") ? null : new Route(kind, name, false);
                }
                yield null;
            }
        };
    }

    private TenancyState tenancy() {
        return server.stateStore.getTenancy();
    }

    private void tenant(HttpExchange exchange, String method, Route route) throws IOException {
        if (route.list()) {
            if (!readable(exchange)) return;
            server.respondJson(exchange, 200, tenancy().tenants().stream().map(TenancyApiRoutes::answer).toList());
            return;
        }
        if (route.name() == null) {
            JsonNode body = body(exchange);
            String name = text(body, "Name");
            if (submitted(exchange, TenancyCommand.createTenant(name, text(body, "Description")))) {
                server.respondJson(exchange, 200, answer(tenancy().tenant(name).orElseThrow()));
            }
            return;
        }
        switch (method) {
            case "GET" -> {
                if (!readable(exchange)) return;
                var tenant = tenancy().tenant(route.name());
                if (tenant.isEmpty()) {
                    server.respondError(exchange, 404, "tenant_not_found", "No such tenant: " + route.name(), false);
                } else {
                    server.respondJson(exchange, 200, answer(tenant.get()));
                }
            }
            case "PUT" -> {
                JsonNode body = body(exchange);
                if (submitted(exchange, TenancyCommand.updateTenant(route.name(), text(body, "Description")))) {
                    server.respondJson(exchange, 200, answer(tenancy().tenant(route.name()).orElseThrow()));
                }
            }
            default -> {
                if (submitted(exchange, TenancyCommand.deleteTenant(route.name()))) server.respondJson(exchange, 200, true);
            }
        }
    }

    private void namespace(HttpExchange exchange, String method, Route route, String headerTenant) throws IOException {
        if (route.list()) {
            if (!readable(exchange)) return;
            if (!tenancy().tenantExists(headerTenant)) {
                server.respondError(exchange, 404, "tenant_not_found", "No such tenant: " + headerTenant, false);
                return;
            }
            server.respondJson(exchange, 200, tenancy().namespaces(headerTenant).stream()
                    .map(TenancyApiRoutes::answer).toList());
            return;
        }
        if (route.name() == null) {
            JsonNode body = body(exchange);
            String tenant = text(body, "Tenant");
            String inTenant = tenant.isEmpty() ? headerTenant : tenant;
            String name = text(body, "Name");
            if (submitted(exchange, TenancyCommand.createNamespace(inTenant, name, text(body, "Description")))) {
                server.respondJson(exchange, 200, answer(tenancy().namespace(inTenant, name).orElseThrow()));
            }
            return;
        }
        switch (method) {
            case "GET" -> {
                if (!readable(exchange)) return;
                var namespace = tenancy().namespace(headerTenant, route.name());
                if (namespace.isEmpty()) {
                    server.respondError(exchange, 404, tenancy().tenantExists(headerTenant) ? "namespace_not_found"
                            : "tenant_not_found", "No such namespace: " + route.name() + " in tenant " + headerTenant,
                            false);
                } else {
                    server.respondJson(exchange, 200, answer(namespace.get()));
                }
            }
            case "PUT" -> {
                JsonNode body = body(exchange);
                if (submitted(exchange, TenancyCommand.updateNamespace(headerTenant, route.name(),
                        text(body, "Description")))) {
                    server.respondJson(exchange, 200, answer(tenancy().namespace(headerTenant, route.name())
                            .orElseThrow()));
                }
            }
            default -> {
                if (submitted(exchange, TenancyCommand.deleteNamespace(headerTenant, route.name()))) {
                    server.respondJson(exchange, 200, true);
                }
            }
        }
    }

    /** Whether the read may be answered, as its consistency mode asks; with the applied index set. */
    private boolean readable(HttpExchange exchange) throws IOException {
        if (!server.readyToRead(exchange, HttpApiServer.ReadIndex.APPLIED)) return false;
        server.setAppliedIndex(exchange);
        return true;
    }

    /** Whether {@code command} was applied; otherwise its refusal, or the servers' unavailability, is answered. */
    private boolean submitted(HttpExchange exchange, TenancyCommand command) throws IOException {
        RaftCommandResult<?> result;
        try {
            result = server.submit(command);
        } catch (CompletionException unavailable) {
            server.respondUnavailable(exchange, unavailable);
            return false;
        }
        if (result instanceof RaftCommandResult.Success<?>) return true;
        if (result instanceof RaftCommandResult.Refused<?> refused) {
            int status = switch (refused.code()) {
                case "tenant_exists", "namespace_exists" -> 409;
                case "tenant_not_found", "namespace_not_found" -> 404;
                default -> 400;
            };
            server.respondError(exchange, status, refused.code(), refused.message(), false);
            return false;
        }
        server.respondError(exchange, 409, "tenancy_rejected", "The replicated tenancy refused the change: " + result,
                false);
        return false;
    }

    private JsonNode body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        JsonNode body = bytes.length == 0 ? server.objectMapper.createObjectNode() : server.objectMapper.readTree(bytes);
        if (body == null || !body.isObject()) throw new IllegalArgumentException("The body must be a JSON object");
        return body;
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be a string");
        return value.asText();
    }

    private static Map<String, Object> answer(Tenant tenant) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("Name", tenant.name());
        answer.put("Description", tenant.description());
        answer.put("CreateIndex", tenant.createIndex());
        answer.put("ModifyIndex", tenant.modifyIndex());
        return answer;
    }

    private static Map<String, Object> answer(Namespace namespace) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("Name", namespace.name());
        answer.put("Tenant", namespace.tenant());
        answer.put("Description", namespace.description());
        answer.put("CreateIndex", namespace.createIndex());
        answer.put("ModifyIndex", namespace.modifyIndex());
        return answer;
    }
}

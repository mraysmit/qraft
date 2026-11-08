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
import dev.mars.qraft.server.acl.JwtKeys;
import dev.mars.qraft.state.acl.AuthCommand;
import dev.mars.qraft.state.acl.AuthMethod;
import dev.mars.qraft.state.acl.AuthState;
import dev.mars.qraft.state.acl.BindType;
import dev.mars.qraft.state.acl.BindingRule;
import dev.mars.qraft.state.acl.JwtConfig;
import dev.mars.qraft.state.acl.Selector;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * Consul's auth method and binding rule API on the server (authentication list, Phase 1): {@code PUT
 * /v1/acl/auth-method} creates a method and {@code GET}, {@code PUT} and {@code DELETE /v1/acl/auth-method/{name}} read,
 * update and delete one, with {@code GET /v1/acl/auth-methods}; {@code PUT /v1/acl/binding-rule} creates a rule with a
 * new ID, and {@code /v1/acl/binding-rule/{id}} and {@code GET /v1/acl/binding-rules}, by {@code authmethod}, do the rest.
 * A method's keys are parsed and a rule's selector read before either is proposed, and the state machine checks again.
 * With ACLs a change needs {@code acl:write} and a read {@code acl:read}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
final class AuthApiRoutes {
    static final List<String> CONTEXTS = List.of("/v1/acl/auth-method", "/v1/acl/auth-methods",
            "/v1/acl/binding-rule", "/v1/acl/binding-rules", "/v1/acl/login", "/v1/acl/logout");
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AuthApiRoutes.class);
    private static final java.util.regex.Pattern BIND_VALUE = java.util.regex.Pattern.compile(
            "\\$\\{value\\.([A-Za-z0-9_]+)}");
    private static final AclRequest ACL_READ = AclRequest.single(AclResource.ACL, AclAccess.READ);
    private static final AclRequest ACL_WRITE = AclRequest.single(AclResource.ACL, AclAccess.WRITE);

    private final HttpApiServer server;

    AuthApiRoutes(HttpApiServer server) {
        this.server = server;
    }

    void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if (path.equals("/v1/acl/login") || path.equals("/v1/acl/logout")) {
            if (!server.prepareStateRequest(exchange, "POST")) return;
            try {
                if (path.equals("/v1/acl/login")) login(exchange);
                else logout(exchange);
            } catch (JsonProcessingException notJson) {
                server.respondError(exchange, 400, "invalid_json", "The body is not JSON: " + notJson.getOriginalMessage(),
                        false);
            }
            return;
        }
        boolean rules = path.startsWith("/v1/acl/binding-rule");
        String collection = rules ? "/v1/acl/binding-rule" : "/v1/acl/auth-method";
        String name = path.startsWith(collection + "/") ? path.substring(collection.length() + 1) : null;
        boolean list = path.equals(collection + "s");
        if (!list && !path.equals(collection) && (name == null || name.isEmpty() || name.contains("/"))) {
            server.respondError(exchange, 404, "not_found", "No such route: " + path, false);
            return;
        }
        boolean allowed = list ? method.equals("GET") : name == null ? method.equals("PUT")
                : method.equals("GET") || method.equals("PUT") || method.equals("DELETE");
        if (!allowed) {
            server.respondError(exchange, 405, "method_not_allowed", "Method not allowed", false);
            return;
        }
        if (!server.prepareStateRequest(exchange, method)) return;
        AclPrincipal principal = server.principal(exchange);
        if (principal == null) return;
        boolean read = method.equals("GET");
        if (server.refused(exchange, principal, read ? ACL_READ : ACL_WRITE)) return;
        try {
            if (rules) {
                rules(exchange, method, name, list);
            } else {
                methods(exchange, method, name, list);
            }
        } catch (JsonProcessingException notJson) {
            server.respondError(exchange, 400, "invalid_json", "The body is not JSON: " + notJson.getOriginalMessage(),
                    false);
        }
    }

    private AuthState auth() {
        return server.stateStore.getAuth();
    }

    /**
     * Exchanges a bearer JWT for a token (decisions 2 and 3), as Consul's {@code POST /v1/acl/login}: it needs no token
     * of its own; the JWT must be valid for the method; every rule whose selector its mapped claims match binds the token,
     * a role or policy by name and a service or node identity by its interpolated name, a missing role or policy being
     * left out; and a login that binds nothing is refused, as Consul refuses it.
     */
    private void login(HttpExchange exchange) throws IOException {
        JsonNode body = body(exchange);
        String methodName = text(body, "AuthMethod");
        var found = auth().method(methodName);
        if (found.isEmpty()) {
            server.respondError(exchange, 404, "auth_method_not_found", "No auth method " + methodName, false);
            return;
        }
        dev.mars.qraft.server.acl.JwtValidator.Mapped mapped;
        try {
            mapped = dev.mars.qraft.server.acl.JwtValidator.validate(text(body, "BearerToken"), found.get(),
                    server.clock);
        } catch (IllegalArgumentException invalid) {
            LOG.info("Login through {} refused: {}", methodName, HttpApiServer.safeMessage(invalid));
            server.respondError(exchange, 403, "permission_denied", "Permission denied: the bearer token is not "
                    + "valid for auth method " + methodName, false);
            return;
        }
        var acl = server.stateStore.getAcl();
        List<String> policyIds = new ArrayList<>();
        List<String> roleIds = new ArrayList<>();
        List<dev.mars.qraft.common.acl.ServiceIdentity> services = new ArrayList<>();
        List<dev.mars.qraft.common.acl.NodeIdentity> nodes = new ArrayList<>();
        for (BindingRule rule : auth().rulesOf(methodName)) {
            if (!Selector.parse(rule.selector()).matches(mapped.values(), mapped.lists())) continue;
            String name = bound(rule.bindName(), mapped.values());
            if (name == null) continue;
            switch (rule.bindType()) {
                case SERVICE -> services.add(new dev.mars.qraft.common.acl.ServiceIdentity(name, "default", "default"));
                case NODE -> nodes.add(new dev.mars.qraft.common.acl.NodeIdentity(name));
                case ROLE -> acl.roleByName(name).ifPresent(role -> {
                    if (!roleIds.contains(role.id())) roleIds.add(role.id());
                });
                case POLICY -> acl.policyByName(name).ifPresent(policy -> {
                    if (!policyIds.contains(policy.id())) policyIds.add(policy.id());
                });
            }
        }
        if (policyIds.isEmpty() && roleIds.isEmpty() && services.isEmpty() && nodes.isEmpty()) {
            LOG.info("Login through {} refused: no binding rule matched", methodName);
            server.respondError(exchange, 403, "permission_denied", "Permission denied: no binding rule of auth method "
                    + methodName + " matched", false);
            return;
        }
        java.time.Instant now = server.clock.instant();
        String secret = UUID.randomUUID().toString();
        dev.mars.qraft.state.acl.AclToken token = new dev.mars.qraft.state.acl.AclToken(UUID.randomUUID().toString(),
                dev.mars.qraft.state.acl.AclSecrets.hash(secret), "token created via login: " + methodName, policyIds,
                roleIds, nodes, services, now, now.plus(found.get().maxTokenTtl()), false);
        RaftCommandResult<?> result;
        try {
            result = server.submit(AuthCommand.login(token, methodName));
        } catch (CompletionException unavailable) {
            server.respondUnavailable(exchange, unavailable);
            return;
        }
        if (!(result instanceof RaftCommandResult.Success<?>)) {
            server.respondError(exchange, 409, "login_rejected", "The replicated ACL state refused the login token: "
                    + result, false);
            return;
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("AccessorID", token.accessorId());
        answer.put("SecretID", secret);
        answer.put("Description", token.description());
        answer.put("Policies", policyIds.stream().map(id -> Map.of("ID", id, "Name",
                acl.policy(id).map(dev.mars.qraft.state.acl.AclPolicy::name).orElse(""))).toList());
        answer.put("Roles", roleIds.stream().map(id -> Map.of("ID", id, "Name",
                acl.role(id).map(dev.mars.qraft.state.acl.AclRole::name).orElse(""))).toList());
        answer.put("ServiceIdentities", services.stream().map(identity -> Map.of("ServiceName",
                identity.serviceName())).toList());
        answer.put("NodeIdentities", nodes.stream().map(identity -> Map.of("NodeName", identity.nodeName())).toList());
        answer.put("AuthMethod", methodName);
        answer.put("CreateTime", now.toString());
        answer.put("ExpirationTime", token.expirationTime().toString());
        server.respondJson(exchange, 200, answer);
    }

    /** Deletes the login token the request is made with, as Consul's {@code POST /v1/acl/logout}; only a login's. */
    private void logout(HttpExchange exchange) throws IOException {
        String secret = HttpApiServer.presentedToken(exchange);
        var token = secret == null ? java.util.Optional.<dev.mars.qraft.state.acl.AclToken>empty()
                : server.stateStore.getAcl().tokenBySecret(secret);
        if (token.isEmpty()) {
            server.respondError(exchange, 403, "token_not_found", "ACL not found", false);
            return;
        }
        if (auth().loginMethodOf(token.get().accessorId()).isEmpty()) {
            server.respondError(exchange, 403, "permission_denied", "Permission denied: only a token created by a "
                    + "login is logged out", false);
            return;
        }
        if (submitted(exchange, AuthCommand.logout(token.get().accessorId()))) server.respondJson(exchange, 200, true);
    }

    /** {@code bindName} with each {@code ${value.<name>}} filled in, or {@code null} when a value is missing. */
    private static String bound(String bindName, Map<String, String> values) {
        java.util.regex.Matcher matcher = BIND_VALUE.matcher(bindName);
        StringBuilder bound = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) return null;
            matcher.appendReplacement(bound, java.util.regex.Matcher.quoteReplacement(value));
        }
        matcher.appendTail(bound);
        return bound.toString();
    }

    private void methods(HttpExchange exchange, String method, String name, boolean list) throws IOException {
        if (list) {
            server.setAppliedIndex(exchange);
            server.respondJson(exchange, 200, auth().methods().stream().map(AuthApiRoutes::answer).toList());
            return;
        }
        if (method.equals("GET")) {
            var found = auth().method(name);
            if (found.isEmpty()) {
                server.respondError(exchange, 404, "auth_method_not_found", "No auth method " + name, false);
            } else {
                server.respondJson(exchange, 200, answer(found.get()));
            }
            return;
        }
        if (method.equals("DELETE")) {
            if (submitted(exchange, AuthCommand.deleteMethod(name))) server.respondJson(exchange, 200, true);
            return;
        }
        AuthMethod parsed;
        try {
            parsed = method(body(exchange), name);
        } catch (IllegalArgumentException invalid) {
            server.respondError(exchange, 400, "invalid_auth_method", HttpApiServer.safeMessage(invalid), false);
            return;
        }
        AuthCommand command = name == null ? AuthCommand.createMethod(parsed) : AuthCommand.updateMethod(parsed);
        if (submitted(exchange, command)) server.respondJson(exchange, 200, answer(auth().method(parsed.name()).orElseThrow()));
    }

    private void rules(HttpExchange exchange, String method, String id, boolean list) throws IOException {
        if (list) {
            String query = exchange.getRequestURI().getQuery();
            String only = query != null && query.startsWith("authmethod=") ? query.substring("authmethod=".length()) : null;
            server.setAppliedIndex(exchange);
            server.respondJson(exchange, 200, (only == null ? auth().rules() : auth().rulesOf(only)).stream()
                    .map(AuthApiRoutes::answer).toList());
            return;
        }
        if (method.equals("GET")) {
            var found = auth().rule(id);
            if (found.isEmpty()) {
                server.respondError(exchange, 404, "binding_rule_not_found", "No binding rule " + id, false);
            } else {
                server.respondJson(exchange, 200, answer(found.get()));
            }
            return;
        }
        if (method.equals("DELETE")) {
            if (submitted(exchange, AuthCommand.deleteBindingRule(id))) server.respondJson(exchange, 200, true);
            return;
        }
        BindingRule parsed;
        try {
            parsed = rule(body(exchange), id == null ? UUID.randomUUID().toString() : id);
        } catch (IllegalArgumentException invalid) {
            server.respondError(exchange, 400, "invalid_binding_rule", HttpApiServer.safeMessage(invalid), false);
            return;
        }
        AuthCommand command = id == null ? AuthCommand.createBindingRule(parsed) : AuthCommand.updateBindingRule(parsed);
        if (submitted(exchange, command)) server.respondJson(exchange, 200, answer(auth().rule(parsed.id()).orElseThrow()));
    }

    private boolean submitted(HttpExchange exchange, AuthCommand command) throws IOException {
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
                case "auth_method_exists", "binding_rule_exists" -> 409;
                case "auth_method_not_found", "binding_rule_not_found" -> 404;
                default -> 400;
            };
            server.respondError(exchange, status, refused.code(), refused.message(), false);
            return false;
        }
        server.respondError(exchange, 409, "auth_rejected", "The replicated ACL state refused the change: " + result,
                false);
        return false;
    }

    private JsonNode body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        JsonNode body = bytes.length == 0 ? server.objectMapper.createObjectNode() : server.objectMapper.readTree(bytes);
        if (body == null || !body.isObject()) throw new IllegalArgumentException("The body must be a JSON object");
        return body;
    }

    /** A method as Consul's API writes one, its keys parsed; the path's name wins over the body's. */
    private static AuthMethod method(JsonNode body, String pathName) {
        String name = pathName != null ? pathName : text(body, "Name");
        if (name.isBlank()) throw new IllegalArgumentException("An auth method needs a Name");
        String type = text(body, "Type");
        if (!"jwt".equals(type)) throw new IllegalArgumentException("Only the jwt auth method type is supported: " + type);
        JsonNode config = body.path("Config");
        List<String> keys = strings(config, "JWTValidationPubKeys");
        if (keys.isEmpty()) throw new IllegalArgumentException("A jwt auth method needs JWTValidationPubKeys");
        JwtKeys.parse(keys);
        String ttl = text(body, "MaxTokenTTL");
        AuthMethod method = new AuthMethod(name, type, text(body, "Description"),
                ttl.isEmpty() ? AuthMethod.DEFAULT_MAX_TOKEN_TTL : AclApiRoutes.duration(ttl),
                new JwtConfig(keys, text(config, "BoundIssuer"), strings(config, "BoundAudiences"),
                        map(config, "ClaimMappings"), map(config, "ListClaimMappings"), duration(config, "ExpirationLeeway"),
                        duration(config, "NotBeforeLeeway"), duration(config, "ClockSkewLeeway")), 0, 0);
        if (AuthState.refusalOf(method) != null) {
            throw new IllegalArgumentException("MaxTokenTTL must be between 1m and 24h: " + ttl);
        }
        return method;
    }

    private static BindingRule rule(JsonNode body, String id) {
        String selector = text(body, "Selector");
        Selector.parse(selector);
        String bindName = text(body, "BindName");
        if (bindName.isBlank()) throw new IllegalArgumentException("A binding rule needs a BindName");
        return new BindingRule(id, text(body, "Description"), text(body, "AuthMethod"), selector,
                BindType.ofApiName(text(body, "BindType")), bindName, 0, 0);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be a string");
        return value.asText();
    }

    private static List<String> strings(JsonNode node, String field) {
        JsonNode value = node.get(field);
        List<String> strings = new ArrayList<>();
        if (value == null || value.isNull()) return strings;
        if (!value.isArray()) throw new IllegalArgumentException(field + " must be a list of strings");
        value.forEach(item -> strings.add(item.asText()));
        return strings;
    }

    private static Map<String, String> map(JsonNode node, String field) {
        JsonNode value = node.get(field);
        Map<String, String> map = new LinkedHashMap<>();
        if (value == null || value.isNull()) return map;
        if (!value.isObject()) throw new IllegalArgumentException(field + " must map claims to names");
        value.properties().forEach(entry -> map.put(entry.getKey(), entry.getValue().asText()));
        return map;
    }

    private static Duration duration(JsonNode node, String field) {
        String text = text(node, field);
        return text.isEmpty() || text.equals("0") ? Duration.ZERO : AclApiRoutes.duration(text);
    }

    static Map<String, Object> answer(AuthMethod method) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("JWTValidationPubKeys", method.config().publicKeysPem());
        config.put("BoundIssuer", method.config().boundIssuer());
        config.put("BoundAudiences", method.config().boundAudiences());
        config.put("ClaimMappings", method.config().claimMappings());
        config.put("ListClaimMappings", method.config().listClaimMappings());
        config.put("ExpirationLeeway", SessionApiRoutes.goDuration(method.config().expirationLeeway()));
        config.put("NotBeforeLeeway", SessionApiRoutes.goDuration(method.config().notBeforeLeeway()));
        config.put("ClockSkewLeeway", SessionApiRoutes.goDuration(method.config().clockSkewLeeway()));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("Name", method.name());
        answer.put("Type", method.type());
        answer.put("Description", method.description());
        answer.put("MaxTokenTTL", SessionApiRoutes.goDuration(method.maxTokenTtl()));
        answer.put("Config", config);
        answer.put("CreateIndex", method.createIndex());
        answer.put("ModifyIndex", method.modifyIndex());
        return answer;
    }

    static Map<String, Object> answer(BindingRule rule) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("ID", rule.id());
        answer.put("Description", rule.description());
        answer.put("AuthMethod", rule.authMethod());
        answer.put("Selector", rule.selector());
        answer.put("BindType", rule.bindType().apiName());
        answer.put("BindName", rule.bindName());
        answer.put("CreateIndex", rule.createIndex());
        answer.put("ModifyIndex", rule.modifyIndex());
        return answer;
    }
}

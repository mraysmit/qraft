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

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import dev.mars.qraft.common.acl.AclAccess;
import dev.mars.qraft.common.acl.AclAuthorizer;
import dev.mars.qraft.common.acl.AclDecision;
import dev.mars.qraft.common.acl.AclOrigin;
import dev.mars.qraft.common.acl.AclRequest;
import dev.mars.qraft.common.acl.AclResource;
import dev.mars.qraft.common.acl.AclRule;
import dev.mars.qraft.common.acl.AclRules;
import dev.mars.qraft.common.acl.AclRulesJson;
import dev.mars.qraft.common.acl.AclScope;
import dev.mars.qraft.common.acl.NodeIdentity;
import dev.mars.qraft.common.acl.ServiceIdentity;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.server.acl.AclPrincipal;
import dev.mars.qraft.state.AclCommand;
import dev.mars.qraft.state.acl.AclPolicy;
import dev.mars.qraft.state.acl.AclRefusal;
import dev.mars.qraft.state.acl.AclRole;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;
import dev.mars.qraft.state.acl.AclView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ACL API of a server (ACL list, Phase 5), after Consul's {@code agent/acl_endpoint.go} in camel case:
 *
 * <ul>
 *   <li>{@code PUT /v1/acl/token}, {@code GET /v1/acl/tokens} with {@code policy}, {@code role} and
 *       {@code servicename}, {@code GET}, {@code PUT} and {@code DELETE /v1/acl/token/{accessorId}},
 *       {@code PUT /v1/acl/token/{accessorId}/clone} and {@code GET /v1/acl/token/self};</li>
 *   <li>{@code PUT /v1/acl/policy}, {@code GET /v1/acl/policies}, {@code GET}, {@code PUT} and
 *       {@code DELETE /v1/acl/policy/{id}} and {@code GET /v1/acl/policy/name/{name}}, and the same for roles, whose
 *       list takes {@code policy};</li>
 *   <li>{@code POST /v1/internal/acl/authorize} and {@code POST /v1/internal/acl/resolve}, which answer only for the
 *       presenting token and need no grant, as {@code self} does; and {@code POST /v1/acl/explain}, which needs
 *       {@code acl:read} and decides through the same authorizer that enforcement uses (proposal 19).</li>
 * </ul>
 *
 * <p>Every other route needs {@code acl:read} to read or {@code acl:write} to change. A secret is answered only by the
 * create or clone that made it; every later answer shows {@code <hidden>}, and none shows the hash (proposal 3). The
 * leader stamps a token's creation time, and a link may name a policy or role by ID or by name, resolved here. With
 * ACLs disabled every route answers 401 {@code acl_disabled}. A refusal of the state machine keeps its code: 409 for
 * a conflict, including {@code acl_lockout} (proposal 17), and 400 for anything else. A body is never logged, since
 * it may hold a secret.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
final class AclApiRoutes {
    /** The paths this class serves; each is a context of the server's HTTP listener. */
    static final List<String> CONTEXTS = List.of("/v1/acl/token", "/v1/acl/tokens", "/v1/acl/policy",
            "/v1/acl/policies", "/v1/acl/role", "/v1/acl/roles", "/v1/acl/explain", "/v1/internal/acl/authorize",
            "/v1/internal/acl/resolve");
    /** What every answer after the first shows for a secret. */
    static final String HIDDEN = "<hidden>";
    /** As many checks as one authorize call takes, as Consul's {@code maxAuthorizationRequests}. */
    static final int MAX_AUTHORIZATIONS = 64;

    private static final Logger LOG = LoggerFactory.getLogger(AclApiRoutes.class);
    private static final AclRequest ACL_READ = AclRequest.single(AclResource.ACL, AclAccess.READ);
    private static final AclRequest ACL_WRITE = AclRequest.single(AclResource.ACL, AclAccess.WRITE);
    private static final Set<String> CONFLICTS = Set.of(AclRefusal.ACCESSOR_IN_USE.code(),
            AclRefusal.SECRET_IN_USE.code(), AclRefusal.ID_IN_USE.code(), AclRefusal.NAME_IN_USE.code(),
            AclRefusal.ACL_LOCKOUT.code(), AclRefusal.ACL_BOOTSTRAPPED.code(), AclRefusal.INVALID_RESET_INDEX.code());
    // ms is tried before m, or 1500ms would be read as 1500m and a stray s.
    private static final Pattern GO_DURATION = Pattern.compile("(\\d+)(ms|h|m|s)");

    /** Who may call a route: the presenting token alone, or a token holding {@code acl:read} or {@code acl:write}. */
    private enum Grant { ANY, READ, WRITE }

    @FunctionalInterface
    private interface Action {
        void run(HttpExchange exchange, AclPrincipal principal, String parameter) throws IOException;
    }

    private record Route(String method, Pattern path, Grant grant, Set<String> query, Action action) {
    }

    private final HttpApiServer server;
    private final List<Route> routes;

    AclApiRoutes(HttpApiServer server) {
        this.server = server;
        String id = "/([^/]+)";
        routes = List.of(
                route("PUT", "/v1/acl/token", Grant.WRITE, Set.of(), (e, p, x) -> createToken(e)),
                route("GET", "/v1/acl/tokens", Grant.READ, Set.of("policy", "role", "servicename"),
                        (e, p, x) -> listTokens(e)),
                route("GET", "/v1/acl/token/self", Grant.ANY, Set.of(), (e, p, x) -> self(e, p)),
                route("PUT", "/v1/acl/token" + id + "/clone", Grant.WRITE, Set.of(), (e, p, x) -> cloneToken(e, x)),
                route("GET", "/v1/acl/token" + id, Grant.READ, Set.of(), (e, p, x) -> readToken(e, x)),
                route("PUT", "/v1/acl/token" + id, Grant.WRITE, Set.of(), (e, p, x) -> updateToken(e, x)),
                route("DELETE", "/v1/acl/token" + id, Grant.WRITE, Set.of(), this::deleteToken),
                route("PUT", "/v1/acl/policy", Grant.WRITE, Set.of(), (e, p, x) -> savePolicy(e, null)),
                route("GET", "/v1/acl/policies", Grant.READ, Set.of(), (e, p, x) -> listPolicies(e)),
                route("GET", "/v1/acl/policy/name/(.+)", Grant.READ, Set.of(),
                        (e, p, x) -> answerOrMissing(e, view().policyByName(x), this::policyAnswer)),
                route("GET", "/v1/acl/policy" + id, Grant.READ, Set.of(),
                        (e, p, x) -> answerOrMissing(e, view().policy(x), this::policyAnswer)),
                route("PUT", "/v1/acl/policy" + id, Grant.WRITE, Set.of(), (e, p, x) -> savePolicy(e, x)),
                route("DELETE", "/v1/acl/policy" + id, Grant.WRITE, Set.of(),
                        (e, p, x) -> delete(e, AclCommand.deletePolicy(canonicalOrMissing(x)), "policy", x)),
                route("PUT", "/v1/acl/role", Grant.WRITE, Set.of(), (e, p, x) -> saveRole(e, null)),
                route("GET", "/v1/acl/roles", Grant.READ, Set.of("policy"), (e, p, x) -> listRoles(e)),
                route("GET", "/v1/acl/role/name/(.+)", Grant.READ, Set.of(),
                        (e, p, x) -> answerOrMissing(e, view().roleByName(x), this::roleAnswer)),
                route("GET", "/v1/acl/role" + id, Grant.READ, Set.of(),
                        (e, p, x) -> answerOrMissing(e, view().role(x), this::roleAnswer)),
                route("PUT", "/v1/acl/role" + id, Grant.WRITE, Set.of(), (e, p, x) -> saveRole(e, x)),
                route("DELETE", "/v1/acl/role" + id, Grant.WRITE, Set.of(),
                        (e, p, x) -> delete(e, AclCommand.deleteRole(canonicalOrMissing(x)), "role", x)),
                route("POST", "/v1/acl/explain", Grant.READ, Set.of(), (e, p, x) -> explain(e)),
                route("POST", "/v1/internal/acl/authorize", Grant.ANY, Set.of(), (e, p, x) -> authorize(e, p)),
                route("POST", "/v1/internal/acl/resolve", Grant.ANY, Set.of(), (e, p, x) -> resolve(e, p)));
    }

    private static Route route(String method, String path, Grant grant, Set<String> query, Action action) {
        return new Route(method, Pattern.compile(path), grant, query, action);
    }

    void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        List<Route> matching = routes.stream().filter(route -> route.path().matcher(path).matches()).toList();
        if (matching.isEmpty()) {
            server.respondError(exchange, 404, "not_found", "No such ACL route: " + path, false);
            return;
        }
        Route route = matching.stream().filter(r -> r.method().equalsIgnoreCase(exchange.getRequestMethod()))
                .findFirst().orElse(null);
        if (!server.prepareStateRequest(exchange, route == null ? matching.getFirst().method() : route.method())) {
            return;
        }
        if (!server.acl.enabled()) {
            server.respondError(exchange, 401, "acl_disabled", "ACL support disabled", false);
            return;
        }
        if (refusedForItsQuery(exchange, route.query())) return;
        AclPrincipal principal = server.principal(exchange);
        if (principal == null) return;
        if (route.grant() == Grant.READ && server.refused(exchange, principal, ACL_READ)) return;
        if (route.grant() == Grant.WRITE && server.refused(exchange, principal, ACL_WRITE)) return;
        Matcher parameters = route.path().matcher(path);
        parameters.matches();
        String parameter = parameters.groupCount() > 0 ? parameters.group(1) : null;
        try {
            route.action().run(exchange, principal, parameter);
        } catch (JacksonException e) {
            // The original message names the problem without quoting the body, which may hold a secret.
            server.respondError(exchange, 400, invalidCode(route), e.getOriginalMessage(), false);
        } catch (IllegalArgumentException e) {
            server.respondError(exchange, 400, invalidCode(route), HttpApiServer.safeMessage(e), false);
        } catch (LinkNotFound e) {
            server.respondError(exchange, 400, e.code, e.getMessage(), false);
        } catch (CompletionException e) {
            server.respondUnavailable(exchange, e);
        }
    }

    private static String invalidCode(Route route) {
        String path = route.path().pattern();
        if (path.startsWith("/v1/acl/policy")) return "invalid_policy";
        if (path.startsWith("/v1/acl/role")) return "invalid_role";
        if (path.startsWith("/v1/acl/explain")) return "invalid_explanation";
        if (path.startsWith("/v1/internal/acl/authorize")) return "invalid_authorization";
        return "invalid_token";
    }

    // ---- Tokens ----

    private void createToken(HttpExchange exchange) throws IOException {
        TokenRequest request = read(exchange, TokenRequest.class);
        String accessor = request.accessorId() == null ? UUID.randomUUID().toString()
                : unreservedUuid(request.accessorId(), "accessorId");
        String secret = request.secretId() == null ? UUID.randomUUID().toString()
                : unreservedUuid(request.secretId(), "secretId");
        Instant now = server.clock.instant();
        AclToken token = new AclToken(accessor, AclSecrets.hash(secret), request.description(),
                policyIds(request.policies()), roleIds(request.roles()), nodeIdentities(request.nodeIdentities()),
                serviceIdentities(request.serviceIdentities()), now, expiry(request, now), false);
        change(exchange, AclCommand.createToken(token), () -> {
            LOG.info("ACL token created: accessor {}", token.accessorId());
            return AclTokenAnswer.of(token, secret, view());
        });
    }

    private void updateToken(HttpExchange exchange, String accessorId) throws IOException {
        TokenRequest request = read(exchange, TokenRequest.class);
        Optional<AclToken> found = view().token(accessorId);
        if (found.isEmpty()) {
            missing(exchange, "token", accessorId);
            return;
        }
        AclToken stored = found.get();
        if (request.accessorId() != null && !request.accessorId().equalsIgnoreCase(stored.accessorId())) {
            throw new IllegalArgumentException("accessorId " + request.accessorId() + " is not the token's: "
                    + stored.accessorId());
        }
        if (request.secretId() != null && !AclSecrets.hash(request.secretId()).equals(stored.secretHash())) {
            server.respondError(exchange, 400, AclRefusal.SECRET_IMMUTABLE.code(),
                    "Changing a token's secret is not permitted", false);
            return;
        }
        if (request.expirationTtl() != null || (request.expirationTime() != null
                && !instant(request.expirationTime()).equals(stored.expirationTime()))) {
            server.respondError(exchange, 400, AclRefusal.EXPIRY_IMMUTABLE.code(),
                    "Cannot change expiration time of " + stored.accessorId(), false);
            return;
        }
        AclToken token = new AclToken(stored.accessorId(), stored.secretHash(), request.description(),
                policyIds(request.policies()), roleIds(request.roles()), nodeIdentities(request.nodeIdentities()),
                serviceIdentities(request.serviceIdentities()), stored.createTime(), stored.expirationTime(),
                stored.installedFromFile());
        change(exchange, AclCommand.updateToken(token), () -> {
            LOG.info("ACL token updated: accessor {}", token.accessorId());
            return hidden(view().token(token.accessorId()).orElse(token));
        });
    }

    private void cloneToken(HttpExchange exchange, String accessorId) throws IOException {
        CloneRequest request = read(exchange, CloneRequest.class);
        Optional<AclToken> found = view().token(accessorId);
        if (found.isEmpty()) {
            missing(exchange, "token", accessorId);
            return;
        }
        AclToken original = found.get();
        String secret = UUID.randomUUID().toString();
        AclToken clone = new AclToken(UUID.randomUUID().toString(), AclSecrets.hash(secret),
                request.description() == null ? original.description() : request.description(), original.policyIds(),
                original.roleIds(), original.nodeIdentities(), original.serviceIdentities(), server.clock.instant(),
                original.expirationTime(), false);
        change(exchange, AclCommand.createToken(clone), () -> {
            LOG.info("ACL token cloned: accessor {} from {}", clone.accessorId(), original.accessorId());
            return AclTokenAnswer.of(clone, secret, view());
        });
    }

    private void readToken(HttpExchange exchange, String accessorId) throws IOException {
        answerOrMissing(exchange, view().token(accessorId), this::hidden);
    }

    private void deleteToken(HttpExchange exchange, AclPrincipal principal, String accessorId) throws IOException {
        if (accessorId.equalsIgnoreCase(principal.accessorId())) {
            // Consul's "Deletion of the request's authorization token is not permitted".
            server.respondError(exchange, 400, "self_deletion",
                    "Deletion of the request's authorization token is not permitted", false);
            return;
        }
        delete(exchange, AclCommand.deleteToken(canonicalOrMissing(accessorId)), "token", accessorId);
    }

    private void self(HttpExchange exchange, AclPrincipal principal) throws IOException {
        answerOrMissing(exchange, view().token(principal.accessorId()), this::hidden);
    }

    private void listTokens(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        String policy = query.get("policy");
        String role = query.get("role");
        String serviceName = query.get("servicename");
        List<AclTokenAnswer> answers = view().tokens().stream()
                .filter(token -> policy == null || token.policyIds().contains(policy.toLowerCase(Locale.ROOT)))
                .filter(token -> role == null || token.roleIds().contains(role.toLowerCase(Locale.ROOT)))
                .filter(token -> serviceName == null || token.serviceIdentities().stream()
                        .anyMatch(identity -> identity.serviceName().equals(serviceName)))
                .map(this::hidden).toList();
        answer(exchange, answers);
    }

    private AclTokenAnswer hidden(AclToken token) {
        return AclTokenAnswer.of(token, HIDDEN, view());
    }

    /** The expiry {@code request} asks for: a time, or a time to live from {@code now}; not both. */
    private static Instant expiry(TokenRequest request, Instant now) {
        if (request.expirationTime() != null && request.expirationTtl() != null) {
            throw new IllegalArgumentException("Give expirationTime or expirationTtl, not both");
        }
        if (request.expirationTime() != null) return instant(request.expirationTime());
        return request.expirationTtl() == null ? null : now.plus(duration(request.expirationTtl()));
    }

    private static Instant instant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("expirationTime must be an ISO-8601 instant, such as "
                    + "2026-11-07T10:00:00Z");
        }
    }

    /** A duration written as Consul writes one, such as {@code 90m} or {@code 1h30m}, or in ISO-8601. */
    static Duration duration(String text) {
        if (text.startsWith("P") || text.startsWith("p")) {
            try {
                return Duration.parse(text);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("expirationTtl is not a duration: " + text);
            }
        }
        Matcher part = GO_DURATION.matcher(text);
        Duration total = Duration.ZERO;
        int end = 0;
        while (part.find() && part.start() == end) {
            long amount = Long.parseLong(part.group(1));
            total = total.plus(switch (part.group(2)) {
                case "h" -> Duration.ofHours(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "s" -> Duration.ofSeconds(amount);
                default -> Duration.ofMillis(amount);
            });
            end = part.end();
        }
        if (end == 0 || end != text.length()) {
            throw new IllegalArgumentException("expirationTtl is not a duration, such as 30m or 1h: " + text);
        }
        return total;
    }

    // ---- Policies ----

    private void savePolicy(HttpExchange exchange, String id) throws IOException {
        PolicyRequest request = read(exchange, PolicyRequest.class);
        Optional<AclPolicy> stored = id == null ? Optional.empty() : view().policy(id);
        if (id != null && stored.isEmpty()) {
            missing(exchange, "policy", id);
            return;
        }
        if (id != null && request.id() != null && !request.id().equalsIgnoreCase(id)) {
            throw new IllegalArgumentException("id " + request.id() + " is not the policy's: " + id);
        }
        String policyId = id != null ? id : request.id() == null ? UUID.randomUUID().toString() : request.id();
        String name = request.name() == null && stored.isPresent() ? stored.get().name() : request.name();
        AclPolicy policy = new AclPolicy(policyId, name, request.description(), rules(request.rules()));
        change(exchange, id == null ? AclCommand.createPolicy(policy) : AclCommand.updatePolicy(policy), () -> {
            LOG.info("ACL policy {}: id {}, name {}", id == null ? "created" : "updated", policy.id(), policy.name());
            return policyAnswer(view().policy(policy.id()).orElse(policy));
        });
    }

    private void listPolicies(HttpExchange exchange) throws IOException {
        answer(exchange, view().policies().stream().map(this::policyAnswer).toList());
    }

    /** Rules given as a JSON object, or as a string holding one, as Consul's {@code Rules} is a string. */
    private static AclRules rules(JsonNode rules) {
        if (rules == null || rules.isNull()) return AclRules.EMPTY;
        return AclRulesJson.parse(rules.isTextual() ? rules.textValue() : rules.toString());
    }

    private PolicyAnswer policyAnswer(AclPolicy policy) {
        try {
            return new PolicyAnswer(policy.id(), policy.name(), policy.description(),
                    server.objectMapper.readTree(AclRulesJson.write(policy.rules())));
        } catch (JacksonException e) {
            throw new IllegalStateException("A policy's rules could not be answered", e);
        }
    }

    // ---- Roles ----

    private void saveRole(HttpExchange exchange, String id) throws IOException {
        RoleRequest request = read(exchange, RoleRequest.class);
        Optional<AclRole> stored = id == null ? Optional.empty() : view().role(id);
        if (id != null && stored.isEmpty()) {
            missing(exchange, "role", id);
            return;
        }
        if (id != null && request.id() != null && !request.id().equalsIgnoreCase(id)) {
            throw new IllegalArgumentException("id " + request.id() + " is not the role's: " + id);
        }
        String roleId = id != null ? id : request.id() == null ? UUID.randomUUID().toString() : request.id();
        String name = request.name() == null && stored.isPresent() ? stored.get().name() : request.name();
        AclRole role = new AclRole(roleId, name, request.description(), policyIds(request.policies()),
                nodeIdentities(request.nodeIdentities()), serviceIdentities(request.serviceIdentities()));
        change(exchange, id == null ? AclCommand.createRole(role) : AclCommand.updateRole(role), () -> {
            LOG.info("ACL role {}: id {}, name {}", id == null ? "created" : "updated", role.id(), role.name());
            return roleAnswer(view().role(role.id()).orElse(role));
        });
    }

    private void listRoles(HttpExchange exchange) throws IOException {
        String policy = query(exchange).get("policy");
        answer(exchange, view().roles().stream()
                .filter(role -> policy == null || role.policyIds().contains(policy.toLowerCase(Locale.ROOT)))
                .map(this::roleAnswer).toList());
    }

    private RoleAnswer roleAnswer(AclRole role) {
        AclView acl = view();
        return new RoleAnswer(role.id(), role.name(), role.description(),
                role.policyIds().stream().map(id -> new AclTokenAnswer.Link(id,
                        acl.policy(id).map(AclPolicy::name).orElse(null))).toList(),
                role.nodeIdentities().stream().map(i -> new AclTokenAnswer.NodeIdentityAnswer(i.nodeName())).toList(),
                role.serviceIdentities().stream().map(i -> new AclTokenAnswer.ServiceIdentityAnswer(i.serviceName(),
                        i.tenant(), i.namespace())).toList());
    }

    // ---- Authorize, resolve and explain ----

    private void authorize(HttpExchange exchange, AclPrincipal principal) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        List<AuthorizationCheck> checks = blank(body) ? List.of() : server.objectMapper
                .readerForListOf(AuthorizationCheck.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(body);
        if (checks == null) throw new IllegalArgumentException("The body must be a JSON array of checks");
        if (checks.size() > MAX_AUTHORIZATIONS) {
            throw new IllegalArgumentException("At most " + MAX_AUTHORIZATIONS + " checks are taken at once, not "
                    + checks.size());
        }
        List<Map<String, Object>> answers = new ArrayList<>();
        for (AuthorizationCheck check : checks) {
            AclRequest request = request(check.resource(), check.segment(), check.access(), check.tenant(),
                    check.namespace());
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("resource", request.resource().ruleName());
            if (request.name() != null) answer.put("segment", request.name());
            answer.put("access", request.access().name().toLowerCase(Locale.ROOT));
            if (request.tenant() != null) {
                answer.put("tenant", request.tenant());
                answer.put("namespace", request.namespace());
            }
            answer.put("allow", principal.allows(request));
            answers.add(answer);
        }
        answer(exchange, answers);
    }

    private void resolve(HttpExchange exchange, AclPrincipal principal) throws IOException {
        AclView acl = view();
        Optional<AclToken> found = acl.token(principal.accessorId());
        if (found.isEmpty()) {
            missing(exchange, "token", principal.accessorId());
            return;
        }
        AclToken token = found.get();
        List<AclRole> roles = token.roleIds().stream().map(acl::role).flatMap(Optional::stream).toList();
        Set<String> policyIds = new LinkedHashSet<>(token.policyIds());
        roles.forEach(role -> policyIds.addAll(role.policyIds()));
        List<PolicyAnswer> policies = policyIds.stream().map(acl::policy).flatMap(Optional::stream)
                .map(this::policyAnswer).toList();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("token", hidden(token));
        answer.put("policies", policies);
        answer.put("roles", roles.stream().map(this::roleAnswer).toList());
        answer(exchange, answer);
    }

    private void explain(HttpExchange exchange) throws IOException {
        ExplainRequest request = read(exchange, ExplainRequest.class);
        if (request.accessorId() == null) throw new IllegalArgumentException("accessorId names the token to explain");
        AclView acl = view();
        Optional<AclToken> found = acl.token(request.accessorId());
        if (found.isEmpty()) {
            missing(exchange, "token", request.accessorId());
            return;
        }
        AclRequest asked = request(request.resource(), request.name(), request.access(), request.tenant(),
                request.namespace());
        AclDecision decision = AclAuthorizer.decide(acl.grants(found.get()), server.acl.defaultPolicy(), asked);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("accessorId", found.get().accessorId());
        answer.put("allowed", decision.allowed());
        answer.put("defaultPolicy", server.acl.defaultPolicy().name().toLowerCase(Locale.ROOT));
        if (decision instanceof AclDecision.ByRule byRule) {
            answer.put("decidedBy", "rule");
            AclOrigin origin = byRule.origin();
            switch (origin) {
                case AclOrigin.Policy policy -> answer.put("policy",
                        new AclTokenAnswer.Link(policy.policyId(), policy.policyName()));
                case AclOrigin.Node node -> answer.put("nodeIdentity",
                        new AclTokenAnswer.NodeIdentityAnswer(node.identity().nodeName()));
                case AclOrigin.Service service -> answer.put("serviceIdentity",
                        new AclTokenAnswer.ServiceIdentityAnswer(service.identity().serviceName(),
                                service.identity().tenant(), service.identity().namespace()));
            }
            if (origin.roleId() != null) {
                answer.put("role", new AclTokenAnswer.Link(origin.roleId(),
                        acl.role(origin.roleId()).map(AclRole::name).orElse(null)));
            }
            answer.put("rule", ruleAnswer(byRule.rule()));
        } else {
            answer.put("decidedBy", "default");
        }
        answer(exchange, answer);
    }

    private static Map<String, Object> ruleAnswer(AclRule rule) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("resource", rule.resource().ruleName());
        if (rule.name() != null) answer.put("name", rule.name());
        answer.put("prefix", rule.prefix());
        answer.put("access", rule.access().name().toLowerCase(Locale.ROOT));
        if (rule.tenant() != null) {
            answer.put("tenant", scopeAnswer(rule.tenant()));
            answer.put("namespace", scopeAnswer(rule.namespace()));
        }
        return answer;
    }

    private static Map<String, Object> scopeAnswer(AclScope scope) {
        return Map.of("name", scope.name(), "prefix", scope.prefix());
    }

    /** A request as the authorize and explain routes give one; a service is in {@code default} unless told. */
    private static AclRequest request(String resource, String name, String access, String tenant, String namespace) {
        if (resource == null || access == null) throw new IllegalArgumentException("A check needs a resource and access");
        AclResource kind;
        try {
            kind = AclResource.valueOf(resource.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not an ACL resource: " + resource);
        }
        AclAccess level = switch (access.toLowerCase(Locale.ROOT)) {
            case "read" -> AclAccess.READ;
            case "write" -> AclAccess.WRITE;
            default -> throw new IllegalArgumentException("The access is read or write, not " + access);
        };
        if (kind.scoped()) {
            return new AclRequest(kind, name, tenant == null ? AclScope.DEFAULT.name() : tenant,
                    namespace == null ? AclScope.DEFAULT.name() : namespace, level);
        }
        return new AclRequest(kind, name, tenant, namespace, level);
    }

    // ---- Links and identities ----

    private List<String> policyIds(List<LinkRequest> links) {
        return ids(links, "policy", AclRefusal.POLICY_NOT_FOUND.code(),
                name -> view().policyByName(name).map(AclPolicy::id));
    }

    private List<String> roleIds(List<LinkRequest> links) {
        return ids(links, "role", AclRefusal.ROLE_NOT_FOUND.code(), name -> view().roleByName(name).map(AclRole::id));
    }

    private static List<String> ids(List<LinkRequest> links, String kind, String notFound,
                                    Function<String, Optional<String>> byName) {
        if (links == null) return List.of();
        List<String> ids = new ArrayList<>();
        for (LinkRequest link : links) {
            if (link == null || (link.id() == null) == (link.name() == null)) {
                throw new IllegalArgumentException("A " + kind + " link gives its id or its name");
            }
            ids.add(link.id() != null ? AclSecrets.canonicalId(link.id(), "a " + kind + " link")
                    : byName.apply(link.name()).orElseThrow(() -> new LinkNotFound(notFound,
                            "No such ACL " + kind + " named " + link.name())));
        }
        return ids;
    }

    private static List<NodeIdentity> nodeIdentities(List<NodeIdentityRequest> identities) {
        return identities == null ? List.of() : identities.stream()
                .map(identity -> new NodeIdentity(identity.nodeName())).toList();
    }

    private static List<ServiceIdentity> serviceIdentities(List<ServiceIdentityRequest> identities) {
        return identities == null ? List.of() : identities.stream()
                .map(i -> new ServiceIdentity(i.serviceName(), i.tenant(), i.namespace())).toList();
    }

    private static String unreservedUuid(String value, String field) {
        // The message never repeats what was supplied: it may be a secret.
        if (!AclSecrets.isUuid(value)) throw new IllegalArgumentException(field + " must be a UUID");
        if (AclSecrets.isReserved(value)) {
            throw new IllegalArgumentException(field + " lies in the range of UUIDs kept for built-ins");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /** {@code id} in lower case, or a UUID that names nothing, so that the state machine answers it as missing. */
    private static String canonicalOrMissing(String id) {
        return AclSecrets.isUuid(id) ? id.toLowerCase(Locale.ROOT) : "ffffffff-ffff-ffff-ffff-ffffffffffff";
    }

    // ---- Answers ----

    @FunctionalInterface
    private interface Answer {
        Object get();
    }

    private void change(HttpExchange exchange, RaftCommand command, Answer onSuccess) throws IOException {
        RaftCommandResult<?> result = server.submit(command);
        switch (result) {
            case RaftCommandResult.Success<?> ignored -> {
                server.setAppliedIndex(exchange);
                server.respondJson(exchange, 200, onSuccess.get());
            }
            case RaftCommandResult.NotFound<?> notFound -> missing(exchange, notFound.entityType(), notFound.id());
            case RaftCommandResult.Refused<?> refused -> server.respondError(exchange,
                    CONFLICTS.contains(refused.code()) ? 409 : 400, refused.code(), refused.message(), false);
            default -> server.respondError(exchange, 500, "internal_error", "Unexpected outcome: " + result, false);
        }
    }

    private void delete(HttpExchange exchange, RaftCommand command, String kind, String id) throws IOException {
        change(exchange, command, () -> {
            LOG.info("ACL {} deleted: {}", kind, id);
            return Boolean.TRUE;
        });
    }

    private <T> void answerOrMissing(HttpExchange exchange, Optional<T> found, Function<T, Object> answer)
            throws IOException {
        if (found.isEmpty()) {
            missing(exchange, "entry", exchange.getRequestURI().getPath());
            return;
        }
        answer(exchange, answer.apply(found.get()));
    }

    private void answer(HttpExchange exchange, Object body) throws IOException {
        server.setAppliedIndex(exchange);
        server.respondJson(exchange, 200, body);
    }

    private void missing(HttpExchange exchange, String kind, String id) throws IOException {
        server.respondError(exchange, 404, "acl_not_found", "ACL not found: no such " + kind.replace("Acl", "")
                .toLowerCase(Locale.ROOT) + " " + id, false);
    }

    private AclView view() {
        return server.stateStore.getAcl();
    }

    private <T> T read(HttpExchange exchange, Class<T> type) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        T request = server.objectMapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(blank(body) ? "{}".getBytes(StandardCharsets.UTF_8) : body);
        if (request == null) throw new IllegalArgumentException("The body must be a JSON object");
        return request;
    }

    private static boolean blank(byte[] body) {
        return new String(body, StandardCharsets.UTF_8).isBlank();
    }

    /** Refuses a query parameter the route does not take, as every other read does. */
    private boolean refusedForItsQuery(HttpExchange exchange, Set<String> allowed) throws IOException {
        for (String name : query(exchange).keySet()) {
            if (!allowed.contains(name)) {
                server.respondError(exchange, 400, "invalid_query", "Unsupported query parameter: " + name, false);
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        Map<String, String> query = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return query;
        for (String parameter : raw.split("&")) {
            if (parameter.isEmpty()) continue;
            String[] parts = parameter.split("=", 2);
            query.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length < 2 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return query;
    }

    /** A link that names a policy or role that does not exist, refused with the state machine's own code. */
    private static final class LinkNotFound extends RuntimeException {
        private final String code;

        LinkNotFound(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    // ---- Bodies ----

    record LinkRequest(String id, String name) {
    }

    record NodeIdentityRequest(String nodeName) {
    }

    record ServiceIdentityRequest(String serviceName, String tenant, String namespace) {
    }

    /** A token as a create or update gives it; its {@link #toString} never shows the secret. */
    record TokenRequest(String accessorId, String secretId, String description, List<LinkRequest> policies,
                        List<LinkRequest> roles, List<NodeIdentityRequest> nodeIdentities,
                        List<ServiceIdentityRequest> serviceIdentities, String expirationTime, String expirationTtl) {
        @Override
        public String toString() {
            return "TokenRequest[accessorId=" + accessorId + ", secretId=" + (secretId == null ? "none" : HIDDEN)
                    + "]";
        }
    }

    record CloneRequest(String description) {
    }

    record PolicyRequest(String id, String name, String description, JsonNode rules) {
    }

    record RoleRequest(String id, String name, String description, List<LinkRequest> policies,
                       List<NodeIdentityRequest> nodeIdentities, List<ServiceIdentityRequest> serviceIdentities) {
    }

    record AuthorizationCheck(String resource, String segment, String access, String tenant, String namespace) {
    }

    record ExplainRequest(String accessorId, String resource, String name, String access, String tenant,
                          String namespace) {
    }

    record PolicyAnswer(String id, String name, String description, JsonNode rules) {
    }

    record RoleAnswer(String id, String name, String description, List<AclTokenAnswer.Link> policies,
                      List<AclTokenAnswer.NodeIdentityAnswer> nodeIdentities,
                      List<AclTokenAnswer.ServiceIdentityAnswer> serviceIdentities) {
    }
}

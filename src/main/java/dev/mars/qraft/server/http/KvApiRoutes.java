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

import com.sun.net.httpserver.HttpExchange;
import dev.mars.qraft.common.acl.AclAccess;
import dev.mars.qraft.common.acl.AclDecision;
import dev.mars.qraft.common.acl.AclRequest;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.server.acl.AclPrincipal;
import dev.mars.qraft.state.kv.KvCommand;
import dev.mars.qraft.state.kv.KvEntry;
import dev.mars.qraft.state.kv.KvScope;
import dev.mars.qraft.state.kv.KvState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

/**
 * Consul's KV API on the server, {@code /v1/kv/{key}} (key/value list, Phase 3). {@code GET} reads one key, or with
 * {@code recurse} every key under it, or with {@code keys} their names, cut after {@code separator}; {@code raw}
 * answers the value alone. {@code PUT} writes the body with {@code flags} and {@code cas}, or with {@code acquire}
 * or {@code release} and a session, which lock and unlock the key (sessions list, Phase 3); {@code DELETE} deletes,
 * with {@code recurse} or {@code cas}; each answers {@code true} or {@code false}. The scope is the request's tenant
 * and namespace headers (decision 3), entries use Consul's field names (decision 4), and {@code X-Qraft-Index} is the
 * key/value state's last index. A read also takes the consistency list's {@code stale}, {@code consistent}, {@code index} and
 * {@code wait}. Any other parameter is refused (decision 7), and so are {@code acquire}, {@code release} and
 * {@code cas} given together, which Consul resolves by taking the last.
 *
 * <p>With ACLs enabled a read needs {@code key:read}, and a recursive read leaves out what the token cannot read; a
 * write, an acquisition and a release need {@code key:write}, and a recursive delete write on every key under the
 * prefix, as Consul's
 * {@code KeyWritePrefix} decides it.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
final class KvApiRoutes {
    static final String CONTEXT = "/v1/kv";
    private static final Logger LOG = LoggerFactory.getLogger(KvApiRoutes.class);
    private static final Set<String> READ_PARAMETERS = Set.of("recurse", "keys", "separator", "raw", "stale",
            "consistent", "index", "wait");
    private static final Set<String> WRITE_PARAMETERS = Set.of("flags", "cas", "acquire", "release");
    private static final Set<String> DELETE_PARAMETERS = Set.of("recurse", "cas");

    private final HttpApiServer server;

    KvApiRoutes(HttpApiServer server) {
        this.server = server;
    }

    void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(java.util.Locale.ROOT);
        Set<String> allowed = switch (method) {
            case "GET" -> READ_PARAMETERS;
            case "PUT" -> WRITE_PARAMETERS;
            case "DELETE" -> DELETE_PARAMETERS;
            default -> null;
        };
        if (allowed == null) {
            server.respondError(exchange, 405, "method_not_allowed", "Method not allowed", false);
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (!path.equals(CONTEXT) && !path.startsWith(CONTEXT + "/")) {
            server.respondError(exchange, 404, "not_found", "No such route: " + path, false);
            return;
        }
        if (!server.prepareStateRequest(exchange, method)) return;
        String key = path.length() <= CONTEXT.length() + 1 ? "" : path.substring(CONTEXT.length() + 1);
        Map<String, String> query = query(exchange, allowed);
        if (query == null) return;
        KvScope scope = scope(exchange);
        if (scope == null) return;
        AclPrincipal principal = server.principal(exchange);
        if (principal == null) return;
        switch (method) {
            case "GET" -> read(exchange, principal, scope, key, query);
            case "PUT" -> write(exchange, principal, scope, key, query);
            default -> delete(exchange, principal, scope, key, query);
        }
    }

    private void read(HttpExchange exchange, AclPrincipal principal, KvScope scope, String key,
                      Map<String, String> query) throws IOException {
        boolean raw = query.containsKey("raw");
        boolean recurse = query.containsKey("recurse");
        boolean keys = query.containsKey("keys");
        if (raw && (recurse || keys)) {
            invalid(exchange, "raw answers one value, and cannot be given with recurse or keys");
            return;
        }
        if (query.containsKey("separator") && !keys) {
            invalid(exchange, "separator is given with keys");
            return;
        }
        if (!server.readyToRead(exchange, HttpApiServer.ReadIndex.KV)) return;
        KvState kv = server.stateStore.getKv();
        exchange.getResponseHeaders().set("X-Qraft-Index", Long.toString(kv.lastIndex()));
        if (recurse || keys) {
            List<KvEntry> all = kv.list(scope, key);
            List<KvEntry> readable = all.stream()
                    .filter(entry -> principal.allows(read(scope, entry.key()))).toList();
            if (readable.size() < all.size()) {
                exchange.getResponseHeaders().set(HttpApiServer.RESULTS_FILTERED_HEADER, "true");
            }
            if (readable.isEmpty()) {
                server.respondError(exchange, 404, "key_not_found", "No key under: " + key, false);
            } else if (keys) {
                server.respondJson(exchange, 200, KvState.keysOf(readable, key, query.get("separator")));
            } else {
                server.respondJson(exchange, 200, readable.stream().map(KvApiRoutes::answer).toList());
            }
            return;
        }
        if (key.isEmpty()) {
            server.respondError(exchange, 400, "key_required", "A key is required, or recurse or keys", false);
            return;
        }
        if (server.refused(exchange, principal, read(scope, key))) return;
        java.util.Optional<KvEntry> entry = kv.get(scope, key);
        if (entry.isEmpty()) {
            server.respondError(exchange, 404, "key_not_found", "No such key: " + key, false);
        } else if (raw) {
            byte[] value = entry.get().value();
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, value.length == 0 ? -1 : value.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(value);
            }
        } else {
            server.respondJson(exchange, 200, List.of(answer(entry.get())));
        }
    }

    private void write(HttpExchange exchange, AclPrincipal principal, KvScope scope, String key,
                       Map<String, String> query) throws IOException {
        if (key.isEmpty()) {
            server.respondError(exchange, 400, "key_required", "A key is required", false);
            return;
        }
        Long flags = number(exchange, query, "flags", true);
        if (flags == null && query.containsKey("flags")) return;
        Long cas = number(exchange, query, "cas", false);
        if (cas == null && query.containsKey("cas")) return;
        String session = query.containsKey("acquire") ? query.get("acquire") : query.get("release");
        if (session != null) {
            if (query.containsKey("acquire") && query.containsKey("release") || cas != null) {
                invalid(exchange, "acquire, release and cas are each given alone");
                return;
            }
            if (session.isEmpty()) {
                invalid(exchange, (query.containsKey("acquire") ? "acquire" : "release") + " names a session");
                return;
            }
        }
        if (server.refused(exchange, principal, write(scope, key))) return;
        if (session != null && server.stateStore.getSessions().get(session).isEmpty()) {
            server.respondError(exchange, 400, "invalid_session", "No session " + session + " exists", false);
            return;
        }
        byte[] value = exchange.getRequestBody().readAllBytes();
        long given = flags == null ? 0 : flags;
        if (query.containsKey("release")) {
            submit(exchange, KvCommand.unlock(scope, key, value, given, session));
            return;
        }
        // A key set or an acquisition lands only where its tenant and namespace exist (tenancy list, decision 3).
        if (server.refusedForItsScope(exchange, scope.tenant(), scope.namespace())) return;
        KvCommand write = query.containsKey("acquire")
                ? KvCommand.lock(scope, key, value, given, session, server.clock.instant())
                : KvCommand.set(scope, key, value, given, cas);
        submit(exchange, dev.mars.qraft.state.tenancy.ScopedWrite.of(scope.tenant(), scope.namespace(), write));
    }

    private void delete(HttpExchange exchange, AclPrincipal principal, KvScope scope, String key,
                        Map<String, String> query) throws IOException {
        if (query.containsKey("recurse")) {
            if (query.containsKey("cas")) {
                invalid(exchange, "cas deletes one key, and cannot be given with recurse");
                return;
            }
            AclRequest prefix = write(scope, key);
            AclDecision decision = principal.decideWritePrefix(prefix);
            if (!decision.allowed()) {
                String denied = principal.permissionDenied(prefix);
                LOG.info("ACL refused DELETE {} with recurse: {}", exchange.getRequestURI().getPath(), denied);
                server.respondError(exchange, 403, "permission_denied", denied, false);
                return;
            }
            submit(exchange, KvCommand.deleteTree(scope, key));
            return;
        }
        if (key.isEmpty()) {
            server.respondError(exchange, 400, "key_required", "A key is required, or recurse", false);
            return;
        }
        Long cas = number(exchange, query, "cas", false);
        if (cas == null && query.containsKey("cas")) return;
        if (server.refused(exchange, principal, write(scope, key))) return;
        submit(exchange, KvCommand.delete(scope, key, cas));
    }

    private void submit(HttpExchange exchange, dev.mars.qraft.raft.RaftCommand command) throws IOException {
        RaftCommandResult<?> result;
        try {
            result = server.submit(command);
        } catch (CompletionException unavailable) {
            server.respondUnavailable(exchange, unavailable);
            return;
        }
        if (server.refusedForItsScope(exchange, result)) return;
        if (result instanceof RaftCommandResult.Success<?>(Boolean applied)) {
            server.respondJson(exchange, 200, applied);
        } else if (result instanceof RaftCommandResult.Refused<?> refused
                && refused.code().equals("invalid_session")) {
            server.respondError(exchange, 400, "invalid_session", refused.message(), false);
        } else {
            server.respondError(exchange, 409, "kv_rejected", "The replicated key/value state refused the write: "
                    + result, false);
        }
    }

    /** An entry in Consul's form (decision 4), with {@code Session} only while a session holds it. */
    private static Map<String, Object> answer(KvEntry entry) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("Key", entry.key());
        answer.put("Value", Base64.getEncoder().encodeToString(entry.value()));
        answer.put("Flags", new java.math.BigInteger(Long.toUnsignedString(entry.flags())));
        answer.put("CreateIndex", entry.createIndex());
        answer.put("ModifyIndex", entry.modifyIndex());
        answer.put("LockIndex", entry.lockIndex());
        if (entry.session() != null) answer.put("Session", entry.session());
        return answer;
    }

    private static AclRequest read(KvScope scope, String key) {
        return AclRequest.key(key, scope.tenant(), scope.namespace(), AclAccess.READ);
    }

    private static AclRequest write(KvScope scope, String key) {
        return AclRequest.key(key, scope.tenant(), scope.namespace(), AclAccess.WRITE);
    }

    /** The request's parameters by name, or {@code null} after refusing one this method does not take. */
    private Map<String, String> query(HttpExchange exchange, Set<String> allowed) throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return query;
        for (String parameter : raw.split("&")) {
            if (parameter.isEmpty()) continue;
            int equals = parameter.indexOf('=');
            String name = URLDecoder.decode(equals < 0 ? parameter : parameter.substring(0, equals),
                    StandardCharsets.UTF_8);
            String value = equals < 0 ? "" : URLDecoder.decode(parameter.substring(equals + 1), StandardCharsets.UTF_8);
            if (!allowed.contains(name)) {
                invalid(exchange, "Unsupported query parameter: " + name);
                return null;
            }
            query.putIfAbsent(name, value);
        }
        return query;
    }

    /**
     * {@code name}'s value as a number, unsigned 64-bit for {@code flags} as Consul's are, or a non-negative index;
     * {@code null} when absent, or after refusing a value that is not one.
     */
    private Long number(HttpExchange exchange, Map<String, String> query, String name, boolean unsigned)
            throws IOException {
        String value = query.get(name);
        if (value == null) return null;
        try {
            long parsed = unsigned ? Long.parseUnsignedLong(value) : Long.parseLong(value);
            if (!unsigned && parsed < 0) throw new NumberFormatException("negative");
            return parsed;
        } catch (NumberFormatException notNumber) {
            invalid(exchange, name + " must be " + (unsigned ? "an unsigned 64-bit number" : "an index, 0 or more")
                    + ": " + value);
            return null;
        }
    }

    private KvScope scope(HttpExchange exchange) throws IOException {
        try {
            return new KvScope(TokenRequestContext.optionalScope(exchange, TokenRequestContext.TENANT_HEADER),
                    TokenRequestContext.optionalScope(exchange, TokenRequestContext.NAMESPACE_HEADER));
        } catch (IllegalArgumentException e) {
            server.respondError(exchange, 400, "invalid_scope", HttpApiServer.safeMessage(e), false);
            return null;
        }
    }

    private void invalid(HttpExchange exchange, String message) throws IOException {
        server.respondError(exchange, 400, "invalid_query", message, false);
    }
}

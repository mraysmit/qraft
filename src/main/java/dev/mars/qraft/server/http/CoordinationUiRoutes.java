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
import dev.mars.qraft.common.acl.AclRequest;
import dev.mars.qraft.common.acl.AclResource;
import dev.mars.qraft.server.acl.AclPrincipal;
import dev.mars.qraft.server.session.SessionTtlExpiry;
import dev.mars.qraft.state.kv.KvEntry;
import dev.mars.qraft.state.kv.KvState;
import dev.mars.qraft.state.session.Session;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The administrative interface's coordination reads (UI-6 Sessions, Locks and Watches list, Phase 0), beside Consul's
 * session and key/value APIs, which have no such reads:
 *
 * <ul>
 *   <li>{@code GET /v1/internal/ui/locks} answers {@code Held}, every key a session holds in every scope, with its
 *       tenant, namespace, session and indexes but never its value, and {@code Delays}, the lock delays still running
 *       by this server's clock with their end; {@code session} narrows {@code Held} to one session. A key is answered
 *       only where the token may read it in its scope, and the filtered header says when one was left out. It takes
 *       the key/value read's consistency and blocking options and answers its index (decision 4).</li>
 *   <li>{@code GET /v1/internal/ui/sessions} answers {@code Sessions} in Consul's form, filtered as
 *       {@code /v1/session/list} is, each with {@code RenewedAt} and {@code ExpiresAt} from the leader's timer, and
 *       {@code TtlKnown}, false on a server that does not lead, which keeps no timers, and answers both as null
 *       (decision 2).</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-17
 * @version 1.0
 */
final class CoordinationUiRoutes {
    static final String LOCKS = "/v1/internal/ui/locks";
    static final String SESSIONS = "/v1/internal/ui/sessions";
    private static final String SESSION_PARAMETER = "session";

    private final HttpApiServer server;

    CoordinationUiRoutes(HttpApiServer server) {
        this.server = server;
    }

    void locks(HttpExchange exchange) throws IOException {
        if (!routed(server, exchange, LOCKS)) return;
        Map<String, String> query = query(server, exchange, Set.of(SESSION_PARAMETER));
        if (query == null) return;
        String session = query.get(SESSION_PARAMETER);
        if (session != null && session.isBlank()) {
            server.respondError(exchange, 400, "invalid_query", "session must not be blank", false);
            return;
        }
        AclPrincipal principal = server.principal(exchange);
        if (principal == null) return;
        if (!server.readyToRead(exchange, HttpApiServer.ReadIndex.KV)) return;
        KvState kv = server.stateStore.getKv();
        exchange.getResponseHeaders().set("X-Qraft-Index", Long.toString(kv.lastIndex()));
        List<KvEntry> all = kv.held().stream().filter(entry -> session == null || session.equals(entry.session()))
                .toList();
        List<KvEntry> held = all.stream().filter(entry -> readable(principal, entry.tenant(), entry.namespace(),
                entry.key())).toList();
        List<KvState.LockDelay> delays = kv.lockDelaysAfter(server.clock.instant());
        List<KvState.LockDelay> readableDelays = delays.stream().filter(delay -> readable(principal, delay.tenant(),
                delay.namespace(), delay.key())).toList();
        if (held.size() < all.size() || readableDelays.size() < delays.size()) {
            exchange.getResponseHeaders().set(HttpApiServer.RESULTS_FILTERED_HEADER, "true");
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("Held", held.stream().map(CoordinationUiRoutes::held).toList());
        answer.put("Delays", readableDelays.stream().map(CoordinationUiRoutes::delay).toList());
        server.respondJson(exchange, 200, answer);
    }

    void sessions(HttpExchange exchange) throws IOException {
        if (!routed(server, exchange, SESSIONS)) return;
        if (query(server, exchange, Set.of()) == null) return;
        AclPrincipal principal = server.principal(exchange);
        if (principal == null) return;
        if (!server.readyToRead(exchange, HttpApiServer.ReadIndex.APPLIED)) return;
        server.setAppliedIndex(exchange);
        List<Session> all = server.stateStore.getSessions().list();
        List<Session> readable = all.stream().filter(session -> principal.allows(AclRequest.named(AclResource.SESSION,
                session.node(), AclAccess.READ))).toList();
        if (readable.size() < all.size()) {
            exchange.getResponseHeaders().set(HttpApiServer.RESULTS_FILTERED_HEADER, "true");
        }
        SessionTtlExpiry ttl = server.sessionTtl();
        Optional<Map<String, SessionTtlExpiry.Timer>> timers = ttl == null ? Optional.empty() : ttl.timers();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("Sessions", readable.stream().map(session -> {
            Map<String, Object> entry = SessionApiRoutes.answer(session);
            SessionTtlExpiry.Timer timer = timers.map(known -> known.get(session.id())).orElse(null);
            entry.put("RenewedAt", timer == null ? null : text(timer.renewedAt()));
            entry.put("ExpiresAt", timer == null ? null : text(timer.expiresAt()));
            return entry;
        }).toList());
        answer.put("TtlKnown", timers.isPresent());
        server.respondJson(exchange, 200, answer);
    }

    /** Whether the request is a GET of {@code path} itself, after answering it when it is not. */
    static boolean routed(HttpApiServer server, HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestURI().getPath().equals(path)) {
            server.respondError(exchange, 404, "not_found", "No such route: " + exchange.getRequestURI().getPath(),
                    false);
            return false;
        }
        return server.prepareStateRequest(exchange, "GET");
    }

    /**
     * The request's parameters, the read options and {@code own}, or null after refusing one it does not take; a
     * parameter given without a value is empty.
     */
    static Map<String, String> query(HttpApiServer server, HttpExchange exchange, Set<String> own) throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return query;
        for (String parameter : raw.split("&")) {
            if (parameter.isEmpty()) continue;
            int equals = parameter.indexOf('=');
            String name = URLDecoder.decode(equals < 0 ? parameter : parameter.substring(0, equals),
                    StandardCharsets.UTF_8);
            if (!HttpApiServer.READ_OPTIONS.contains(name) && !own.contains(name)) {
                server.respondError(exchange, 400, "invalid_query", "Unsupported query parameter: " + name, false);
                return null;
            }
            query.putIfAbsent(name, equals < 0 ? "" : URLDecoder.decode(parameter.substring(equals + 1),
                    StandardCharsets.UTF_8));
        }
        return query;
    }

    private static boolean readable(AclPrincipal principal, String tenant, String namespace, String key) {
        return principal.allows(AclRequest.key(key, tenant, namespace, AclAccess.READ));
    }

    private static Map<String, Object> held(KvEntry entry) {
        Map<String, Object> held = new LinkedHashMap<>();
        held.put("Tenant", entry.tenant());
        held.put("Namespace", entry.namespace());
        held.put("Key", entry.key());
        held.put("Session", entry.session());
        held.put("LockIndex", entry.lockIndex());
        held.put("CreateIndex", entry.createIndex());
        held.put("ModifyIndex", entry.modifyIndex());
        return held;
    }

    private static Map<String, Object> delay(KvState.LockDelay delay) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("Tenant", delay.tenant());
        answer.put("Namespace", delay.namespace());
        answer.put("Key", delay.key());
        answer.put("Until", text(delay.until()));
        return answer;
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}

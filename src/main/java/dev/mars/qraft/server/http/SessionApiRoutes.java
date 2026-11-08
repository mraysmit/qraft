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
import dev.mars.qraft.server.session.SessionTtlExpiry;
import dev.mars.qraft.state.session.Session;
import dev.mars.qraft.state.session.SessionBehavior;
import dev.mars.qraft.state.session.SessionCommand;
import dev.mars.qraft.state.session.SessionState;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Consul's session API on the server, {@code /v1/session} (sessions list, Phase 3):
 *
 * <ul>
 *   <li>{@code PUT create} takes {@code Node}, which the server requires (decision 5), {@code Name}, {@code TTL}
 *       (10s to 24h, or none), {@code LockDelay} (15s by default and at most 60s; a number below 1000 is seconds, and
 *       any other nanoseconds, as Consul reads it) and {@code Behavior} ({@code release} by default, or {@code delete}), and
 *       answers {@code {"ID": ...}}; sessions bound to checks are refused, being left for later;</li>
 *   <li>{@code PUT destroy/{id}} answers {@code true}; {@code PUT renew/{id}} renews on the leader alone (decision 2)
 *       and answers the session, or 404 {@code session_not_found};</li>
 *   <li>{@code GET info/{id}}, {@code node/{node}} and {@code list} answer sessions in Consul's form, with the
 *       consistency list's {@code stale}, {@code consistent}, {@code index} and {@code wait}.</li>
 * </ul>
 *
 * <p>With ACLs enabled, creating, renewing or destroying a session needs {@code session:write} on its node, and a read
 * leaves out the sessions whose node the token cannot read with {@code session:read}, as Consul's results filter does.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
final class SessionApiRoutes {
    static final String CONTEXT = "/v1/session";
    static final Duration MIN_TTL = Duration.ofSeconds(10);
    static final Duration MAX_TTL = Duration.ofHours(24);
    static final Duration DEFAULT_LOCK_DELAY = Duration.ofSeconds(15);
    static final Duration MAX_LOCK_DELAY = Duration.ofSeconds(60);
    private static final String MIN_TTL_TEXT = "10s";
    private static final String MAX_TTL_TEXT = "24h";
    private static final String DEFAULT_LOCK_DELAY_TEXT = "15s";
    private static final String MAX_LOCK_DELAY_TEXT = "60s";
    /** A {@code LockDelay} number below this is seconds, and any other nanoseconds, as Consul's threshold is. */
    private static final long LOCK_DELAY_SECONDS_THRESHOLD = 1000;

    private final HttpApiServer server;

    SessionApiRoutes(HttpApiServer server) {
        this.server = server;
    }

    void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String rest = path.length() > CONTEXT.length() ? path.substring(CONTEXT.length()) : "";
        String[] parts = rest.startsWith("/") ? rest.substring(1).split("/", 2) : new String[] {""};
        String verb = parts[0];
        String argument = parts.length > 1 ? parts[1] : null;
        boolean known = switch (verb) {
            case "create", "list" -> argument == null;
            case "destroy", "renew", "info", "node" -> argument != null && !argument.isEmpty()
                    && !argument.contains("/");
            default -> false;
        };
        if (!known) {
            server.respondError(exchange, 404, "not_found", "No such route: " + path, false);
            return;
        }
        boolean write = switch (verb) {
            case "create", "destroy", "renew" -> true;
            default -> false;
        };
        if (!server.prepareStateRequest(exchange, write ? "PUT" : "GET")) return;
        if (write) {
            String query = exchange.getRequestURI().getRawQuery();
            if (query != null && !query.isEmpty()) {
                server.respondError(exchange, 400, "invalid_query", "A session " + verb + " takes no query", false);
                return;
            }
        } else if (server.refusedForItsQuery(exchange)) {
            return;
        }
        AclPrincipal principal = server.principal(exchange);
        if (principal == null) return;
        switch (verb) {
            case "create" -> create(exchange, principal);
            case "destroy" -> destroy(exchange, principal, argument);
            case "renew" -> renew(exchange, principal, argument);
            case "info" -> read(exchange, principal, () -> sessions().get(argument).stream().toList());
            case "node" -> read(exchange, principal, () -> sessions().ofNode(argument));
            default -> read(exchange, principal, () -> sessions().list());
        }
    }

    private SessionState sessions() {
        return server.stateStore.getSessions();
    }

    private void create(HttpExchange exchange, AclPrincipal principal) throws IOException {
        Session session;
        try {
            session = parse(exchange.getRequestBody().readAllBytes());
        } catch (JsonProcessingException notJson) {
            server.respondError(exchange, 400, "invalid_json", "The session is not JSON: "
                    + notJson.getOriginalMessage(), false);
            return;
        } catch (NodeRequired missing) {
            server.respondError(exchange, 400, "node_required", missing.getMessage(), false);
            return;
        } catch (IllegalArgumentException invalid) {
            server.respondError(exchange, 400, "invalid_session", HttpApiServer.safeMessage(invalid), false);
            return;
        }
        if (server.refused(exchange, principal, write(session.node()))) return;
        RaftCommandResult<?> result = submit(exchange, SessionCommand.create(session));
        if (result == null) return;
        switch (result) {
            case RaftCommandResult.Success<?>(Boolean created) when created ->
                    server.respondJson(exchange, 200, Map.of("ID", session.id()));
            case RaftCommandResult.Refused<?> refused when refused.code().equals("node_not_found") ->
                    server.respondError(exchange, 400, "node_not_found", refused.message(), false);
            default -> server.respondError(exchange, 409, "session_rejected",
                    "The replicated sessions refused the session: " + result, false);
        }
    }

    private void destroy(HttpExchange exchange, AclPrincipal principal, String id) throws IOException {
        Optional<Session> session = sessions().get(id);
        if (session.isPresent() && server.refused(exchange, principal, write(session.get().node()))) return;
        RaftCommandResult<?> result = submit(exchange, SessionCommand.destroy(id, server.clock.instant()));
        if (result == null) return;
        if (result instanceof RaftCommandResult.Success<?>) {
            server.respondJson(exchange, 200, true);
        } else {
            server.respondError(exchange, 409, "session_rejected", "The replicated sessions refused the destroy: "
                    + result, false);
        }
    }

    private void renew(HttpExchange exchange, AclPrincipal principal, String id) throws IOException {
        Optional<Session> known = sessions().get(id);
        if (known.isPresent() && server.refused(exchange, principal, write(known.get().node()))) return;
        SessionTtlExpiry ttl = server.sessionTtl();
        Optional<Session> renewed;
        try {
            if (ttl == null) throw new IllegalStateException("This server keeps no session timers");
            renewed = ttl.renew(id);
        } catch (IllegalStateException notLeader) {
            server.respondUnavailable(exchange, new CompletionException(new IllegalStateException(
                    "Only the leader renews sessions: " + notLeader.getMessage())));
            return;
        }
        if (renewed.isEmpty()) {
            server.respondError(exchange, 404, "session_not_found", "No session " + id + " exists to renew", false);
            return;
        }
        server.respondJson(exchange, 200, List.of(answer(renewed.get())));
    }

    /** Answers the sessions {@code chosen} gives once the read may be answered, as its consistency mode asks. */
    private void read(HttpExchange exchange, AclPrincipal principal, Supplier<List<Session>> chosen)
            throws IOException {
        if (!server.readyToRead(exchange, HttpApiServer.ReadIndex.APPLIED)) return;
        server.setAppliedIndex(exchange);
        List<Session> all = chosen.get();
        List<Session> readable = all.stream()
                .filter(session -> principal.allows(AclRequest.named(AclResource.SESSION, session.node(),
                        AclAccess.READ))).toList();
        if (readable.size() < all.size()) {
            exchange.getResponseHeaders().set(HttpApiServer.RESULTS_FILTERED_HEADER, "true");
        }
        server.respondJson(exchange, 200, readable.stream().map(SessionApiRoutes::answer).toList());
    }

    /** The command's result, or {@code null} after answering that the servers could not take it. */
    private RaftCommandResult<?> submit(HttpExchange exchange, SessionCommand command) throws IOException {
        try {
            return server.submit(command);
        } catch (CompletionException unavailable) {
            server.respondUnavailable(exchange, unavailable);
            return null;
        }
    }

    private static AclRequest write(String node) {
        return AclRequest.named(AclResource.SESSION, node, AclAccess.WRITE);
    }

    /** A session's request body read as Consul reads it, with a new ID and this server's time. */
    private Session parse(byte[] body) throws IOException {
        JsonNode request = body.length == 0 ? server.objectMapper.createObjectNode() : server.objectMapper.readTree(body);
        if (request == null || !request.isObject()) throw new IllegalArgumentException("A session is a JSON object");
        for (String checks : List.of("Checks", "NodeChecks", "ServiceChecks")) {
            JsonNode given = request.get(checks);
            if (given == null || given.isNull()) continue;
            if (!given.isArray()) throw new IllegalArgumentException(checks + " must be a list");
            if (!given.isEmpty()) {
                throw new IllegalArgumentException("Sessions bound to checks are not supported; a session ends when it "
                        + "is destroyed, its TTL lapses, or its node fails or leaves");
            }
        }
        String node = text(request, "Node");
        if (node.isBlank()) throw new NodeRequired();
        String behaviorText = text(request, "Behavior");
        SessionBehavior behavior = behaviorText.isEmpty() ? SessionBehavior.RELEASE
                : SessionBehavior.ofApiName(behaviorText);
        Duration ttl = ttl(text(request, "TTL"));
        Duration lockDelay = lockDelay(request.get("LockDelay"));
        return new Session(UUID.randomUUID().toString(), node, text(request, "Name"), behavior, ttl, lockDelay, 0,
                server.clock.instant());
    }

    private static String text(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be a string");
        return value.asText();
    }

    private static Duration ttl(String text) {
        if (text.isEmpty()) return Duration.ZERO;
        Duration ttl = duration(text, "TTL");
        if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("TTL must be between " + MIN_TTL_TEXT + " and " + MAX_TTL_TEXT + ", not "
                    + text);
        }
        return ttl;
    }

    private static Duration lockDelay(JsonNode value) {
        Duration delay;
        if (value == null || value.isNull()) {
            delay = DEFAULT_LOCK_DELAY;
        } else if (value.isIntegralNumber()) {
            long amount = value.asLong();
            if (amount < 0) throw new IllegalArgumentException("LockDelay cannot be negative: " + amount);
            delay = amount < LOCK_DELAY_SECONDS_THRESHOLD ? Duration.ofSeconds(amount) : Duration.ofNanos(amount);
        } else if (value.isTextual()) {
            delay = value.asText().isEmpty() ? DEFAULT_LOCK_DELAY : duration(value.asText(), "LockDelay");
        } else {
            throw new IllegalArgumentException("LockDelay must be a duration, such as 15s, or a number");
        }
        return delay.compareTo(MAX_LOCK_DELAY) > 0 ? MAX_LOCK_DELAY : delay;
    }

    private static Duration duration(String text, String field) {
        if (text.equals("0") || text.equals("0s")) return Duration.ZERO;
        try {
            return AclApiRoutes.duration(text);
        } catch (IllegalArgumentException notDuration) {
            throw new IllegalArgumentException(field + " must be a duration, such as 15s or 1m: " + text);
        }
    }

    /** A session in Consul's form: the lock delay in nanoseconds and the TTL as Go writes a duration. */
    static Map<String, Object> answer(Session session) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("ID", session.id());
        answer.put("Name", session.name());
        answer.put("Node", session.node());
        answer.put("LockDelay", session.lockDelay().toNanos());
        answer.put("Behavior", session.behavior().apiName());
        answer.put("TTL", session.ttl().isZero() ? "" : goDuration(session.ttl()));
        answer.put("CreateIndex", session.createIndex());
        answer.put("ModifyIndex", session.createIndex());
        return answer;
    }

    /** {@code duration} as Go's {@code time.Duration.String} writes it, such as {@code 15s}, {@code 1m30s} or {@code 24h0m0s}. */
    static String goDuration(Duration duration) {
        long hours = duration.toHours();
        int minutes = duration.toMinutesPart();
        int seconds = duration.toSecondsPart();
        int nanos = duration.toNanosPart();
        String secondsText = Integer.toString(seconds);
        if (nanos != 0) {
            secondsText += "." + String.format("%09d", nanos).replaceAll("0+$", "");
        }
        if (hours > 0) return hours + "h" + minutes + "m" + secondsText + "s";
        if (minutes > 0) return minutes + "m" + secondsText + "s";
        return secondsText + "s";
    }

    private static final class NodeRequired extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        NodeRequired() {
            super("Node names the node the session is bound to; the server has none of its own");
        }
    }
}

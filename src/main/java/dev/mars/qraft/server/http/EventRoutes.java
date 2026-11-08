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
import dev.mars.qraft.common.events.EventCategory;
import dev.mars.qraft.common.events.EventConsumer;
import dev.mars.qraft.common.events.EventCursor;
import dev.mars.qraft.common.events.EventEnvelope;
import dev.mars.qraft.common.events.EventFilter;
import dev.mars.qraft.common.events.EventJournal;
import dev.mars.qraft.common.events.EventPage;
import dev.mars.qraft.common.events.EventSubscription;
import dev.mars.qraft.common.events.SubscriptionEnd;
import dev.mars.qraft.server.acl.AclPrincipal;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One server's events over HTTP (event query and watch list, decision 2): {@code GET /v1/operator/events} pages through
 * the server's journal after {@code after} ({@code <boot>:<sequence>}, the boot's start by default), at most
 * {@code limit} events (100 by default, 1,000 at most), of a {@code category} and {@code type} if given; it answers the
 * events, the cursor to ask from next and the page's status. It needs {@code operator:read}.
 *
 * <p>{@code GET /v1/operator/events/watch} (decision 3) takes the same cursor and filters, the limit aside, and answers
 * server-sent events: each event the journal holds after the cursor, then each as it is published, with its cursor as
 * the {@code id}, its type as the {@code event} and its view as the {@code data}, after an opening comment that answers
 * a quiet watch at once; a comment line as a heartbeat after
 * each interval without one; and, unless the client left, a last {@code end} event whose data names the {@code reason}
 * ({@code overflow}, {@code history_gap}, {@code other_boot}, {@code failed} or {@code server_stopping}) and the cursor
 * to resume after ({@code resumeAfter}). A watch is ended as {@code server_stopping} before the server stops listening.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
final class EventRoutes {
    static final String CONTEXT = "/v1/operator/events";
    static final String WATCH = CONTEXT + "/watch";
    /** How long stopping waits for the open watches to send their end. */
    private static final Duration END_GRACE = Duration.ofSeconds(5);
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1000;
    private static final AclRequest OPERATOR_READ = AclRequest.single(AclResource.OPERATOR, AclAccess.READ);
    private static final Set<String> PARAMETERS = Set.of("after", "limit", "category", "type");

    private final HttpApiServer server;
    private volatile Duration heartbeat = Duration.ofSeconds(15);
    private final Set<Watch> watches = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;

    EventRoutes(HttpApiServer server) {
        this.server = server;
    }

    void heartbeat(Duration interval) {
        this.heartbeat = Objects.requireNonNull(interval, "interval");
    }

    int openWatches() {
        return watches.size();
    }

    /** Ends every open watch as {@code server_stopping}, waiting a bounded time for each to send its end. */
    void closeWatches() {
        stopping = true;
        List<Watch> open = List.copyOf(watches);
        open.forEach(Watch::stop);
        long deadline = System.nanoTime() + END_GRACE.toNanos();
        try {
            for (Watch watch : open) {
                if (!watch.done.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) break;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        boolean watch = path.equals(WATCH);
        if (!path.equals(CONTEXT) && !watch) {
            server.respondError(exchange, 404, "not_found", "No such route: " + path, false);
            return;
        }
        if (!server.prepareStateRequest(exchange, "GET")) return;
        AclPrincipal principal = server.principal(exchange);
        if (principal == null || server.refused(exchange, principal, OPERATOR_READ)) return;
        EventJournal journal = server.eventJournal();
        if (journal == null) {
            server.respondError(exchange, 503, "events_unavailable", "This server keeps no event journal", true);
            return;
        }
        Request request;
        try {
            request = request(exchange, journal);
        } catch (IllegalArgumentException invalid) {
            server.respondError(exchange, 400, "invalid_query", HttpApiServer.safeMessage(invalid), false);
            return;
        }
        if (watch) {
            watch(exchange, journal, request);
            return;
        }
        EventPage page = journal.recent(request.after(), request.filter(), request.limit());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("events", page.events().stream().map(this::view).toList());
        answer.put("next", cursor(page.next()));
        answer.put("status", page.status().name().toLowerCase(Locale.ROOT));
        server.respondJson(exchange, 200, answer);
    }

    /** Replays then follows {@code request}'s events on {@code exchange} until the subscription ends. */
    private void watch(HttpExchange exchange, EventJournal journal, Request request) throws IOException {
        Watch watch = new Watch(exchange.getResponseBody());
        EventSubscription subscription;
        try {
            subscription = journal.subscribe(request.after(), request.filter(), watch);
        } catch (IllegalArgumentException invalid) {
            server.respondError(exchange, 400, "invalid_query", HttpApiServer.safeMessage(invalid), false);
            return;
        }
        watches.add(watch);
        try {
            watch.subscription = subscription;
            if (stopping) watch.stop();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            try {
                exchange.sendResponseHeaders(200, 0);
            } catch (IOException left) {
                watch.gone = true;
                subscription.close();
            }
            // The server sends the headers with the first body it flushes: an opening comment answers a quiet watch now.
            if (!watch.write(": watching\n\n")) subscription.close();
            watch.open.countDown();
            while (!watch.finished.await(heartbeat.toMillis(), TimeUnit.MILLISECONDS)) {
                if (!watch.write(":\n\n")) subscription.close();
            }
            String reason = watch.stopped ? "server_stopping" : switch (watch.end.reason()) {
                case CANCELLED -> null;
                case OVERFLOW -> "overflow";
                case HISTORY_GAP -> "history_gap";
                case OTHER_BOOT -> "other_boot";
                case FAILED -> "failed";
                case CLOSED -> "server_stopping";
            };
            if (reason != null) {
                Map<String, Object> end = new LinkedHashMap<>();
                end.put("reason", reason);
                end.put("resumeAfter", cursor(watch.end.resumeAfter()));
                watch.write("event: end\ndata: " + server.objectMapper.writeValueAsString(end) + "\n\n");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            subscription.close();
        } finally {
            watches.remove(watch);
            exchange.close();
            watch.done.countDown();
        }
    }

    /**
     * One open watch: the subscription's events are written from its thread once the headers are sent, the heartbeats
     * and the end from the request's thread, one at a time.
     */
    private final class Watch implements EventConsumer {
        private final OutputStream body;
        private final CountDownLatch open = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile EventSubscription subscription;
        private volatile SubscriptionEnd end;
        private volatile boolean stopped;
        private volatile boolean gone;

        Watch(OutputStream body) {
            this.body = body;
        }

        @Override
        public void accept(EventEnvelope event) throws Exception {
            open.await();
            String frame = "id: " + cursor(new EventCursor(event.id().bootId(), event.id().sequence())) + "\nevent: "
                    + event.event().type() + "\ndata: " + server.objectMapper.writeValueAsString(view(event)) + "\n\n";
            if (!write(frame)) subscription.close();
        }

        @Override
        public void ended(SubscriptionEnd end) {
            this.end = end;
            finished.countDown();
        }

        /** Ends this watch as the server stopping. */
        void stop() {
            stopped = true;
            EventSubscription current = subscription;
            if (current != null) current.close();
        }

        /** Writes {@code text} and sends it now; false once the client has left. */
        synchronized boolean write(String text) {
            if (gone) return false;
            try {
                body.write(text.getBytes(StandardCharsets.UTF_8));
                body.flush();
                return true;
            } catch (IOException left) {
                gone = true;
                return false;
            }
        }
    }

    /** A request's cursor, filter and limit. */
    record Request(EventCursor after, EventFilter filter, int limit) { }

    static Request request(HttpExchange exchange, EventJournal journal) {
        Map<String, String> query = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null && !raw.isEmpty()) {
            for (String parameter : raw.split("&")) {
                if (parameter.isEmpty()) continue;
                int equals = parameter.indexOf('=');
                String name = URLDecoder.decode(equals < 0 ? parameter : parameter.substring(0, equals),
                        StandardCharsets.UTF_8);
                if (!PARAMETERS.contains(name)) throw new IllegalArgumentException("Unsupported query parameter: " + name);
                query.putIfAbsent(name, equals < 0 ? "" : URLDecoder.decode(parameter.substring(equals + 1),
                        StandardCharsets.UTF_8));
            }
        }
        EventCursor after = query.containsKey("after") ? parseCursor(query.get("after"))
                : EventCursor.start(journal.bootId());
        int limit = DEFAULT_LIMIT;
        if (query.containsKey("limit")) {
            try {
                limit = Integer.parseInt(query.get("limit"));
            } catch (NumberFormatException notNumber) {
                throw new IllegalArgumentException("limit must be a number: " + query.get("limit"));
            }
            if (limit < 1 || limit > MAX_LIMIT) {
                throw new IllegalArgumentException("limit must be 1 to " + MAX_LIMIT + ": " + limit);
            }
        }
        Set<EventCategory> categories = Set.of();
        if (query.containsKey("category")) {
            try {
                categories = Set.of(EventCategory.valueOf(query.get("category").toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("category must be system, domain, observation or audit: "
                        + query.get("category"));
            }
        }
        Set<String> types = query.containsKey("type") ? Set.of(query.get("type")) : Set.of();
        return new Request(after, new EventFilter(categories, types, Optional.empty(), Optional.empty()), limit);
    }

    static EventCursor parseCursor(String text) {
        int colon = text.lastIndexOf(':');
        if (colon < 1) throw new IllegalArgumentException("after is <boot>:<sequence>: " + text);
        try {
            return new EventCursor(UUID.fromString(text.substring(0, colon)), Long.parseLong(text.substring(colon + 1)));
        } catch (IllegalArgumentException notCursor) {
            throw new IllegalArgumentException("after is <boot>:<sequence>: " + text);
        }
    }

    static String cursor(EventCursor cursor) {
        return cursor.bootId() + ":" + cursor.sequence();
    }

    /** An event as the query and the watch answer it. */
    Map<String, Object> view(EventEnvelope event) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("cursor", event.id().bootId() + ":" + event.id().sequence());
        view.put("node", event.id().nodeId());
        view.put("observedAt", event.observedAt().toString());
        view.put("category", event.category().name().toLowerCase(Locale.ROOT));
        view.put("severity", event.severity().name().toLowerCase(Locale.ROOT));
        view.put("term", event.term());
        event.committedIndex().ifPresent(index -> view.put("committedIndex", index));
        event.replicatedId().ifPresent(id -> view.put("replicatedId", id.toString()));
        event.tenant().ifPresent(tenant -> view.put("tenant", tenant));
        event.namespace().ifPresent(namespace -> view.put("namespace", namespace));
        event.correlationId().ifPresent(id -> view.put("correlationId", id));
        view.put("type", event.event().type());
        view.put("event", server.objectMapper.convertValue(event.event(), LinkedHashMap.class));
        return view;
    }
}

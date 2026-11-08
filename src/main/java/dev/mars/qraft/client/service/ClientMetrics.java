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
import dev.mars.qraft.client.catalog.ServiceReconciler;
import dev.mars.qraft.common.http.JsonApi;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A client's metrics (observability list, Phase 2, decision 4), at {@code GET /v1/client/metrics}, Consul's
 * {@code /v1/agent/metrics} under Qraft's name: {@code qraft.client.ready}, {@code qraft.client.last_contact_age_seconds}
 * (-1 before any server has answered), {@code qraft.client.server}, 1 with the {@code endpoint} the client tries first
 * as its label (the client endpoint metric list, decision 1), and {@code qraft.client.reconciliations} by result, {@code clean} for a pass the
 * servers took whole, {@code rejected} for one they refused something in, and {@code failed} for one that reached no
 * server. Answered as Consul's JSON, or with {@code ?format=prometheus} in Prometheus' text format. A client opens no
 * telemetry port of its own; reading its metrics needs {@code client:read}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class ClientMetrics {
    private static final String ROUTE = "/v1/client/metrics";
    private static final List<String> RESULTS = List.of("clean", "rejected", "failed");

    private final BooleanSupplier ready;
    private final Supplier<java.net.URI> server;
    private final Supplier<Instant> lastContact;
    private final Clock clock;
    private final Map<String, LongAdder> passes = new LinkedHashMap<>();
    private final JsonApi api = new JsonApi(new ObjectMapper());

    public ClientMetrics(BooleanSupplier ready, Supplier<Instant> lastContact, Supplier<java.net.URI> server,
                         Clock clock) {
        this.ready = Objects.requireNonNull(ready, "ready");
        this.lastContact = Objects.requireNonNull(lastContact, "lastContact");
        this.server = Objects.requireNonNull(server, "server");
        this.clock = Objects.requireNonNull(clock, "clock");
        RESULTS.forEach(result -> passes.put(result, new LongAdder()));
    }

    /** Counts a reconciliation pass by its result, or as {@code failed} when it ended in {@code failure}. */
    public void pass(ServiceReconciler.Result result, Throwable failure) {
        String outcome = failure != null || result == null ? "failed"
                : result.rejections().isEmpty() ? "clean" : "rejected";
        passes.get(outcome).increment();
    }

    /** The metrics in Consul's JSON form: a timestamp, the gauges, and the counters with their labels. */
    public Map<String, Object> json() {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("Timestamp", clock.instant().toString());
        List<Map<String, Object>> gauges = new ArrayList<>();
        gauges.add(gauge("qraft.client.ready", ready.getAsBoolean() ? 1 : 0));
        gauges.add(gauge("qraft.client.last_contact_age_seconds", lastContactAge()));
        Map<String, Object> endpoint = gauge("qraft.client.server", 1);
        endpoint.put("Labels", Map.of("endpoint", server.get().toString()));
        gauges.add(endpoint);
        answer.put("Gauges", gauges);
        List<Map<String, Object>> counters = new ArrayList<>();
        passes.forEach((result, count) -> {
            Map<String, Object> counter = new LinkedHashMap<>();
            counter.put("Name", "qraft.client.reconciliations");
            counter.put("Count", count.sum());
            counter.put("Labels", Map.of("result", result));
            counters.add(counter);
        });
        answer.put("Counters", counters);
        return answer;
    }

    /** The metrics in Prometheus' text format, version 0.0.4. */
    public String prometheus() {
        StringBuilder text = new StringBuilder();
        text.append("# TYPE qraft_client_ready gauge\nqraft_client_ready ").append(ready.getAsBoolean() ? 1 : 0)
                .append('\n');
        text.append("# TYPE qraft_client_last_contact_age_seconds gauge\nqraft_client_last_contact_age_seconds ")
                .append(lastContactAge()).append('\n');
        text.append("# TYPE qraft_client_server_info gauge\nqraft_client_server_info{endpoint=\"")
                .append(labelValue(server.get().toString())).append("\"} 1\n");
        text.append("# TYPE qraft_client_reconciliations_total counter\n");
        passes.forEach((result, count) -> text.append("qraft_client_reconciliations_total{result=\"").append(result)
                .append("\"} ").append(count.sum()).append('\n'));
        return text.toString();
    }

    /** {@code value} escaped as Prometheus' text format escapes a label's value. */
    private static String labelValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** Serves {@code GET /v1/client/metrics} on {@code localApi}, under {@code acl}. */
    public void install(LocalApiServer localApi, ClientAuthorizer acl) {
        Objects.requireNonNull(acl, "acl");
        localApi.route(ROUTE, exchange -> serve(exchange, acl));
    }

    private void serve(HttpExchange exchange, ClientAuthorizer acl) throws IOException {
        if (!exchange.getRequestURI().getPath().equals(ROUTE)) {
            api.error(exchange, 404, "not_found", "No such route: " + exchange.getRequestURI().getPath(), false,
                    Map.of());
            return;
        }
        if (!api.requireMethod(exchange, "GET")) return;
        String query = exchange.getRequestURI().getRawQuery();
        boolean prometheus = "format=prometheus".equals(query);
        if (query != null && !query.isEmpty() && !prometheus) {
            api.error(exchange, 400, "invalid_query", "The metrics take only format=prometheus: " + query, false,
                    Map.of());
            return;
        }
        if (acl.refused(exchange, acl.clientRead())) return;
        if (!prometheus) {
            api.json(exchange, 200, json());
            return;
        }
        byte[] body = prometheus().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private long lastContactAge() {
        Instant contact = lastContact.get();
        return contact == null ? -1 : Math.max(0, Duration.between(contact, clock.instant()).toSeconds());
    }

    private static Map<String, Object> gauge(String name, long value) {
        Map<String, Object> gauge = new LinkedHashMap<>();
        gauge.put("Name", name);
        gauge.put("Value", value);
        gauge.put("Labels", Map.of());
        return gauge;
    }
}

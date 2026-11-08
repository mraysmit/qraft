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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

import java.util.Locale;
import java.util.Set;

/**
 * The server's request metrics (observability list, Phase 2, decision 3): {@code qraft.http.requests} by route, method
 * and status, and {@code qraft.http.request.duration}, in milliseconds, by route and method. The route is the prefix
 * the handler was registered under, never the path, and a method outside HTTP's own is {@code OTHER}, so every label is
 * bounded.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
final class RequestMetrics {
    private static final AttributeKey<String> ROUTE = AttributeKey.stringKey("route");
    private static final AttributeKey<String> METHOD = AttributeKey.stringKey("method");
    private static final AttributeKey<String> STATUS = AttributeKey.stringKey("status");
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "PUT", "POST", "DELETE", "PATCH", "OPTIONS");

    private final LongCounter requests;
    private final DoubleHistogram duration;

    RequestMetrics(Meter meter) {
        requests = meter.counterBuilder("qraft.http.requests")
                .setDescription("HTTP requests answered, by route, method and status").setUnit("{request}").build();
        duration = meter.histogramBuilder("qraft.http.request.duration")
                .setDescription("Time to answer an HTTP request, by route and method").setUnit("ms").build();
    }

    void record(String route, String method, int status, long elapsedNanos) {
        String known = method == null ? "OTHER" : method.toUpperCase(Locale.ROOT);
        if (!METHODS.contains(known)) known = "OTHER";
        requests.add(1, Attributes.of(ROUTE, route, METHOD, known, STATUS, status < 0 ? "none" : Integer.toString(status)));
        duration.record(elapsedNanos / 1_000_000.0, Attributes.of(ROUTE, route, METHOD, known));
    }
}

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

package dev.mars.qraft.state;

import dev.mars.qraft.common.Node;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;
import dev.mars.qraft.state.kv.KvCommand;
import dev.mars.qraft.state.kv.KvScope;
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the state metrics as Prometheus scrapes them (observability list, Phase 4): through the same Prometheus reader a
 * server exports on {@code server.telemetry.prometheusPort}, the store's counters and gauge appear under Prometheus'
 * names, with their bounded labels and nothing that names a service or key.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class PrometheusExportTest {
    private static final Instant T0 = Instant.parse("2026-11-08T12:00:00Z");

    @Test
    void theStateMetricsAreScrapedUnderPrometheusNamesWithTheirBoundedLabels() throws Exception {
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        PrometheusHttpServer prometheus = PrometheusHttpServer.builder().setHost("127.0.0.1").setPort(port).build();
        try (SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(prometheus).build();
             HttpClient http = HttpClient.newHttpClient()) {
            QraftStateStore store = new QraftStateStore(provider.get(StateMetrics.METER_NAME));
            store.apply(NodeCommand.register(Node.of("host-a", "10.0.0.1", null, null, Map.of()), T0));
            store.setLastAppliedIndex(1);
            store.apply(CatalogCommand.register(new ServiceInstance("billing-7", "billing", "host-a", "10.0.0.1", 8080,
                    List.of(), Map.of(), ServiceHealth.PASSING, "default", "default", "", "", true)));
            store.setLastAppliedIndex(2);
            store.apply(KvCommand.set(KvScope.DEFAULT, "secret/path", "v".getBytes(StandardCharsets.UTF_8), 0, null));
            store.setLastAppliedIndex(3);

            String scraped = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + port + "/metrics")).build(), HttpResponse.BodyHandlers.ofString())
                    .body();

            assertTrue(scraped.contains("qraft_catalog_registrations_total{"), scraped);
            assertTrue(scraped.contains("result=\"registered\""), scraped);
            assertTrue(scraped.contains("qraft_kv_applies_total{"), scraped);
            assertTrue(scraped.contains("operation=\"set\""), scraped);
            assertTrue(scraped.contains("qraft_session_active"), scraped);
            assertTrue(!scraped.contains("billing-7") && !scraped.contains("secret/path"),
                    "no label names a service or a key: " + scraped);
        } finally {
            prometheus.close();
        }
    }
}

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.client.catalog.CatalogOutcome;
import dev.mars.qraft.client.catalog.ServiceReconciler;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests a client's metrics (observability list, Phase 2, decision 4): reconciliation passes counted by result,
 * readiness, and the age of the last successful contact with a server, answered in Consul's JSON form of
 * {@code /v1/agent/metrics} and in Prometheus' text format.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class ClientMetricsTest {
    private static final Instant NOW = Instant.parse("2026-11-08T12:00:30Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void passesAreCountedByResultBesideReadinessAndTheLastContactsAge() throws Exception {
        AtomicBoolean ready = new AtomicBoolean(true);
        AtomicReference<Instant> lastContact = new AtomicReference<>(NOW.minusSeconds(12));
        ClientMetrics metrics = new ClientMetrics(ready::get, lastContact::get, Clock.fixed(NOW, ZoneOffset.UTC));

        metrics.pass(new ServiceReconciler.Result(1, 0, Map.of(), NOW), null);
        metrics.pass(new ServiceReconciler.Result(0, 0, Map.of(), NOW), null);
        metrics.pass(new ServiceReconciler.Result(0, 0, Map.of("web",
                new CatalogOutcome.Rejected("invalid_registration", "bad", null)), NOW), null);
        metrics.pass(null, new IllegalStateException("no server answered"));

        JsonNode json = JSON.valueToTree(metrics.json());
        assertEquals(NOW.toString(), json.path("Timestamp").asText());
        assertEquals(1, gauge(json, "qraft.client.ready"));
        assertEquals(12, gauge(json, "qraft.client.last_contact_age_seconds"));
        assertEquals(2, counter(json, "clean"));
        assertEquals(1, counter(json, "rejected"));
        assertEquals(1, counter(json, "failed"));

        String text = metrics.prometheus();
        assertTrue(text.contains("# TYPE qraft_client_ready gauge\nqraft_client_ready 1\n"), text);
        assertTrue(text.contains("qraft_client_reconciliations_total{result=\"clean\"} 2\n"), text);
        assertTrue(text.contains("qraft_client_last_contact_age_seconds 12\n"), text);

        ready.set(false);
        lastContact.set(null);
        JsonNode later = JSON.valueToTree(metrics.json());
        assertEquals(0, gauge(later, "qraft.client.ready"));
        assertEquals(-1, gauge(later, "qraft.client.last_contact_age_seconds"), "no server reached yet");
    }

    private static long gauge(JsonNode json, String name) {
        for (JsonNode gauge : json.path("Gauges")) {
            if (gauge.path("Name").asText().equals(name)) return gauge.path("Value").asLong();
        }
        throw new AssertionError("no gauge " + name + " in " + json);
    }

    private static long counter(JsonNode json, String result) {
        for (JsonNode counter : json.path("Counters")) {
            if (counter.path("Name").asText().equals("qraft.client.reconciliations")
                    && counter.path("Labels").path("result").asText().equals(result)) {
                return counter.path("Count").asLong();
            }
        }
        throw new AssertionError("no reconciliation count for " + result + " in " + json);
    }
}

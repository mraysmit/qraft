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
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.state.catalog.HealthObservation;
import dev.mars.qraft.state.catalog.ServiceCheckId;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;
import dev.mars.qraft.state.kv.KvCommand;
import dev.mars.qraft.state.kv.KvScope;
import dev.mars.qraft.state.session.Session;
import dev.mars.qraft.state.session.SessionBehavior;
import dev.mars.qraft.state.session.SessionCommand;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the state metrics (observability list, Phase 1, decisions 1 and 2): the store counts what it applies, so every
 * replica counts the same changes, with bounded labels only: registrations and deregistrations by result, a service's
 * health transitions by state, key/value applies by operation and result, the active sessions, invalidations by reason,
 * and acquisitions by result.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class QraftStateStoreMetricsTest {
    private static final Instant T0 = Instant.parse("2026-11-08T12:00:00Z");
    private final InMemoryMetricReader reader = InMemoryMetricReader.create();
    private final SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    private final QraftStateStore store = new QraftStateStore(provider.get(StateMetrics.METER_NAME));
    private long index;

    @AfterEach
    void close() {
        provider.close();
    }

    private RaftCommandResult<?> apply(RaftCommand command) {
        RaftCommandResult<?> result = store.apply(command);
        store.setLastAppliedIndex(++index);
        return result;
    }

    private static ServiceInstance service(String id, String node) {
        return new ServiceInstance(id, "web", node, "10.0.0.1", 8080, List.of(), Map.of(), ServiceHealth.PASSING,
                "default", "default", "", "", true);
    }

    @Test
    void registrationsDeregistrationsAndHealthTransitionsAreCountedByResultAndState() {
        apply(NodeCommand.register(Node.of("host-a", "10.0.0.1", null, null, Map.of()), T0));
        apply(CatalogCommand.register(service("web-1", "host-a")));
        apply(CatalogCommand.register(service("web-2", "host-a")));
        apply(CatalogCommand.deregister(service("web-2", "host-a").identity()));
        apply(CatalogCommand.deregister(service("absent", "host-a").identity()));
        ServiceCheckId check = new ServiceCheckId(service("web-1", "host-a").identity(), "http");
        apply(CatalogCommand.observe(new HealthObservation(check, ServiceHealth.CRITICAL, 1, T0, 30_000, true, "down", 0),
                T0));
        apply(CatalogCommand.observe(new HealthObservation(check, ServiceHealth.PASSING, 2, T0, 30_000, true, "up", 0),
                T0));

        assertEquals(2, counter("qraft.catalog.registrations", "result", "registered"));
        assertEquals(1, counter("qraft.catalog.deregistrations", "result", "deregistered"));
        assertEquals(1, counter("qraft.catalog.deregistrations", "result", "not_found"));
        assertEquals(1, counter("qraft.health.transitions", Attributes.of(AttributeKey.stringKey("from"), "passing",
                AttributeKey.stringKey("to"), "critical")));
        assertEquals(1, counter("qraft.health.transitions", Attributes.of(AttributeKey.stringKey("from"), "critical",
                AttributeKey.stringKey("to"), "passing")));
    }

    @Test
    void keyValueSessionsAndLocksAreCountedByOperationResultAndReason() {
        KvScope scope = KvScope.DEFAULT;
        apply(NodeCommand.register(Node.of("host-a", "10.0.0.1", null, null, Map.of()), T0));
        apply(KvCommand.set(scope, "a", bytes("1"), 0, null));
        apply(KvCommand.set(scope, "a", bytes("2"), 0, 0L));
        apply(KvCommand.delete(scope, "a", null));
        for (String id : List.of("s1", "s2", "s3")) {
            apply(SessionCommand.create(new Session(id, "host-a", id, SessionBehavior.RELEASE, Duration.ZERO,
                    Duration.ZERO, 0, T0)));
        }
        apply(KvCommand.lock(scope, "leader", bytes("me"), 0, "s1", T0));
        apply(KvCommand.lock(scope, "leader", bytes("them"), 0, "s2", T0));
        apply(SessionCommand.destroy("s1", T0));

        assertEquals(1, counter("qraft.kv.applies", Attributes.of(AttributeKey.stringKey("operation"), "set",
                AttributeKey.stringKey("result"), "applied")));
        assertEquals(1, counter("qraft.kv.applies", Attributes.of(AttributeKey.stringKey("operation"), "set",
                AttributeKey.stringKey("result"), "not_applied")));
        assertEquals(1, counter("qraft.kv.applies", Attributes.of(AttributeKey.stringKey("operation"), "delete",
                AttributeKey.stringKey("result"), "applied")));
        assertEquals(1, counter("qraft.lock.acquisitions", "result", "acquired"));
        assertEquals(1, counter("qraft.lock.acquisitions", "result", "contended"));
        assertEquals(1, counter("qraft.session.invalidations", "reason", "destroyed"));
        assertEquals(2, gauge("qraft.session.active"));

        apply(NodeCommand.deregister("host-a"));
        assertEquals(2, counter("qraft.session.invalidations", "reason", "node"), "the node's two sessions");
        assertEquals(0, gauge("qraft.session.active"));
    }

    private Collection<MetricData> metrics() {
        return reader.collectAllMetrics();
    }

    private long counter(String name, String key, String value) {
        return counter(name, Attributes.of(AttributeKey.stringKey(key), value));
    }

    private long counter(String name, Attributes attributes) {
        return metrics().stream().filter(metric -> metric.getName().equals(name))
                .flatMap(metric -> metric.getLongSumData().getPoints().stream())
                .filter(point -> point.getAttributes().equals(attributes)).mapToLong(LongPointData::getValue).sum();
    }

    private long gauge(String name) {
        return metrics().stream().filter(metric -> metric.getName().equals(name))
                .flatMap(metric -> metric.getLongGaugeData().getPoints().stream()).mapToLong(LongPointData::getValue)
                .sum();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}

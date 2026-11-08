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

import dev.mars.qraft.state.catalog.ServiceHealth;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * The state metrics (observability list, Phase 1, decisions 1 and 2), counted where commands are applied, so every
 * replica counts the same changes. Every label is bounded: a result, an operation, a health state or a reason, and never
 * a service, key, session or node name.
 *
 * <ul>
 *   <li>{@code qraft.catalog.registrations} and {@code qraft.catalog.deregistrations}, by {@code result};</li>
 *   <li>{@code qraft.health.transitions}, a service's aggregate health changing, by {@code from} and {@code to};</li>
 *   <li>{@code qraft.kv.applies}, by {@code operation} and {@code result};</li>
 *   <li>{@code qraft.session.active}, a gauge, and {@code qraft.session.invalidations}, by {@code reason};</li>
 *   <li>{@code qraft.lock.acquisitions}, by {@code result}.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class StateMetrics {
    /** The meter the state store's instruments come from. */
    public static final String METER_NAME = "qraft-state";

    private static final AttributeKey<String> RESULT = AttributeKey.stringKey("result");
    private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("operation");
    private static final AttributeKey<String> REASON = AttributeKey.stringKey("reason");
    private static final AttributeKey<String> FROM = AttributeKey.stringKey("from");
    private static final AttributeKey<String> TO = AttributeKey.stringKey("to");

    private final LongCounter registrations;
    private final LongCounter deregistrations;
    private final LongCounter healthTransitions;
    private final LongCounter kvApplies;
    private final LongCounter sessionInvalidations;
    private final LongCounter lockAcquisitions;

    StateMetrics(Meter meter, LongSupplier activeSessions) {
        registrations = meter.counterBuilder("qraft.catalog.registrations")
                .setDescription("Service registrations applied, by result").setUnit("{registration}").build();
        deregistrations = meter.counterBuilder("qraft.catalog.deregistrations")
                .setDescription("Service deregistrations applied, by result").setUnit("{deregistration}").build();
        healthTransitions = meter.counterBuilder("qraft.health.transitions")
                .setDescription("Changes of a service's aggregate health, by the state it left and the state it took")
                .setUnit("{transition}").build();
        kvApplies = meter.counterBuilder("qraft.kv.applies")
                .setDescription("Key/value commands applied, by operation and result").setUnit("{command}").build();
        sessionInvalidations = meter.counterBuilder("qraft.session.invalidations")
                .setDescription("Sessions invalidated, by reason").setUnit("{session}").build();
        lockAcquisitions = meter.counterBuilder("qraft.lock.acquisitions")
                .setDescription("Lock acquisitions applied, by result").setUnit("{acquisition}").build();
        meter.gaugeBuilder("qraft.session.active").setDescription("Sessions that exist").setUnit("{session}").ofLongs()
                .buildWithCallback(measurement -> measurement.record(activeSessions.getAsLong()));
    }

    void registration(String result) {
        registrations.add(1, Attributes.of(RESULT, result));
    }

    void deregistration(String result) {
        deregistrations.add(1, Attributes.of(RESULT, result));
    }

    void healthTransition(ServiceHealth from, ServiceHealth to) {
        healthTransitions.add(1, Attributes.of(FROM, label(from), TO, label(to)));
    }

    void kvApply(String operation, boolean applied) {
        kvApplies.add(1, Attributes.of(OPERATION, operation, RESULT, applied ? "applied" : "not_applied"));
    }

    void sessionInvalidated(String reason) {
        sessionInvalidations.add(1, Attributes.of(REASON, reason));
    }

    void lockAcquisition(boolean acquired) {
        lockAcquisitions.add(1, Attributes.of(RESULT, acquired ? "acquired" : "contended"));
    }

    private static String label(ServiceHealth health) {
        return health == null ? "unknown" : health.name().toLowerCase(Locale.ROOT);
    }
}

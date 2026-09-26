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

package dev.mars.qraft.agent.service;

import dev.mars.qraft.agent.catalog.ControllerContactTracker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Derived agent readiness; it owns no mutable lifecycle state.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
public final class ReadinessPolicy {
    private final BooleanSupplier running;
    private final BooleanSupplier nodeRegistered;
    private final BooleanSupplier servicesConverged;
    private final BooleanSupplier requiredChecksSatisfied;
    private final ControllerContactTracker contactTracker;
    private final Duration freshnessWindow;
    private final Clock clock;

    public ReadinessPolicy(BooleanSupplier running, BooleanSupplier nodeRegistered,
                           BooleanSupplier servicesConverged,
                           BooleanSupplier requiredChecksSatisfied,
                           ControllerContactTracker contactTracker,
                           Duration freshnessWindow, Clock clock) {
        this.running = Objects.requireNonNull(running, "running");
        this.nodeRegistered = Objects.requireNonNull(nodeRegistered, "nodeRegistered");
        this.servicesConverged = Objects.requireNonNull(servicesConverged, "servicesConverged");
        this.requiredChecksSatisfied = Objects.requireNonNull(requiredChecksSatisfied, "requiredChecksSatisfied");
        this.contactTracker = Objects.requireNonNull(contactTracker, "contactTracker");
        this.freshnessWindow = Objects.requireNonNull(freshnessWindow, "freshnessWindow");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (freshnessWindow.isZero() || freshnessWindow.isNegative()) {
            throw new IllegalArgumentException("freshnessWindow must be positive");
        }
    }

    public boolean isReady() {
        if (!running.getAsBoolean() || !nodeRegistered.getAsBoolean()
                || !servicesConverged.getAsBoolean()
                || !requiredChecksSatisfied.getAsBoolean()) return false;
        Instant contact = contactTracker.lastSuccessfulContact();
        if (contact == null) return false;
        Duration age = Duration.between(contact, clock.instant());
        return age.isNegative() || age.compareTo(freshnessWindow) <= 0;
    }
}

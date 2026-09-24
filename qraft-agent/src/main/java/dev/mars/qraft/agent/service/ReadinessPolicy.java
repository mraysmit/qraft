package dev.mars.qraft.agent.service;

import dev.mars.qraft.agent.catalog.ControllerContactTracker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Derived agent readiness; it owns no mutable lifecycle state. */
public final class ReadinessPolicy {
    private final BooleanSupplier running;
    private final BooleanSupplier nodeRegistered;
    private final BooleanSupplier servicesConverged;
    private final ControllerContactTracker contactTracker;
    private final Duration freshnessWindow;
    private final Clock clock;

    public ReadinessPolicy(BooleanSupplier running, BooleanSupplier nodeRegistered,
                           BooleanSupplier servicesConverged,
                           ControllerContactTracker contactTracker,
                           Duration freshnessWindow, Clock clock) {
        this.running = Objects.requireNonNull(running, "running");
        this.nodeRegistered = Objects.requireNonNull(nodeRegistered, "nodeRegistered");
        this.servicesConverged = Objects.requireNonNull(servicesConverged, "servicesConverged");
        this.contactTracker = Objects.requireNonNull(contactTracker, "contactTracker");
        this.freshnessWindow = Objects.requireNonNull(freshnessWindow, "freshnessWindow");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (freshnessWindow.isZero() || freshnessWindow.isNegative()) {
            throw new IllegalArgumentException("freshnessWindow must be positive");
        }
    }

    public boolean isReady() {
        if (!running.getAsBoolean() || !nodeRegistered.getAsBoolean()
                || !servicesConverged.getAsBoolean()) return false;
        Instant contact = contactTracker.lastSuccessfulContact();
        if (contact == null) return false;
        Duration age = Duration.between(contact, clock.instant());
        return age.isNegative() || age.compareTo(freshnessWindow) <= 0;
    }
}

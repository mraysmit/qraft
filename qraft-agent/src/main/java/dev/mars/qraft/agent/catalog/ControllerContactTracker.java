package dev.mars.qraft.agent.catalog;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Records the most recent successfully classified controller response. */
public final class ControllerContactTracker {
    private final Clock clock;
    private final AtomicReference<Instant> lastSuccessfulContact = new AtomicReference<>();

    public ControllerContactTracker(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void recordSuccessfulContact() { lastSuccessfulContact.set(clock.instant()); }
    public Instant lastSuccessfulContact() { return lastSuccessfulContact.get(); }
}

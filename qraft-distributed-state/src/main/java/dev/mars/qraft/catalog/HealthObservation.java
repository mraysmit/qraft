package dev.mars.qraft.catalog;

import java.time.Instant;
import java.util.Objects;

/** Ordered agent observation for one service health check. */
public record HealthObservation(
        ServiceCheckId checkId,
        ServiceHealth status,
        long sequenceNumber,
        Instant observedAt,
        long ttlMillis,
        boolean required,
        String output) {

    public HealthObservation {
        Objects.requireNonNull(checkId, "checkId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(observedAt, "observedAt");
        observedAt = Instant.ofEpochMilli(observedAt.toEpochMilli());
        if (status != ServiceHealth.PASSING
                && status != ServiceHealth.WARNING
                && status != ServiceHealth.CRITICAL
                && status != ServiceHealth.MAINTENANCE) {
            throw new IllegalArgumentException(
                    "observation status must be PASSING, WARNING, CRITICAL, or MAINTENANCE");
        }
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("sequenceNumber must be positive");
        }
        if (ttlMillis < 1) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        output = output == null ? "" : output;
    }
}

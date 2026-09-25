package dev.mars.qraft.catalog;

import java.time.Instant;
import java.util.Objects;

/** Replicated accepted state and server-derived deadline for one health check. */
public record HealthCheckState(
        HealthObservation observation,
        Instant acceptedAt,
        Instant deadline,
        boolean expired) {

    public HealthCheckState {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.isBefore(acceptedAt)) {
            throw new IllegalArgumentException("deadline must not precede acceptedAt");
        }
    }

    public ServiceCheckId checkId() {
        return observation.checkId();
    }

    public HealthCheckState asExpired() {
        return expired ? this : new HealthCheckState(observation, acceptedAt, deadline, true);
    }
}

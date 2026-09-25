package dev.mars.qraft.catalog;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HealthObservationTest {
    private static final ServiceCheckId CHECK = new ServiceCheckId(
            new ServiceInstanceId("tenant-a", "production", "node-1", "web"), "http");

    @Test
    void carriesCompleteOrderedCheckIdentityAndTiming() {
        Instant observedAt = Instant.parse("2026-09-25T09:00:00Z");
        HealthObservation observation = new HealthObservation(
                CHECK, ServiceHealth.PASSING, 7, observedAt, 30_000, true, "HTTP 200");

        assertEquals(CHECK, observation.checkId());
        assertEquals(7, observation.sequenceNumber());
        assertEquals(observedAt, observation.observedAt());
        assertEquals(30_000, observation.ttlMillis());
        assertEquals("HTTP 200", observation.output());
    }

    @Test
    void rejectsInvalidObservationValues() {
        Instant now = Instant.parse("2026-09-25T09:00:00Z");

        assertThrows(IllegalArgumentException.class,
                () -> new HealthObservation(CHECK, ServiceHealth.UNKNOWN, 1, now, 1, true, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new HealthObservation(CHECK, ServiceHealth.PASSING, 0, now, 1, true, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new HealthObservation(CHECK, ServiceHealth.PASSING, 1, now, 0, true, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceCheckId(CHECK.serviceInstanceId(), " "));
    }

    @Test
    void normalizesObservedTimeToTheReplicatedMillisecondPrecision() {
        HealthObservation observation = new HealthObservation(
                CHECK, ServiceHealth.PASSING, 1,
                Instant.parse("2026-09-25T09:00:00.123456789Z"), 1, true, "");

        assertEquals(Instant.parse("2026-09-25T09:00:00.123Z"), observation.observedAt());
    }
}

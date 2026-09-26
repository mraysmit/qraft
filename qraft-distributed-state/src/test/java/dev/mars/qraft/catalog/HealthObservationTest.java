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

package dev.mars.qraft.catalog;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link HealthObservation} check identity and timing, value validation, and millisecond
 * normalization of observed time.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-25
 * @version 1.0
 */
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

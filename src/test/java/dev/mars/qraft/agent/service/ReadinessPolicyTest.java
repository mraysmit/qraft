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
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ReadinessPolicy} readiness conditions: running, registered, converged services,
 * satisfied required checks, and fresh controller contact.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
class ReadinessPolicyTest {
    @Test
    void requiresRunningRegisteredConvergedAndFreshContact() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-24T12:00:00Z"));
        ControllerContactTracker contact = new ControllerContactTracker(clock);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean registered = new AtomicBoolean(false);
        AtomicBoolean converged = new AtomicBoolean(false);
        ReadinessPolicy policy = new ReadinessPolicy(running::get, registered::get,
                converged::get, () -> true, contact, Duration.ofSeconds(30), clock);

        assertFalse(policy.isReady(), "no accepted node registration");
        registered.set(true);
        contact.recordSuccessfulContact();
        assertFalse(policy.isReady(), "required services have not converged");
        converged.set(true);
        assertTrue(policy.isReady());

        clock.advance(Duration.ofSeconds(31));
        assertFalse(policy.isReady(), "stale controller contact must expire readiness");
        contact.recordSuccessfulContact();
        assertTrue(policy.isReady(), "successful contact must restore readiness");

        running.set(false);
        assertFalse(policy.isReady(), "shutdown must always be unready");
    }

    @Test
    void noServiceAgentIsReadyAfterNodeRegistrationAndFreshContact() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-24T12:00:00Z"));
        ControllerContactTracker contact = new ControllerContactTracker(clock);
        AtomicBoolean registered = new AtomicBoolean(true);
        contact.recordSuccessfulContact();
        ReadinessPolicy policy = new ReadinessPolicy(() -> true, registered::get,
                () -> true, () -> true, contact, Duration.ofMinutes(1), clock);

        assertTrue(policy.isReady());
    }

    @Test
    void failingRequiredChecksWithdrawReadinessUntilTheyRecover() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-26T12:00:00Z"));
        ControllerContactTracker contact = new ControllerContactTracker(clock);
        contact.recordSuccessfulContact();
        AtomicBoolean checksSatisfied = new AtomicBoolean(false);
        ReadinessPolicy policy = new ReadinessPolicy(() -> true, () -> true,
                () -> true, checksSatisfied::get, contact, Duration.ofMinutes(1), clock);

        assertFalse(policy.isReady(), "a required check that is critical or has no result withdraws readiness");
        checksSatisfied.set(true);
        assertTrue(policy.isReady());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}

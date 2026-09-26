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

package dev.mars.qraft.agent.health;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link RequiredCheckReadiness} policy: every required check must have produced a result
 * and its latest result must not be critical; optional checks never affect readiness.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
class RequiredCheckReadinessTest {
    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private static final TtlCheck REQUIRED = new TtlCheck("web", "app", Duration.ofSeconds(10), true);
    private static final TtlCheck ALSO_REQUIRED = new TtlCheck("db", "app", Duration.ofSeconds(10), true);
    private static final TtlCheck OPTIONAL = new TtlCheck("web", "cache", Duration.ofSeconds(10), false);

    @Test
    void noRequiredChecksIsSatisfied() {
        RequiredCheckReadiness readiness = new RequiredCheckReadiness(List.of(OPTIONAL));

        assertTrue(readiness.isSatisfied());
        readiness.onResult(OPTIONAL, result(CheckStatus.CRITICAL));
        assertTrue(readiness.isSatisfied(), "an optional check never withdraws readiness");
    }

    @Test
    void everyRequiredCheckMustReportANonCriticalResult() {
        RequiredCheckReadiness readiness = new RequiredCheckReadiness(List.of(REQUIRED, ALSO_REQUIRED, OPTIONAL));

        assertFalse(readiness.isSatisfied(), "a required check without a result is not yet satisfied");
        readiness.onResult(REQUIRED, result(CheckStatus.PASSING));
        assertFalse(readiness.isSatisfied());
        readiness.onResult(ALSO_REQUIRED, result(CheckStatus.WARNING));
        assertTrue(readiness.isSatisfied(), "warning does not withdraw readiness");
        readiness.onResult(REQUIRED, result(CheckStatus.MAINTENANCE));
        assertTrue(readiness.isSatisfied(), "maintenance does not withdraw readiness");

        readiness.onResult(ALSO_REQUIRED, result(CheckStatus.CRITICAL));
        assertFalse(readiness.isSatisfied());
        readiness.onResult(ALSO_REQUIRED, result(CheckStatus.PASSING));
        assertTrue(readiness.isSatisfied(), "readiness returns when the check recovers");
    }

    private static CheckResult result(CheckStatus status) {
        return new CheckResult(status, "", NOW);
    }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TtlCheckRunner} reporting, local expiry, renewal, and stop, driven by
 * {@link ManualTime}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
class TtlCheckRunnerTest {
    private static final Instant START = Instant.parse("2026-09-26T12:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(15);

    private final ManualTime time = new ManualTime(START);
    private final RecordingListener results = new RecordingListener();
    private final TtlCheckRunner runner = new TtlCheckRunner(
            new TtlCheck("web", "app", TTL, true), time, time, results);

    @Test
    void reportedStatusesAreDeliveredImmediatelyWithBoundedOutput() throws Exception {
        runner.start();

        assertTrue(runner.report(CheckStatus.PASSING, "ok"));
        assertResult(results.next(), CheckStatus.PASSING, "ok", START);
        time.advance(Duration.ofSeconds(1));
        assertTrue(runner.report(CheckStatus.WARNING, "x".repeat(CheckResult.MAX_OUTPUT_LENGTH + 10)));
        CheckResult warning = results.next();
        assertEquals(CheckStatus.WARNING, warning.status());
        assertEquals("x".repeat(CheckResult.MAX_OUTPUT_LENGTH), warning.output());
        assertTrue(runner.report(CheckStatus.MAINTENANCE, null));
        assertResult(results.next(), CheckStatus.MAINTENANCE, "", START.plusSeconds(1));
        assertTrue(runner.report(CheckStatus.CRITICAL, "dependency down"));
        assertResult(results.next(), CheckStatus.CRITICAL, "dependency down", START.plusSeconds(1));
        assertThrows(NullPointerException.class, () -> runner.report(null, "missing"));
    }

    @Test
    void missingRenewalExpiresLocallyAsCriticalAndALaterReportRecovers() throws Exception {
        runner.start();

        time.advance(TTL.minusMillis(1));
        results.assertNoResult();
        time.advance(Duration.ofMillis(1));
        assertResult(results.next(), CheckStatus.CRITICAL,
                "No status reported within 15000 ms", START.plus(TTL));

        runner.report(CheckStatus.PASSING, "ready");
        assertResult(results.next(), CheckStatus.PASSING, "ready", START.plus(TTL));
        time.advance(TTL);
        assertResult(results.next(), CheckStatus.CRITICAL,
                "TTL of 15000 ms expired without a status report", START.plus(TTL.multipliedBy(2)));
        assertEquals(0, time.pendingTasks(), "an expired check waits for the next report");

        runner.report(CheckStatus.PASSING, "recovered");
        assertResult(results.next(), CheckStatus.PASSING, "recovered", START.plus(TTL.multipliedBy(2)));
        assertEquals(1, time.pendingTasks());
    }

    @Test
    void renewalsRearmExactlyOneExpiry() throws Exception {
        runner.start();

        for (int renewal = 0; renewal < 5; renewal++) {
            time.advance(Duration.ofSeconds(10));
            assertTrue(runner.report(CheckStatus.PASSING, "renewal " + renewal));
            assertEquals(CheckStatus.PASSING, results.next().status());
            assertEquals(1, time.pendingTasks());
        }
        time.advance(TTL.minusMillis(1));
        results.assertNoResult();
    }

    @Test
    void reportsAreRefusedBeforeStartAndAfterStopAndStopCancelsExpiry() throws Exception {
        assertFalse(runner.report(CheckStatus.PASSING, "too early"));
        runner.start();
        runner.start();
        assertEquals(1, time.pendingTasks());
        assertTrue(runner.report(CheckStatus.PASSING, "ok"));
        results.next();

        runner.stop();

        assertEquals(0, time.pendingTasks());
        assertFalse(runner.report(CheckStatus.PASSING, "too late"));
        time.advance(TTL.multipliedBy(3));
        results.assertNoResult();
    }

    @Test
    void checkResultsBoundOutputAndDefaultMissingOutput() {
        CheckResult bounded = new CheckResult(CheckStatus.PASSING, "y".repeat(5000), START);
        assertEquals(CheckResult.MAX_OUTPUT_LENGTH, bounded.output().length());
        assertEquals("", new CheckResult(CheckStatus.PASSING, null, START).output());
        assertThrows(NullPointerException.class, () -> new CheckResult(null, "", START));
        assertThrows(NullPointerException.class, () -> new CheckResult(CheckStatus.PASSING, "", null));
    }

    private static void assertResult(CheckResult result, CheckStatus status, String output, Instant observedAt) {
        assertEquals(status, result.status(), result.output());
        assertEquals(output, result.output());
        assertEquals(observedAt, result.observedAt());
    }
}

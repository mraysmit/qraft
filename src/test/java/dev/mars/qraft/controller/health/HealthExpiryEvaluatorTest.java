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

package dev.mars.qraft.controller.health;

import dev.mars.qraft.catalog.HealthCheckState;
import dev.mars.qraft.catalog.HealthObservation;
import dev.mars.qraft.catalog.ServiceCheckId;
import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstanceId;
import dev.mars.qraft.controller.state.CatalogCommand;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the side-effect-free {@link HealthExpiryEvaluator}: exact deadline boundaries, the grace period
 * after leadership is acquired, two-phase expiry and deregistration, deterministic ordering, and
 * saturating time arithmetic.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
class HealthExpiryEvaluatorTest {
    private static final Instant ACCEPTED = Instant.parse("2026-09-26T12:00:00Z");
    private static final Instant DEADLINE = ACCEPTED.plusSeconds(30);
    /** A leadership term that began long before the deadline, so no grace applies. */
    private static final Instant LONG_LEADER = ACCEPTED.minusSeconds(3_600);

    private final HealthExpiryEvaluator evaluator = new HealthExpiryEvaluator();

    @Test
    void aCheckExpiresExactlyAtItsServerDerivedDeadline() {
        HealthCheckState state = state("http", 7, 30_000, 0, false);

        ExpiryPlan before = evaluator.plan(List.of(state), DEADLINE.minusMillis(1), LONG_LEADER);
        ExpiryPlan at = evaluator.plan(List.of(state), DEADLINE, LONG_LEADER);

        assertEquals(List.of(), before.commands());
        assertEquals(Optional.of(DEADLINE), before.nextDue());
        assertEquals(List.of(CatalogCommand.expire(state.checkId(), 7, DEADLINE, false)), at.commands(),
                "the command carries the stored sequence and deadline the state machine matches");
    }

    @Test
    void aNewLeaderGrantsEveryCheckAFullTtlBeforeExpiringIt() {
        HealthCheckState state = state("http", 7, 30_000, 0, false);
        Instant electedAfterDeadline = DEADLINE.plusSeconds(10);

        ExpiryPlan withinGrace = evaluator.plan(List.of(state), electedAfterDeadline.plusSeconds(29),
                electedAfterDeadline);
        ExpiryPlan afterGrace = evaluator.plan(List.of(state), electedAfterDeadline.plusSeconds(30),
                electedAfterDeadline);

        assertEquals(List.of(), withinGrace.commands(),
                "renewals may have failed while there was no leader, so a failover must not mass-expire");
        assertEquals(Optional.of(electedAfterDeadline.plusSeconds(30)), withinGrace.nextDue());
        assertEquals(List.of(CatalogCommand.expire(state.checkId(), 7, DEADLINE, false)), afterGrace.commands(),
                "the grace period changes when to expire, never the deadline the command names");
    }

    @Test
    void anExpiredCheckDeregistersItsServiceOnlyAfterItsDelay() {
        HealthCheckState expired = state("ttl", 3, 30_000, 60_000, true);

        ExpiryPlan waiting = evaluator.plan(List.of(expired), DEADLINE.plusSeconds(59), LONG_LEADER);
        ExpiryPlan due = evaluator.plan(List.of(expired), DEADLINE.plusSeconds(60), LONG_LEADER);

        assertEquals(List.of(), waiting.commands(), "an already expired check is not expired again");
        assertEquals(Optional.of(DEADLINE.plusSeconds(60)), waiting.nextDue());
        assertEquals(List.of(CatalogCommand.expire(expired.checkId(), 3, DEADLINE, true)), due.commands());
    }

    @Test
    void aZeroDelayNeverDeregistersAndSchedulesNothingFurther() {
        HealthCheckState expired = state("ttl", 3, 30_000, 0, true);

        ExpiryPlan plan = evaluator.plan(List.of(expired), DEADLINE.plus(Duration.ofDays(365)), LONG_LEADER);

        assertEquals(List.of(), plan.commands());
        assertEquals(Optional.empty(), plan.nextDue());
    }

    @Test
    void deregistrationAlsoWaitsForTheLeadershipGracePeriod() {
        HealthCheckState expired = state("ttl", 3, 30_000, 60_000, true);
        Instant elected = DEADLINE.plusSeconds(120);

        ExpiryPlan plan = evaluator.plan(List.of(expired), elected.plusSeconds(89), elected);

        assertEquals(List.of(), plan.commands());
        assertEquals(Optional.of(elected.plusSeconds(90)), plan.nextDue());
    }

    @Test
    void dueCommandsAreDeterministicallyOrderedAndTheEarliestFutureDeadlineIsReported() {
        HealthCheckState laterDue = state("b", 1, 40_000, 0, false);
        HealthCheckState due = state("c", 1, 10_000, 0, false);
        HealthCheckState alsoDue = state("a", 1, 20_000, 0, false);
        HealthCheckState notDue = state("d", 1, 60_000, 0, false);
        Instant now = ACCEPTED.plusSeconds(25);

        ExpiryPlan plan = evaluator.plan(List.of(laterDue, notDue, due, alsoDue), now, LONG_LEADER);

        assertEquals(List.of(alsoDue.checkId(), due.checkId()),
                plan.commands().stream().map(CatalogCommand.ExpireHealth::checkId).toList());
        assertEquals(Optional.of(ACCEPTED.plusSeconds(40)), plan.nextDue());
    }

    @Test
    void nothingToEvaluateProducesAnEmptyPlan() {
        assertEquals(new ExpiryPlan(List.of(), Optional.empty()), evaluator.plan(List.of(), ACCEPTED, LONG_LEADER));
    }

    @Test
    void extremeDurationsSaturateInsteadOfOverflowing() {
        HealthCheckState state = new HealthCheckState(new HealthObservation(check("huge"), ServiceHealth.PASSING, 1,
                ACCEPTED, 30_000, true, "", Long.MAX_VALUE), ACCEPTED, DEADLINE, true);
        HealthCheckState hugeTtl = new HealthCheckState(new HealthObservation(check("ttl"), ServiceHealth.PASSING, 1,
                ACCEPTED, Long.MAX_VALUE, true, ""), ACCEPTED, DEADLINE, false);

        ExpiryPlan plan = evaluator.plan(List.of(state, hugeTtl), ACCEPTED.plusSeconds(60), ACCEPTED.plusSeconds(60));

        assertEquals(List.of(), plan.commands());
    }

    private static HealthCheckState state(String checkId, long sequence, long ttlMillis,
                                          long deregisterAfterMillis, boolean expired) {
        HealthObservation observation = new HealthObservation(check(checkId), ServiceHealth.PASSING, sequence,
                ACCEPTED, ttlMillis, true, "", deregisterAfterMillis);
        return new HealthCheckState(observation, ACCEPTED, ACCEPTED.plusMillis(ttlMillis), expired);
    }

    private static ServiceCheckId check(String checkId) {
        return new ServiceCheckId(new ServiceInstanceId("default", "default", "node-1", "web"), checkId);
    }
}

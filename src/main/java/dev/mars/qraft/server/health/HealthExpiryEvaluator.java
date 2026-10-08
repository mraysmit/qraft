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

package dev.mars.qraft.server.health;

import dev.mars.qraft.state.catalog.HealthCheckState;
import dev.mars.qraft.state.CatalogCommand;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides which replicated health checks are due for expiry or automatic deregistration. It has no
 * side effects and reads no clock; the caller supplies the leader's current time.
 *
 * <p>A check is due for expiry at its server-derived deadline. Because agents cannot renew while there
 * is no leader, a new leader first grants every check one full TTL from the moment it acquired
 * leadership: the effective deadline is the later of the stored deadline and {@code leaderSince + ttl}.
 * An expired check whose observation carries a positive deregistration delay is due for deregistration
 * that long after its effective deadline. Commands always name the stored sequence number and deadline,
 * so the state machine turns an expiry raced by a renewal into a no-op.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public final class HealthExpiryEvaluator {
    private static final Comparator<HealthCheckState> CHECK_ORDER =
            Comparator.comparing((HealthCheckState state) -> state.checkId().serviceInstanceId().tenantId())
                    .thenComparing(state -> state.checkId().serviceInstanceId().namespace())
                    .thenComparing(state -> state.checkId().serviceInstanceId().nodeId())
                    .thenComparing(state -> state.checkId().serviceInstanceId().serviceId())
                    .thenComparing(state -> state.checkId().checkId());

    public ExpiryPlan plan(List<HealthCheckState> checks, Instant now, Instant leaderSince) {
        Objects.requireNonNull(checks, "checks");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaderSince, "leaderSince");
        List<CatalogCommand.ExpireHealth> due = new ArrayList<>();
        Instant nextDue = null;
        for (HealthCheckState state : checks.stream().sorted(CHECK_ORDER).toList()) {
            Instant effectiveDeadline = later(state.deadline(),
                    plusSaturating(leaderSince, state.observation().ttlMillis()));
            Instant dueAt;
            boolean deregister;
            if (!state.expired()) {
                dueAt = effectiveDeadline;
                deregister = false;
            } else if (state.observation().deregisterAfterMillis() > 0) {
                dueAt = plusSaturating(effectiveDeadline, state.observation().deregisterAfterMillis());
                deregister = true;
            } else {
                continue;
            }
            if (dueAt.equals(Instant.MAX)) continue;
            if (now.isBefore(dueAt)) {
                if (nextDue == null || dueAt.isBefore(nextDue)) nextDue = dueAt;
            } else {
                due.add((CatalogCommand.ExpireHealth) CatalogCommand.expire(state.checkId(),
                        state.observation().sequenceNumber(), state.deadline(), deregister));
            }
        }
        return new ExpiryPlan(due, Optional.ofNullable(nextDue));
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    /** Adds milliseconds, saturating at {@link Instant#MAX}, which the evaluator treats as never. */
    private static Instant plusSaturating(Instant instant, long millis) {
        if (instant.equals(Instant.MAX)) return Instant.MAX;
        try {
            return instant.plusMillis(millis);
        } catch (ArithmeticException | DateTimeException beyondRepresentableTime) {
            return Instant.MAX;
        }
    }
}

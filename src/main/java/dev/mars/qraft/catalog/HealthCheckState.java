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

import java.time.Instant;
import java.util.Objects;

/**
 * Replicated accepted state and server-derived deadline for one health check.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-25
 * @version 1.0
 */
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

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
 * Ordered agent observation for one service health check.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-25
 * @version 1.0
 */
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

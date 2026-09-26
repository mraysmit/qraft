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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One sequenced observation of a local check, as published to the controller. Retrying the same
 * instance is idempotent at the server; a new sequence number makes it a new observation or renewal.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public record CheckObservation(String serviceId, String checkId, CheckStatus status, long sequenceNumber,
                               Instant observedAt, Duration ttl, boolean required, String output) {
    public CheckObservation {
        HealthCheckDefinition.requireText(serviceId, "serviceId");
        HealthCheckDefinition.requireText(checkId, "checkId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(observedAt, "observedAt");
        HealthCheckDefinition.requirePositive(ttl, "ttl");
        if (sequenceNumber < 1) throw new IllegalArgumentException("sequenceNumber must be positive");
        output = output == null ? "" : output;
    }

    static CheckObservation of(HealthCheckDefinition check, CheckResult result, long sequenceNumber) {
        return new CheckObservation(check.serviceId(), check.checkId(), result.status(), sequenceNumber,
                result.observedAt(), check.ttl(), check.required(), result.output());
    }

    boolean reports(CheckResult result) {
        return status == result.status() && output.equals(result.output());
    }
}

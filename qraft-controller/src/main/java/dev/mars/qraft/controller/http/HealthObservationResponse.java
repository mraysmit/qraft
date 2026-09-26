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

package dev.mars.qraft.controller.http;

import dev.mars.qraft.catalog.HealthCheckState;
import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstanceId;

/**
 * Stable response body for an accepted or exactly replayed health observation.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
record HealthObservationResponse(
        String serviceId,
        String checkId,
        String nodeId,
        String tenantId,
        String namespace,
        long sequenceNumber,
        ServiceHealth status,
        String deadline,
        boolean accepted) {

    static HealthObservationResponse accepted(HealthCheckState state) {
        ServiceInstanceId instance = state.checkId().serviceInstanceId();
        return new HealthObservationResponse(instance.serviceId(), state.checkId().checkId(), instance.nodeId(),
                instance.tenantId(), instance.namespace(), state.observation().sequenceNumber(),
                state.observation().status(), state.deadline().toString(), true);
    }
}

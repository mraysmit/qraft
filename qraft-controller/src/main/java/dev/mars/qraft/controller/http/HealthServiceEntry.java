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
import dev.mars.qraft.catalog.ServiceInstance;

import java.util.Comparator;
import java.util.List;

/**
 * One service instance and its replicated checks, as returned by health discovery.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
record HealthServiceEntry(ServiceInstance service, List<Check> checks) {

    static HealthServiceEntry from(ServiceInstance service, List<HealthCheckState> states) {
        return new HealthServiceEntry(service, states.stream()
                .sorted(Comparator.comparing((HealthCheckState state) -> state.checkId().checkId()))
                .map(Check::from)
                .toList());
    }

    record Check(
            String checkId,
            ServiceHealth status,
            boolean required,
            long sequenceNumber,
            String observedAt,
            String acceptedAt,
            String deadline,
            boolean expired,
            String output) {

        static Check from(HealthCheckState state) {
            var observation = state.observation();
            return new Check(state.checkId().checkId(), observation.status(), observation.required(),
                    observation.sequenceNumber(), observation.observedAt().toString(),
                    state.acceptedAt().toString(), state.deadline().toString(), state.expired(),
                    observation.output());
        }
    }
}

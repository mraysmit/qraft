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

package dev.mars.qraft.server.http;

import dev.mars.qraft.common.NodeStatus;
import dev.mars.qraft.state.catalog.HealthCheckState;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;

import java.util.Comparator;
import java.util.List;

/**
 * One service instance, its replicated checks and its node's status, as returned by health discovery. The status
 * is {@code null} for an instance whose node has no entry.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
record HealthServiceEntry(@com.fasterxml.jackson.annotation.JsonIgnore ServiceInstance service,
                          @com.fasterxml.jackson.annotation.JsonProperty("service") java.util.Map<String, Object> serviceView,
                          List<Check> checks, NodeStatus nodeStatus) {

    /** An entry whose service is answered as {@code serviceView}, the instance with its indexes (catalog indexes list). */
    static HealthServiceEntry from(ServiceInstance service, java.util.Map<String, Object> serviceView,
                                   List<HealthCheckState> states, NodeStatus nodeStatus) {
        return new HealthServiceEntry(service, serviceView, states.stream()
                .sorted(Comparator.comparing((HealthCheckState state) -> state.checkId().checkId()))
                .map(Check::from)
                .toList(), nodeStatus);
    }

    /** Whether {@code ?passing} includes this instance: its checks pass and its node is not unreachable. */
    boolean passing() {
        return service.health() == ServiceHealth.PASSING && nodeStatus != NodeStatus.UNREACHABLE;
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
            String output,
            long deregisterAfterMillis) {

        static Check from(HealthCheckState state) {
            var observation = state.observation();
            return new Check(state.checkId().checkId(), observation.status(), observation.required(),
                    observation.sequenceNumber(), observation.observedAt().toString(),
                    state.acceptedAt().toString(), state.deadline().toString(), state.expired(),
                    observation.output(), observation.deregisterAfterMillis());
        }
    }
}

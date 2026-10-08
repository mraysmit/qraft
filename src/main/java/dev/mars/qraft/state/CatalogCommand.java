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

package dev.mars.qraft.state;

import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.state.catalog.HealthObservation;
import dev.mars.qraft.state.catalog.ServiceCheckId;
import dev.mars.qraft.state.catalog.ServiceInstance;
import dev.mars.qraft.state.catalog.ServiceInstanceId;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Mutations applied to the replicated service catalog.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-12
 * @version 1.0
 */
public sealed interface CatalogCommand extends RaftCommand
        permits CatalogCommand.Register, CatalogCommand.Deregister,
                CatalogCommand.ObserveHealth, CatalogCommand.ExpireHealth {

    /**
     * Registers or replaces an instance. {@code declaredCheckIds} lists the checks the registering
     * agent will publish, sorted and distinct; replicated checks outside the list are pruned and later
     * observations for them are rejected. {@code null} means the registration declares nothing, as
     * older agents and log entries do, and existing checks are kept.
     */
    record Register(ServiceInstance instance, List<String> declaredCheckIds) implements CatalogCommand {
        public Register {
            Objects.requireNonNull(instance, "instance");
            if (declaredCheckIds != null) {
                TreeSet<String> canonical = new TreeSet<>();
                for (String checkId : declaredCheckIds) {
                    if (checkId == null || checkId.isBlank()) {
                        throw new IllegalArgumentException("declared check identifiers must not be blank");
                    }
                    canonical.add(checkId);
                }
                declaredCheckIds = List.copyOf(canonical);
            }
        }

        public boolean declaresChecks() {
            return declaredCheckIds != null;
        }
    }

    record Deregister(String serviceId, String nodeId, String tenantId, String namespace)
            implements CatalogCommand {
        public Deregister {
            if (serviceId == null || serviceId.isBlank()) {
                throw new IllegalArgumentException("serviceId must not be blank");
            }
        }

        public boolean isLegacy() {
            return nodeId == null || nodeId.isBlank();
        }

        public ServiceInstanceId identity() {
            if (isLegacy()) throw new IllegalStateException("Legacy deregistration has no composite identity");
            return new ServiceInstanceId(tenantId, namespace, nodeId, serviceId);
        }
    }

    record ObserveHealth(HealthObservation observation, Instant acceptedAt) implements CatalogCommand {
        public ObserveHealth {
            Objects.requireNonNull(observation, "observation");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            acceptedAt = Instant.ofEpochMilli(acceptedAt.toEpochMilli());
        }
    }

    record ExpireHealth(ServiceCheckId checkId, long expectedSequenceNumber,
                        Instant expectedDeadline, boolean deregisterService) implements CatalogCommand {
        public ExpireHealth {
            Objects.requireNonNull(checkId, "checkId");
            Objects.requireNonNull(expectedDeadline, "expectedDeadline");
            expectedDeadline = Instant.ofEpochMilli(expectedDeadline.toEpochMilli());
            if (expectedSequenceNumber < 1) {
                throw new IllegalArgumentException("expectedSequenceNumber must be positive");
            }
        }
    }

    static CatalogCommand register(ServiceInstance instance) {
        return new Register(instance, null);
    }

    static CatalogCommand register(ServiceInstance instance, Collection<String> declaredCheckIds) {
        return new Register(instance, List.copyOf(Objects.requireNonNull(declaredCheckIds, "declaredCheckIds")));
    }

    static CatalogCommand deregister(String serviceId) {
        return new Deregister(serviceId, "", "", "");
    }

    static CatalogCommand deregister(ServiceInstanceId identity) {
        Objects.requireNonNull(identity, "identity");
        return new Deregister(identity.serviceId(), identity.nodeId(),
                identity.tenantId(), identity.namespace());
    }

    static CatalogCommand observe(HealthObservation observation, Instant acceptedAt) {
        return new ObserveHealth(observation, acceptedAt);
    }

    static CatalogCommand expire(ServiceCheckId checkId, long expectedSequenceNumber,
                                 Instant expectedDeadline, boolean deregisterService) {
        return new ExpireHealth(checkId, expectedSequenceNumber, expectedDeadline, deregisterService);
    }
}

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

package dev.mars.qraft.controller.state;

import dev.mars.qraft.catalog.HealthObservation;
import dev.mars.qraft.catalog.ServiceCheckId;
import dev.mars.qraft.catalog.ServiceInstance;
import dev.mars.qraft.catalog.ServiceInstanceId;

import java.time.Instant;
import java.util.Objects;

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

    record Register(ServiceInstance instance) implements CatalogCommand {
        public Register {
            Objects.requireNonNull(instance, "instance");
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
        return new Register(instance);
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

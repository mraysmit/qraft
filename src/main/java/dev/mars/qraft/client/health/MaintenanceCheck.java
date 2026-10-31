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

package dev.mars.qraft.client.health;

import java.time.Duration;

/**
 * The check that holds a service in maintenance, as Consul's agent enables maintenance by adding a check of its own.
 * Consul's check is critical, which takes the service out of discovery; a Qraft server has a {@code maintenance}
 * status of its own, which does that whatever else the service's checks say. It is not required, so it never holds
 * the client's readiness back, and an expired observation of it does not make the service critical.
 *
 * <p>A check always names a service, so a node in maintenance has one of these on each of its services.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-31
 * @version 1.0
 */
public record MaintenanceCheck(String serviceId, String checkId, String reason) implements HealthCheckDefinition {
    private static final String SERVICE_PREFIX = "_service_maintenance:";
    private static final String NODE_PREFIX = "_node_maintenance:";
    private static final Duration TTL = Duration.ofSeconds(60);

    public MaintenanceCheck {
        HealthCheckDefinition.requireText(serviceId, "serviceId");
        HealthCheckDefinition.requireText(checkId, "checkId");
        HealthCheckDefinition.requireText(reason, "reason");
    }

    /** The check of a service in maintenance by itself. */
    public static MaintenanceCheck ofService(String serviceId, String reason) {
        return new MaintenanceCheck(serviceId, SERVICE_PREFIX + serviceId, reason);
    }

    /** The check on a service whose node is in maintenance. */
    public static MaintenanceCheck ofNode(String serviceId, String reason) {
        return new MaintenanceCheck(serviceId, NODE_PREFIX + serviceId, reason);
    }

    /** Whether a check ID is one only this kind of check may have. */
    public static boolean isReserved(String checkId) {
        return checkId.startsWith(SERVICE_PREFIX) || checkId.startsWith(NODE_PREFIX);
    }

    @Override
    public Duration ttl() {
        return TTL;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    public Duration deregisterAfter() {
        return Duration.ZERO;
    }
}

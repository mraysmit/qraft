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

import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstance;

import java.util.List;
import java.util.Map;

/**
 * JSON body of a service registration; converts to a service instance using the header identity,
 * with health forced to UNKNOWN.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
record ServiceRegistrationRequest(
        String serviceId,
        String serviceName,
        String address,
        int port,
        List<String> tags,
        Map<String, String> metadata,
        String datacenter,
        String region,
        Boolean enabled,
        String health) {

    ServiceInstance toServiceInstance(RequestContext context) {
        return new ServiceInstance(serviceId, serviceName, context.nodeId(), address, port,
                tags == null ? List.of() : tags,
                metadata == null ? Map.of() : metadata,
                ServiceHealth.UNKNOWN,
                context.tenantId(), context.namespace(), datacenter, region,
                enabled == null || enabled);
    }
}

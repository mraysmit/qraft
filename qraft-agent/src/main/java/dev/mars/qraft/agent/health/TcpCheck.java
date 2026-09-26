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

/**
 * TCP connect check: an established connection is passing and anything else is critical.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public record TcpCheck(String serviceId, String checkId, String host, int port, Duration interval,
                       Duration timeout, Duration ttl, boolean required) implements ProbeCheck {
    public TcpCheck {
        HealthCheckDefinition.requireText(serviceId, "serviceId");
        HealthCheckDefinition.requireText(checkId, "checkId");
        HealthCheckDefinition.requireText(host, "TCP check address");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("TCP check port must be between 1 and 65535");
        }
        ProbeCheck.validateTiming(interval, timeout, ttl);
    }
}

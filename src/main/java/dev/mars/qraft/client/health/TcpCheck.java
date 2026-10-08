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
 * TCP connect check: an established connection is passing and anything else is critical. With a
 * {@code warnAfter} threshold, a connection that takes at least that long is a warning; a zero
 * threshold never warns.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public record TcpCheck(String serviceId, String checkId, String host, int port, Duration interval,
                       Duration timeout, Duration ttl, boolean required, Duration deregisterAfter,
                       Duration warnAfter)
        implements ProbeCheck {
    public TcpCheck {
        HealthCheckDefinition.requireText(serviceId, "serviceId");
        HealthCheckDefinition.requireText(checkId, "checkId");
        HealthCheckDefinition.requireText(host, "TCP check address");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("TCP check port must be between 1 and 65535");
        }
        ProbeCheck.validateTiming(interval, timeout, ttl);
        HealthCheckDefinition.requireNotNegative(deregisterAfter, "check deregisterAfter");
        HealthCheckDefinition.requireNotNegative(warnAfter, "TCP check warnAfter");
        if (warnAfter.compareTo(timeout) >= 0) {
            throw new IllegalArgumentException("TCP check warnAfter must be shorter than its timeout");
        }
    }

    /** TCP check that never reports a slow connection as a warning. */
    public TcpCheck(String serviceId, String checkId, String host, int port, Duration interval,
                    Duration timeout, Duration ttl, boolean required, Duration deregisterAfter) {
        this(serviceId, checkId, host, port, interval, timeout, ttl, required, deregisterAfter, Duration.ZERO);
    }

    /** TCP check whose service is never deregistered automatically. */
    public TcpCheck(String serviceId, String checkId, String host, int port, Duration interval,
                    Duration timeout, Duration ttl, boolean required) {
        this(serviceId, checkId, host, port, interval, timeout, ttl, required, Duration.ZERO, Duration.ZERO);
    }
}

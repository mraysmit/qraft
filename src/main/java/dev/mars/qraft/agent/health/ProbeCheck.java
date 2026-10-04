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
 * A check the agent actively probes on an interval with a bounded timeout.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public sealed interface ProbeCheck extends HealthCheckDefinition permits HttpCheck, TcpCheck {
    Duration interval();

    Duration timeout();

    static void validateTiming(Duration interval, Duration timeout, Duration ttl) {
        HealthCheckDefinition.requirePositive(interval, "check interval");
        HealthCheckDefinition.requirePositive(timeout, "check timeout");
        HealthCheckDefinition.requirePositive(ttl, "check ttl");
        if (timeout.compareTo(interval) > 0) {
            throw new IllegalArgumentException("check timeout must not exceed its interval");
        }
        if (ttl.compareTo(interval) <= 0) {
            throw new IllegalArgumentException("check ttl must exceed its interval");
        }
    }
}

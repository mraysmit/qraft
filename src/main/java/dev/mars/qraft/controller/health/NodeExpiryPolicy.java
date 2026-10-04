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

package dev.mars.qraft.controller.health;

import java.time.Duration;
import java.util.Objects;

/**
 * Server-wide node membership policy: a node silent for {@code ttl} is marked unreachable, and an
 * unreachable node is reaped with its services {@code reapAfter} later; a zero reap delay never reaps.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public record NodeExpiryPolicy(Duration ttl, Duration reapAfter) {
    public NodeExpiryPolicy {
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(reapAfter, "reapAfter");
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("node ttl must be positive");
        if (reapAfter.isNegative()) throw new IllegalArgumentException("node reapAfter must not be negative");
    }
}

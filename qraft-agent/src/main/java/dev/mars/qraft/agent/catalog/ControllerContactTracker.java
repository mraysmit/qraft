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

package dev.mars.qraft.agent.catalog;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Records the most recent successfully classified controller response.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
public final class ControllerContactTracker {
    private final Clock clock;
    private final AtomicReference<Instant> lastSuccessfulContact = new AtomicReference<>();

    public ControllerContactTracker(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void recordSuccessfulContact() { lastSuccessfulContact.set(clock.instant()); }
    public Instant lastSuccessfulContact() { return lastSuccessfulContact.get(); }
}

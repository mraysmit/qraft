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

import java.time.Instant;
import java.util.Objects;

/**
 * One completed local check evaluation with bounded diagnostic output.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public record CheckResult(CheckStatus status, String output, Instant observedAt) {
    /** Matches the controller's accepted observation output limit. */
    public static final int MAX_OUTPUT_LENGTH = 4096;

    public CheckResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(observedAt, "observedAt");
        output = output == null ? "" : output;
        if (output.length() > MAX_OUTPUT_LENGTH) output = output.substring(0, MAX_OUTPUT_LENGTH);
    }
}

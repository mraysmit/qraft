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

import java.util.concurrent.CompletableFuture;

/**
 * Outbound port that publishes health observations to the server. The returned future completes
 * with a classified outcome and does not complete exceptionally for transport failures.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
@FunctionalInterface
public interface ObservationClient {
    CompletableFuture<ObservationOutcome> observe(CheckObservation observation);

    /**
     * Publishes {@code observation}, which keeps the check passing only if it lands before {@code landBy}, the time,
     * on this client's clock, that the last accepted observation keeps the check for. A client that can try more than
     * one server bounds each attempt so the next can still be tried in that time (open-items task list, decision 3).
     */
    default CompletableFuture<ObservationOutcome> observe(CheckObservation observation, java.time.Instant landBy) {
        return observe(observation);
    }
}

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

/**
 * Machine-actionable result of publishing one {@link CheckObservation} across the controller seeds.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public sealed interface ObservationOutcome permits ObservationOutcome.Accepted, ObservationOutcome.Stale,
        ObservationOutcome.Retryable, ObservationOutcome.Rejected {

    /** The observation, or an exact replay of it, is committed; the deadline is server-derived. */
    record Accepted(long sequenceNumber, Instant deadline) implements ObservationOutcome { }

    /** The server already holds a newer or conflicting observation with this sequence number. */
    record Stale(long currentSequenceNumber) implements ObservationOutcome { }

    /** A transport or server outcome that may succeed at another controller or later. */
    record Retryable(String code, String message, String leaderId) implements ObservationOutcome { }

    /** A validation or semantic outcome, such as an unregistered service, from a reachable controller. */
    record Rejected(String code, String message, String leaderId) implements ObservationOutcome { }
}

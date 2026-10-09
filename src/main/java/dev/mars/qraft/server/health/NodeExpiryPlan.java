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

package dev.mars.qraft.server.health;

import dev.mars.qraft.state.ClientCommand;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Node membership expiry commands due now, ordered by node identifier, and the earliest later instant at
 * which another becomes due.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public record NodeExpiryPlan(List<ClientCommand.Expire> commands, Optional<Instant> nextDue) {
    public NodeExpiryPlan {
        commands = List.copyOf(commands);
        Objects.requireNonNull(nextDue, "nextDue");
    }
}

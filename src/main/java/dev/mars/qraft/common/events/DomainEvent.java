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

package dev.mars.qraft.common.events;

import java.util.Objects;

/**
 * A domain event as the state machine produces it (events list, Phase 4; architecture section 6): the fact, and the
 * tenant and namespace it belongs to, or {@code null} for a fact that has none, such as a node's. The node gives it its
 * committed index, term and replicated identity when it offers it.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record DomainEvent(String tenant, String namespace, QraftEvent event) {
    public DomainEvent {
        Objects.requireNonNull(event, "event");
    }
}

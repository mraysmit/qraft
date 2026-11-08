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

import java.util.List;

/**
 * A state machine that produces domain events with its changes (events list, Phase 4): once asked to record them, it
 * keeps the events of the command it last applied until they are drained, which the node does after each entry.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public interface DomainEventSource {
    /** Starts or stops recording; a state machine that is not recording keeps nothing. */
    void recordDomainEvents(boolean record);

    /** The events recorded since the last drain, in the order the changes were made, and forgets them. */
    List<DomainEvent> drainDomainEvents();
}

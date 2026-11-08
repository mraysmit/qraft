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

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * An event with its routing and ordering metadata (architecture section 5). A domain event carries the committed index
 * of the entry that produced it and its replicated identity at that index; an event of any other category carries
 * neither, as it is not a replicated fact. The observation time is for people, never for ordering.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record EventEnvelope(EventId id, Instant observedAt, EventCategory category, EventSeverity severity, long term,
                            OptionalLong committedIndex, Optional<ReplicatedEventId> replicatedId,
                            Optional<String> tenant, Optional<String> namespace, Optional<String> correlationId,
                            QraftEvent event) {
    public EventEnvelope {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(committedIndex, "committedIndex");
        Objects.requireNonNull(replicatedId, "replicatedId");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(event, "event");
        if (term < 0) throw new IllegalArgumentException("a term is never negative: " + term);
        if (category == EventCategory.DOMAIN) {
            if (committedIndex.isEmpty() || replicatedId.isEmpty()) {
                throw new IllegalArgumentException("a domain event carries its committed index and replicated identity");
            }
            ReplicatedEventId replicated = replicatedId.get();
            if (replicated.index() != committedIndex.getAsLong() || !replicated.type().equals(event.type())) {
                throw new IllegalArgumentException("a domain event's identity is its committed index's and type's: "
                        + replicated + " at " + committedIndex.getAsLong() + " for " + event.type());
            }
        } else if (committedIndex.isPresent() || replicatedId.isPresent()) {
            throw new IllegalArgumentException("only a domain event is replicated: " + category);
        }
    }

    /** A domain event produced by the entry at {@code index}, the {@code ordinal}th of its events. */
    public static EventEnvelope domain(EventId id, Instant observedAt, long term, long index, int ordinal, String tenant,
                                       String namespace, QraftEvent event) {
        return new EventEnvelope(id, observedAt, EventCategory.DOMAIN, EventSeverity.INFO, term, OptionalLong.of(index),
                Optional.of(new ReplicatedEventId(index, ordinal, event.type())), Optional.ofNullable(tenant),
                Optional.ofNullable(namespace), Optional.empty(), event);
    }

    /** A system event of one node. */
    public static EventEnvelope system(EventId id, Instant observedAt, EventSeverity severity, long term,
                                       QraftEvent event) {
        return new EventEnvelope(id, observedAt, EventCategory.SYSTEM, severity, term, OptionalLong.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), event);
    }
}

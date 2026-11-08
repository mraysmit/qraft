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
 * A domain event's replicated identity (architecture section 5): the committed index of the entry that produced it,
 * its ordinal among that entry's events, and its type. Every node that applies the entry gives the event the same
 * identity, so a consumer can tell the same fact seen through two nodes; no local field or time is part of it.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record ReplicatedEventId(long index, int ordinal, String type) {
    public ReplicatedEventId {
        if (index < 1) throw new IllegalArgumentException("a committed index starts at 1: " + index);
        if (ordinal < 0) throw new IllegalArgumentException("an ordinal is 0 or more: " + ordinal);
        Objects.requireNonNull(type, "type");
    }

    @Override
    public String toString() {
        return index + ":" + ordinal + ":" + type;
    }
}

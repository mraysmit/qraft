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
import java.util.UUID;

/**
 * A node-local event's ID (architecture section 5): the node, its boot, and the sequence within that boot, which starts
 * at 1. IDs are ordered within one boot; across boots the sequence starts again, so two IDs of different boots are not
 * ordered, and comparing them is refused rather than answered by wall-clock time.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record EventId(String nodeId, UUID bootId, long sequence) implements Comparable<EventId> {
    public EventId {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(bootId, "bootId");
        if (sequence < 1) throw new IllegalArgumentException("a local sequence starts at 1: " + sequence);
    }

    /** Whether {@code other} was raised in the same boot of the same node. */
    public boolean sameBoot(EventId other) {
        return nodeId.equals(other.nodeId) && bootId.equals(other.bootId);
    }

    @Override
    public int compareTo(EventId other) {
        if (!sameBoot(other)) {
            throw new IllegalArgumentException("events of different boots are not ordered: " + this + ", " + other);
        }
        return Long.compare(sequence, other.sequence);
    }
}

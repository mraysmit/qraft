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
 * A position in one boot's events (architecture section 8): the events after it are those of the same boot with a
 * greater sequence. A cursor of sequence 0 is the boot's start.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record EventCursor(UUID bootId, long sequence) {
    public EventCursor {
        Objects.requireNonNull(bootId, "bootId");
        if (sequence < 0) throw new IllegalArgumentException("a cursor's sequence is 0 or more: " + sequence);
    }

    /** The cursor before the first event of {@code bootId}. */
    public static EventCursor start(UUID bootId) {
        return new EventCursor(bootId, 0);
    }

    /** The cursor after {@code id}. */
    public static EventCursor after(EventId id) {
        return new EventCursor(id.bootId(), id.sequence());
    }

    /** Whether {@code id} is after this cursor: in its boot, with a greater sequence. */
    public boolean precedes(EventId id) {
        return bootId.equals(id.bootId()) && sequence < id.sequence();
    }
}

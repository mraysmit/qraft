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
import java.util.Objects;

/**
 * A page of recent events (architecture section 8), with the cursor to ask from next and why the page ends: every
 * retained event after the cursor was returned, more remain, the cursor is older than the retained history, or it
 * belongs to another boot, or the request is invalid.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record EventPage(List<EventEnvelope> events, EventCursor next, Status status) {
    public enum Status { COMPLETE, MORE, HISTORY_GAP, OTHER_BOOT, INVALID }

    public EventPage {
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(status, "status");
    }
}

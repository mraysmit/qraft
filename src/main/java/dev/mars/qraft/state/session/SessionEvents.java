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

package dev.mars.qraft.state.session;

import dev.mars.qraft.common.events.QraftEvent;

/**
 * The sessions' domain events (event query and watch list, Phase 1, decision 1).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class SessionEvents {
    private SessionEvents() { }

    public record SessionCreated(String id, String node) implements QraftEvent {
        @Override
        public String type() {
            return "session.created";
        }
    }

    /** A session ended: {@code destroyed}, which a lapsed TTL also is, or {@code node}, when its node failed or left. */
    public record SessionDestroyed(String id, String reason) implements QraftEvent {
        @Override
        public String type() {
            return "session.destroyed";
        }
    }
}

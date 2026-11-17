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

import dev.mars.qraft.common.events.SessionEvent;

/**
 * The sessions' domain events (event query and watch list, Phase 1, decision 1). Each is a {@link SessionEvent}, so one
 * session's events can be read (UI-6 Sessions, Locks and Watches list, decision 6).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class SessionEvents {
    private SessionEvents() { }

    public record SessionCreated(String id, String node) implements SessionEvent {
        @Override
        public String type() {
            return "session.created";
        }

        @Override
        public String sessionId() {
            return id;
        }
    }

    /**
     * A session ended: {@code destroyed} through the API, {@code ttl} when its TTL lapsed without renewal, or
     * {@code node}, when its node failed or left (Sessions, Locks and Watches list, decision 3). A replica that
     * predates the {@code ttl} cause records a lapse as {@code destroyed}.
     */
    public record SessionDestroyed(String id, String reason) implements SessionEvent {
        @Override
        public String type() {
            return "session.destroyed";
        }

        @Override
        public String sessionId() {
            return id;
        }
    }
}

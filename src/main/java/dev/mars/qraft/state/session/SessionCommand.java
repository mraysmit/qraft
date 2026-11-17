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

import dev.mars.qraft.raft.RaftCommand;

import java.time.Instant;
import java.util.Objects;

/**
 * A replicated session change (sessions list, Phase 1): a creation, or a destruction, which invalidates the session
 * at the time it carries, so every replica starts the same lock delays (decision 3).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public sealed interface SessionCommand extends RaftCommand permits SessionCommand.Create, SessionCommand.Destroy {
    record Create(Session session) implements SessionCommand {
        private static final long serialVersionUID = 1L;

        public Create {
            Objects.requireNonNull(session, "session");
        }
    }

    record Destroy(String id, Instant at, Cause cause) implements SessionCommand {
        private static final long serialVersionUID = 1L;

        public Destroy {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("a session needs an ID");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(cause, "cause");
        }

        public Destroy(String id, Instant at) {
            this(id, at, Cause.DESTROYED);
        }
    }

    /** Why a session was destroyed (Sessions, Locks and Watches list, decision 3); a node's failure is not a command. */
    enum Cause {
        DESTROYED("destroyed"),
        TTL("ttl");

        private final String reason;

        Cause(String reason) {
            this.reason = reason;
        }

        /** The reason a {@code session.destroyed} event and the invalidation metric give. */
        public String reason() {
            return reason;
        }
    }

    static SessionCommand create(Session session) {
        return new Create(session);
    }

    /** A destroy through the API. */
    static SessionCommand destroy(String id, Instant at) {
        return new Destroy(id, at, Cause.DESTROYED);
    }

    /** A destroy by the leader's timer, for a session whose TTL lapsed without renewal. */
    static SessionCommand lapse(String id, Instant at) {
        return new Destroy(id, at, Cause.TTL);
    }
}

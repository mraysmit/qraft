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

package dev.mars.qraft.state.kv;

import dev.mars.qraft.common.events.KeyEvent;
import dev.mars.qraft.common.events.SessionEvent;

/**
 * The key/value store's domain events (event query and watch list, Phase 1, decision 1): each carries the key, and
 * never the value (architecture section 13). Each is a {@link KeyEvent}, so one key's events can be read (UI-6 Key/Value
 * list, decision 8), and a lock's is also a {@link SessionEvent}, so one session's can be (UI-6 Sessions, Locks and
 * Watches list, decision 6).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class KvEvents {
    private KvEvents() { }

    /** A key was written; {@code created} when it did not exist. */
    public record KeySet(String key, boolean created) implements KeyEvent {
        @Override
        public String type() {
            return "key.set";
        }

        @Override
        public boolean concerns(String named) {
            return key.equals(named);
        }
    }

    public record KeyDeleted(String key) implements KeyEvent {
        @Override
        public String type() {
            return "key.deleted";
        }

        @Override
        public boolean concerns(String named) {
            return key.equals(named);
        }
    }

    /** Every key under {@code prefix} was deleted. */
    public record TreeDeleted(String prefix) implements KeyEvent {
        @Override
        public String type() {
            return "key.tree_deleted";
        }

        /** Whether {@code named} was under the prefix, so this deletion removed it if it existed. */
        @Override
        public boolean concerns(String named) {
            return named.startsWith(prefix);
        }
    }

    public record LockAcquired(String key, String session) implements KeyEvent, SessionEvent {
        @Override
        public String type() {
            return "lock.acquired";
        }

        @Override
        public boolean concerns(String named) {
            return key.equals(named);
        }

        @Override
        public String sessionId() {
            return session;
        }
    }

    public record LockReleased(String key, String session) implements KeyEvent, SessionEvent {
        @Override
        public String type() {
            return "lock.released";
        }

        @Override
        public boolean concerns(String named) {
            return key.equals(named);
        }

        @Override
        public String sessionId() {
            return session;
        }
    }
}

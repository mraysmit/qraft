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

import dev.mars.qraft.common.events.QraftEvent;

/**
 * The key/value store's domain events (event query and watch list, Phase 1, decision 1): each carries the key, and
 * never the value (architecture section 13).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class KvEvents {
    private KvEvents() { }

    /** A key was written; {@code created} when it did not exist. */
    public record KeySet(String key, boolean created) implements QraftEvent {
        @Override
        public String type() {
            return "key.set";
        }
    }

    public record KeyDeleted(String key) implements QraftEvent {
        @Override
        public String type() {
            return "key.deleted";
        }
    }

    /** Every key under {@code prefix} was deleted. */
    public record TreeDeleted(String prefix) implements QraftEvent {
        @Override
        public String type() {
            return "key.tree_deleted";
        }
    }

    public record LockAcquired(String key, String session) implements QraftEvent {
        @Override
        public String type() {
            return "lock.acquired";
        }
    }

    public record LockReleased(String key, String session) implements QraftEvent {
        @Override
        public String type() {
            return "lock.released";
        }
    }
}

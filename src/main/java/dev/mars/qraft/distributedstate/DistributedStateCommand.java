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

package dev.mars.qraft.distributedstate;

import dev.mars.qraft.raft.api.ReplicatedCommand;

import java.util.Objects;

/**
 * Generic replicated key-value command model.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
public sealed interface DistributedStateCommand extends ReplicatedCommand
        permits DistributedStateCommand.Put, DistributedStateCommand.Delete {

    String key();

    record Put(String key, String value) implements DistributedStateCommand {
        private static final long serialVersionUID = 1L;

        public Put {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    record Delete(String key) implements DistributedStateCommand {
        private static final long serialVersionUID = 1L;

        public Delete {
            Objects.requireNonNull(key, "key");
        }
    }

    static DistributedStateCommand put(String key, String value) {
        return new Put(key, value);
    }

    static DistributedStateCommand delete(String key) {
        return new Delete(key);
    }
}

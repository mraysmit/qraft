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

package dev.mars.qraft.controller.state;

import dev.mars.qraft.controller.raft.RaftConfiguration;

import java.util.Objects;

/**
 * A log entry recording the servers a cluster replicates to. The Raft node acts on it as soon as it is in the
 * log, committed or not, and never passes it to the state machine.
 *
 * @param configuration the configuration the entry records
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
public record ConfigurationCommand(RaftConfiguration configuration) implements RaftCommand {
    public ConfigurationCommand {
        Objects.requireNonNull(configuration, "configuration");
    }
}

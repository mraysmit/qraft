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

import dev.mars.qraft.raft.api.ReplicatedCommand;

import java.io.Serializable;

/**
 * Marker interface for commands applied by the controller state machine.
 *
 * <p>The active runtime operates in generic distributed-state mode and currently
 * applies {@link DistributedStateRaftCommand} values through Raft.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 1.0
 * @since 2026-02-19
 */
public interface RaftCommand extends ReplicatedCommand, Serializable {
}

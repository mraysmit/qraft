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

import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.distributedstate.DistributedStateCommandCodec;
import dev.mars.qraft.raft.api.CommandCodec;

/**
 * Adapter codec that bridges controller RaftCommand and distributed-state command codec.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
public class DistributedStateRaftCommandCodec implements CommandCodec<RaftCommand> {

    private final DistributedStateCommandCodec delegate = new DistributedStateCommandCodec();

    @Override
    public byte[] serialize(RaftCommand command) {
        if (!(command instanceof DistributedStateRaftCommand wrapped)) {
            throw new IllegalArgumentException("Unsupported command for distributed-state codec: " + command.getClass().getName());
        }
        return delegate.serialize(wrapped.delegate());
    }

    @Override
    public RaftCommand deserialize(byte[] bytes) {
        DistributedStateCommand command = delegate.deserialize(bytes);
        return new DistributedStateRaftCommand(command);
    }
}

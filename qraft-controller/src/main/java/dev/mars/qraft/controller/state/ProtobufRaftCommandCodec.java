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

import dev.mars.qraft.raft.api.CommandCodec;
import com.google.protobuf.ByteString;
import dev.mars.qraft.distributedstate.DistributedStateCommandCodec;

import java.util.Objects;

/**
 * Command codec adapter backed by the existing protobuf command serializer.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
public class ProtobufRaftCommandCodec implements CommandCodec<RaftCommand> {

    private final DistributedStateCommandCodec legacyDistributedStateCodec = new DistributedStateCommandCodec();

    @Override
    public byte[] serialize(RaftCommand command) {
        return ProtobufCommandCodec.serialize(command).toByteArray();
    }

    @Override
    public RaftCommand deserialize(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        // Releases before catalog replication encoded distributed-state WAL entries as JSON.
        // Retaining this read path allows an existing server to recover those logs during upgrade.
        if (bytes.length > 0 && bytes[0] == '{') {
            return new DistributedStateRaftCommand(legacyDistributedStateCodec.deserialize(bytes));
        }
        return ProtobufCommandCodec.deserialize(ByteString.copyFrom(bytes));
    }
}

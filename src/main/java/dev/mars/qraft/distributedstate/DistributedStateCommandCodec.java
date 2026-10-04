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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.raft.api.CommandCodec;

import java.io.IOException;

/**
 * JSON codec for distributed-state commands.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
public class DistributedStateCommandCodec implements CommandCodec<DistributedStateCommand> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public byte[] serialize(DistributedStateCommand command) {
        Envelope envelope = switch (command) {
            case DistributedStateCommand.Put put -> new Envelope("PUT", put.key(), put.value());
            case DistributedStateCommand.Delete delete -> new Envelope("DELETE", delete.key(), null);
        };

        try {
            return objectMapper.writeValueAsBytes(envelope);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize distributed state command", e);
        }
    }

    @Override
    public DistributedStateCommand deserialize(byte[] bytes) {
        try {
            Envelope envelope = objectMapper.readValue(bytes, Envelope.class);
            return switch (envelope.type()) {
                case "PUT" -> DistributedStateCommand.put(envelope.key(), envelope.value());
                case "DELETE" -> DistributedStateCommand.delete(envelope.key());
                default -> throw new IllegalArgumentException("Unknown distributed state command type: " + envelope.type());
            };
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize distributed state command", e);
        }
    }

    private record Envelope(String type, String key, String value) {
    }
}

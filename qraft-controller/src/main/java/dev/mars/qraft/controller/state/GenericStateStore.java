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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.controller.raft.RaftLogApplicator;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal key-value state machine for generic Raft service mode.
 */
public class GenericStateStore implements RaftLogApplicator {

    private static final Logger logger = LoggerFactory.getLogger(GenericStateStore.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong lastAppliedIndex = new AtomicLong(0);
    private final Map<String, String> metadata = new ConcurrentHashMap<>();

    public GenericStateStore(Map<String, String> initialMetadata) {
        if (initialMetadata != null) {
            metadata.putAll(initialMetadata);
        }
    }

    public GenericStateStore() {
        this(null);
    }

    @Override
    public CommandResult<?> apply(RaftCommand command) {
        if (command == null) {
            return new CommandResult.NoOp<>();
        }

        return switch (command) {
            case DistributedStateRaftCommand wrapped -> switch (wrapped.delegate()) {
                case DistributedStateCommand.Put put -> {
                    metadata.put(put.key(), put.value());
                    yield new CommandResult.Success<>(put.value());
                }
                case DistributedStateCommand.Delete delete -> {
                    String removed = metadata.remove(delete.key());
                    if (removed == null) {
                        yield new CommandResult.NotFound<>(delete.key(), "SystemMetadata");
                    }
                    yield new CommandResult.Success<>(removed);
                }
            };
            default -> {
                logger.warn("Rejected unsupported command type in generic mode: {}", command.getClass().getName());
                throw new IllegalArgumentException("Unsupported command type in generic mode: " + command.getClass().getName());
            }
        };
    }

    @Override
    public byte[] takeSnapshot() {
        Snapshot snapshot = new Snapshot(Map.copyOf(metadata), lastAppliedIndex.get());
        try {
            return objectMapper.writeValueAsBytes(snapshot);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize generic state snapshot", e);
        }
    }

    @Override
    public void restoreSnapshot(byte[] snapshotBytes) {
        try {
            Snapshot snapshot = objectMapper.readValue(snapshotBytes, Snapshot.class);
            metadata.clear();
            metadata.putAll(snapshot.metadata());
            lastAppliedIndex.set(snapshot.lastAppliedIndex());
        } catch (IOException e) {
            throw new RuntimeException("Failed to restore generic state snapshot", e);
        }
    }

    @Override
    public long getLastAppliedIndex() {
        return lastAppliedIndex.get();
    }

    @Override
    public void setLastAppliedIndex(long index) {
        lastAppliedIndex.set(index);
    }

    @Override
    public void reset() {
        metadata.clear();
        lastAppliedIndex.set(0);
    }

    public Map<String, String> getMetadata() {
        return Map.copyOf(metadata);
    }

    private record Snapshot(Map<String, String> metadata, long lastAppliedIndex) {
    }
}

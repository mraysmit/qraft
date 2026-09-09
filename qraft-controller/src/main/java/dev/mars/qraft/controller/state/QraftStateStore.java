package dev.mars.qraft.controller.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import dev.mars.qraft.agent.AgentInfo;
import dev.mars.qraft.agent.AgentStatus;
import dev.mars.qraft.controller.raft.RaftLogApplicator;
import dev.mars.qraft.distributedstate.DistributedStateCommand;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Replicated controller state for agents and generic distributed metadata. */
public final class QraftStateStore implements RaftLogApplicator {
    private static final String DEFAULT_VERSION = "3.0";
    private final Map<String, AgentInfo> agents = new ConcurrentHashMap<>();
    private final Map<String, String> metadata = new ConcurrentHashMap<>();
    private final AtomicLong lastAppliedIndex = new AtomicLong();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public QraftStateStore() {
        this(null);
    }

    public QraftStateStore(Map<String, String> initialMetadata) {
        metadata.put("version", DEFAULT_VERSION);
        if (initialMetadata != null) {
            metadata.putAll(initialMetadata);
        }
    }

    @Override
    public CommandResult<?> apply(RaftCommand command) {
        if (command == null) {
            return new CommandResult.NoOp<>();
        }
        return switch (command) {
            case AgentCommand agentCommand -> applyAgentCommand(agentCommand);
            case DistributedStateRaftCommand stateCommand -> applyMetadataCommand(stateCommand.delegate());
            default -> throw new IllegalArgumentException("Unsupported controller command: "
                    + command.getClass().getName());
        };
    }

    private CommandResult<?> applyAgentCommand(AgentCommand command) {
        return switch (command) {
            case AgentCommand.Register register -> {
                agents.put(register.agentId(), register.agentInfo());
                yield new CommandResult.Success<>(register.agentInfo());
            }
            case AgentCommand.Deregister deregister -> {
                AgentInfo removed = agents.remove(deregister.agentId());
                yield removed == null ? new CommandResult.NotFound<>(deregister.agentId(), "Agent")
                        : new CommandResult.Success<>(removed);
            }
            case AgentCommand.UpdateStatus update -> {
                AgentInfo current = agents.get(update.agentId());
                if (current == null) {
                    yield new CommandResult.NotFound<>(update.agentId(), "Agent");
                }
                if (current.getStatus() != update.expectedStatus()) {
                    yield new CommandResult.CasMismatch<>(current);
                }
                AgentInfo changed = AgentInfo.copyOf(current);
                changed.setStatus(update.newStatus());
                changed.setLastHeartbeat(update.timestamp());
                agents.put(update.agentId(), changed);
                yield new CommandResult.Success<>(changed);
            }
            case AgentCommand.UpdateCapabilities update -> {
                AgentInfo current = agents.get(update.agentId());
                if (current == null) {
                    yield new CommandResult.NotFound<>(update.agentId(), "Agent");
                }
                AgentInfo changed = AgentInfo.copyOf(current);
                changed.setCapabilities(update.newCapabilities());
                changed.setLastHeartbeat(update.timestamp());
                agents.put(update.agentId(), changed);
                yield new CommandResult.Success<>(changed);
            }
            case AgentCommand.Heartbeat heartbeat -> {
                AgentInfo current = agents.get(heartbeat.agentId());
                if (current == null) {
                    yield new CommandResult.NotFound<>(heartbeat.agentId(), "Agent");
                }
                AgentInfo changed = AgentInfo.copyOf(current);
                changed.setLastHeartbeat(heartbeat.timestamp());
                if (heartbeat.status() != null) {
                    changed.setStatus(heartbeat.status());
                } else if (changed.getStatus() == AgentStatus.REGISTERING) {
                    changed.setStatus(AgentStatus.HEALTHY);
                }
                agents.put(heartbeat.agentId(), changed);
                yield new CommandResult.Success<>(changed);
            }
        };
    }

    private CommandResult<?> applyMetadataCommand(DistributedStateCommand command) {
        return switch (command) {
            case DistributedStateCommand.Put put -> {
                metadata.put(put.key(), put.value());
                yield new CommandResult.Success<>(put.value());
            }
            case DistributedStateCommand.Delete delete -> {
                String removed = metadata.remove(delete.key());
                yield removed == null ? new CommandResult.NotFound<>(delete.key(), "Metadata")
                        : new CommandResult.Success<>(removed);
            }
        };
    }

    @Override
    public byte[] takeSnapshot() {
        try {
            return objectMapper.writeValueAsBytes(new Snapshot(Map.copyOf(agents), Map.copyOf(metadata),
                    lastAppliedIndex.get()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize controller snapshot", e);
        }
    }

    @Override
    public void restoreSnapshot(byte[] snapshotBytes) {
        try {
            Snapshot snapshot = objectMapper.readValue(snapshotBytes, Snapshot.class);
            agents.clear();
            agents.putAll(snapshot.agents());
            metadata.clear();
            metadata.putAll(snapshot.metadata());
            lastAppliedIndex.set(snapshot.lastAppliedIndex());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to restore controller snapshot", e);
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
        agents.clear();
        metadata.clear();
        metadata.put("version", DEFAULT_VERSION);
        lastAppliedIndex.set(0);
    }

    public Map<String, AgentInfo> getAgents() {
        return Map.copyOf(agents);
    }

    public Optional<AgentInfo> findAgent(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    public Optional<String> findMetadata(String key) {
        return Optional.ofNullable(metadata.get(key));
    }

    public String getMetadata(String key) {
        return metadata.get(key);
    }

    public Map<String, String> getMetadata() {
        return Map.copyOf(metadata);
    }

    private record Snapshot(Map<String, AgentInfo> agents, Map<String, String> metadata, long lastAppliedIndex) {
    }
}

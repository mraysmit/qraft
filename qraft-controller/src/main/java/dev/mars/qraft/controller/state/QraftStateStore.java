package dev.mars.qraft.controller.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import dev.mars.qraft.agent.AgentInfo;
import dev.mars.qraft.agent.AgentStatus;
import dev.mars.qraft.controller.raft.RaftLogApplicator;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.catalog.ServiceCatalog;
import dev.mars.qraft.catalog.ServiceInstance;

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
    private final ServiceCatalog serviceCatalog = new ServiceCatalog();
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
    public RaftCommandResult<?> apply(RaftCommand command) {
        if (command == null) {
            return new RaftCommandResult.NoOp<>();
        }
        return switch (command) {
            case AgentCommand agentCommand -> applyAgentCommand(agentCommand);
            case DistributedStateRaftCommand stateCommand -> applyMetadataCommand(stateCommand.delegate());
            case CatalogCommand catalogCommand -> applyCatalogCommand(catalogCommand);
            default -> throw new IllegalArgumentException("Unsupported controller command: "
                    + command.getClass().getName());
        };
    }

    private RaftCommandResult<?> applyCatalogCommand(CatalogCommand command) {
        return switch (command) {
            case CatalogCommand.Register register -> {
                serviceCatalog.register(register.instance());
                yield new RaftCommandResult.Success<>(register.instance());
            }
            case CatalogCommand.Deregister deregister -> serviceCatalog.deregister(deregister.serviceId())
                    ? new RaftCommandResult.Success<>(deregister.serviceId())
                    : new RaftCommandResult.NotFound<>(deregister.serviceId(), "ServiceInstance");
        };
    }

    private RaftCommandResult<?> applyAgentCommand(AgentCommand command) {
        return switch (command) {
            case AgentCommand.Register register -> {
                agents.put(register.agentId(), register.agentInfo());
                yield new RaftCommandResult.Success<>(register.agentInfo());
            }
            case AgentCommand.Deregister deregister -> {
                AgentInfo removed = agents.remove(deregister.agentId());
                yield removed == null ? new RaftCommandResult.NotFound<>(deregister.agentId(), "Agent")
                        : new RaftCommandResult.Success<>(removed);
            }
            case AgentCommand.UpdateStatus update -> {
                AgentInfo current = agents.get(update.agentId());
                if (current == null) {
                    yield new RaftCommandResult.NotFound<>(update.agentId(), "Agent");
                }
                if (current.getStatus() != update.expectedStatus()) {
                    yield new RaftCommandResult.CasMismatch<>(current);
                }
                AgentInfo changed = AgentInfo.copyOf(current);
                changed.setStatus(update.newStatus());
                changed.setLastHeartbeat(update.timestamp());
                agents.put(update.agentId(), changed);
                yield new RaftCommandResult.Success<>(changed);
            }
            case AgentCommand.UpdateCapabilities update -> {
                AgentInfo current = agents.get(update.agentId());
                if (current == null) {
                    yield new RaftCommandResult.NotFound<>(update.agentId(), "Agent");
                }
                AgentInfo changed = AgentInfo.copyOf(current);
                changed.setCapabilities(update.newCapabilities());
                changed.setLastHeartbeat(update.timestamp());
                agents.put(update.agentId(), changed);
                yield new RaftCommandResult.Success<>(changed);
            }
            case AgentCommand.Heartbeat heartbeat -> {
                AgentInfo current = agents.get(heartbeat.agentId());
                if (current == null) {
                    yield new RaftCommandResult.NotFound<>(heartbeat.agentId(), "Agent");
                }
                AgentInfo changed = AgentInfo.copyOf(current);
                changed.setLastHeartbeat(heartbeat.timestamp());
                if (heartbeat.status() != null) {
                    changed.setStatus(heartbeat.status());
                } else if (changed.getStatus() == AgentStatus.REGISTERING) {
                    changed.setStatus(AgentStatus.HEALTHY);
                }
                agents.put(heartbeat.agentId(), changed);
                yield new RaftCommandResult.Success<>(changed);
            }
        };
    }

    private RaftCommandResult<?> applyMetadataCommand(DistributedStateCommand command) {
        return switch (command) {
            case DistributedStateCommand.Put put -> {
                metadata.put(put.key(), put.value());
                yield new RaftCommandResult.Success<>(put.value());
            }
            case DistributedStateCommand.Delete delete -> {
                String removed = metadata.remove(delete.key());
                yield removed == null ? new RaftCommandResult.NotFound<>(delete.key(), "Metadata")
                        : new RaftCommandResult.Success<>(removed);
            }
        };
    }

    @Override
    public byte[] takeSnapshot() {
        try {
            return objectMapper.writeValueAsBytes(new Snapshot(Map.copyOf(agents), Map.copyOf(metadata),
                    serviceCatalog.instances(), lastAppliedIndex.get()));
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
            serviceCatalog.replaceAll(snapshot.services() == null ? java.util.List.of() : snapshot.services());
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
        serviceCatalog.clear();
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

    public ServiceCatalog getServiceCatalog() {
        return serviceCatalog;
    }

    private record Snapshot(Map<String, AgentInfo> agents, Map<String, String> metadata,
                            java.util.List<ServiceInstance> services, long lastAppliedIndex) {
    }
}

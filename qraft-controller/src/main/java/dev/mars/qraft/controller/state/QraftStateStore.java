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
import com.fasterxml.jackson.databind.DeserializationFeature;
import dev.mars.qraft.agent.AgentCapabilities;
import dev.mars.qraft.agent.AgentInfo;
import dev.mars.qraft.agent.AgentStatus;
import dev.mars.qraft.controller.raft.RaftLogApplicator;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.catalog.ServiceCatalog;
import dev.mars.qraft.catalog.ServiceCatalogView;
import dev.mars.qraft.catalog.HealthCheckState;
import dev.mars.qraft.catalog.HealthObservation;
import dev.mars.qraft.catalog.ServiceCheckId;
import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstance;
import dev.mars.qraft.catalog.ServiceInstanceId;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Replicated controller state for agents and generic distributed metadata.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
public final class QraftStateStore implements RaftLogApplicator {
    private static final String DEFAULT_VERSION = "3.0";
    /** Entity type reported when an observation names a check its registration does not declare. */
    public static final String HEALTH_CHECK_ENTITY = "HealthCheck";
    private final Map<String, AgentInfo> agents = new ConcurrentHashMap<>();
    private final Map<String, Long> heartbeatSequences = new ConcurrentHashMap<>();
    private final Map<String, String> metadata = new ConcurrentHashMap<>();
    private final ServiceCatalog serviceCatalog = new ServiceCatalog();
    private final ServiceCatalogView catalogView = ServiceCatalogView.of(serviceCatalog);
    private final Map<ServiceCheckId, HealthCheckState> healthChecks = new ConcurrentHashMap<>();
    private final Map<ServiceInstanceId, List<String>> declaredChecks = new ConcurrentHashMap<>();
    private final AtomicLong lastAppliedIndex = new AtomicLong();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            // Map entries in key order make snapshot bytes reproducible on every replica and JVM.
            .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

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
                ServiceInstanceId identity = register.instance().identity();
                serviceCatalog.register(register.instance());
                boolean pruned = false;
                if (register.declaresChecks()) {
                    declaredChecks.put(identity, register.declaredCheckIds());
                    pruned = healthChecks.keySet().removeIf(check -> check.serviceInstanceId().equals(identity)
                            && !register.declaredCheckIds().contains(check.checkId()));
                } else {
                    declaredChecks.remove(identity);
                }
                // Without checks, a registration keeps the health it carries, as older log entries expect.
                if (pruned) updateServiceHealth(identity);
                else updateServiceHealthIfObserved(identity);
                yield new RaftCommandResult.Success<>(
                        serviceCatalog.find(register.instance().identity()).orElseThrow());
            }
            case CatalogCommand.Deregister deregister -> {
                boolean removed = deregister.isLegacy()
                        ? serviceCatalog.deregisterLegacy(deregister.serviceId())
                        : serviceCatalog.deregister(deregister.identity());
                if (removed) {
                    if (deregister.isLegacy()) {
                        healthChecks.keySet().removeIf(check ->
                                check.serviceInstanceId().serviceId().equals(deregister.serviceId()));
                        declaredChecks.keySet().removeIf(instance ->
                                instance.serviceId().equals(deregister.serviceId()));
                    } else {
                        removeHealthChecks(deregister.identity());
                    }
                }
                yield removed
                        ? new RaftCommandResult.Success<>(deregister.serviceId())
                        : new RaftCommandResult.NotFound<>(deregister.serviceId(), "ServiceInstance");
            }
            case CatalogCommand.ObserveHealth observe -> applyHealthObservation(observe);
            case CatalogCommand.ExpireHealth expire -> applyHealthExpiry(expire);
        };
    }

    private RaftCommandResult<?> applyHealthObservation(CatalogCommand.ObserveHealth command) {
        HealthObservation observation = command.observation();
        ServiceCheckId checkId = observation.checkId();
        if (serviceCatalog.find(checkId.serviceInstanceId()).isEmpty()) {
            return new RaftCommandResult.NotFound<>(checkId.serviceInstanceId().toString(), "ServiceInstance");
        }
        List<String> declared = declaredChecks.get(checkId.serviceInstanceId());
        if (declared != null && !declared.contains(checkId.checkId())) {
            // A late observation must not resurrect a check its agent no longer declares.
            return new RaftCommandResult.NotFound<>(checkId.checkId(), HEALTH_CHECK_ENTITY);
        }
        HealthCheckState current = healthChecks.get(checkId);
        if (current != null
                && observation.sequenceNumber() <= current.observation().sequenceNumber()) {
            return new RaftCommandResult.Success<>(current);
        }
        HealthCheckState accepted = new HealthCheckState(observation, command.acceptedAt(),
                command.acceptedAt().plusMillis(observation.ttlMillis()), false);
        healthChecks.put(checkId, accepted);
        updateServiceHealth(checkId.serviceInstanceId());
        return new RaftCommandResult.Success<>(accepted);
    }

    private RaftCommandResult<?> applyHealthExpiry(CatalogCommand.ExpireHealth command) {
        HealthCheckState current = healthChecks.get(command.checkId());
        if (current == null
                || current.observation().sequenceNumber() != command.expectedSequenceNumber()
                || !current.deadline().equals(command.expectedDeadline())) {
            return new RaftCommandResult.NoOp<>();
        }
        ServiceInstanceId identity = command.checkId().serviceInstanceId();
        if (command.deregisterService()) {
            // Deregistration is the second phase: the same check must already have been expired.
            if (!current.expired() || !serviceCatalog.deregister(identity)) {
                return new RaftCommandResult.NoOp<>();
            }
            removeHealthChecks(identity);
            return new RaftCommandResult.Success<>(identity);
        }
        HealthCheckState expired = current.asExpired();
        healthChecks.put(command.checkId(), expired);
        updateServiceHealth(identity);
        return new RaftCommandResult.Success<>(expired);
    }

    private void updateServiceHealthIfObserved(ServiceInstanceId identity) {
        if (healthChecks.keySet().stream().anyMatch(check -> check.serviceInstanceId().equals(identity))) {
            updateServiceHealth(identity);
        }
    }

    private void updateServiceHealth(ServiceInstanceId identity) {
        if (serviceCatalog.find(identity).isEmpty()) return;
        List<HealthCheckState> states = healthChecks.values().stream()
                .filter(state -> state.checkId().serviceInstanceId().equals(identity))
                .toList();
        serviceCatalog.setHealth(identity, aggregateHealth(states));
    }

    private static ServiceHealth aggregateHealth(List<HealthCheckState> states) {
        if (states.isEmpty()) return ServiceHealth.UNKNOWN;
        boolean warning = false;
        boolean requiredCritical = false;
        boolean maintenance = false;
        for (HealthCheckState state : states) {
            ServiceHealth status = state.observation().status();
            maintenance |= status == ServiceHealth.MAINTENANCE;
            boolean critical = state.expired()
                    || status == ServiceHealth.CRITICAL
                    || status == ServiceHealth.FAILING;
            requiredCritical |= critical && state.observation().required();
            if (critical || status == ServiceHealth.WARNING) warning = true;
        }
        if (maintenance) return ServiceHealth.MAINTENANCE;
        if (requiredCritical) return ServiceHealth.CRITICAL;
        return warning ? ServiceHealth.WARNING : ServiceHealth.PASSING;
    }

    private RaftCommandResult<?> applyNodeExpiry(AgentCommand.Expire expire) {
        AgentInfo current = agents.get(expire.agentId());
        if (current == null || !expire.expectedLastContact().equals(lastContact(current))) {
            return new RaftCommandResult.NoOp<>();
        }
        boolean unreachable = current.getStatus() == AgentStatus.UNREACHABLE;
        if (!expire.reap()) {
            if (unreachable) return new RaftCommandResult.NoOp<>();
            AgentInfo changed = AgentInfo.copyOf(current);
            changed.setStatus(AgentStatus.UNREACHABLE);
            agents.put(expire.agentId(), changed);
            return new RaftCommandResult.Success<>(changed);
        }
        // Reaping is the second phase: the node must already have been marked unreachable.
        if (!unreachable) return new RaftCommandResult.NoOp<>();
        agents.remove(expire.agentId());
        heartbeatSequences.remove(expire.agentId());
        for (ServiceInstance instance : serviceCatalog.instances()) {
            if (instance.nodeId().equals(expire.agentId())) {
                serviceCatalog.deregister(instance.identity());
                removeHealthChecks(instance.identity());
            }
        }
        return new RaftCommandResult.Success<>(current);
    }

    /** A node's last contact is its last heartbeat, or its registration time before its first heartbeat. */
    public static Instant lastContact(AgentInfo agent) {
        return agent.getLastHeartbeat() != null ? agent.getLastHeartbeat() : agent.getRegistrationTime();
    }

    private void removeHealthChecks(ServiceInstanceId identity) {
        declaredChecks.remove(identity);
        healthChecks.keySet().removeIf(check -> check.serviceInstanceId().equals(identity));
    }

    private RaftCommandResult<?> applyAgentCommand(AgentCommand command) {
        return switch (command) {
            case AgentCommand.Expire expire -> applyNodeExpiry(expire);
            case AgentCommand.Register register -> {
                // Lifecycle status and times are server-owned; a registration cannot claim them.
                AgentInfo registered = detached(register.agentInfo());
                registered.setStatus(AgentStatus.REGISTERING);
                registered.setRegistrationTime(register.timestamp());
                registered.setLastHeartbeat(null);
                agents.put(register.agentId(), registered);
                heartbeatSequences.remove(register.agentId());
                yield new RaftCommandResult.Success<>(registered);
            }
            case AgentCommand.Deregister deregister -> {
                AgentInfo removed = agents.remove(deregister.agentId());
                heartbeatSequences.remove(deregister.agentId());
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
                changed.setCapabilities(objectMapper.convertValue(update.newCapabilities(), AgentCapabilities.class));
                changed.setLastHeartbeat(update.timestamp());
                agents.put(update.agentId(), changed);
                yield new RaftCommandResult.Success<>(changed);
            }
            case AgentCommand.Heartbeat heartbeat -> {
                AgentInfo current = agents.get(heartbeat.agentId());
                if (current == null) {
                    yield new RaftCommandResult.NotFound<>(heartbeat.agentId(), "Agent");
                }
                String currentRegistrationId = current.getMetadata().get(AgentInfo.REGISTRATION_ID_METADATA_KEY);
                if (heartbeat.registrationId() != null
                        && !heartbeat.registrationId().equals(currentRegistrationId)) {
                    yield new RaftCommandResult.CasMismatch<>(current);
                }
                long previousSequence = heartbeatSequences.getOrDefault(heartbeat.agentId(), 0L);
                if (heartbeat.sequenceNumber() > 0 && heartbeat.sequenceNumber() <= previousSequence) {
                    yield new RaftCommandResult.CasMismatch<>(current);
                }
                AgentInfo changed = AgentInfo.copyOf(current);
                changed.setLastHeartbeat(heartbeat.timestamp());
                if (heartbeat.status() != null) {
                    changed.setStatus(heartbeat.status());
                } else if (changed.getStatus() == AgentStatus.REGISTERING
                        || changed.getStatus() == AgentStatus.UNREACHABLE) {
                    changed.setStatus(AgentStatus.HEALTHY);
                }
                agents.put(heartbeat.agentId(), changed);
                if (heartbeat.sequenceNumber() > 0) {
                    heartbeatSequences.put(heartbeat.agentId(), heartbeat.sequenceNumber());
                }
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
            List<HealthCheckState> orderedHealthChecks = healthChecks.values().stream()
                    .sorted(Comparator.comparing((HealthCheckState state) ->
                                    state.checkId().serviceInstanceId().tenantId())
                            .thenComparing(state -> state.checkId().serviceInstanceId().namespace())
                            .thenComparing(state -> state.checkId().serviceInstanceId().nodeId())
                            .thenComparing(state -> state.checkId().serviceInstanceId().serviceId())
                            .thenComparing(state -> state.checkId().checkId()))
                    .toList();
            List<DeclaredChecks> orderedDeclarations = declaredChecks.entrySet().stream()
                    .map(entry -> new DeclaredChecks(entry.getKey(), entry.getValue()))
                    .sorted(Comparator.comparing((DeclaredChecks declared) -> declared.instance().tenantId())
                            .thenComparing(declared -> declared.instance().namespace())
                            .thenComparing(declared -> declared.instance().nodeId())
                            .thenComparing(declared -> declared.instance().serviceId()))
                    .toList();
            return objectMapper.writeValueAsBytes(new Snapshot(Map.copyOf(agents), Map.copyOf(heartbeatSequences),
                    Map.copyOf(metadata), serviceCatalog.instances(), orderedHealthChecks, orderedDeclarations,
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
            heartbeatSequences.clear();
            if (snapshot.heartbeatSequences() != null) {
                heartbeatSequences.putAll(snapshot.heartbeatSequences());
            }
            metadata.clear();
            metadata.putAll(snapshot.metadata());
            serviceCatalog.replaceAll(snapshot.services() == null ? java.util.List.of() : snapshot.services());
            healthChecks.clear();
            if (snapshot.healthChecks() != null) {
                snapshot.healthChecks().forEach(state -> healthChecks.put(state.checkId(), state));
            }
            declaredChecks.clear();
            if (snapshot.declaredChecks() != null) {
                snapshot.declaredChecks().forEach(declared ->
                        declaredChecks.put(declared.instance(), List.copyOf(declared.checkIds())));
            }
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
        heartbeatSequences.clear();
        metadata.clear();
        metadata.put("version", DEFAULT_VERSION);
        serviceCatalog.clear();
        healthChecks.clear();
        declaredChecks.clear();
        lastAppliedIndex.set(0);
    }

    /** Returns detached copies; changing them cannot change replicated state. */
    public Map<String, AgentInfo> getAgents() {
        Map<String, AgentInfo> copies = new java.util.TreeMap<>();
        agents.forEach((agentId, agent) -> copies.put(agentId, detached(agent)));
        return java.util.Collections.unmodifiableMap(copies);
    }

    /** Returns a detached copy; changing it cannot change replicated state. */
    public Optional<AgentInfo> findAgent(String agentId) {
        return Optional.ofNullable(agents.get(agentId)).map(this::detached);
    }

    /**
     * A deep copy, so replicated state never shares a mutable object with a command, a caller, or an
     * earlier version of the same agent.
     */
    private AgentInfo detached(AgentInfo agent) {
        return objectMapper.convertValue(agent, AgentInfo.class);
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

    /** Returns a read-only view; the catalog changes only through committed commands. */
    public ServiceCatalogView getServiceCatalog() {
        return catalogView;
    }

    public Optional<HealthCheckState> findHealthCheck(ServiceCheckId checkId) {
        return Optional.ofNullable(healthChecks.get(checkId));
    }

    public List<HealthCheckState> healthChecks() {
        return healthChecks.values().stream()
                .sorted(Comparator.comparing(state -> state.checkId().toString()))
                .toList();
    }

    private record Snapshot(Map<String, AgentInfo> agents, Map<String, Long> heartbeatSequences,
                            Map<String, String> metadata,
                            java.util.List<ServiceInstance> services,
                            java.util.List<HealthCheckState> healthChecks,
                            java.util.List<DeclaredChecks> declaredChecks,
                            long lastAppliedIndex) {
    }

    /** Check identifiers a registration declared for one instance; absent from snapshots written earlier. */
    private record DeclaredChecks(ServiceInstanceId instance, java.util.List<String> checkIds) {
    }
}

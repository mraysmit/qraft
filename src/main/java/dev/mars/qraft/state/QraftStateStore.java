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

package dev.mars.qraft.state;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.NodeStatus;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftLogApplicator;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import dev.mars.qraft.state.catalog.ServiceCatalog;
import dev.mars.qraft.state.catalog.ServiceCatalogView;
import dev.mars.qraft.state.catalog.HealthCheckState;
import dev.mars.qraft.state.catalog.HealthObservation;
import dev.mars.qraft.state.catalog.ServiceCheckId;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;
import dev.mars.qraft.state.catalog.ServiceInstanceId;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Replicated server state for nodes and generic distributed metadata.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
public final class QraftStateStore implements RaftLogApplicator {
    private static final String DEFAULT_VERSION = "3.0";
    /** Entity type reported when an observation names a check its registration does not declare. */
    public static final String HEALTH_CHECK_ENTITY = "HealthCheck";
    private final Map<String, Node> nodes = new ConcurrentHashMap<>();
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

    /**
     * A state store in its initial state. That state is the same on every server: replicated state changes
     * only through committed commands, so nothing a server knows on its own can be put into it here.
     */
    public QraftStateStore() {
        metadata.put("version", DEFAULT_VERSION);
    }

    @Override
    public RaftCommandResult<?> apply(RaftCommand command) {
        if (command == null) {
            return new RaftCommandResult.NoOp<>();
        }
        return switch (command) {
            case NodeCommand clientCommand -> applyNodeCommand(clientCommand);
            case DistributedStateRaftCommand stateCommand -> applyMetadataCommand(stateCommand.delegate());
            case CatalogCommand catalogCommand -> applyCatalogCommand(catalogCommand);
            default -> throw new IllegalArgumentException("Unsupported server command: "
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
            // A late observation must not resurrect a check its client no longer declares.
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

    private RaftCommandResult<?> applyNodeExpiry(NodeCommand.Expire expire) {
        Node current = nodes.get(expire.name());
        if (current == null || !expire.expectedLastContact().equals(lastContact(current))) {
            return new RaftCommandResult.NoOp<>();
        }
        boolean unreachable = current.status() == NodeStatus.UNREACHABLE;
        if (!expire.reap()) {
            if (unreachable) return new RaftCommandResult.NoOp<>();
            Node changed = current.withStatus(NodeStatus.UNREACHABLE);
            nodes.put(expire.name(), changed);
            return new RaftCommandResult.Success<>(changed);
        }
        // Reaping is the second phase: the node must already have been marked unreachable.
        if (!unreachable) return new RaftCommandResult.NoOp<>();
        nodes.remove(expire.name());
        heartbeatSequences.remove(expire.name());
        removeServicesOf(expire.name());
        return new RaftCommandResult.Success<>(current);
    }

    /** Removes every service instance registered on a node, in every tenant and namespace, with its checks. */
    private void removeServicesOf(String node) {
        for (ServiceInstance instance : serviceCatalog.instances()) {
            if (instance.nodeId().equals(node)) {
                serviceCatalog.deregister(instance.identity());
                removeHealthChecks(instance.identity());
            }
        }
    }

    /** A node's last contact is its last heartbeat, or its registration time before its first heartbeat. */
    public static Instant lastContact(Node node) {
        return node.lastContact();
    }

    private void removeHealthChecks(ServiceInstanceId identity) {
        declaredChecks.remove(identity);
        healthChecks.keySet().removeIf(check -> check.serviceInstanceId().equals(identity));
    }

    private RaftCommandResult<?> applyNodeCommand(NodeCommand command) {
        return switch (command) {
            case NodeCommand.Expire expire -> applyNodeExpiry(expire);
            case NodeCommand.Register register -> {
                // Lifecycle status and times are server-owned; a registration cannot claim them.
                Node registered = register.node().registeredAt(register.timestamp());
                nodes.put(register.name(), registered);
                heartbeatSequences.remove(register.name());
                yield new RaftCommandResult.Success<>(registered);
            }
            case NodeCommand.Deregister deregister -> {
                // As in Consul, a node takes what is registered on it with it. That holds without a node entry
                // too, so that services left under the node's name cannot outlive it.
                Node removed = nodes.remove(deregister.name());
                heartbeatSequences.remove(deregister.name());
                removeServicesOf(deregister.name());
                yield removed == null ? new RaftCommandResult.NotFound<>(deregister.name(), "Node")
                        : new RaftCommandResult.Success<>(removed);
            }
            case NodeCommand.UpdateStatus update -> {
                Node current = nodes.get(update.name());
                if (current == null) {
                    yield new RaftCommandResult.NotFound<>(update.name(), "Node");
                }
                if (current.status() != update.expectedStatus()) {
                    yield new RaftCommandResult.CasMismatch<>(current);
                }
                Node changed = current.withStatus(update.newStatus()).withLastHeartbeat(update.timestamp());
                nodes.put(update.name(), changed);
                yield new RaftCommandResult.Success<>(changed);
            }
            case NodeCommand.Heartbeat heartbeat -> {
                Node current = nodes.get(heartbeat.name());
                if (current == null) {
                    yield new RaftCommandResult.NotFound<>(heartbeat.name(), "Node");
                }
                String currentRegistrationId = current.metadata().get(Node.REGISTRATION_ID_METADATA_KEY);
                if (heartbeat.registrationId() != null
                        && !heartbeat.registrationId().equals(currentRegistrationId)) {
                    yield new RaftCommandResult.CasMismatch<>(current);
                }
                long previousSequence = heartbeatSequences.getOrDefault(heartbeat.name(), 0L);
                if (heartbeat.sequenceNumber() > 0 && heartbeat.sequenceNumber() <= previousSequence) {
                    yield new RaftCommandResult.CasMismatch<>(current);
                }
                Node changed = current.withLastHeartbeat(heartbeat.timestamp());
                if (heartbeat.status() != null) {
                    changed = changed.withStatus(heartbeat.status());
                } else if (changed.status() == NodeStatus.REGISTERING
                        || changed.status() == NodeStatus.UNREACHABLE) {
                    changed = changed.withStatus(NodeStatus.HEALTHY);
                }
                nodes.put(heartbeat.name(), changed);
                if (heartbeat.sequenceNumber() > 0) {
                    heartbeatSequences.put(heartbeat.name(), heartbeat.sequenceNumber());
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
            return objectMapper.writeValueAsBytes(new Snapshot(Map.copyOf(nodes), Map.copyOf(heartbeatSequences),
                    Map.copyOf(metadata), serviceCatalog.instances(), orderedHealthChecks, orderedDeclarations,
                    lastAppliedIndex.get()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize server snapshot", e);
        }
    }

    @Override
    public void restoreSnapshot(byte[] snapshotBytes) {
        try {
            Snapshot snapshot = objectMapper.readValue(snapshotBytes, Snapshot.class);
            nodes.clear();
            nodes.putAll(snapshot.nodes());
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
            throw new IllegalStateException("Failed to restore server snapshot", e);
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
        nodes.clear();
        heartbeatSequences.clear();
        metadata.clear();
        metadata.put("version", DEFAULT_VERSION);
        serviceCatalog.clear();
        healthChecks.clear();
        declaredChecks.clear();
        lastAppliedIndex.set(0);
    }

    /** The registered nodes in name order. A node is immutable, so a caller cannot change replicated state. */
    public Map<String, Node> getNodes() {
        return java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(nodes));
    }

    /** The node registered under {@code name}, if there is one. */
    public Optional<Node> findNode(String name) {
        return Optional.ofNullable(nodes.get(name));
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

    /**
     * {@code nodes} is also read under the two names that earlier snapshots use. Unknown properties are ignored
     * on restore, so without these names such a snapshot would restore with its nodes silently missing. They are
     * read and never written.
     */
    private record Snapshot(@JsonAlias({"clients", "agents"}) Map<String, Node> nodes,
                            Map<String, Long> heartbeatSequences,
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

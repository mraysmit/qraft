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
import dev.mars.qraft.state.acl.AclState;
import dev.mars.qraft.state.acl.AclView;
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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Replicated server state: nodes, the service catalog and its health, ACLs, and generic distributed metadata.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
public final class QraftStateStore implements RaftLogApplicator, dev.mars.qraft.common.events.DomainEventSource {
    private static final String DEFAULT_VERSION = "3.0";
    /** Entity type reported when an observation names a check its registration does not declare. */
    public static final String HEALTH_CHECK_ENTITY = "HealthCheck";
    private final Map<String, Node> nodes = new ConcurrentHashMap<>();
    private final Map<String, Long> heartbeatSequences = new ConcurrentHashMap<>();
    private final Map<String, String> metadata = new ConcurrentHashMap<>();
    /** The key/value store (key/value list, Phase 1); not the {@code metadata} map (decision 1). */
    private final dev.mars.qraft.state.kv.KvState kv = new dev.mars.qraft.state.kv.KvState();
    /** The sessions that lock keys (sessions list, Phase 1). */
    private final dev.mars.qraft.state.session.SessionState sessions = new dev.mars.qraft.state.session.SessionState();
    /** The meter the metrics come from, or {@code null} for the global one (observability list). */
    private final io.opentelemetry.api.metrics.Meter meter;
    private volatile StateMetrics metrics;
    /** The domain events of the change being applied, kept until the node drains them (events list, Phase 4). */
    private final List<dev.mars.qraft.common.events.DomainEvent> pendingEvents = new java.util.ArrayList<>();
    private boolean recordingEvents;
    /** The tenants and namespaces that exist (tenancy list, Phase 1). */
    private final dev.mars.qraft.state.tenancy.TenancyState tenancy = new dev.mars.qraft.state.tenancy.TenancyState();
    private final dev.mars.qraft.state.acl.AuthState auth = new dev.mars.qraft.state.acl.AuthState();
    private final ServiceCatalog serviceCatalog = new ServiceCatalog();
    private final ServiceCatalogView catalogView = ServiceCatalogView.of(serviceCatalog);
    private final Map<ServiceCheckId, HealthCheckState> healthChecks = new ConcurrentHashMap<>();
    private final Map<ServiceInstanceId, List<String>> declaredChecks = new ConcurrentHashMap<>();
    private final AclState acl = new AclState();
    private final AclView aclView = AclView.of(acl);
    private final AtomicLong lastAppliedIndex = new AtomicLong();
    /** Guards the waits of blocking queries, which an applied entry, a restore or a reset wakes. */
    private final java.util.concurrent.locks.ReentrantLock indexLock = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.Condition indexChanged = indexLock.newCondition();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            // Map entries in key order make snapshot bytes reproducible on every replica and JVM.
            .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /**
     * A state store in its initial state. That state is the same on every server: replicated state changes
     * only through committed commands, so nothing a server knows on its own can be put into it here.
     */
    /** A store whose metrics go to OpenTelemetry's global meter, resolved when it first applies a command. */
    public QraftStateStore() {
        this(null);
    }

    /**
     * A store whose metrics go to {@code meter} (observability list, decision 1), or to the global meter when it is
     * {@code null}, resolved when the store first applies a command, after the telemetry is configured.
     */
    public QraftStateStore(io.opentelemetry.api.metrics.Meter meter) {
        metadata.put("version", DEFAULT_VERSION);
        this.meter = meter;
    }

    /** Starts or stops recording domain events (events list, Phase 4); the node starts it when it keeps a journal. */
    @Override
    public void recordDomainEvents(boolean record) {
        recordingEvents = record;
        if (!record) pendingEvents.clear();
    }

    @Override
    public List<dev.mars.qraft.common.events.DomainEvent> drainDomainEvents() {
        List<dev.mars.qraft.common.events.DomainEvent> drained = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return drained;
    }

    /** Records a domain event of the change being applied, when recording; {@code null} scope for a node's. */
    private void event(String tenant, String namespace, dev.mars.qraft.common.events.QraftEvent event) {
        if (recordingEvents) pendingEvents.add(new dev.mars.qraft.common.events.DomainEvent(tenant, namespace, event));
    }

    private void serviceLeft(ServiceInstance instance) {
        event(instance.tenantId(), instance.namespace(), new CatalogEvents.ServiceDeregistered(instance.serviceId(),
                instance.serviceName(), instance.nodeId()));
    }

    private StateMetrics metrics() {
        StateMetrics current = metrics;
        if (current != null) return current;
        synchronized (this) {
            if (metrics == null) {
                metrics = new StateMetrics(meter != null ? meter
                        : io.opentelemetry.api.GlobalOpenTelemetry.getMeter(StateMetrics.METER_NAME), sessions::size);
            }
            return metrics;
        }
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
            case AclCommand aclCommand -> applyAclCommand(aclCommand);
            case dev.mars.qraft.state.kv.KvCommand kvCommand -> applyKvCommand(kvCommand);
            case dev.mars.qraft.state.session.SessionCommand sessionCommand -> applySessionCommand(sessionCommand);
            case dev.mars.qraft.state.tenancy.TenancyCommand tenancyCommand -> applyTenancyCommand(tenancyCommand);
            case dev.mars.qraft.state.tenancy.ScopedWrite scopedWrite -> applyScopedWrite(scopedWrite);
            case dev.mars.qraft.state.acl.AuthCommand authCommand -> applyAuthCommand(authCommand);
            default -> throw new IllegalArgumentException("Unsupported server command: "
                    + command.getClass().getName());
        };
    }

    /**
     * Applies a key/value write at its entry's Raft index. {@code RaftNode} applies entries in order and gives the
     * store each entry's index once it is applied, configuration entries included, and a snapshot carries the last
     * one, so the entry being applied is at the last applied index plus one (key/value list, decision 2).
     */
    private RaftCommandResult<?> applyKvCommand(dev.mars.qraft.state.kv.KvCommand command) {
        long index = lastAppliedIndex.get() + 1;
        if (command instanceof dev.mars.qraft.state.kv.KvCommand.Lock lock && sessions.get(lock.session()).isEmpty()) {
            return new RaftCommandResult.Refused<>("invalid_session", "No session " + lock.session()
                    + " exists to acquire " + lock.key());
        }
        boolean applied = switch (command) {
            case dev.mars.qraft.state.kv.KvCommand.Set set ->
                    kv.set(set.scope(), set.key(), set.value(), set.flags(), set.casIndex(), index);
            case dev.mars.qraft.state.kv.KvCommand.Delete delete ->
                    kv.delete(delete.scope(), delete.key(), delete.casIndex(), index);
            case dev.mars.qraft.state.kv.KvCommand.DeleteTree tree -> {
                kv.deleteTree(tree.scope(), tree.prefix(), index);
                yield true;
            }
            case dev.mars.qraft.state.kv.KvCommand.Lock lock ->
                    kv.lock(lock.scope(), lock.key(), lock.value(), lock.flags(), lock.session(), index, lock.at());
            case dev.mars.qraft.state.kv.KvCommand.Unlock unlock ->
                    kv.unlock(unlock.scope(), unlock.key(), unlock.value(), unlock.flags(), unlock.session(), index);
        };
        String operation = switch (command) {
            case dev.mars.qraft.state.kv.KvCommand.Set set -> "set";
            case dev.mars.qraft.state.kv.KvCommand.Delete delete -> "delete";
            case dev.mars.qraft.state.kv.KvCommand.DeleteTree tree -> "delete_tree";
            case dev.mars.qraft.state.kv.KvCommand.Lock lock -> "lock";
            case dev.mars.qraft.state.kv.KvCommand.Unlock unlock -> "unlock";
        };
        metrics().kvApply(operation, applied);
        if (command instanceof dev.mars.qraft.state.kv.KvCommand.Lock) metrics().lockAcquisition(applied);
        return new RaftCommandResult.Success<>(applied);
    }

    /**
     * Creates a session bound to a registered node, or destroys one, which invalidates it (sessions list, Phase 1). As in
     * Consul, destroying a session that does not exist succeeds.
     */
    private RaftCommandResult<?> applySessionCommand(dev.mars.qraft.state.session.SessionCommand command) {
        long index = lastAppliedIndex.get() + 1;
        return switch (command) {
            case dev.mars.qraft.state.session.SessionCommand.Create create -> {
                if (!nodes.containsKey(create.session().node())) {
                    yield new RaftCommandResult.Refused<>("node_not_found", "No node named " + create.session().node()
                            + " is registered to bind the session to");
                }
                yield new RaftCommandResult.Success<>(sessions.create(create.session(), index));
            }
            case dev.mars.qraft.state.session.SessionCommand.Destroy destroy -> {
                invalidateSession(destroy.id(), destroy.at(), index, "destroyed");
                yield new RaftCommandResult.Success<>(true);
            }
        };
    }

    /**
     * Invalidates a session at {@code at}: its keys are released or deleted, and their lock delay starts; counted by
     * {@code reason}.
     */
    private void invalidateSession(String id, Instant at, long index, String reason) {
        sessions.destroy(id).ifPresent(session -> {
            kv.releaseSession(id, session.behavior() == dev.mars.qraft.state.session.SessionBehavior.DELETE, index,
                    session.lockDelay().isZero() ? null : at.plus(session.lockDelay()));
            metrics().sessionInvalidated(reason);
        });
    }

    /** Invalidates every session bound to {@code node}, which failed or left (sessions list, decision 4). */
    private void invalidateSessionsOf(String node, Instant at) {
        long index = lastAppliedIndex.get() + 1;
        Instant when = at == null ? Instant.EPOCH : at;
        for (dev.mars.qraft.state.session.Session session : sessions.ofNode(node)) {
            invalidateSession(session.id(), when, index, "node");
        }
    }

    /**
     * Creates, updates or deletes a tenant or namespace (tenancy list, Phase 1). Deleting a namespace first removes its
     * services, their checks and its keys, and deleting a tenant does so for each of its namespaces (decision 5).
     */
    private RaftCommandResult<?> applyTenancyCommand(dev.mars.qraft.state.tenancy.TenancyCommand command) {
        long index = lastAppliedIndex.get() + 1;
        String refusal = switch (command) {
            case dev.mars.qraft.state.tenancy.TenancyCommand.CreateTenant create ->
                    tenancy.createTenant(create.name(), create.description(), index);
            case dev.mars.qraft.state.tenancy.TenancyCommand.UpdateTenant update ->
                    tenancy.updateTenant(update.name(), update.description(), index);
            case dev.mars.qraft.state.tenancy.TenancyCommand.DeleteTenant delete -> {
                if (!dev.mars.qraft.state.tenancy.TenancyState.DEFAULT.equals(delete.name())
                        && tenancy.tenantExists(delete.name())) {
                    tenancy.namespaces(delete.name()).forEach(namespace ->
                            removeScope(delete.name(), namespace.name(), index));
                }
                yield tenancy.deleteTenant(delete.name());
            }
            case dev.mars.qraft.state.tenancy.TenancyCommand.CreateNamespace create ->
                    tenancy.createNamespace(create.tenant(), create.name(), create.description(), index);
            case dev.mars.qraft.state.tenancy.TenancyCommand.UpdateNamespace update ->
                    tenancy.updateNamespace(update.tenant(), update.name(), update.description(), index);
            case dev.mars.qraft.state.tenancy.TenancyCommand.DeleteNamespace delete -> {
                if (tenancy.refusesNamespaceDeletion(delete.tenant(), delete.name()) == null) {
                    removeScope(delete.tenant(), delete.name(), index);
                }
                yield tenancy.deleteNamespace(delete.tenant(), delete.name());
            }
        };
        if (refusal == null) return new RaftCommandResult.Success<>(true);
        return new RaftCommandResult.Refused<>(refusal, tenancyRefusal(refusal, command));
    }

    /**
     * Applies a scoped write's command only where its tenant and namespace exist (tenancy list, decision 3), checked at
     * the entry's place in the log.
     */
    private RaftCommandResult<?> applyScopedWrite(dev.mars.qraft.state.tenancy.ScopedWrite write) {
        if (!tenancy.tenantExists(write.tenant())) {
            return new RaftCommandResult.Refused<>("tenant_not_found", "No tenant " + write.tenant()
                    + " exists to write in");
        }
        if (!tenancy.namespaceExists(write.tenant(), write.namespace())) {
            return new RaftCommandResult.Refused<>("namespace_not_found", "No namespace " + write.namespace()
                    + " exists in tenant " + write.tenant() + " to write in");
        }
        return apply(write.command());
    }

    private static String tenancyRefusal(String code, dev.mars.qraft.state.tenancy.TenancyCommand command) {
        return switch (code) {
            case "invalid_name" -> "A tenant or namespace name is " + dev.mars.qraft.state.tenancy.TenancyNames.rule()
                    + ": " + command;
            case "tenant_exists" -> "The tenant exists: " + command;
            case "namespace_exists" -> "The namespace exists: " + command;
            case "tenant_not_found" -> "No such tenant: " + command;
            case "namespace_not_found" -> "No such namespace: " + command;
            case "tenant_reserved" -> "The default tenant cannot be deleted";
            case "namespace_reserved" -> "A default namespace is deleted only with its tenant";
            default -> code + ": " + command;
        };
    }

    /** Removes the services, their checks and the keys of {@code namespace} in {@code tenant}. */
    private void removeScope(String tenant, String namespace, long index) {
        for (ServiceInstance instance : serviceCatalog.instances()) {
            if (instance.tenantId().equals(tenant) && instance.namespace().equals(namespace)) {
                serviceCatalog.deregister(instance.identity());
                removeHealthChecks(instance.identity());
                serviceLeft(instance);
            }
        }
        kv.deleteTree(new dev.mars.qraft.state.kv.KvScope(tenant, namespace), "", index);
    }

    private RaftCommandResult<?> applyCatalogCommand(CatalogCommand command) {
        return switch (command) {
            case CatalogCommand.Register register -> {
                Node holder = heldByAnotherNode(register.instance().nodeId(), register.nodeUuid());
                if (holder != null) {
                    metrics().registration("refused");
                    yield new RaftCommandResult.CasMismatch<>(holder);
                }
                metrics().registration("registered");
                ServiceInstanceId identity = register.instance().identity();
                serviceCatalog.register(register.instance());
                event(register.instance().tenantId(), register.instance().namespace(),
                        new CatalogEvents.ServiceRegistered(register.instance().serviceId(),
                                register.instance().serviceName(), register.instance().nodeId()));
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
                Node holder = deregister.isLegacy() ? null
                        : heldByAnotherNode(deregister.nodeId(), deregister.nodeUuid());
                if (holder != null) {
                    metrics().deregistration("refused");
                    yield new RaftCommandResult.CasMismatch<>(holder);
                }
                List<ServiceInstance> leaving = deregister.isLegacy()
                        ? serviceCatalog.instances().stream()
                                .filter(instance -> instance.serviceId().equals(deregister.serviceId())).toList()
                        : serviceCatalog.find(deregister.identity()).stream().toList();
                boolean removed = deregister.isLegacy()
                        ? serviceCatalog.deregisterLegacy(deregister.serviceId())
                        : serviceCatalog.deregister(deregister.identity());
                if (removed) leaving.forEach(this::serviceLeft);
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
                metrics().deregistration(removed ? "deregistered" : "not_found");
                yield removed
                        ? new RaftCommandResult.Success<>(deregister.serviceId())
                        : new RaftCommandResult.NotFound<>(deregister.serviceId(), "ServiceInstance");
            }
            case CatalogCommand.ObserveHealth observe -> applyHealthObservation(observe);
            case CatalogCommand.ExpireHealth expire -> applyHealthExpiry(expire);
        };
    }

    /**
     * Writes an auth method or binding rule, or creates or deletes a login token (authentication list, Phases 1 and 2).
     * Deleting a method deletes its rules and the tokens its logins created, as in Consul.
     */
    private RaftCommandResult<?> applyAuthCommand(dev.mars.qraft.state.acl.AuthCommand command) {
        long index = lastAppliedIndex.get() + 1;
        if (command instanceof dev.mars.qraft.state.acl.AuthCommand.Login login) {
            if (auth.method(login.method()).isEmpty()) {
                return new RaftCommandResult.Refused<>("auth_method_not_found", "No auth method " + login.method());
            }
            RaftCommandResult<?> created = acl.createToken(login.token());
            if (created instanceof RaftCommandResult.Success<?>) {
                auth.recordLogin(login.token().accessorId(), login.method());
            }
            return created;
        }
        String refusal = switch (command) {
            case dev.mars.qraft.state.acl.AuthCommand.CreateMethod create -> auth.createMethod(create.method(), index);
            case dev.mars.qraft.state.acl.AuthCommand.UpdateMethod update -> auth.updateMethod(update.method(), index);
            case dev.mars.qraft.state.acl.AuthCommand.DeleteMethod delete -> {
                List<String> tokens = auth.deleteMethod(delete.name());
                if (tokens == null) yield "auth_method_not_found";
                tokens.forEach(acl::deleteToken);
                yield null;
            }
            case dev.mars.qraft.state.acl.AuthCommand.CreateBindingRule create -> auth.createRule(create.rule(), index);
            case dev.mars.qraft.state.acl.AuthCommand.UpdateBindingRule update -> auth.updateRule(update.rule(), index);
            case dev.mars.qraft.state.acl.AuthCommand.DeleteBindingRule delete -> auth.deleteRule(delete.id());
            case dev.mars.qraft.state.acl.AuthCommand.Login login ->
                    throw new IllegalStateException("a login is applied before the switch");
            case dev.mars.qraft.state.acl.AuthCommand.Logout logout -> {
                if (auth.loginMethodOf(logout.accessorId()).isEmpty()) yield "not_a_login_token";
                acl.deleteToken(logout.accessorId());
                auth.forgetLogin(logout.accessorId());
                yield null;
            }
        };
        return refusal == null ? new RaftCommandResult.Success<>(true)
                : new RaftCommandResult.Refused<>(refusal, refusal.replace('_', ' ') + ": " + command);
    }

    private RaftCommandResult<?> applyAclCommand(AclCommand command) {
        return switch (command) {
            case AclCommand.CreateToken create -> acl.createToken(create.token());
            case AclCommand.UpdateToken update -> acl.updateToken(update.token());
            case AclCommand.DeleteToken delete -> acl.deleteToken(delete.accessorId());
            case AclCommand.CreatePolicy create -> acl.createPolicy(create.policy());
            case AclCommand.UpdatePolicy update -> acl.updatePolicy(update.policy());
            case AclCommand.DeletePolicy delete -> acl.deletePolicy(delete.id());
            case AclCommand.CreateRole create -> acl.createRole(create.role());
            case AclCommand.UpdateRole update -> acl.updateRole(update.role());
            case AclCommand.DeleteRole delete -> acl.deleteRole(delete.id());
            case AclCommand.Bootstrap bootstrap -> acl.bootstrap(bootstrap.token(), bootstrap.resetIndex());
            case AclCommand.DeleteExpiredTokens expired -> acl.deleteExpiredTokens(expired.asOf(), expired.accessorIds());
        };
    }

    private RaftCommandResult<?> applyHealthObservation(CatalogCommand.ObserveHealth command) {
        HealthObservation observation = command.observation();
        ServiceCheckId checkId = observation.checkId();
        Node holder = heldByAnotherNode(checkId.serviceInstanceId().nodeId(), command.nodeUuid());
        if (holder != null) return new RaftCommandResult.CasMismatch<>(holder);
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
            Optional<ServiceInstance> leaving = serviceCatalog.find(identity);
            if (!current.expired() || !serviceCatalog.deregister(identity)) {
                return new RaftCommandResult.NoOp<>();
            }
            removeHealthChecks(identity);
            leaving.ifPresent(this::serviceLeft);
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
        Optional<ServiceInstance> instance = serviceCatalog.find(identity);
        if (instance.isEmpty()) return;
        List<HealthCheckState> states = healthChecks.values().stream()
                .filter(state -> state.checkId().serviceInstanceId().equals(identity))
                .toList();
        ServiceHealth health = aggregateHealth(states);
        if (instance.get().health() != health) {
            metrics().healthTransition(instance.get().health(), health);
            event(instance.get().tenantId(), instance.get().namespace(), new CatalogEvents.HealthChanged(
                    instance.get().serviceId(), instance.get().serviceName(), instance.get().health().name(),
                    health.name()));
        }
        serviceCatalog.setHealth(identity, health);
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
            invalidateSessionsOf(expire.name(), expire.timestamp());
            event(null, null, new CatalogEvents.NodeUnreachable(expire.name()));
            return new RaftCommandResult.Success<>(changed);
        }
        // Reaping is the second phase: the node must already have been marked unreachable.
        if (!unreachable) return new RaftCommandResult.NoOp<>();
        nodes.remove(expire.name());
        heartbeatSequences.remove(expire.name());
        removeServicesOf(expire.name());
        invalidateSessionsOf(expire.name(), expire.timestamp());
        event(null, null, new CatalogEvents.NodeRemoved(expire.name(), "reaped"));
        return new RaftCommandResult.Success<>(current);
    }

    /**
     * Registers a node under its name by Consul's catalog rules (client list decision 8), deciding on what the
     * replicated state holds, so that every replica decides the same:
     * <ul>
     *   <li>names are compared regardless of case;</li>
     *   <li>a name held by another node, one with another ID or, for a registration without an ID, one with an ID,
     *       is refused while that node holds it, answering {@code CasMismatch} with the holder; a node holds its
     *       name until it is unreachable, and an unreachable holder is taken over, leaving with what it
     *       registered;</li>
     *   <li>a registration with an ID that no node has claims a node without an ID, keeping what it registered;</li>
     *   <li>an ID registered under another name is a rename: the node leaves its old name with what it registered
     *       there, and its client registers its services again under the new one.</li>
     * </ul>
     * The same node under its exact name keeps what it registered. Lifecycle status and times are server-owned; a
     * registration cannot claim them.
     */
    private RaftCommandResult<?> applyNodeRegistration(NodeCommand.Register register) {
        String name = register.name();
        String id = register.node().id();
        String holderName = nameHeldAs(name);
        Node holder = holderName == null ? null : nodes.get(holderName);
        String previousName = id == null ? null : nameRegisteredWith(id);
        boolean sameNode = holder != null && (Objects.equals(holder.id(), id)
                || (holder.id() == null && previousName == null));
        if (holder != null && !sameNode && holder.status() != NodeStatus.UNREACHABLE) {
            return new RaftCommandResult.CasMismatch<>(holder);
        }
        if (holder != null && (!sameNode || !holderName.equals(name))) removeNode(holderName, register.timestamp());
        if (previousName != null && !previousName.equals(holderName)) removeNode(previousName, register.timestamp());
        Node registered = register.node().registeredAt(register.timestamp());
        boolean existed = nodes.containsKey(name);
        nodes.put(name, registered);
        event(null, null, existed ? new CatalogEvents.NodeUpdated(name) : new CatalogEvents.NodeRegistered(name));
        heartbeatSequences.remove(name);
        return new RaftCommandResult.Success<>(registered);
    }

    /** The name a node is registered under that matches {@code name} regardless of case: the exact one first. */
    private String nameHeldAs(String name) {
        if (nodes.containsKey(name)) return name;
        return nodes.keySet().stream().filter(held -> held.equalsIgnoreCase(name)).sorted().findFirst().orElse(null);
    }

    /** The name of the node registered with {@code id}, or {@code null}; the first in name order, for determinism. */
    private String nameRegisteredWith(String id) {
        return nodes.entrySet().stream().filter(entry -> id.equals(entry.getValue().id()))
                .map(Map.Entry::getKey).sorted().findFirst().orElse(null);
    }

    /**
     * The node registered under {@code name} when it has an ID other than {@code nodeUuid}, the ID of the node that
     * sent a write: such a write is refused, as Consul refuses a registration from a node that does not hold the
     * name, so a node that lost its name cannot change what the node holding it registered. {@code null} when the
     * write may go ahead: it carries no ID, as clients from before node IDs send none, or the node is not
     * registered, has no ID, or has this one.
     */
    private Node heldByAnotherNode(String name, String nodeUuid) {
        if (nodeUuid == null) return null;
        Node node = nodes.get(name);
        return node != null && node.id() != null && !node.id().equals(nodeUuid) ? node : null;
    }

    /** Removes the node registered under {@code name} with everything registered on it. */
    private void removeNode(String name, Instant at) {
        Node removed = nodes.remove(name);
        heartbeatSequences.remove(name);
        removeServicesOf(name);
        invalidateSessionsOf(name, at);
        if (removed != null) event(null, null, new CatalogEvents.NodeRemoved(name, "replaced"));
    }

    /** Removes every service instance registered on a node, in every tenant and namespace, with its checks. */
    private void removeServicesOf(String node) {
        for (ServiceInstance instance : serviceCatalog.instances()) {
            if (instance.nodeId().equals(node)) {
                serviceCatalog.deregister(instance.identity());
                removeHealthChecks(instance.identity());
                serviceLeft(instance);
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
            case NodeCommand.Register register -> applyNodeRegistration(register);
            case NodeCommand.Deregister deregister -> {
                Node holder = heldByAnotherNode(deregister.name(), deregister.nodeUuid());
                if (holder != null) yield new RaftCommandResult.CasMismatch<>(holder);
                // As in Consul, a node takes what is registered on it with it. That holds without a node entry
                // too, so that services left under the node's name cannot outlive it.
                Node removed = nodes.remove(deregister.name());
                heartbeatSequences.remove(deregister.name());
                removeServicesOf(deregister.name());
                invalidateSessionsOf(deregister.name(), deregister.timestamp());
                if (removed != null) event(null, null, new CatalogEvents.NodeRemoved(deregister.name(), "deregistered"));
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
                    acl.snapshot(), kv.snapshot(), sessions.snapshot(), tenancy.snapshot(), auth.snapshot(),
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
            acl.restore(snapshot.acl());
            kv.restore(snapshot.kv());
            sessions.restore(snapshot.sessions());
            tenancy.restore(snapshot.tenancy());
            auth.restore(snapshot.auth());
            pendingEvents.clear();
            lastAppliedIndex.set(snapshot.lastAppliedIndex());
            signalIndexChange();
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
        signalIndexChange();
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
        acl.reset();
        kv.restore(null);
        sessions.restore(null);
        tenancy.restore(null);
        auth.restore(null);
        pendingEvents.clear();
        lastAppliedIndex.set(0);
        signalIndexChange();
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

    /** Returns a read-only view; ACL state changes only through committed commands. */
    /**
     * Waits until the applied index is greater than {@code index}, or {@code timeout} passes, and answers the applied
     * index then, as a blocking query needs (consistency list, Phase 2). An index of 0 or less answers at once.
     */
    public long awaitAppliedIndex(long index, java.time.Duration timeout) throws InterruptedException {
        return awaitIndex(index, timeout, lastAppliedIndex::get);
    }

    /** As {@link #awaitAppliedIndex}, on the key/value state's last index, which only a key/value change moves. */
    public long awaitKvIndex(long index, java.time.Duration timeout) throws InterruptedException {
        return awaitIndex(index, timeout, kv::lastIndex);
    }

    private long awaitIndex(long index, java.time.Duration timeout, java.util.function.LongSupplier current)
            throws InterruptedException {
        if (index <= 0) return current.getAsLong();
        long remaining = timeout.toNanos();
        indexLock.lock();
        try {
            while (current.getAsLong() <= index && remaining > 0) remaining = indexChanged.awaitNanos(remaining);
            return current.getAsLong();
        } finally {
            indexLock.unlock();
        }
    }

    /** Wakes every waiter, after the applied state has moved. */
    private void signalIndexChange() {
        indexLock.lock();
        try {
            indexChanged.signalAll();
        } finally {
            indexLock.unlock();
        }
    }

    /** The replicated auth methods, binding rules and logins, for reads (authentication list). */
    public dev.mars.qraft.state.acl.AuthState getAuth() {
        return auth;
    }

    /** The replicated tenants and namespaces, for reads (tenancy list). */
    public dev.mars.qraft.state.tenancy.TenancyState getTenancy() {
        return tenancy;
    }

    /** The replicated sessions, for reads (sessions list). */
    public dev.mars.qraft.state.session.SessionState getSessions() {
        return sessions;
    }

    /** The replicated key/value state, for reads. */
    public dev.mars.qraft.state.kv.KvState getKv() {
        return kv;
    }

    public AclView getAcl() {
        return aclView;
    }

    public List<HealthCheckState> healthChecks() {
        return healthChecks.values().stream()
                .sorted(Comparator.comparing(state -> state.checkId().toString()))
                .toList();
    }

    /**
     * {@code nodes} is also read under the two names that earlier snapshots use. Unknown properties are ignored
     * on restore, so without these names such a snapshot would restore with its nodes silently missing. They are
     * read and never written. {@code acl} is absent from snapshots written before ACLs, which restore the initial ACL
     * state, and {@code kv} from those written before key/value, which restore none.
     */
    private record Snapshot(@JsonAlias({"clients", "agents"}) Map<String, Node> nodes,
                            Map<String, Long> heartbeatSequences,
                            Map<String, String> metadata,
                            java.util.List<ServiceInstance> services,
                            java.util.List<HealthCheckState> healthChecks,
                            java.util.List<DeclaredChecks> declaredChecks,
                            AclState.Snapshot acl,
                            dev.mars.qraft.state.kv.KvState.Snapshot kv,
                            java.util.List<dev.mars.qraft.state.session.Session> sessions,
                            dev.mars.qraft.state.tenancy.TenancyState.Snapshot tenancy,
                            dev.mars.qraft.state.acl.AuthState.Snapshot auth,
                            long lastAppliedIndex) {
    }

    /** Check identifiers a registration declared for one instance; absent from snapshots written earlier. */
    private record DeclaredChecks(ServiceInstanceId instance, java.util.List<String> checkIds) {
    }
}

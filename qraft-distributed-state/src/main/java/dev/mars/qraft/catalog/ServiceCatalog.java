package dev.mars.qraft.catalog;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe in-memory service catalog.
 *
 * <p>Replication and persistence are deliberately outside this class. A Raft
 * state machine can apply registration commands to this deterministic model.</p>
 */
public final class ServiceCatalog {

    private static final Comparator<ServiceInstance> INSTANCE_ORDER =
            Comparator.comparing(ServiceInstance::tenantId)
                    .thenComparing(ServiceInstance::namespace)
                    .thenComparing(ServiceInstance::nodeId)
                    .thenComparing(ServiceInstance::serviceId);

    private final ConcurrentMap<ServiceInstanceId, ServiceInstance> instances = new ConcurrentHashMap<>();

    public void register(ServiceInstance instance) {
        Objects.requireNonNull(instance, "instance");
        instances.put(instance.identity(), instance);
    }

    public boolean deregister(ServiceInstanceId identity) {
        Objects.requireNonNull(identity, "identity");
        return instances.remove(identity) != null;
    }

    public Optional<ServiceInstance> find(ServiceInstanceId identity) {
        Objects.requireNonNull(identity, "identity");
        return Optional.ofNullable(instances.get(identity));
    }

    public ServiceInstance setHealth(ServiceInstanceId identity, ServiceHealth health) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(health, "health");
        return instances.compute(identity, (id, current) -> {
            if (current == null) {
                throw new IllegalArgumentException("Unknown service instance: " + id);
            }
            return current.withHealth(health);
        });
    }

    /** Replays a pre-composite deregistration whose historical key was serviceId alone. */
    public boolean deregisterLegacy(String serviceId) {
        Objects.requireNonNull(serviceId, "serviceId");
        boolean[] removed = {false};
        instances.entrySet().removeIf(entry -> {
            boolean matches = serviceId.equals(entry.getKey().serviceId());
            removed[0] |= matches;
            return matches;
        });
        return removed[0];
    }

    public List<String> services() {
        return instances.values().stream()
                .map(ServiceInstance::serviceName)
                .distinct()
                .sorted()
                .toList();
    }

    public List<ServiceInstance> instances(String serviceName) {
        Objects.requireNonNull(serviceName, "serviceName");
        return instances.values().stream()
                .filter(instance -> serviceName.equals(instance.serviceName()))
                .sorted(INSTANCE_ORDER)
                .toList();
    }

    /** Returns every registration in deterministic service-id order. */
    public List<ServiceInstance> instances() {
        return instances.values().stream()
                .sorted(INSTANCE_ORDER)
                .toList();
    }

    /** Replaces the catalog contents, primarily when restoring a Raft snapshot. */
    public void replaceAll(List<ServiceInstance> restoredInstances) {
        Objects.requireNonNull(restoredInstances, "restoredInstances");
        instances.clear();
        restoredInstances.forEach(this::register);
    }

    public void clear() {
        instances.clear();
    }
}

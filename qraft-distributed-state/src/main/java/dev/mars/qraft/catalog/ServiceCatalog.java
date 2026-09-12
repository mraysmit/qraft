package dev.mars.qraft.catalog;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
            Comparator.comparing(ServiceInstance::serviceId);

    private final ConcurrentMap<String, ServiceInstance> instances = new ConcurrentHashMap<>();

    public void register(ServiceInstance instance) {
        Objects.requireNonNull(instance, "instance");
        instances.put(instance.serviceId(), instance);
    }

    public boolean deregister(String serviceId) {
        Objects.requireNonNull(serviceId, "serviceId");
        return instances.remove(serviceId) != null;
    }

    public ServiceInstance setHealth(String serviceId, ServiceHealth health) {
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(health, "health");
        return instances.compute(serviceId, (id, current) -> {
            if (current == null) {
                throw new IllegalArgumentException("Unknown service instance: " + id);
            }
            return current.withHealth(health);
        });
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

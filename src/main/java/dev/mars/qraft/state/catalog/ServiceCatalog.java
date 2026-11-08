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

package dev.mars.qraft.state.catalog;

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
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 2.0
 */
public final class ServiceCatalog implements ServiceCatalogView {

    private static final Comparator<ServiceInstance> INSTANCE_ORDER =
            Comparator.comparing(ServiceInstance::tenantId)
                    .thenComparing(ServiceInstance::namespace)
                    .thenComparing(ServiceInstance::nodeId)
                    .thenComparing(ServiceInstance::serviceId);

    private final ConcurrentMap<ServiceInstanceId, ServiceInstance> instances = new ConcurrentHashMap<>();
    /** Each instance's indexes (catalog indexes list, decision 1). */
    private final ConcurrentMap<ServiceInstanceId, ServiceCatalogView.Indexes> indexes = new ConcurrentHashMap<>();

    public void register(ServiceInstance instance) {
        register(instance, 0);
    }

    /**
     * Registers {@code instance} by the entry at {@code index}: a new instance takes it as both its indexes, and one
     * already registered takes it as its modification index only when the registration changes more than the health it
     * carries (decision 3), as Consul's does.
     */
    public void register(ServiceInstance instance, long index) {
        Objects.requireNonNull(instance, "instance");
        ServiceInstance previous = instances.put(instance.identity(), instance);
        ServiceCatalogView.Indexes current = indexes.get(instance.identity());
        if (previous == null || current == null) {
            indexes.put(instance.identity(), new ServiceCatalogView.Indexes(index, index));
        } else if (!previous.withHealth(instance.health()).equals(instance)) {
            indexes.put(instance.identity(), new ServiceCatalogView.Indexes(current.createIndex(), index));
        }
    }

    public boolean deregister(ServiceInstanceId identity) {
        Objects.requireNonNull(identity, "identity");
        indexes.remove(identity);
        return instances.remove(identity) != null;
    }

    public ServiceCatalogView.Indexes indexesOf(ServiceInstanceId identity) {
        return indexes.getOrDefault(identity, new ServiceCatalogView.Indexes(0, 0));
    }

    /** Every instance's indexes, for a snapshot. */
    public java.util.Map<ServiceInstanceId, ServiceCatalogView.Indexes> indexes() {
        return java.util.Map.copyOf(indexes);
    }

    /** Restores the indexes a snapshot holds; an instance the snapshot gives none for keeps 0. */
    public void restoreIndexes(java.util.Map<ServiceInstanceId, ServiceCatalogView.Indexes> restored) {
        indexes.clear();
        instances.keySet().forEach(identity -> indexes.put(identity, new ServiceCatalogView.Indexes(0, 0)));
        if (restored != null) {
            restored.forEach((identity, held) -> {
                if (instances.containsKey(identity)) indexes.put(identity, held);
            });
        }
    }

    @Override
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
        indexes.keySet().removeIf(identity -> serviceId.equals(identity.serviceId()));
        instances.entrySet().removeIf(entry -> {
            boolean matches = serviceId.equals(entry.getKey().serviceId());
            removed[0] |= matches;
            return matches;
        });
        return removed[0];
    }

    @Override
    public List<String> services(String tenantId, String namespace) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(namespace, "namespace");
        return instances.values().stream()
                .filter(instance -> tenantId.equals(instance.tenantId()) && namespace.equals(instance.namespace()))
                .map(ServiceInstance::serviceName)
                .distinct()
                .sorted()
                .toList();
    }

    @Override
    public List<ServiceInstance> instances(ServiceKey service) {
        Objects.requireNonNull(service, "service");
        return instances.values().stream()
                .filter(instance -> service.equals(instance.serviceKey()))
                .sorted(INSTANCE_ORDER)
                .toList();
    }

    /** Returns every registration, in every tenant and namespace, in deterministic identity order. */
    @Override
    public List<ServiceInstance> instances() {
        return instances.values().stream()
                .sorted(INSTANCE_ORDER)
                .toList();
    }

    /** Replaces the catalog contents, primarily when restoring a Raft snapshot. */
    public void replaceAll(List<ServiceInstance> restoredInstances) {
        Objects.requireNonNull(restoredInstances, "restoredInstances");
        instances.clear();
        indexes.clear();
        restoredInstances.forEach(this::register);
    }

    public void clear() {
        instances.clear();
        indexes.clear();
    }
}

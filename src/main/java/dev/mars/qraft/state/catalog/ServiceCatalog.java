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

    public void register(ServiceInstance instance) {
        Objects.requireNonNull(instance, "instance");
        instances.put(instance.identity(), instance);
    }

    public boolean deregister(ServiceInstanceId identity) {
        Objects.requireNonNull(identity, "identity");
        return instances.remove(identity) != null;
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
        restoredInstances.forEach(this::register);
    }

    public void clear() {
        instances.clear();
    }
}

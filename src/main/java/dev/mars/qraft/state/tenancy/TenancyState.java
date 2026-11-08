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

package dev.mars.qraft.state.tenancy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * The replicated tenants and namespaces (tenancy list, Phase 1), in name order. The {@code default} tenant and its
 * {@code default} namespace are always present, at index 0, and every tenant holds a {@code default} namespace from its
 * creation (decision 1). Commands change it on the state machine's thread; readers on others see each entry whole.
 * A change answers {@code null} when it was made, or the code of its refusal.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class TenancyState {
    /** The tenant and the namespace that always exist. */
    public static final String DEFAULT = "default";

    private final ConcurrentSkipListMap<String, Tenant> tenants = new ConcurrentSkipListMap<>();
    private final ConcurrentSkipListMap<String, ConcurrentSkipListMap<String, Namespace>> namespaces =
            new ConcurrentSkipListMap<>();

    public TenancyState() {
        restore(null);
    }

    public boolean tenantExists(String tenant) {
        return tenant != null && tenants.containsKey(tenant);
    }

    public boolean namespaceExists(String tenant, String namespace) {
        if (tenant == null || namespace == null) return false;
        Map<String, Namespace> held = namespaces.get(tenant);
        return held != null && held.containsKey(namespace);
    }

    public Optional<Tenant> tenant(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(tenants.get(name));
    }

    public Optional<Namespace> namespace(String tenant, String name) {
        if (tenant == null || name == null) return Optional.empty();
        Map<String, Namespace> held = namespaces.get(tenant);
        return held == null ? Optional.empty() : Optional.ofNullable(held.get(name));
    }

    /** Every tenant, in name order. */
    public List<Tenant> tenants() {
        return List.copyOf(tenants.values());
    }

    /** The namespaces of {@code tenant}, in name order; none for a tenant that does not exist. */
    public List<Namespace> namespaces(String tenant) {
        Map<String, Namespace> held = tenant == null ? null : namespaces.get(tenant);
        return held == null ? List.of() : List.copyOf(held.values());
    }

    public String createTenant(String name, String description, long index) {
        if (!TenancyNames.valid(name)) return "invalid_name";
        if (tenants.containsKey(name)) return "tenant_exists";
        tenants.put(name, new Tenant(name, description, index, index));
        ConcurrentSkipListMap<String, Namespace> held = new ConcurrentSkipListMap<>();
        held.put(DEFAULT, new Namespace(name, DEFAULT, "", index, index));
        namespaces.put(name, held);
        return null;
    }

    public String updateTenant(String name, String description, long index) {
        Tenant current = tenants.get(name);
        if (current == null) return "tenant_not_found";
        tenants.put(name, new Tenant(name, description, current.createIndex(), index));
        return null;
    }

    /** Deletes {@code name} and its namespaces; the store removes what they held before calling this. */
    public String deleteTenant(String name) {
        if (DEFAULT.equals(name)) return "tenant_reserved";
        if (!tenants.containsKey(name)) return "tenant_not_found";
        tenants.remove(name);
        namespaces.remove(name);
        return null;
    }

    public String createNamespace(String tenant, String name, String description, long index) {
        if (!TenancyNames.valid(tenant) || !TenancyNames.valid(name)) return "invalid_name";
        Map<String, Namespace> held = namespaces.get(tenant);
        if (held == null) return "tenant_not_found";
        if (held.containsKey(name)) return "namespace_exists";
        held.put(name, new Namespace(tenant, name, description, index, index));
        return null;
    }

    public String updateNamespace(String tenant, String name, String description, long index) {
        Map<String, Namespace> held = namespaces.get(tenant);
        if (held == null) return "tenant_not_found";
        Namespace current = held.get(name);
        if (current == null) return "namespace_not_found";
        held.put(name, new Namespace(tenant, name, description, current.createIndex(), index));
        return null;
    }

    /** Whether {@code name} in {@code tenant} may be deleted: the code of the refusal, or {@code null}. */
    public String refusesNamespaceDeletion(String tenant, String name) {
        if (DEFAULT.equals(name)) return "namespace_reserved";
        Map<String, Namespace> held = namespaces.get(tenant);
        if (held == null) return "tenant_not_found";
        return held.containsKey(name) ? null : "namespace_not_found";
    }

    public String deleteNamespace(String tenant, String name) {
        String refusal = refusesNamespaceDeletion(tenant, name);
        if (refusal == null) namespaces.get(tenant).remove(name);
        return refusal;
    }

    public Snapshot snapshot() {
        List<Namespace> all = new ArrayList<>();
        namespaces.values().forEach(held -> all.addAll(held.values()));
        return new Snapshot(tenants(), List.copyOf(all));
    }

    /** Replaces the registry with {@code snapshot}'s; the defaults alone, from a snapshot written before tenancy. */
    public void restore(Snapshot snapshot) {
        tenants.clear();
        namespaces.clear();
        if (snapshot != null) {
            if (snapshot.tenants() != null) snapshot.tenants().forEach(tenant -> tenants.put(tenant.name(), tenant));
            if (snapshot.namespaces() != null) {
                snapshot.namespaces().forEach(namespace -> namespaces
                        .computeIfAbsent(namespace.tenant(), tenant -> new ConcurrentSkipListMap<>())
                        .put(namespace.name(), namespace));
            }
        }
        tenants.putIfAbsent(DEFAULT, new Tenant(DEFAULT, "", 0, 0));
        namespaces.computeIfAbsent(DEFAULT, tenant -> new ConcurrentSkipListMap<>())
                .putIfAbsent(DEFAULT, new Namespace(DEFAULT, DEFAULT, "", 0, 0));
    }

    /** The registry in a snapshot. */
    public record Snapshot(List<Tenant> tenants, List<Namespace> namespaces) {
    }
}

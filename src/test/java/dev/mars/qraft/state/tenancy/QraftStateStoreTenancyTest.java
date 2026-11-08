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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.qraft.common.Node;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.state.CatalogCommand;
import dev.mars.qraft.state.NodeCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.kv.KvCommand;
import dev.mars.qraft.state.kv.KvScope;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the replicated tenants and namespaces (tenancy list, Phase 1): the defaults always exist; a tenant is created
 * with its {@code default} namespace; creation, update and deletion are refused as Consul refuses them; deleting a
 * namespace removes its services, checks and keys, and deleting a tenant its namespaces; the snapshot carries the
 * registry, and one written before tenancy restores only the defaults; and the commands round-trip through the codec.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class QraftStateStoreTenancyTest {
    private static final Instant T0 = Instant.parse("2026-11-08T12:00:00Z");
    private final QraftStateStore store = new QraftStateStore();
    private long index;

    private RaftCommandResult<?> apply(RaftCommand command) {
        RaftCommandResult<?> result = store.apply(command);
        store.setLastAppliedIndex(++index);
        return result;
    }

    private static void refused(String code, RaftCommandResult<?> result) {
        assertEquals(code, assertInstanceOf(RaftCommandResult.Refused.class, result).code(), result.toString());
    }

    private static ServiceInstance service(String id, String tenant, String namespace) {
        return new ServiceInstance(id, "web", "host-a", "10.0.0.1", 8080, List.of(), Map.of(), ServiceHealth.PASSING,
                tenant, namespace, "", "", true);
    }

    @Test
    void theDefaultsAlwaysExistAndATenantIsCreatedWithItsDefaultNamespace() {
        TenancyState tenancy = store.getTenancy();
        assertTrue(tenancy.namespaceExists("default", "default"));
        assertFalse(tenancy.tenantExists("acme"));
        assertEquals(List.of("default"), tenancy.tenants().stream().map(Tenant::name).toList());

        assertEquals(new RaftCommandResult.Success<>(true), apply(TenancyCommand.createTenant("acme", "Acme Ltd")));
        assertTrue(tenancy.namespaceExists("acme", "default"), "a tenant comes with its default namespace");
        assertFalse(tenancy.namespaceExists("acme", "prod"));
        assertEquals(new RaftCommandResult.Success<>(true), apply(TenancyCommand.createNamespace("acme", "prod", "")));
        assertTrue(tenancy.namespaceExists("acme", "prod"));
        assertEquals(List.of("default", "prod"),
                tenancy.namespaces("acme").stream().map(Namespace::name).toList());
        assertEquals(List.of("acme", "default"), tenancy.tenants().stream().map(Tenant::name).toList());

        Tenant acme = tenancy.tenant("acme").orElseThrow();
        assertEquals("Acme Ltd", acme.description());
        assertTrue(acme.createIndex() > 0);
        assertEquals(new RaftCommandResult.Success<>(true), apply(TenancyCommand.updateTenant("acme", "Acme")));
        Tenant updated = tenancy.tenant("acme").orElseThrow();
        assertEquals("Acme", updated.description());
        assertEquals(acme.createIndex(), updated.createIndex());
        assertTrue(updated.modifyIndex() > acme.modifyIndex());
    }

    @Test
    void whatCannotBeCreatedUpdatedOrDeletedIsRefused() {
        apply(TenancyCommand.createTenant("acme", ""));

        refused("tenant_exists", apply(TenancyCommand.createTenant("acme", "")));
        refused("tenant_exists", apply(TenancyCommand.createTenant("default", "")));
        refused("namespace_exists", apply(TenancyCommand.createNamespace("acme", "default", "")));
        refused("tenant_not_found", apply(TenancyCommand.createNamespace("absent", "prod", "")));
        refused("invalid_name", apply(TenancyCommand.createTenant("Acme_Ltd", "")));
        refused("invalid_name", apply(TenancyCommand.createNamespace("acme", "-prod", "")));
        refused("tenant_not_found", apply(TenancyCommand.updateTenant("absent", "")));
        refused("namespace_not_found", apply(TenancyCommand.updateNamespace("acme", "absent", "")));
        refused("tenant_reserved", apply(TenancyCommand.deleteTenant("default")));
        refused("namespace_reserved", apply(TenancyCommand.deleteNamespace("acme", "default")));
        refused("namespace_reserved", apply(TenancyCommand.deleteNamespace("default", "default")));
        refused("tenant_not_found", apply(TenancyCommand.deleteTenant("absent")));
        refused("namespace_not_found", apply(TenancyCommand.deleteNamespace("acme", "absent")));
    }

    @Test
    void deletingANamespaceRemovesItsServicesChecksAndKeysAndDeletingATenantItsNamespaces() {
        apply(NodeCommand.register(Node.of("host-a", "10.0.0.1", null, null, Map.of()), T0));
        apply(TenancyCommand.createTenant("acme", ""));
        apply(TenancyCommand.createNamespace("acme", "prod", ""));
        for (ServiceInstance instance : List.of(service("web-prod", "acme", "prod"),
                service("web-acme", "acme", "default"), service("web-default", "default", "default"))) {
            assertInstanceOf(RaftCommandResult.Success.class, apply(CatalogCommand.register(instance)));
        }
        KvScope prod = new KvScope("acme", "prod");
        apply(KvCommand.set(prod, "config", bytes("p"), 0, null));
        apply(KvCommand.set(new KvScope("acme", "default"), "config", bytes("a"), 0, null));
        apply(KvCommand.set(KvScope.DEFAULT, "config", bytes("d"), 0, null));

        assertEquals(new RaftCommandResult.Success<>(true), apply(TenancyCommand.deleteNamespace("acme", "prod")));
        assertFalse(store.getTenancy().namespaceExists("acme", "prod"));
        assertEquals(List.of("web-acme", "web-default"), serviceIds());
        assertTrue(store.getKv().get(prod, "config").isEmpty());
        assertTrue(store.getKv().get(new KvScope("acme", "default"), "config").isPresent());

        assertEquals(new RaftCommandResult.Success<>(true), apply(TenancyCommand.deleteTenant("acme")));
        assertFalse(store.getTenancy().tenantExists("acme"));
        assertFalse(store.getTenancy().namespaceExists("acme", "default"));
        assertEquals(List.of("web-default"), serviceIds());
        assertTrue(store.getKv().get(new KvScope("acme", "default"), "config").isEmpty());
        assertTrue(store.getKv().get(KvScope.DEFAULT, "config").isPresent(), "the default tenant keeps its own");
    }

    @Test
    void theSnapshotCarriesTheRegistryAndOneFromBeforeTenancyRestoresTheDefaults() throws Exception {
        apply(TenancyCommand.createTenant("acme", "Acme"));
        apply(TenancyCommand.createNamespace("acme", "prod", "Production"));

        QraftStateStore restored = new QraftStateStore();
        restored.restoreSnapshot(store.takeSnapshot());
        assertEquals(store.getTenancy().tenants(), restored.getTenancy().tenants());
        assertEquals(store.getTenancy().namespaces("acme"), restored.getTenancy().namespaces("acme"));

        ObjectNode older = (ObjectNode) new ObjectMapper().readTree(store.takeSnapshot());
        older.remove("tenancy");
        QraftStateStore fromOlder = new QraftStateStore();
        fromOlder.restoreSnapshot(new ObjectMapper().writeValueAsBytes(older));
        assertEquals(List.of("default"), fromOlder.getTenancy().tenants().stream().map(Tenant::name).toList());
        assertTrue(fromOlder.getTenancy().namespaceExists("default", "default"));

        store.reset();
        assertFalse(store.getTenancy().tenantExists("acme"), "a reset clears the registry");
    }

    @Test
    void theCommandsRoundTripThroughTheCodec() {
        ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();
        for (RaftCommand command : List.of(TenancyCommand.createTenant("acme", "Acme"),
                TenancyCommand.updateTenant("acme", ""), TenancyCommand.deleteTenant("acme"),
                TenancyCommand.createNamespace("acme", "prod", "Production"),
                TenancyCommand.updateNamespace("acme", "prod", "Prod"), TenancyCommand.deleteNamespace("acme", "prod"))) {
            assertEquals(command, codec.deserialize(codec.serialize(command)), command.toString());
        }
    }

    @Test
    void namesAreLowerCaseDnsLabels() {
        for (String valid : List.of("a", "acme", "cluster-test", "t1", "a".repeat(63))) {
            assertTrue(TenancyNames.valid(valid), valid);
        }
        for (String invalid : List.of("", "Acme", "acme_ltd", "-acme", "acme-", "a.b", "a/b", "a".repeat(64), " acme")) {
            assertFalse(TenancyNames.valid(invalid), invalid);
        }
    }

    private List<String> serviceIds() {
        return store.getServiceCatalog().instances().stream().map(ServiceInstance::serviceId).sorted().toList();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}

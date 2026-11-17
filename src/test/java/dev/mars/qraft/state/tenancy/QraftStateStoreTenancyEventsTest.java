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

import dev.mars.qraft.common.events.DomainEvent;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the tenancy changes' domain events (UI-6 Tenants, Namespaces and Audit task list, decision 1): a tenant's in its
 * own tenant and its {@code default} namespace, a namespace's in its own scope, after what its deletion removes, and none
 * for a refused change.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-17
 * @version 1.0
 */
class QraftStateStoreTenancyEventsTest {
    private final QraftStateStore store = new QraftStateStore();
    private long index;

    private List<DomainEvent> apply(RaftCommand command) {
        store.apply(command);
        store.setLastAppliedIndex(++index);
        return store.drainDomainEvents();
    }

    private static DomainEvent in(String tenant, String namespace, Object event) {
        return new DomainEvent(tenant, namespace, (dev.mars.qraft.common.events.QraftEvent) event);
    }

    @Test
    void eachTenancyChangeProducesItsEventInTheScopeItNamesAndARefusedOneNone() {
        store.recordDomainEvents(true);

        assertEquals(List.of(in("acme", "default", new TenancyEvents.TenantCreated("acme"))),
                apply(TenancyCommand.createTenant("acme", "Acme Ltd")));
        assertEquals(List.of(in("acme", "default", new TenancyEvents.TenantUpdated("acme"))),
                apply(TenancyCommand.updateTenant("acme", "Acme")));
        assertEquals(List.of(in("acme", "prod", new TenancyEvents.NamespaceCreated("acme", "prod"))),
                apply(TenancyCommand.createNamespace("acme", "prod", "")));
        assertEquals(List.of(in("acme", "prod", new TenancyEvents.NamespaceUpdated("acme", "prod"))),
                apply(TenancyCommand.updateNamespace("acme", "prod", "production")));

        assertEquals(List.of(), apply(TenancyCommand.createTenant("acme", "")), "a tenant that exists");
        assertEquals(List.of(), apply(TenancyCommand.deleteNamespace("acme", "default")), "a default namespace");
        assertEquals(List.of(), apply(TenancyCommand.deleteTenant("default")), "the default tenant");
        assertEquals(List.of(), apply(TenancyCommand.updateNamespace("acme", "absent", "")), "an absent namespace");

        List<DomainEvent> deleted = apply(TenancyCommand.deleteNamespace("acme", "prod"));
        assertEquals(in("acme", "prod", new TenancyEvents.NamespaceDeleted("acme", "prod")), deleted.getLast());
        assertEquals(in("acme", "default", new TenancyEvents.TenantDeleted("acme")),
                apply(TenancyCommand.deleteTenant("acme")).getLast(), "after what the deletion removed");
    }
}

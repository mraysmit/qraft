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

import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.events.DomainEvent;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.state.catalog.HealthObservation;
import dev.mars.qraft.state.catalog.ServiceCheckId;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the domain events the store produces with its changes (events list, Phase 4, decision 5; architecture section
 * 6): a node registered, updated, made unreachable and removed; a service registered and deregistered, in its scope;
 * and a service's health changed; while a refused, missing or no-op command, and a store that is not recording,
 * produce none.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class QraftStateStoreDomainEventsTest {
    private static final Instant T0 = Instant.parse("2026-11-08T12:00:00Z");
    private final QraftStateStore store = new QraftStateStore();
    private long index;

    private List<DomainEvent> apply(RaftCommand command) {
        store.apply(command);
        store.setLastAppliedIndex(++index);
        return store.drainDomainEvents();
    }

    private static Node node(String name, String uuid) {
        return Node.of(name, "10.0.0.1", null, null, Map.of()).withId(uuid);
    }

    private static ServiceInstance service(String id) {
        return new ServiceInstance(id, "web", "host-a", "10.0.0.1", 8080, List.of(), Map.of(), ServiceHealth.PASSING,
                "acme", "prod", "", "", true);
    }

    @Test
    void nodeChangesProduceTheirEventsAndARefusedRegistrationNone() {
        assertEquals(List.of(), apply(NodeCommand.register(node("host-a", null), T0)),
                "a store that is not recording produces none");
        store.recordDomainEvents(true);

        assertEquals(List.of(new DomainEvent(null, null, new CatalogEvents.NodeRegistered("host-b"))),
                apply(NodeCommand.register(node("host-b", "1b4e28ba-2fa1-4d2a-883f-0016d3cca427"), T0)));
        assertEquals(List.of(new DomainEvent(null, null, new CatalogEvents.NodeUpdated("host-b"))),
                apply(NodeCommand.register(node("host-b", "1b4e28ba-2fa1-4d2a-883f-0016d3cca427"), T0.plusSeconds(1))));
        assertEquals(List.of(), apply(NodeCommand.register(node("host-b", "9c5b94b1-35ad-49bb-b118-8e8fc24abf80"), T0)),
                "a name another node holds is refused, and changes nothing");
        assertEquals(List.of(new DomainEvent(null, null, new CatalogEvents.NodeRemoved("host-b", "deregistered"))),
                apply(NodeCommand.deregister("host-b")));
        assertEquals(List.of(), apply(NodeCommand.deregister("absent")), "a missing node changes nothing");
    }

    @Test
    void serviceAndHealthChangesProduceTheirEventsInTheirScope() {
        apply(NodeCommand.register(node("host-a", null), T0));
        store.recordDomainEvents(true);

        assertEquals(List.of(new DomainEvent("acme", "prod", new CatalogEvents.ServiceRegistered("web-1", "web", "host-a"))),
                apply(CatalogCommand.register(service("web-1"))));
        ServiceCheckId check = new ServiceCheckId(service("web-1").identity(), "http");
        assertEquals(List.of(new DomainEvent("acme", "prod", new CatalogEvents.HealthChanged("web-1", "web", "PASSING",
                "CRITICAL"))), apply(CatalogCommand.observe(new HealthObservation(check, ServiceHealth.CRITICAL, 2, T0,
                30_000, true, "down", 0), T0)));
        assertEquals(List.of(), apply(CatalogCommand.observe(new HealthObservation(check, ServiceHealth.PASSING, 1, T0,
                30_000, true, "late", 0), T0)), "an observation older than the last is a no-op");
        assertEquals(List.of(new DomainEvent("acme", "prod", new CatalogEvents.ServiceDeregistered("web-1", "web",
                "host-a"))), apply(CatalogCommand.deregister(service("web-1").identity())));
        assertEquals(List.of(), apply(CatalogCommand.deregister(service("web-1").identity())), "not found");

        apply(CatalogCommand.register(service("web-2")));
        assertEquals(List.of(new DomainEvent("acme", "prod", new CatalogEvents.ServiceDeregistered("web-2", "web",
                        "host-a")), new DomainEvent(null, null, new CatalogEvents.NodeRemoved("host-a", "deregistered"))),
                apply(NodeCommand.deregister("host-a")), "a node takes its services with it, in that order");
    }
}

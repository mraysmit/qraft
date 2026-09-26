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

package dev.mars.qraft.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ServiceCatalog} registration, lookup, composite identity per node, idempotent
 * replacement, deregistration, and health transitions.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
class ServiceCatalogTest {

    @Test
    void registersAndLooksUpServiceInstancesByName() {
        ServiceCatalog catalog = new ServiceCatalog();
        ServiceInstance instance = instance("payments-1", "payments", 8080);

        catalog.register(instance);

        assertEquals(List.of(instance), catalog.instances("payments"));
        assertEquals(List.of("payments"), catalog.services());
    }

    @Test
    void sameLocalServiceIdOnDifferentNodesDoesNotCollide() {
        ServiceCatalog catalog = new ServiceCatalog();
        ServiceInstance nodeB = instance("web", "web", "node-b", 8081);
        ServiceInstance nodeA = instance("web", "web", "node-a", 8080);

        catalog.register(nodeB);
        catalog.register(nodeA);

        assertEquals(List.of(nodeA, nodeB), catalog.instances("web"));
    }

    @Test
    void replacingAnExistingCompositeIdentityIsIdempotent() {
        ServiceCatalog catalog = new ServiceCatalog();
        catalog.register(instance("payments-1", "payments", "node-1", 8080));
        ServiceInstance replacement = instance("payments-1", "payments", "node-1", 9090);

        catalog.register(replacement);

        assertEquals(List.of(replacement), catalog.instances("payments"));
    }

    @Test
    void deregistersOnlyTheRequestedInstance() {
        ServiceCatalog catalog = new ServiceCatalog();
        ServiceInstance first = instance("web", "payments", "node-a", 8080);
        ServiceInstance second = instance("web", "payments", "node-b", 8081);
        catalog.register(first);
        catalog.register(second);

        assertTrue(catalog.deregister(first.identity()));

        assertEquals(List.of(second), catalog.instances("payments"));
        assertFalse(catalog.deregister(first.identity()));
    }

    @Test
    void healthTransitionsAreVisibleWithoutMutatingTheRegistration() {
        ServiceCatalog catalog = new ServiceCatalog();
        ServiceInstance registered = instance("payments-1", "payments", 8080);
        catalog.register(registered);

        ServiceInstance unhealthy = catalog.setHealth(registered.identity(), ServiceHealth.FAILING);

        assertEquals(ServiceHealth.FAILING, unhealthy.health());
        assertEquals("payments-1", unhealthy.serviceId());
        assertEquals(8080, unhealthy.port());
        assertNotSame(registered, unhealthy);
        assertEquals(List.of(unhealthy), catalog.instances("payments"));
    }

    @Test
    void lookupsAreImmutableAndUnknownServicesAreEmpty() {
        ServiceCatalog catalog = new ServiceCatalog();
        catalog.register(instance("payments-1", "payments", 8080));

        List<ServiceInstance> instances = catalog.instances("payments");

        assertEquals(List.of(), catalog.instances("unknown"));
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> instances.add(instance("payments-2", "payments", 8081)));
    }

    private static ServiceInstance instance(String id, String name, int port) {
        return instance(id, name, "node-1", port);
    }

    private static ServiceInstance instance(String id, String name, String nodeId, int port) {
        return new ServiceInstance(
                id,
                name,
                nodeId,
                "127.0.0.1",
                port,
                List.of("v1"),
                Map.of("team", "platform"),
                ServiceHealth.PASSING,
                "default", "default", "dc-1", "eu-west", true);
    }
}

package dev.mars.qraft.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void replacingAnExistingServiceIdIsIdempotent() {
        ServiceCatalog catalog = new ServiceCatalog();
        catalog.register(instance("payments-1", "payments", 8080));
        ServiceInstance replacement = instance("payments-1", "payments", 9090);

        catalog.register(replacement);

        assertEquals(List.of(replacement), catalog.instances("payments"));
    }

    @Test
    void deregistersOnlyTheRequestedInstance() {
        ServiceCatalog catalog = new ServiceCatalog();
        ServiceInstance first = instance("payments-1", "payments", 8080);
        ServiceInstance second = instance("payments-2", "payments", 8081);
        catalog.register(first);
        catalog.register(second);

        assertTrue(catalog.deregister("payments-1"));

        assertEquals(List.of(second), catalog.instances("payments"));
        assertFalse(catalog.deregister("payments-1"));
    }

    @Test
    void healthTransitionsAreVisibleWithoutMutatingTheRegistration() {
        ServiceCatalog catalog = new ServiceCatalog();
        ServiceInstance registered = instance("payments-1", "payments", 8080);
        catalog.register(registered);

        ServiceInstance unhealthy = catalog.setHealth("payments-1", ServiceHealth.FAILING);

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
        return new ServiceInstance(
                id,
                name,
                "node-1",
                "127.0.0.1",
                port,
                List.of("v1"),
                Map.of("team", "platform"),
                ServiceHealth.PASSING);
    }
}

package dev.mars.qraft.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceCatalogValidationTest {

    @Test
    void rejectsInvalidServiceInstanceFields() {
        assertThrows(IllegalArgumentException.class, () -> instance("", "payments", 8080));
        assertThrows(IllegalArgumentException.class, () -> instance("id", "", 8080));
        assertThrows(IllegalArgumentException.class, () -> instance("id", "payments", 0));
        assertThrows(IllegalArgumentException.class, () -> instance("id", "payments", 65536));
        assertThrows(NullPointerException.class, () -> new ServiceInstance(
                "id", "payments", "node", "127.0.0.1", 8080, null, Map.of(), ServiceHealth.PASSING));
        assertThrows(NullPointerException.class, () -> new ServiceInstance(
                "id", "payments", "node", "127.0.0.1", 8080, List.of(), Map.of(), null));
    }

    @Test
    void rejectsUnknownHealthUpdates() {
        ServiceCatalog catalog = new ServiceCatalog();

        assertThrows(IllegalArgumentException.class,
                () -> catalog.setHealth(
                        new ServiceInstanceId("default", "default", "node", "missing"),
                        ServiceHealth.FAILING));
    }

    @Test
    void defaultsScopeAndEnabledButRejectsSuppliedBlankScope() {
        ServiceInstance defaults = new ServiceInstance(
                "id", "payments", "node", "127.0.0.1", 8080,
                List.of(), Map.of(), ServiceHealth.PASSING);

        assertEquals("default", defaults.tenantId());
        assertEquals("default", defaults.namespace());
        assertEquals("", defaults.datacenter());
        assertEquals("", defaults.region());
        assertTrue(defaults.enabled());
        assertThrows(IllegalArgumentException.class, () -> scoped(" ", "default", "dc-1"));
        assertThrows(IllegalArgumentException.class, () -> scoped("default", " ", "dc-1"));
        assertThrows(IllegalArgumentException.class, () -> scoped("default", "default", " "));
    }

    @Test
    void replacementCanMoveAnInstanceBetweenServices() {
        ServiceCatalog catalog = new ServiceCatalog();
        catalog.register(instance("id", "payments", 8080));
        catalog.register(instance("id", "orders", 8081));

        assertEquals(List.of(), catalog.instances("payments"));
        assertEquals(List.of("orders"), catalog.services());
        ServiceInstanceId identity = new ServiceInstanceId("default", "default", "node-1", "id");
        assertTrue(catalog.deregister(identity));
        assertFalse(catalog.deregister(identity));
        assertEquals(List.of(), catalog.services());
    }

    private static ServiceInstance instance(String id, String name, int port) {
        return new ServiceInstance(id, name, "node-1", "127.0.0.1", port,
                List.of(), Map.of(), ServiceHealth.PASSING);
    }

    private static ServiceInstance scoped(String tenant, String namespace, String datacenter) {
        return new ServiceInstance("id", "payments", "node", "127.0.0.1", 8080,
                List.of(), Map.of(), ServiceHealth.PASSING,
                tenant, namespace, datacenter, "eu-west", true);
    }
}

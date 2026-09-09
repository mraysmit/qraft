package dev.mars.qraft.tenant.service;

import dev.mars.qraft.tenant.model.Namespace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NamespaceServiceTest {
    private final NamespaceService service = new InMemoryNamespaceService();

    @Test
    void createsAndFindsNamespace() throws Exception {
        Namespace created = service.create(Namespace.named("payments"));

        assertEquals(created, service.find("payments").orElseThrow());
    }

    @Test
    void rejectsDuplicateNames() throws Exception {
        service.create(Namespace.named("payments"));

        assertThrows(NamespaceService.NamespaceException.class,
                () -> service.create(Namespace.named("payments")));
    }

    @Test
    void updatesMetadataAndListsInStableOrder() throws Exception {
        service.create(Namespace.named("zeta"));
        service.create(Namespace.named("alpha"));
        Namespace updated = service.find("alpha").orElseThrow()
                .withDescription("production")
                .withMetadata(Map.of("tier", "critical"));

        service.update(updated);

        assertEquals(List.of("alpha", "zeta"), service.list().stream().map(Namespace::name).toList());
        assertEquals("critical", service.find("alpha").orElseThrow().metadata().get("tier"));
    }

    @Test
    void deletesNamespace() throws Exception {
        service.create(Namespace.named("payments"));

        service.delete("payments");

        assertTrue(service.find("payments").isEmpty());
        assertThrows(NamespaceService.NamespaceException.class, () -> service.delete("payments"));
    }

    @Test
    void rejectsBlankNames() {
        assertThrows(IllegalArgumentException.class, () -> Namespace.named(" "));
    }
}

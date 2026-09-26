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

package dev.mars.qraft.tenant.service;

import dev.mars.qraft.tenant.model.Namespace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link NamespaceService} create, find, update, list, and delete of {@link Namespace}
 * entries, and rejection of duplicate and blank names.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
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

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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the namespace service and model at their edges: updating a namespace that does not exist, null
 * arguments, a delete that races an update, and the record's defaults, defensive copy, and timestamps.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-28
 * @version 1.0
 */
class NamespaceServiceEdgeCaseTest {
    private static final Instant CREATED = Instant.parse("2020-01-01T00:00:00Z");

    @Test
    void updatingANamespaceThatDoesNotExistIsRefusedAndCreatesNothing() {
        NamespaceService service = new InMemoryNamespaceService();

        NamespaceService.NamespaceException refused = assertThrows(NamespaceService.NamespaceException.class,
                () -> service.update(Namespace.named("ghost")));

        assertEquals("Namespace not found: ghost", refused.getMessage());
        assertTrue(service.find("ghost").isEmpty(), "a refused update does not create the namespace");
    }

    @Test
    void nullArgumentsAreRefusedWithTheReason() {
        NamespaceService service = new InMemoryNamespaceService();

        assertEquals("Namespace cannot be null",
                assertThrows(IllegalArgumentException.class, () -> service.create(null)).getMessage());
        assertEquals("Namespace cannot be null",
                assertThrows(IllegalArgumentException.class, () -> service.update(null)).getMessage());
        assertEquals("Namespace name cannot be null",
                assertThrows(IllegalArgumentException.class, () -> service.find(null)).getMessage());
        assertEquals("Namespace name cannot be null",
                assertThrows(IllegalArgumentException.class, () -> service.delete(null)).getMessage());
    }

    @Test
    void aDeleteThatRacesAnUpdateIsNeverUndone() throws Exception {
        // The delete lands just after the update's first operation on the map returns: for a check followed by
        // a write, that is between the two. The update may succeed (ordered before the delete) or be refused
        // (after it), but the delete must stand.
        DeletingAfterFirstLookup namespaces = new DeletingAfterFirstLookup("payments");
        InMemoryNamespaceService service = new InMemoryNamespaceService(namespaces);
        service.create(Namespace.named("payments"));
        namespaces.arm(() -> {
            try {
                service.delete("payments");
            } catch (NamespaceService.NamespaceException error) {
                throw new AssertionError(error);
            }
        });

        try {
            service.update(Namespace.named("payments").withDescription("late"));
        } catch (NamespaceService.NamespaceException refusedAfterTheDelete) {
            // Also correct: the update came second and found nothing to update.
        }

        assertTrue(namespaces.fired(), "the delete ran inside the update");
        assertTrue(service.find("payments").isEmpty(), "the deleted namespace stays deleted");
    }

    @Test
    void theRecordDefaultsMissingValuesAndCopiesItsMetadata() {
        Map<String, String> metadata = new HashMap<>(Map.of("tier", "critical"));
        Namespace namespace = new Namespace("payments", null, metadata, CREATED, null);
        metadata.put("tier", "changed");

        assertEquals(Map.of("tier", "critical"), namespace.metadata(), "the caller's map is copied");
        assertEquals(CREATED, namespace.updatedAt(), "a namespace never updated was updated when created");
        assertEquals(Map.of(), new Namespace("payments", null, null, CREATED, null).metadata());
        assertFalse(new Namespace("payments", null, null, null, null).createdAt().isBefore(CREATED));
    }

    @Test
    void changingANamespaceKeepsItsCreationTimeAndMovesItsUpdateTime() {
        Namespace original = new Namespace("payments", null, Map.of(), CREATED, CREATED);

        for (Namespace changed : new Namespace[] {original.withDescription("live"),
                original.withMetadata(Map.of("tier", "critical"))}) {
            assertEquals(CREATED, changed.createdAt());
            assertTrue(changed.updatedAt().isAfter(CREATED), "a change records when it was made");
        }
        assertEquals("live", original.withDescription("live").description());
        assertEquals(Map.of("tier", "critical"), original.withMetadata(Map.of("tier", "critical")).metadata());
    }

    /** A namespace map that runs one action just after the given name's first lookup or replacement returns. */
    private static final class DeletingAfterFirstLookup extends ConcurrentHashMap<String, Namespace> {
        private final String name;
        private Runnable action;
        private boolean fired;

        DeletingAfterFirstLookup(String name) {
            this.name = name;
        }

        void arm(Runnable afterFirstLookup) {
            action = afterFirstLookup;
        }

        boolean fired() {
            return fired;
        }

        private <T> T thenFire(Object key, T result) {
            Runnable pending = action;
            if (pending != null && name.equals(key)) {
                action = null;
                fired = true;
                pending.run();
            }
            return result;
        }

        @Override public boolean containsKey(Object key) {
            return thenFire(key, super.containsKey(key));
        }

        @Override public Namespace get(Object key) {
            return thenFire(key, super.get(key));
        }

        @Override public Namespace replace(String key, Namespace value) {
            return thenFire(key, super.replace(key, value));
        }

        @Override public Namespace computeIfPresent(String key,
                BiFunction<? super String, ? super Namespace, ? extends Namespace> remapping) {
            return thenFire(key, super.computeIfPresent(key, remapping));
        }
    }
}

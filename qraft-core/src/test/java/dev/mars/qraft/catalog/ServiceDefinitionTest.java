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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that {@link ServiceDefinition} is immutable and rejects missing identity fields and invalid
 * ports.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
class ServiceDefinitionTest {
    @Test
    void isAnImmutableClientOwnedDefinition() {
        List<String> tags = new java.util.ArrayList<>(List.of("primary"));
        Map<String, String> metadata = new java.util.HashMap<>(Map.of("zone", "a"));

        ServiceDefinition definition = new ServiceDefinition(
                "web-1", "web", "10.0.0.5", 8080, tags, metadata, true);
        tags.add("changed");
        metadata.put("owner", "other");

        assertEquals(List.of("primary"), definition.tags());
        assertEquals(Map.of("zone", "a"), definition.metadata());
        assertThrows(UnsupportedOperationException.class, () -> definition.tags().add("changed"));
        assertThrows(UnsupportedOperationException.class, () -> definition.metadata().put("x", "y"));
    }

    @Test
    void rejectsMissingIdentityAndInvalidPorts() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceDefinition("", "web", "localhost", 80, List.of(), Map.of(), true));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceDefinition("web", "", "localhost", 80, List.of(), Map.of(), true));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceDefinition("web", "web", "", 80, List.of(), Map.of(), true));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceDefinition("web", "web", "localhost", 0, List.of(), Map.of(), true));
    }
}

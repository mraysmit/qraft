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

package dev.mars.qraft.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link ConfigFileResolver} precedence of explicit argument, JVM property, and conventional
 * locations, and rejection of invalid or missing paths.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
class ConfigFileResolverTest {
    @Test
    void explicitArgumentHasHighestPrecedence() {
        assertEquals(Path.of("explicit.json"), ConfigFileResolver.resolve(
                new String[]{"--config", "explicit.json"}, "server", "property.json", path -> true));
    }

    @Test
    void usesJvmPropertyWhenNoArgumentIsSupplied() {
        assertEquals(Path.of("property.json"), ConfigFileResolver.resolve(
                new String[0], "client", "property.json", path -> true));
    }

    @Test
    void selectsFirstExistingConventionalLocation() {
        Path systemFile = Path.of("/etc/qraft/server.json");
        assertEquals(systemFile, ConfigFileResolver.resolve(
                new String[0], "server", null, Set.of(systemFile)::contains));
    }

    @Test
    void localConventionalLocationPrecedesSystemLocation() {
        assertEquals(Path.of("config/client.json"), ConfigFileResolver.resolve(
                new String[0], "client", null, path -> true));
    }

    @Test
    void rejectsBlankPathsInvalidArgumentsAndMissingDefaults() {
        assertThrows(IllegalArgumentException.class, () -> ConfigFileResolver.resolve(
                new String[]{"--config", " "}, "server", null, path -> false));
        assertThrows(IllegalArgumentException.class, () -> ConfigFileResolver.resolve(
                new String[0], "server", " ", path -> false));
        assertThrows(IllegalArgumentException.class, () -> ConfigFileResolver.resolve(
                new String[]{"unexpected"}, "server", null, path -> false));
        assertThrows(IllegalArgumentException.class, () -> ConfigFileResolver.resolve(
                new String[0], "server", null, path -> false));
    }
}

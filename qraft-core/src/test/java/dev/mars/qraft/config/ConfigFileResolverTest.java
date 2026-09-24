package dev.mars.qraft.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

package dev.mars.qraft.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import dev.mars.qraft.config.ConfigFileResolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QraftRuntimeApplicationTest {
    @Test
    void acceptsExplicitServerAndClientModes() {
        assertEquals(new QraftRuntimeApplication.Startup("server", java.nio.file.Path.of("server.json")),
                QraftRuntimeApplication.parseArguments(
                        new String[]{"server", "--config", "server.json"}));
        assertEquals(new QraftRuntimeApplication.Startup("client", java.nio.file.Path.of("client.json")),
                QraftRuntimeApplication.parseArguments(
                        new String[]{"client", "--config", "client.json"}));
    }

    @Test
    @ResourceLock("systemProperties")
    void acceptsJvmPropertyWhenConfigArgumentIsOmitted() {
        String previous = System.getProperty(ConfigFileResolver.CONFIG_FILE_PROPERTY);
        try {
            System.setProperty(ConfigFileResolver.CONFIG_FILE_PROPERTY, "property-server.json");
            assertEquals(new QraftRuntimeApplication.Startup(
                            "server", java.nio.file.Path.of("property-server.json")),
                    QraftRuntimeApplication.parseArguments(new String[]{"server"}));
        } finally {
            if (previous == null) System.clearProperty(ConfigFileResolver.CONFIG_FILE_PROPERTY);
            else System.setProperty(ConfigFileResolver.CONFIG_FILE_PROPERTY, previous);
        }
    }

    @Test
    void rejectsMissingInvalidAndAmbiguousModes() {
        assertThrows(IllegalArgumentException.class,
                () -> QraftRuntimeApplication.parseArguments(new String[0]));
        assertThrows(IllegalArgumentException.class,
                () -> QraftRuntimeApplication.parseArguments(new String[]{"worker", "--config", "a.json"}));
        assertThrows(IllegalArgumentException.class,
                () -> QraftRuntimeApplication.parseArguments(new String[]{"server"}));
        assertThrows(IllegalArgumentException.class,
                () -> QraftRuntimeApplication.parseArguments(
                        new String[]{"server", "--config", "a.json", "extra"}));
    }
}

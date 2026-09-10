package dev.mars.qraft.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QraftRuntimeApplicationTest {
    @Test
    void acceptsExplicitServerAndClientModes() {
        assertEquals("server", QraftRuntimeApplication.resolveMode(new String[]{"server"}, null));
        assertEquals("client", QraftRuntimeApplication.resolveMode(new String[]{"--mode=client"}, null));
    }

    @Test
    void usesEnvironmentModeWhenNoArgumentIsProvided() {
        assertEquals("server", QraftRuntimeApplication.resolveMode(new String[0], "server"));
    }

    @Test
    void rejectsMissingInvalidAndAmbiguousModes() {
        assertThrows(IllegalArgumentException.class,
                () -> QraftRuntimeApplication.resolveMode(new String[0], null));
        assertThrows(IllegalArgumentException.class,
                () -> QraftRuntimeApplication.resolveMode(new String[]{"worker"}, null));
        assertThrows(IllegalArgumentException.class,
                () -> QraftRuntimeApplication.resolveMode(new String[]{"server", "client"}, null));
    }
}

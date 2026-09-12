package dev.mars.qraft.controller.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigCoverageTest {

    private static final String[] KEYS = {
            "qraft.test.string", "qraft.test.int", "qraft.test.long", "qraft.test.boolean",
            "qraft.raft.storage.type", "qraft.raft.storage.fsync"
    };

    @AfterEach
    void clearProperties() {
        for (String key : KEYS) System.clearProperty(key);
    }

    @Test
    void resolvesTypedSystemPropertiesAndFallbacks() {
        AppConfig config = AppConfig.get();
        System.setProperty("qraft.test.string", "value");
        System.setProperty("qraft.test.int", "42");
        System.setProperty("qraft.test.long", "9000000000");
        System.setProperty("qraft.test.boolean", "true");

        assertEquals("value", config.getString("qraft.test.string", "fallback"));
        assertEquals(42, config.getInt("qraft.test.int", 1));
        assertEquals(9_000_000_000L, config.getLong("qraft.test.long", 1));
        assertTrue(config.getBoolean("qraft.test.boolean", false));
        assertEquals("fallback", config.getString("qraft.test.missing", "fallback"));

        System.setProperty("qraft.test.int", "invalid");
        System.setProperty("qraft.test.long", "invalid");
        assertEquals(7, config.getInt("qraft.test.int", 7));
        assertEquals(8, config.getLong("qraft.test.long", 8));
    }

    @Test
    void exposesValidDefaultConfiguration() {
        AppConfig config = AppConfig.get();
        assertDoesNotThrow(config::validate);
        assertTrue(config.getHttpPort() > 0);
        assertFalse(config.getHttpHost().isBlank());
        assertTrue(config.getRaftPort() > 0);
        assertTrue(config.getApiGrpcPort() > 0);
        assertFalse(config.getRaftStorageType().isBlank());
        assertFalse(config.getRaftStoragePath().isBlank());
        assertTrue(config.getSnapshotThreshold() > 0);
        assertTrue(config.getSnapshotCheckIntervalMs() > 0);
        assertTrue(config.getLogHardLimit() > 0);
        assertFalse(config.getServiceName().isBlank());
        assertTrue(config.getRaftIoPoolSize() > 0);
        assertTrue(config.getRaftIoQueueSize() > 0);
    }

    @Test
    void acceptsOnlyTheExternalWalStorageType() {
        AppConfig config = AppConfig.get();

        System.setProperty("qraft.raft.storage.type", "raftlog");
        assertDoesNotThrow(config::validate);

        System.setProperty("qraft.raft.storage.type", "file");
        IllegalStateException failure = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(failure.getMessage().contains("raftlog"));
    }

    @Test
    void rejectsDisablingWalDurability() {
        AppConfig config = AppConfig.get();
        System.setProperty("qraft.raft.storage.type", "raftlog");
        System.setProperty("qraft.raft.storage.fsync", "false");

        IllegalStateException failure = assertThrows(IllegalStateException.class, config::validate);
        assertTrue(failure.getMessage().contains("fsync"));
    }
}

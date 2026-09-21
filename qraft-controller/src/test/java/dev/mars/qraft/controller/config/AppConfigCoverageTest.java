package dev.mars.qraft.controller.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigCoverageTest {

    @Test
    void packagesTheConfigurationFileThatAppConfigLoads() throws Exception {
        ClassLoader classLoader = AppConfig.class.getClassLoader();
        try (InputStream current = classLoader.getResourceAsStream("qraft-controller.properties")) {
            assertNotNull(current, "qraft-controller.properties must be packaged for AppConfig");
        }
        assertNull(classLoader.getResource("quorus-controller.properties"),
                "legacy Quorus configuration must not be packaged");
    }

    @Test
    void packagesTheProductionConfigurationRatherThanOnlyATestResource() throws Exception {
        Path classesDirectory = Path.of(AppConfig.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path productionConfiguration = classesDirectory.resolve("qraft-controller.properties");
        Path legacyConfiguration = classesDirectory.resolve("quorus-controller.properties");

        assertTrue(Files.isRegularFile(productionConfiguration),
                "the controller artifact must contain qraft-controller.properties");
        assertFalse(Files.exists(legacyConfiguration),
                "the controller artifact must not contain the legacy Quorus resource");

        Properties packaged = new Properties();
        try (InputStream input = Files.newInputStream(productionConfiguration)) {
            packaged.load(input);
        }
        Map<String, String> liveDefaults = Map.ofEntries(
                Map.entry("qraft.version", "2.0-ext"),
                Map.entry("qraft.node.id", ""),
                Map.entry("qraft.http.port", "8080"),
                Map.entry("qraft.http.host", "0.0.0.0"),
                Map.entry("qraft.api.grpc.port", "10080"),
                Map.entry("qraft.raft.port", "9080"),
                Map.entry("qraft.cluster.nodes", ""),
                Map.entry("qraft.raft.storage.type", "raftlog"),
                Map.entry("qraft.raft.storage.path", ""),
                Map.entry("qraft.raft.storage.fsync", "true"),
                Map.entry("qraft.raft.snapshot.enabled", "true"),
                Map.entry("qraft.raft.snapshot.threshold", "10000"),
                Map.entry("qraft.raft.snapshot.check-interval-ms", "60000"),
                Map.entry("qraft.raft.log.hard-limit", "100000"),
                Map.entry("qraft.telemetry.otlp.endpoint", "http://localhost:4317"),
                Map.entry("qraft.telemetry.prometheus.port", "9464"),
                Map.entry("qraft.telemetry.enabled", "true"),
                Map.entry("qraft.telemetry.service.name", "qraft-controller"),
                Map.entry("qraft.raft.io.pool-size", "10"),
                Map.entry("qraft.raft.io.queue-size", "1000"));
        liveDefaults.forEach((key, value) -> assertEquals(value, packaged.getProperty(key),
                () -> "the production resource must declare live setting " + key));
        assertFalse(packaged.stringPropertyNames().stream().anyMatch(key -> key.startsWith("qraft.jobs.")),
                "the production resource must not advertise unsupported job-scheduler settings");

        byte[] productionBytes = Files.readAllBytes(productionConfiguration);
        AtomicReference<String> requestedResource = new AtomicReference<>();
        ClassLoader productionResources = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                requestedResource.set(name);
                return "qraft-controller.properties".equals(name)
                        ? new ByteArrayInputStream(productionBytes)
                        : null;
            }
        };

        new AppConfig(productionResources);
        assertEquals("qraft-controller.properties", requestedResource.get(),
                "AppConfig must request the packaged production resource by its exact name");
    }

    @Test
    void refusesToStartWhenTheConfigurationResourceIsMissing() {
        ClassLoader withoutConfiguration = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return null;
            }
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new AppConfig(withoutConfiguration));

        assertTrue(failure.getMessage().contains("qraft-controller.properties"));
    }

    @Test
    void refusesToStartWhenTheConfigurationResourceCannotBeRead() {
        ClassLoader withUnreadableConfiguration = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("unreadable test resource");
                    }
                };
            }
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new AppConfig(withUnreadableConfiguration));

        assertTrue(failure.getMessage().contains("qraft-controller.properties"));
        assertInstanceOf(IOException.class, failure.getCause());
    }

    @Test
    void refusesToStartWhenTheConfigurationResourceIsMalformed() {
        ClassLoader withMalformedConfiguration = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return new ByteArrayInputStream(
                        "qraft.version=\\u12G4\n".getBytes(StandardCharsets.ISO_8859_1));
            }
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new AppConfig(withMalformedConfiguration));

        assertTrue(failure.getMessage().contains("qraft-controller.properties"));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

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
    void blankConfiguredStoragePathUsesTheNodeSpecificDefault() {
        AppConfig config = AppConfig.get();

        assertEquals("./data/raft/" + config.getNodeId(), config.getRaftStoragePath());
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

package dev.mars.qraft.config;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for QraftConfiguration.
 * Validates configuration loading, property access, type conversion, and default values.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-01-20
 * @version 1.0
 */
class QraftConfigurationTest {

    private QraftConfiguration config;
    private String originalSystemProperty;

    @BeforeEach
    void setUp() {
        config = new QraftConfiguration();
        // Save original system property to restore later
        originalSystemProperty = System.getProperty("qraft.test.property");
    }

    @AfterEach
    void tearDown() {
        // Restore original system property
        if (originalSystemProperty != null) {
            System.setProperty("qraft.test.property", originalSystemProperty);
        } else {
            System.clearProperty("qraft.test.property");
        }
        // Clean up any test system properties
        System.clearProperty("qraft.transfer.max.concurrent");
        System.clearProperty("qraft.transfer.max.retries");
        System.clearProperty("qraft.monitoring.metrics.enabled");
    }

    // ========== Default Configuration Tests ==========

    @Test
    void testDefaultMaxConcurrentTransfers() {
        assertEquals(10, config.getMaxConcurrentTransfers());
    }

    @Test
    void testDefaultMaxRetryAttempts() {
        assertEquals(3, config.getMaxRetryAttempts());
    }

    @Test
    void testDefaultRetryDelayMs() {
        assertEquals(1000, config.getRetryDelayMs());
    }

    @Test
    void testDefaultBufferSize() {
        assertEquals(8192, config.getBufferSize());
    }

    @Test
    void testDefaultConnectionTimeoutMs() {
        assertEquals(30000, config.getConnectionTimeoutMs());
    }

    @Test
    void testDefaultReadTimeoutMs() {
        assertEquals(60000, config.getReadTimeoutMs());
    }

    @Test
    void testDefaultMaxFileSize() {
        assertEquals(10L * 1024 * 1024 * 1024, config.getMaxFileSize()); // 10GB
    }

    @Test
    void testDefaultChecksumAlgorithm() {
        assertEquals("SHA-256", config.getChecksumAlgorithm());
    }

    @Test
    void testDefaultTempDirectory() {
        assertEquals(System.getProperty("java.io.tmpdir"), config.getTempDirectory());
    }

    @Test
    void testDefaultMetricsEnabled() {
        assertTrue(config.isMetricsEnabled());
    }

    @Test
    void testDefaultHealthCheckEnabled() {
        assertTrue(config.isHealthCheckEnabled());
    }

    @Test
    void testDefaultStateCleanupIntervalMs() {
        assertEquals(3600000, config.getStateCleanupIntervalMs()); // 1 hour
    }

    @Test
    void testDefaultMaxStateAgeMs() {
        assertEquals(86400000, config.getMaxStateAgeMs()); // 24 hours
    }

    // ========== Constructor with Properties Tests ==========

    @Test
    void testConstructorWithProperties() {
        Properties props = new Properties();
        props.setProperty("qraft.transfer.max.concurrent", "20");
        props.setProperty("qraft.transfer.max.retries", "5");
        props.setProperty("qraft.file.checksum.algorithm", "MD5");

        QraftConfiguration customConfig = new QraftConfiguration(props);

        assertEquals(20, customConfig.getMaxConcurrentTransfers());
        assertEquals(5, customConfig.getMaxRetryAttempts());
        assertEquals("MD5", customConfig.getChecksumAlgorithm());
    }

    @Test
    void testConstructorWithNullProperties() {
        QraftConfiguration customConfig = new QraftConfiguration(null);

        // Should fall back to defaults
        assertEquals(10, customConfig.getMaxConcurrentTransfers());
        assertEquals(3, customConfig.getMaxRetryAttempts());
    }

    @Test
    void testConstructorWithEmptyProperties() {
        Properties props = new Properties();
        QraftConfiguration customConfig = new QraftConfiguration(props);

        // Should use defaults
        assertEquals(10, customConfig.getMaxConcurrentTransfers());
        assertEquals(3, customConfig.getMaxRetryAttempts());
    }

    // ========== Property Override Tests ==========

    @Test
    void testSetAndGetProperty() {
        config.setProperty("custom.property", "custom-value");
        assertEquals("custom-value", config.getProperty("custom.property"));
    }

    @Test
    void testGetPropertyWithDefault() {
        assertNull(config.getProperty("nonexistent.property"));
        assertEquals("default-value", config.getProperty("nonexistent.property", "default-value"));
    }

    @Test
    void testOverrideMaxConcurrentTransfers() {
        config.setProperty("qraft.transfer.max.concurrent", "25");
        assertEquals(25, config.getMaxConcurrentTransfers());
    }

    @Test
    void testOverrideRetryDelayMs() {
        config.setProperty("qraft.transfer.retry.delay.ms", "2500");
        assertEquals(2500, config.getRetryDelayMs());
    }

    @Test
    void testOverrideMaxFileSize() {
        config.setProperty("qraft.file.max.size", "5368709120"); // 5GB
        assertEquals(5368709120L, config.getMaxFileSize());
    }

    @Test
    void testOverrideChecksumAlgorithm() {
        config.setProperty("qraft.file.checksum.algorithm", "SHA-512");
        assertEquals("SHA-512", config.getChecksumAlgorithm());
    }

    @Test
    void testOverrideTempDirectory() {
        config.setProperty("qraft.file.temp.dir", "/custom/temp");
        assertEquals("/custom/temp", config.getTempDirectory());
    }

    @Test
    void testOverrideMetricsEnabled() {
        config.setProperty("qraft.monitoring.metrics.enabled", "false");
        assertFalse(config.isMetricsEnabled());
    }

    @Test
    void testOverrideHealthCheckEnabled() {
        config.setProperty("qraft.monitoring.health.enabled", "false");
        assertFalse(config.isHealthCheckEnabled());
    }

    // ========== Type Conversion Tests ==========

    @Test
    void testInvalidIntegerPropertyUsesDefault() {
        config.setProperty("qraft.transfer.max.concurrent", "not-a-number");
        assertEquals(10, config.getMaxConcurrentTransfers()); // Falls back to default
    }

    @Test
    void testInvalidLongPropertyUsesDefault() {
        config.setProperty("qraft.file.max.size", "invalid-long");
        assertEquals(10L * 1024 * 1024 * 1024, config.getMaxFileSize()); // Falls back to default
    }

    @Test
    void testBooleanPropertyTrueVariations() {
        config.setProperty("qraft.monitoring.metrics.enabled", "true");
        assertTrue(config.isMetricsEnabled());

        config.setProperty("qraft.monitoring.metrics.enabled", "TRUE");
        assertTrue(config.isMetricsEnabled());

        config.setProperty("qraft.monitoring.metrics.enabled", "True");
        assertTrue(config.isMetricsEnabled());
    }

    @Test
    void testBooleanPropertyFalseVariations() {
        config.setProperty("qraft.monitoring.metrics.enabled", "false");
        assertFalse(config.isMetricsEnabled());

        config.setProperty("qraft.monitoring.metrics.enabled", "FALSE");
        assertFalse(config.isMetricsEnabled());

        config.setProperty("qraft.monitoring.metrics.enabled", "anything-else");
        assertFalse(config.isMetricsEnabled()); // Non-true values are false
    }

    @Test
    void testIntegerPropertyWithWhitespace() {
        config.setProperty("qraft.transfer.max.concurrent", "  15  ");
        assertEquals(15, config.getMaxConcurrentTransfers());
    }

    @Test
    void testLongPropertyWithWhitespace() {
        config.setProperty("qraft.transfer.retry.delay.ms", "  3000  ");
        assertEquals(3000, config.getRetryDelayMs());
    }

    @Test
    void testBooleanPropertyWithWhitespace() {
        config.setProperty("qraft.monitoring.metrics.enabled", "  true  ");
        assertTrue(config.isMetricsEnabled());
    }

    // ========== Boundary Value Tests ==========

    @Test
    void testMaximumIntValue() {
        config.setProperty("qraft.transfer.max.concurrent", String.valueOf(Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, config.getMaxConcurrentTransfers());
    }

    @Test
    void testMinimumIntValue() {
        config.setProperty("qraft.transfer.max.retries", String.valueOf(0));
        assertEquals(0, config.getMaxRetryAttempts());
    }

    @Test
    void testNegativeIntValue() {
        config.setProperty("qraft.transfer.max.concurrent", "-5");
        assertEquals(-5, config.getMaxConcurrentTransfers());
    }

    @Test
    void testMaximumLongValue() {
        config.setProperty("qraft.file.max.size", String.valueOf(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, config.getMaxFileSize());
    }

    @Test
    void testZeroLongValue() {
        config.setProperty("qraft.transfer.retry.delay.ms", "0");
        assertEquals(0, config.getRetryDelayMs());
    }

    // ========== toString() Tests ==========

    @Test
    void testToStringContainsKeyInformation() {
        String configStr = config.toString();

        assertTrue(configStr.contains("QraftConfiguration"));
        assertTrue(configStr.contains("maxConcurrentTransfers=10"));
        assertTrue(configStr.contains("maxRetryAttempts=3"));
        assertTrue(configStr.contains("checksumAlgorithm='SHA-256'"));
        assertTrue(configStr.contains("metricsEnabled=true"));
    }

    @Test
    void testToStringWithCustomValues() {
        config.setProperty("qraft.transfer.max.concurrent", "50");
        config.setProperty("qraft.transfer.max.retries", "7");
        config.setProperty("qraft.monitoring.metrics.enabled", "false");

        String configStr = config.toString();

        assertTrue(configStr.contains("maxConcurrentTransfers=50"));
        assertTrue(configStr.contains("maxRetryAttempts=7"));
        assertTrue(configStr.contains("metricsEnabled=false"));
    }

    // ========== System Property Override Tests ==========

    @Test
    void testSystemPropertyOverride() {
        // Set system property before creating config
        System.setProperty("qraft.transfer.max.concurrent", "100");

        QraftConfiguration sysConfig = new QraftConfiguration();
        assertEquals(100, sysConfig.getMaxConcurrentTransfers());
    }

    @Test
    void testMultipleSystemPropertyOverrides() {
        System.setProperty("qraft.transfer.max.concurrent", "99");
        System.setProperty("qraft.transfer.max.retries", "8");
        System.setProperty("qraft.monitoring.metrics.enabled", "false");

        QraftConfiguration sysConfig = new QraftConfiguration();
        assertEquals(99, sysConfig.getMaxConcurrentTransfers());
        assertEquals(8, sysConfig.getMaxRetryAttempts());
        assertFalse(sysConfig.isMetricsEnabled());
    }

    // ========== Edge Cases ==========

    @Test
    void testEmptyStringProperty() {
        config.setProperty("qraft.file.checksum.algorithm", "");
        assertEquals("", config.getChecksumAlgorithm());
    }

    @Test
    void testNullStringPropertyUsesDefault() {
        // Setting null should use default
        String result = config.getProperty("nonexistent.key", "default");
        assertEquals("default", result);
    }

    @Test
    void testAllConfigurationGettersInvoked() {
        // Ensure all getters can be called without exceptions
        assertNotNull(config.getMaxConcurrentTransfers());
        assertNotNull(config.getMaxRetryAttempts());
        assertNotNull(config.getRetryDelayMs());
        assertNotNull(config.getBufferSize());
        assertNotNull(config.getConnectionTimeoutMs());
        assertNotNull(config.getReadTimeoutMs());
        assertNotNull(config.getMaxFileSize());
        assertNotNull(config.getChecksumAlgorithm());
        assertNotNull(config.getTempDirectory());
        assertNotNull(config.isMetricsEnabled());
        assertNotNull(config.isHealthCheckEnabled());
        assertNotNull(config.getStateCleanupIntervalMs());
        assertNotNull(config.getMaxStateAgeMs());
    }

    @Test
    void testConfigurationIsImmutableAfterCreation() {
        // Test that original defaults are preserved
        QraftConfiguration config1 = new QraftConfiguration();
        int original = config1.getMaxConcurrentTransfers();

        // Modify instance
        config1.setProperty("qraft.transfer.max.concurrent", "999");
        assertEquals(999, config1.getMaxConcurrentTransfers());

        // New instance should have original defaults
        QraftConfiguration config2 = new QraftConfiguration();
        assertEquals(original, config2.getMaxConcurrentTransfers());
    }
}

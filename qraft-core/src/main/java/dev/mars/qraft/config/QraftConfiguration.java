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


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Configuration management for Qraft system.
 * Handles loading and providing access to system configuration parameters.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-17
 * @version 1.0
 */
public class QraftConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(QraftConfiguration.class);
    
    // Default configuration values
    private static final int DEFAULT_MAX_CONCURRENT_TRANSFERS = 10;
    private static final int DEFAULT_MAX_RETRY_ATTEMPTS = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 1000;
    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private static final int DEFAULT_CONNECTION_TIMEOUT_MS = 30000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 60000;
    private static final long DEFAULT_MAX_FILE_SIZE = 10L * 1024 * 1024 * 1024; // 10GB
    private static final String DEFAULT_CHECKSUM_ALGORITHM = "SHA-256";
    private static final String DEFAULT_TEMP_DIR = System.getProperty("java.io.tmpdir");
    
    private final Properties properties;
    
    public QraftConfiguration() {
        logger.debug("Initializing QraftConfiguration with default settings");
        this.properties = new Properties();
        loadDefaultConfiguration();
        loadConfigurationFromFile();
        loadConfigurationFromSystemProperties();
        logger.debug("QraftConfiguration initialized: maxConcurrentTransfers={}, maxRetryAttempts={}", 
                     getMaxConcurrentTransfers(), getMaxRetryAttempts());
    }
    
    public QraftConfiguration(Properties properties) {
        logger.debug("Initializing QraftConfiguration with custom properties: count={}", 
                     properties != null ? properties.size() : 0);
        this.properties = new Properties();
        loadDefaultConfiguration();
        if (properties != null) {
            this.properties.putAll(properties);
            logger.debug("Applied {} custom properties", properties.size());
        }
    }
    
    // Transfer Engine Configuration
    public int getMaxConcurrentTransfers() {
        return getIntProperty("qraft.transfer.max.concurrent", DEFAULT_MAX_CONCURRENT_TRANSFERS);
    }
    
    public int getMaxRetryAttempts() {
        return getIntProperty("qraft.transfer.max.retries", DEFAULT_MAX_RETRY_ATTEMPTS);
    }
    
    public long getRetryDelayMs() {
        return getLongProperty("qraft.transfer.retry.delay.ms", DEFAULT_RETRY_DELAY_MS);
    }
    
    public int getBufferSize() {
        return getIntProperty("qraft.transfer.buffer.size", DEFAULT_BUFFER_SIZE);
    }
    
    // Network Configuration
    public int getConnectionTimeoutMs() {
        return getIntProperty("qraft.network.connection.timeout.ms", DEFAULT_CONNECTION_TIMEOUT_MS);
    }
    
    public int getReadTimeoutMs() {
        return getIntProperty("qraft.network.read.timeout.ms", DEFAULT_READ_TIMEOUT_MS);
    }
    
    // File Configuration
    public long getMaxFileSize() {
        return getLongProperty("qraft.file.max.size", DEFAULT_MAX_FILE_SIZE);
    }
    
    public String getChecksumAlgorithm() {
        return getStringProperty("qraft.file.checksum.algorithm", DEFAULT_CHECKSUM_ALGORITHM);
    }
    
    public String getTempDirectory() {
        return getStringProperty("qraft.file.temp.dir", DEFAULT_TEMP_DIR);
    }
    
    // Monitoring Configuration
    public boolean isMetricsEnabled() {
        return getBooleanProperty("qraft.monitoring.metrics.enabled", true);
    }
    
    public boolean isHealthCheckEnabled() {
        return getBooleanProperty("qraft.monitoring.health.enabled", true);
    }
    
    public long getStateCleanupIntervalMs() {
        return getLongProperty("qraft.monitoring.state.cleanup.interval.ms", 3600000); // 1 hour
    }
    
    public long getMaxStateAgeMs() {
        return getLongProperty("qraft.monitoring.state.max.age.ms", 86400000); // 24 hours
    }
    
    // Generic property access
    public String getProperty(String key) {
        return properties.getProperty(key);
    }
    
    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }
    
    public void setProperty(String key, String value) {
        properties.setProperty(key, value);
    }
    
    // Utility methods for type conversion
    private String getStringProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }
    
    private int getIntProperty(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            try {
                int result = Integer.parseInt(value.trim());
                logger.debug("Retrieved int property: key={}, value={}", key, result);
                return result;
            } catch (NumberFormatException e) {
                logger.warn("Invalid integer value for property {}: {}. Using default: {}", 
                           key, value, defaultValue);
            }
        }
        logger.debug("Using default int property: key={}, defaultValue={}", key, defaultValue);
        return defaultValue;
    }
    
    private long getLongProperty(String key, long defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            try {
                long result = Long.parseLong(value.trim());
                logger.debug("Retrieved long property: key={}, value={}", key, result);
                return result;
            } catch (NumberFormatException e) {
                logger.warn("Invalid long value for property {}: {}. Using default: {}", 
                           key, value, defaultValue);
            }
        }
        logger.debug("Using default long property: key={}, defaultValue={}", key, defaultValue);
        return defaultValue;
    }
    
    private boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            return Boolean.parseBoolean(value.trim());
        }
        return defaultValue;
    }
    
    private void loadDefaultConfiguration() {
        logger.debug("Loading default configuration values");
        // Set default values
        properties.setProperty("qraft.transfer.max.concurrent", String.valueOf(DEFAULT_MAX_CONCURRENT_TRANSFERS));
        properties.setProperty("qraft.transfer.max.retries", String.valueOf(DEFAULT_MAX_RETRY_ATTEMPTS));
        properties.setProperty("qraft.transfer.retry.delay.ms", String.valueOf(DEFAULT_RETRY_DELAY_MS));
        properties.setProperty("qraft.transfer.buffer.size", String.valueOf(DEFAULT_BUFFER_SIZE));
        properties.setProperty("qraft.network.connection.timeout.ms", String.valueOf(DEFAULT_CONNECTION_TIMEOUT_MS));
        properties.setProperty("qraft.network.read.timeout.ms", String.valueOf(DEFAULT_READ_TIMEOUT_MS));
        properties.setProperty("qraft.file.max.size", String.valueOf(DEFAULT_MAX_FILE_SIZE));
        properties.setProperty("qraft.file.checksum.algorithm", DEFAULT_CHECKSUM_ALGORITHM);
        properties.setProperty("qraft.file.temp.dir", DEFAULT_TEMP_DIR);
        properties.setProperty("qraft.monitoring.metrics.enabled", "true");
        properties.setProperty("qraft.monitoring.health.enabled", "true");
    }
    
    private void loadConfigurationFromFile() {
        // Try to load from various locations
        String[] configFiles = {
                "qraft.properties",
                "config/qraft.properties",
                System.getProperty("user.home") + "/.qraft/qraft.properties",
                "/etc/qraft/qraft.properties"
        };
        
        for (String configFile : configFiles) {
            Path configPath = Paths.get(configFile);
            if (Files.exists(configPath) && Files.isReadable(configPath)) {
                logger.debug("Found configuration file: {}", configPath);
                try (InputStream input = Files.newInputStream(configPath)) {
                    properties.load(input);
                    logger.info("Loaded configuration from: {}", configPath);
                    return;
                } catch (IOException e) {
                    logger.warn("Failed to load configuration from {}: {}", configPath, e.getMessage());
                }
            } else {
                logger.debug("Configuration file not found or not readable: {}", configPath);
            }
        }
        
        // Try to load from classpath
        logger.debug("Attempting to load configuration from classpath");
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("qraft.properties")) {
            if (input != null) {
                properties.load(input);
                logger.info("Loaded configuration from classpath");
            } else {
                logger.debug("No configuration file found on classpath");
            }
        } catch (IOException e) {
            logger.warn("Failed to load configuration from classpath: {}", e.getMessage());
        }
    }
    
    private void loadConfigurationFromSystemProperties() {
        logger.debug("Loading configuration overrides from system properties");
        // Override with system properties that start with "qraft."
        long count = System.getProperties().entrySet().stream()
                .filter(entry -> entry.getKey().toString().startsWith("qraft."))
                .peek(entry -> {
                    properties.setProperty(entry.getKey().toString(), entry.getValue().toString());
                    logger.debug("Override from system property: {}={}", entry.getKey(), entry.getValue());
                })
                .count();
        logger.debug("Applied {} system property overrides", count);
    }
    
    @Override
    public String toString() {
        return "QraftConfiguration{" +
                "maxConcurrentTransfers=" + getMaxConcurrentTransfers() +
                ", maxRetryAttempts=" + getMaxRetryAttempts() +
                ", checksumAlgorithm='" + getChecksumAlgorithm() + '\'' +
                ", metricsEnabled=" + isMetricsEnabled() +
                '}';
    }
}

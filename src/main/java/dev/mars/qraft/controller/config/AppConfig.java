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

package dev.mars.qraft.controller.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.mars.qraft.config.ConfigurationPlaceholders;
import dev.mars.qraft.controller.ui.AdminUiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Immutable server configuration loaded exclusively from a versioned JSON document.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
public final class AppConfig {
    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final String DEFAULT_RESOURCE = "qraft-controller.json";
    /** An unreachable node is reaped with its services after 72 hours, the reconnect window Consul uses. */
    private static final long DEFAULT_NODE_REAP_AFTER_MS = 72L * 60 * 60 * 1000;
    private static final int MIN_OPERATOR_TOKEN_LENGTH = 16;
    private static volatile AppConfig instance = loadDefault();

    private final Map<String, Object> values;
    private volatile String resolvedNodeId;

    private AppConfig(Map<String, Object> values) {
        this.values = Map.copyOf(values);
    }

    /** Retained for package-level resource-contract tests. */
    AppConfig(ClassLoader resourceLoader) {
        this(parseResource(resourceLoader));
    }

    public static AppConfig get() { return instance; }

    public static AppConfig install(Path path) {
        return install(fromFile(path));
    }

    /**
     * Validates {@code config} and makes it the process-wide configuration. A caller that installs a
     * configuration temporarily, such as a test that launches a server, reinstalls the one it replaced.
     */
    public static AppConfig install(AppConfig config) {
        config.validate();
        instance = config;
        config.logConfiguration();
        return config;
    }

    public static AppConfig fromFile(Path path) {
        if (path == null) throw new IllegalArgumentException("configuration path is required");
        try {
            return fromJson(Files.readString(path));
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not read server configuration " + path, error);
        }
    }

    public static AppConfig fromJson(String document) {
        final JsonNode root;
        try {
            root = JSON.readTree(document);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Server configuration is not valid JSON", error);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Server configuration must be a JSON object");
        }
        ConfigurationPlaceholders.reject(root);
        rejectUnknown(root, "root", "version", "server", "logging");
        int version = requiredInt(root, "version");
        if (version != 1) throw new IllegalArgumentException("Unsupported configuration version: " + version);

        JsonNode server = requiredObject(root, "server");
        JsonNode http = optionalObject(server, "http");
        JsonNode raft = optionalObject(server, "raft");
        JsonNode storage = optionalObject(raft, "storage");
        JsonNode snapshot = optionalObject(raft, "snapshot");
        JsonNode io = optionalObject(raft, "io");
        JsonNode telemetry = optionalObject(server, "telemetry");
        JsonNode shutdown = optionalObject(server, "shutdown");
        JsonNode health = optionalObject(server, "health");
        JsonNode ui = optionalObject(server, "ui");
        JsonNode operator = optionalObject(server, "operator");
        JsonNode logging = optionalObject(root, "logging");
        rejectUnknown(server, "server", "id", "applicationVersion", "http", "apiGrpcPort",
                "raft", "telemetry", "shutdown", "health", "ui", "operator");
        rejectUnknown(http, "server.http", "host", "port");
        rejectUnknown(raft, "server.raft", "port", "nodes", "electionTimeoutMs",
                "heartbeatIntervalMs", "storage", "snapshot", "logHardLimit", "io");
        rejectUnknown(storage, "server.raft.storage", "type", "path", "fsync");
        rejectUnknown(snapshot, "server.raft.snapshot", "enabled", "threshold", "checkIntervalMs");
        rejectUnknown(io, "server.raft.io", "poolSize", "queueSize");
        rejectUnknown(telemetry, "server.telemetry", "enabled", "otlpEndpoint",
                "prometheusPort", "serviceName");
        rejectUnknown(shutdown, "server.shutdown", "drainTimeoutMs", "timeoutMs");
        rejectUnknown(health, "server.health", "expiryIntervalMs", "nodeTtlMs", "nodeReapAfterMs");
        rejectUnknown(ui, "server.ui", "enabled", "path", "devAssetsDirectory");
        rejectUnknown(operator, "server.operator", "token");
        rejectUnknown(logging, "logging", "directory");

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("qraft.version", optionalText(server, "applicationVersion", "2.0-ext"));
        values.put("qraft.node.id", optionalText(server, "id", ""));
        values.put("qraft.http.host", optionalText(http, "host", "0.0.0.0"));
        values.put("qraft.http.port", optionalInt(http, "port", 8080));
        values.put("qraft.api.grpc.port", optionalInt(server, "apiGrpcPort", 10080));
        values.put("qraft.raft.port", optionalInt(raft, "port", 9080));
        values.put("qraft.cluster.nodes", parseNodes(raft.get("nodes")));
        values.put("qraft.raft.election-timeout-ms", optionalLong(raft, "electionTimeoutMs", 5000));
        values.put("qraft.raft.heartbeat-interval-ms", optionalLong(raft, "heartbeatIntervalMs", 1000));
        values.put("qraft.raft.storage.type", optionalText(storage, "type", "raftlog"));
        values.put("qraft.raft.storage.path", optionalText(storage, "path", ""));
        values.put("qraft.raft.storage.fsync", optionalBoolean(storage, "fsync", true));
        values.put("qraft.raft.snapshot.enabled", optionalBoolean(snapshot, "enabled", true));
        values.put("qraft.raft.snapshot.threshold", optionalLong(snapshot, "threshold", 10_000));
        values.put("qraft.raft.snapshot.check-interval-ms",
                optionalLong(snapshot, "checkIntervalMs", 60_000));
        values.put("qraft.raft.log.hard-limit", optionalLong(raft, "logHardLimit", 100_000));
        values.put("qraft.operator.token", optionalText(operator, "token", ""));
        values.put("qraft.raft.io.pool-size", optionalInt(io, "poolSize", 10));
        values.put("qraft.raft.io.queue-size", optionalInt(io, "queueSize", 1000));
        values.put("qraft.telemetry.enabled", optionalBoolean(telemetry, "enabled", true));
        values.put("qraft.telemetry.otlp.endpoint",
                optionalText(telemetry, "otlpEndpoint", "http://localhost:4317"));
        values.put("qraft.telemetry.prometheus.port", optionalInt(telemetry, "prometheusPort", 9464));
        values.put("qraft.telemetry.service.name",
                optionalText(telemetry, "serviceName", "qraft-controller"));
        values.put("qraft.shutdown.drain.timeout.ms", optionalLong(shutdown, "drainTimeoutMs", 5000));
        values.put("qraft.shutdown.timeout.ms", optionalLong(shutdown, "timeoutMs", 30_000));
        long expiryIntervalMs = optionalLong(health, "expiryIntervalMs", 1_000);
        if (expiryIntervalMs < 1) throw new IllegalArgumentException("server.health.expiryIntervalMs must be positive");
        values.put("qraft.health.expiry-interval-ms", expiryIntervalMs);
        long nodeTtlMs = optionalLong(health, "nodeTtlMs", 90_000);
        if (nodeTtlMs < 1) throw new IllegalArgumentException("server.health.nodeTtlMs must be positive");
        long nodeReapAfterMs = optionalLong(health, "nodeReapAfterMs", DEFAULT_NODE_REAP_AFTER_MS);
        if (nodeReapAfterMs < 0) throw new IllegalArgumentException("server.health.nodeReapAfterMs must not be negative");
        values.put("qraft.health.node-ttl-ms", nodeTtlMs);
        values.put("qraft.health.node-reap-after-ms", nodeReapAfterMs);
        AdminUiConfig adminUi = parseAdminUi(ui);
        values.put("qraft.ui.enabled", adminUi.enabled());
        values.put("qraft.ui.path", adminUi.path());
        values.put("qraft.ui.dev-assets-directory", adminUi.devAssetsDirectory().map(Path::toString).orElse(""));
        values.put("qraft.logging.directory", optionalText(logging, "directory", "./logs"));
        return new AppConfig(values);
    }

    public synchronized String getNodeId() {
        if (resolvedNodeId != null) return resolvedNodeId;
        String nodeId = getString("qraft.node.id", "");
        if (nodeId.isBlank()) {
            if (isMultiNodeCluster()) {
                throw new IllegalStateException("server.id is required for a multi-node cluster");
            }
            nodeId = deriveNodeIdFromHostname();
            logger.warn("Using hostname '{}' as node ID. Set server.id explicitly for production.", nodeId);
        }
        resolvedNodeId = nodeId;
        return nodeId;
    }

    private boolean isMultiNodeCluster() {
        String nodes = getString("qraft.cluster.nodes", "");
        return !nodes.isEmpty() && nodes.contains(",");
    }

    private String deriveNodeIdFromHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException error) {
            return "node-" + ProcessHandle.current().pid();
        }
    }

    public int getHttpPort() { return getInt("qraft.http.port", 8080); }
    public String getHttpHost() { return getString("qraft.http.host", "0.0.0.0"); }
    public int getRaftPort() { return getInt("qraft.raft.port", 9080); }
    public int getApiGrpcPort() { return getInt("qraft.api.grpc.port", 10080); }
    public long getElectionTimeoutMs() { return getLong("qraft.raft.election-timeout-ms", 5000); }
    public long getHeartbeatIntervalMs() { return getLong("qraft.raft.heartbeat-interval-ms", 1000); }

    public String getClusterNodes() {
        String nodes = getString("qraft.cluster.nodes", "");
        return nodes.isEmpty() ? getNodeId() + "=localhost:" + getRaftPort() : nodes;
    }

    public String getRaftStorageType() { return getString("qraft.raft.storage.type", "raftlog"); }
    public String getRaftStoragePath() {
        String path = getString("qraft.raft.storage.path", "");
        return path.isBlank() ? "./data/raft/" + getNodeId() : path;
    }
    public boolean getRaftStorageFsync() { return getBoolean("qraft.raft.storage.fsync", true); }
    public boolean isSnapshotEnabled() { return getBoolean("qraft.raft.snapshot.enabled", true); }
    public long getSnapshotThreshold() { return getLong("qraft.raft.snapshot.threshold", 10_000); }
    public long getSnapshotCheckIntervalMs() {
        return getLong("qraft.raft.snapshot.check-interval-ms", 60_000);
    }
    public long getLogHardLimit() { return getLong("qraft.raft.log.hard-limit", 100_000); }
    /** How often the leader evaluates health-check deadlines, unless a check falls due sooner. */
    public long getHealthExpiryIntervalMs() { return getLong("qraft.health.expiry-interval-ms", 1_000); }
    /** How long a node may go without a heartbeat before the leader marks it unreachable. */
    public long getNodeTtlMs() { return getLong("qraft.health.node-ttl-ms", 90_000); }
    /** How long an unreachable node is kept before it and its services are reaped; zero never reaps. */
    public long getNodeReapAfterMs() { return getLong("qraft.health.node-reap-after-ms", DEFAULT_NODE_REAP_AFTER_MS); }
    public boolean isTelemetryEnabled() { return getBoolean("qraft.telemetry.enabled", true); }
    public String getOtlpEndpoint() {
        return getString("qraft.telemetry.otlp.endpoint", "http://localhost:4317");
    }
    public String getRedactedOtlpEndpoint() {
        try {
            URI endpoint = URI.create(getOtlpEndpoint());
            return new URI(endpoint.getScheme(), null, endpoint.getHost(), endpoint.getPort(),
                    endpoint.getPath(), null, null).toString();
        } catch (Exception ignored) {
            return "<invalid endpoint>";
        }
    }
    public int getPrometheusPort() { return getInt("qraft.telemetry.prometheus.port", 9464); }
    public String getServiceName() {
        return getString("qraft.telemetry.service.name", "qraft-controller");
    }
    /**
     * The token a Raft operator's removal of a server must carry (see {@code MembershipService}); empty when
     * none is configured, and then every removal is refused. Never logged.
     */
    public Optional<String> getOperatorToken() {
        String token = getString("qraft.operator.token", "");
        return token.isBlank() ? Optional.empty() : Optional.of(token);
    }

    public int getRaftIoPoolSize() { return getInt("qraft.raft.io.pool-size", 10); }
    public int getRaftIoQueueSize() { return getInt("qraft.raft.io.queue-size", 1000); }
    public String getVersion() { return getString("qraft.version", "2.0-ext"); }
    public String getLoggingDirectory() { return getString("qraft.logging.directory", "./logs"); }
    /** The validated {@code server.ui} settings; parsing already rejected any invalid combination. */
    public AdminUiConfig getAdminUi() {
        String directory = getString("qraft.ui.dev-assets-directory", "");
        return new AdminUiConfig(getBoolean("qraft.ui.enabled", true), getString("qraft.ui.path", AdminUiConfig.DEFAULT_PATH),
                directory.isEmpty() ? Optional.empty() : Optional.of(Path.of(directory)));
    }

    /** Validates {@code server.ui} while the file is parsed, before any listener or file is opened. */
    private static AdminUiConfig parseAdminUi(JsonNode ui) {
        JsonNode directory = ui.get("devAssetsDirectory");
        if (directory != null && (!directory.isTextual() || directory.textValue().isBlank())) {
            throw new IllegalArgumentException("server.ui.devAssetsDirectory must be a non-blank path");
        }
        return new AdminUiConfig(optionalBoolean(ui, "enabled", true),
                optionalText(ui, "path", AdminUiConfig.DEFAULT_PATH),
                directory == null ? Optional.empty() : Optional.of(Path.of(directory.textValue().trim())));
    }

    public String getString(String key, String defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : value.toString();
    }
    public int getInt(String key, int defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : ((Number) value).intValue();
    }
    public long getLong(String key, long defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : ((Number) value).longValue();
    }
    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : (Boolean) value;
    }

    public void validate() {
        if (getOperatorToken().filter(token -> token.length() < MIN_OPERATOR_TOKEN_LENGTH).isPresent()) {
            throw new IllegalStateException("server.operator.token must be at least " + MIN_OPERATOR_TOKEN_LENGTH
                    + " characters");
        }
        validateListeningPort("server.http.port", getHttpPort());
        validateListeningPort("server.raft.port", getRaftPort());
        validateListeningPort("server.apiGrpcPort", getApiGrpcPort());
        validatePort("server.telemetry.prometheusPort", getPrometheusPort());
        if (getRaftPort() == 0 && hasPeers()) {
            throw new IllegalStateException("server.raft.port may be 0 only on a cluster's sole member: "
                    + "peers dial the configured Raft address");
        }
        if (clash(getHttpPort(), getRaftPort()) || clash(getHttpPort(), getApiGrpcPort())
                || clash(getRaftPort(), getApiGrpcPort())) {
            throw new IllegalStateException("HTTP, Raft, and API gRPC ports must be different");
        }
        if (getRaftIoPoolSize() < 1 || getRaftIoPoolSize() > 100) {
            throw new IllegalStateException("Raft I/O pool size must be between 1 and 100");
        }
        if (getRaftIoQueueSize() < 10 || getRaftIoQueueSize() > 100_000) {
            throw new IllegalStateException("Raft I/O queue size must be between 10 and 100000");
        }
        if (getElectionTimeoutMs() < 1 || getHeartbeatIntervalMs() < 1) {
            throw new IllegalStateException("Raft timing intervals must be positive");
        }
        if (getSnapshotThreshold() < 1 || getSnapshotCheckIntervalMs() < 1000) {
            throw new IllegalStateException("Snapshot threshold must be positive and check interval at least 1000ms");
        }
        if (!getRaftStorageType().equalsIgnoreCase("raftlog")) {
            throw new IllegalStateException("Raft storage type must be 'raftlog'");
        }
        if (!getRaftStorageFsync()) throw new IllegalStateException("Raft WAL fsync must be enabled for durability");
        getNodeId();
    }

    /** A port this server listens on: 0 asks the system for any free port. */
    private static void validateListeningPort(String name, int port) {
        if (port < 0 || port > 65_535) {
            throw new IllegalStateException(name + " must be between 0 and 65535, got: " + port);
        }
    }

    /** Two zeros never clash: each asks the system for its own free port. */
    private static boolean clash(int first, int second) {
        return first != 0 && first == second;
    }

    private boolean hasPeers() {
        String self = getNodeId();
        for (String entry : getClusterNodes().split(",")) {
            String member = entry.split("=", 2)[0].trim();
            if (!member.isEmpty() && !member.equals(self)) return true;
        }
        return false;
    }

    private static void validatePort(String name, int port) {
        if (port < 1 || port > 65_535) {
            throw new IllegalStateException(name + " must be between 1 and 65535, got: " + port);
        }
    }

    private static String parseNodes(JsonNode nodes) {
        if (nodes == null || nodes.isNull()) return "";
        if (!nodes.isObject()) throw new IllegalArgumentException("server.raft.nodes must be an object");
        StringJoiner result = new StringJoiner(",");
        Iterator<Map.Entry<String, JsonNode>> fields = nodes.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getKey().isBlank() || !entry.getValue().isTextual()
                    || entry.getValue().textValue().isBlank()) {
                throw new IllegalArgumentException("server.raft.nodes must map non-blank IDs to addresses");
            }
            result.add(entry.getKey() + "=" + entry.getValue().textValue().trim());
        }
        return result.toString();
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) throw new IllegalArgumentException(field + " must be an object");
        return value;
    }
    private static JsonNode optionalObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return JSON.createObjectNode();
        if (!value.isObject()) throw new IllegalArgumentException(field + " must be an object");
        return value;
    }
    private static String optionalText(JsonNode parent, String field, String fallback) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual() || (value.textValue().isBlank() && !fallback.isEmpty())) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.textValue().trim();
    }
    private static int requiredInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.intValue();
    }
    private static int optionalInt(JsonNode parent, String field, int fallback) {
        return parent.has(field) ? requiredInt(parent, field) : fallback;
    }
    private static long optionalLong(JsonNode parent, String field, long fallback) {
        JsonNode value = parent.get(field);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.longValue();
    }
    private static boolean optionalBoolean(JsonNode parent, String field, boolean fallback) {
        JsonNode value = parent.get(field);
        if (value == null) return fallback;
        if (!value.isBoolean()) throw new IllegalArgumentException(field + " must be a boolean");
        return value.booleanValue();
    }

    private static void rejectUnknown(JsonNode object, String location, String... allowedNames) {
        java.util.Set<String> allowed = java.util.Set.of(allowedNames);
        object.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("Unknown " + location + " setting: " + name);
            }
        });
    }

    private static AppConfig loadDefault() {
        return new AppConfig(AppConfig.class.getClassLoader());
    }
    private static Map<String, Object> parseResource(ClassLoader loader) {
        if (loader == null) throw new IllegalArgumentException("resourceLoader is required");
        try (InputStream input = loader.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (input == null) throw new IllegalStateException("Required configuration resource "
                    + DEFAULT_RESOURCE + " was not found");
            return fromJson(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).values;
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("Could not read required configuration resource "
                    + DEFAULT_RESOURCE, error);
        }
    }

    private void logConfiguration() {
        logger.info("Controller configuration: nodeId={}, http={}:{}, raftPort={}, apiGrpcPort={}, "
                        + "clusterNodes={}, storagePath={}, telemetryEnabled={}",
                getNodeId(), getHttpHost(), getHttpPort(), getRaftPort(), getApiGrpcPort(),
                getClusterNodes(), getRaftStoragePath(), isTelemetryEnabled());
    }
}

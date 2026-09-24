package dev.mars.qraft.controller.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
import java.util.StringJoiner;

/** Immutable server configuration loaded exclusively from a versioned JSON document. */
public final class AppConfig {
    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final String DEFAULT_RESOURCE = "qraft-controller.json";
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
        AppConfig loaded = fromFile(path);
        loaded.validate();
        instance = loaded;
        loaded.logConfiguration();
        return loaded;
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
        JsonNode logging = optionalObject(root, "logging");
        rejectUnknown(server, "server", "id", "applicationVersion", "http", "apiGrpcPort",
                "raft", "telemetry", "shutdown");
        rejectUnknown(http, "server.http", "host", "port");
        rejectUnknown(raft, "server.raft", "port", "nodes", "electionTimeoutMs",
                "heartbeatIntervalMs", "storage", "snapshot", "logHardLimit", "io");
        rejectUnknown(storage, "server.raft.storage", "type", "path", "fsync");
        rejectUnknown(snapshot, "server.raft.snapshot", "enabled", "threshold", "checkIntervalMs");
        rejectUnknown(io, "server.raft.io", "poolSize", "queueSize");
        rejectUnknown(telemetry, "server.telemetry", "enabled", "otlpEndpoint",
                "prometheusPort", "serviceName");
        rejectUnknown(shutdown, "server.shutdown", "drainTimeoutMs", "timeoutMs");
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
    public int getRaftIoPoolSize() { return getInt("qraft.raft.io.pool-size", 10); }
    public int getRaftIoQueueSize() { return getInt("qraft.raft.io.queue-size", 1000); }
    public String getVersion() { return getString("qraft.version", "2.0-ext"); }
    public String getLoggingDirectory() { return getString("qraft.logging.directory", "./logs"); }

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
        validatePort("server.http.port", getHttpPort());
        validatePort("server.raft.port", getRaftPort());
        validatePort("server.apiGrpcPort", getApiGrpcPort());
        validatePort("server.telemetry.prometheusPort", getPrometheusPort());
        if (getHttpPort() == getRaftPort() || getHttpPort() == getApiGrpcPort()
                || getRaftPort() == getApiGrpcPort()) {
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

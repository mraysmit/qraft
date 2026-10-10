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

package dev.mars.qraft.server.config;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mars.qraft.common.config.JsonSettings;
import dev.mars.qraft.server.ui.AdminUiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

import static dev.mars.qraft.common.config.JsonSettings.optionalBoolean;
import static dev.mars.qraft.common.config.JsonSettings.optionalInt;
import static dev.mars.qraft.common.config.JsonSettings.optionalLong;
import static dev.mars.qraft.common.config.JsonSettings.optionalObject;
import static dev.mars.qraft.common.config.JsonSettings.rejectUnknown;
import static dev.mars.qraft.common.config.JsonSettings.requiredObject;

/**
 * Immutable server configuration, read from a versioned JSON document and from nothing else. There is no
 * default document and no process-wide instance: whoever starts a server reads its document and passes the
 * configuration on.
 *
 * <p>The settings are held as records that follow the document's own structure.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 2.0
 */
public final class AppConfig {
    /** Loading and parsing configuration must not initialize Logback before its directory is selected. */
    private static final class Logging {
        private static final Logger LOGGER = LoggerFactory.getLogger(AppConfig.class);
    }
    /** An unreachable node is reaped with its services after 72 hours, the reconnect window Consul uses. */
    private static final long DEFAULT_NODE_REAP_AFTER_MS = 72L * 60 * 60 * 1000;
    private static final int MIN_OPERATOR_TOKEN_LENGTH = 16;

    private final Settings settings;
    private volatile String resolvedNodeId;

    private AppConfig(Settings settings) {
        this.settings = settings;
    }

    /** The {@code server} object of the document, with {@code logging.directory} beside it. */
    private record Settings(String id, Http http, int apiGrpcPort, Raft raft, String operatorToken,
                            Telemetry telemetry, Shutdown shutdown, Health health, AdminUiConfig ui,
                            String loggingDirectory) { }

    private record Http(String host, int port) { }

    /** {@code nodes} maps each listed member's name to its Raft address, in the document's order. */
    private record Raft(int port, Map<String, String> nodes, long electionTimeoutMs, long heartbeatIntervalMs,
                        Storage storage, Snapshot snapshot, long logHardLimit, Io io) { }

    private record Storage(String type, String path, boolean fsync) { }

    private record Snapshot(boolean enabled, long threshold, long checkIntervalMs) { }

    private record Io(int poolSize, int queueSize) { }

    private record Telemetry(boolean enabled, String otlpEndpoint, int prometheusPort, String serviceName) { }

    private record Shutdown(long drainTimeoutMs, long timeoutMs) { }

    private record Health(long expiryIntervalMs, long nodeTtlMs, long nodeReapAfterMs) { }

    public static AppConfig fromFile(Path path) {
        if (path == null) throw new IllegalArgumentException("configuration path is required");
        try {
            return fromJson(Files.readString(path));
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not read server configuration " + path, error);
        }
    }

    public static AppConfig fromJson(String document) {
        JsonNode root = JsonSettings.readDocument(document, "Server");
        rejectUnknown(root, "root", "version", "server", "logging");
        JsonSettings.requireFormatVersion(root);

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
        rejectUnknown(server, "server", "id", "http", "apiGrpcPort",
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

        // The settings are read in one fixed order, so that a document with several faults always reports
        // the same one first.
        String id = optionalText(server, "id", "");
        Http httpSettings = new Http(optionalText(http, "host", "0.0.0.0"), optionalInt(http, "port", 8080));
        int apiGrpcPort = optionalInt(server, "apiGrpcPort", 10080);
        int raftPort = optionalInt(raft, "port", 9080);
        Map<String, String> nodes = parseNodes(raft.get("nodes"));
        long electionTimeoutMs = optionalLong(raft, "electionTimeoutMs", 5000);
        long heartbeatIntervalMs = optionalLong(raft, "heartbeatIntervalMs", 1000);
        Storage storageSettings = new Storage(optionalText(storage, "type", "raftlog"),
                optionalText(storage, "path", ""), optionalBoolean(storage, "fsync", true));
        Snapshot snapshotSettings = new Snapshot(optionalBoolean(snapshot, "enabled", true),
                optionalLong(snapshot, "threshold", 10_000), optionalLong(snapshot, "checkIntervalMs", 60_000));
        long logHardLimit = optionalLong(raft, "logHardLimit", 100_000);
        String operatorToken = optionalText(operator, "token", "");
        Io ioSettings = new Io(optionalInt(io, "poolSize", 10), optionalInt(io, "queueSize", 1000));
        Telemetry telemetrySettings = new Telemetry(optionalBoolean(telemetry, "enabled", true),
                optionalText(telemetry, "otlpEndpoint", "http://localhost:4317"),
                optionalInt(telemetry, "prometheusPort", 9464),
                optionalText(telemetry, "serviceName", "qraft-server"));
        Shutdown shutdownSettings = new Shutdown(optionalLong(shutdown, "drainTimeoutMs", 5000),
                optionalLong(shutdown, "timeoutMs", 30_000));
        long expiryIntervalMs = optionalLong(health, "expiryIntervalMs", 1_000);
        if (expiryIntervalMs < 1) throw new IllegalArgumentException("server.health.expiryIntervalMs must be positive");
        long nodeTtlMs = optionalLong(health, "nodeTtlMs", 90_000);
        if (nodeTtlMs < 1) throw new IllegalArgumentException("server.health.nodeTtlMs must be positive");
        long nodeReapAfterMs = optionalLong(health, "nodeReapAfterMs", DEFAULT_NODE_REAP_AFTER_MS);
        if (nodeReapAfterMs < 0) throw new IllegalArgumentException("server.health.nodeReapAfterMs must not be negative");
        AdminUiConfig adminUi = parseAdminUi(ui);
        String loggingDirectory = optionalText(logging, "directory", "./logs");

        return new AppConfig(new Settings(id, httpSettings, apiGrpcPort,
                new Raft(raftPort, nodes, electionTimeoutMs, heartbeatIntervalMs, storageSettings, snapshotSettings,
                        logHardLimit, ioSettings),
                operatorToken, telemetrySettings, shutdownSettings,
                new Health(expiryIntervalMs, nodeTtlMs, nodeReapAfterMs), adminUi, loggingDirectory));
    }

    public synchronized String getNodeId() {
        if (resolvedNodeId != null) return resolvedNodeId;
        String nodeId = settings.id();
        if (nodeId.isBlank()) {
            if (isMultiNodeCluster()) {
                throw new IllegalStateException("server.id is required for a multi-node cluster");
            }
            nodeId = deriveNodeIdFromHostname();
            Logging.LOGGER.warn("Using hostname '{}' as node ID. Set server.id explicitly for production.", nodeId);
        }
        resolvedNodeId = nodeId;
        return nodeId;
    }

    private boolean isMultiNodeCluster() {
        return settings.raft().nodes().size() > 1;
    }

    private String deriveNodeIdFromHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException error) {
            return "node-" + ProcessHandle.current().pid();
        }
    }

    public int getHttpPort() { return settings.http().port(); }
    public String getHttpHost() { return settings.http().host(); }
    public int getRaftPort() { return settings.raft().port(); }
    public int getApiGrpcPort() { return settings.apiGrpcPort(); }
    public long getElectionTimeoutMs() { return settings.raft().electionTimeoutMs(); }
    public long getHeartbeatIntervalMs() { return settings.raft().heartbeatIntervalMs(); }

    /**
     * The cluster's members: each name with its Raft address, in the document's order. A document that lists
     * none describes a cluster of this server alone, at {@code localhost} and its own Raft port.
     */
    public Map<String, String> getClusterMembers() {
        Map<String, String> nodes = settings.raft().nodes();
        return nodes.isEmpty() ? Map.of(getNodeId(), "localhost:" + getRaftPort()) : nodes;
    }

    /** The cluster's members in the form {@code name=address,name=address}, for display. */
    public String getClusterNodes() {
        StringJoiner joined = new StringJoiner(",");
        getClusterMembers().forEach((name, address) -> joined.add(name + "=" + address));
        return joined.toString();
    }

    public String getRaftStorageType() { return settings.raft().storage().type(); }
    public String getRaftStoragePath() {
        String path = settings.raft().storage().path();
        return path.isBlank() ? "./data/raft/" + getNodeId() : path;
    }
    public boolean getRaftStorageFsync() { return settings.raft().storage().fsync(); }
    public boolean isSnapshotEnabled() { return settings.raft().snapshot().enabled(); }
    public long getSnapshotThreshold() { return settings.raft().snapshot().threshold(); }
    public long getSnapshotCheckIntervalMs() { return settings.raft().snapshot().checkIntervalMs(); }
    public long getLogHardLimit() { return settings.raft().logHardLimit(); }
    /** How often the leader evaluates health-check deadlines, unless a check falls due sooner. */
    public long getHealthExpiryIntervalMs() { return settings.health().expiryIntervalMs(); }
    /** How long a node may go without a heartbeat before the leader marks it unreachable. */
    public long getNodeTtlMs() { return settings.health().nodeTtlMs(); }
    /** How long an unreachable node is kept before it and its services are reaped; zero never reaps. */
    public long getNodeReapAfterMs() { return settings.health().nodeReapAfterMs(); }
    public boolean isTelemetryEnabled() { return settings.telemetry().enabled(); }
    public String getOtlpEndpoint() { return settings.telemetry().otlpEndpoint(); }
    public String getRedactedOtlpEndpoint() {
        try {
            URI endpoint = URI.create(getOtlpEndpoint());
            return new URI(endpoint.getScheme(), null, endpoint.getHost(), endpoint.getPort(),
                    endpoint.getPath(), null, null).toString();
        } catch (Exception ignored) {
            return "<invalid endpoint>";
        }
    }
    public int getPrometheusPort() { return settings.telemetry().prometheusPort(); }
    public String getServiceName() { return settings.telemetry().serviceName(); }
    /**
     * The token a Raft operator's removal of a server must carry (see {@code MembershipService}); empty when
     * none is configured, and then every removal is refused. Never logged.
     */
    public Optional<String> getOperatorToken() {
        String token = settings.operatorToken();
        return token.isBlank() ? Optional.empty() : Optional.of(token);
    }

    public int getRaftIoPoolSize() { return settings.raft().io().poolSize(); }
    public int getRaftIoQueueSize() { return settings.raft().io().queueSize(); }
    /** How long a stopping server drains the requests it already has. */
    public long getShutdownDrainTimeoutMs() { return settings.shutdown().drainTimeoutMs(); }
    /** How long a stopping server may take in all. */
    public long getShutdownTimeoutMs() { return settings.shutdown().timeoutMs(); }
    public String getLoggingDirectory() { return settings.loggingDirectory(); }
    /** The validated {@code server.ui} settings; parsing already rejected any invalid combination. */
    public AdminUiConfig getAdminUi() { return settings.ui(); }

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

    /** Logs the settings an operator looks for first. Call it once the log directory has been selected. */
    public void logConfiguration() {
        Logging.LOGGER.info("Server configuration: nodeId={}, http={}:{}, raftPort={}, apiGrpcPort={}, "
                        + "clusterNodes={}, storagePath={}, telemetryEnabled={}",
                getNodeId(), getHttpHost(), getHttpPort(), getRaftPort(), getApiGrpcPort(),
                getClusterNodes(), getRaftStoragePath(), isTelemetryEnabled());
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
        return getClusterMembers().keySet().stream().anyMatch(member -> !member.equals(self));
    }

    private static void validatePort(String name, int port) {
        if (port < 1 || port > 65_535) {
            throw new IllegalStateException(name + " must be between 1 and 65535, got: " + port);
        }
    }

    /** Reads {@code server.raft.nodes}: each member's name, trimmed, with its address, in the document's order. */
    private static Map<String, String> parseNodes(JsonNode nodes) {
        if (nodes == null || nodes.isNull()) return Map.of();
        if (!nodes.isObject()) throw new IllegalArgumentException("server.raft.nodes must be an object");
        Map<String, String> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = nodes.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getKey().isBlank() || !entry.getValue().isTextual()
                    || entry.getValue().textValue().isBlank()) {
                throw new IllegalArgumentException("server.raft.nodes must map non-blank IDs to addresses");
            }
            result.put(entry.getKey().trim(), entry.getValue().textValue().trim());
        }
        return Collections.unmodifiableMap(result);
    }

    /** A text setting of the server. A blank value is refused unless the setting may be left empty. */
    private static String optionalText(JsonNode parent, String field, String fallback) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual() || (value.textValue().isBlank() && !fallback.isEmpty())) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.textValue().trim();
    }
}

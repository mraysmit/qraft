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

package dev.mars.qraft.client.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.mars.qraft.client.health.HealthCheckDefinition;
import dev.mars.qraft.client.health.HttpCheck;
import dev.mars.qraft.client.health.TcpCheck;
import dev.mars.qraft.client.health.TtlCheck;
import dev.mars.qraft.common.ServiceDefinition;
import dev.mars.qraft.common.config.JsonSettings;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static dev.mars.qraft.common.config.JsonSettings.optionalBoolean;
import static dev.mars.qraft.common.config.JsonSettings.optionalInt;
import static dev.mars.qraft.common.config.JsonSettings.optionalLong;
import static dev.mars.qraft.common.config.JsonSettings.optionalObject;
import static dev.mars.qraft.common.config.JsonSettings.rejectUnknown;
import static dev.mars.qraft.common.config.JsonSettings.requiredInt;
import static dev.mars.qraft.common.config.JsonSettings.requiredObject;

/**
 * Immutable, file-backed configuration for a Qraft discovery client.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
public final class ClientConfiguration {

    private final String clientId;
    private final String address;
    private final int clientPort;
    private final String region;
    private final String datacenter;
    private final List<URI> serverUrls;
    private final long heartbeatInterval;
    private final long shutdownTimeoutMs;
    private final int requestTimeoutMs;
    private final long registrationRetryMinMs;
    private final long registrationRetryMaxMs;
    private final long contactFreshnessMs;
    private final String tenant;
    private final String namespace;
    private final List<ServiceDefinition> services;
    private final List<HealthCheckDefinition> healthChecks;
    private final String loggingDirectory;

    private ClientConfiguration(Builder builder) {
        clientId = builder.clientId.trim();
        address = builder.address.trim();
        clientPort = builder.clientPort;
        region = builder.region.trim();
        datacenter = builder.datacenter.trim();
        serverUrls = List.copyOf(builder.serverUrls);
        heartbeatInterval = builder.heartbeatInterval;
        shutdownTimeoutMs = builder.shutdownTimeoutMs;
        requestTimeoutMs = builder.requestTimeoutMs;
        registrationRetryMinMs = builder.registrationRetryMinMs;
        registrationRetryMaxMs = builder.registrationRetryMaxMs;
        contactFreshnessMs = builder.contactFreshnessMs;
        tenant = builder.tenant.trim();
        namespace = builder.namespace.trim();
        healthChecks = List.copyOf(builder.healthChecks);
        // The configured checks are the single source of each service's declared check identifiers.
        services = builder.services.stream()
                .map(service -> service.withCheckIds(healthChecks.stream()
                        .filter(check -> check.serviceId().equals(service.id()))
                        .map(HealthCheckDefinition::checkId).toList()))
                .toList();
        loggingDirectory = builder.loggingDirectory.trim();
    }

    public static ClientConfiguration fromFile(Path path) {
        if (path == null) throw new IllegalArgumentException("configuration path is required");
        try {
            return fromJson(Files.readString(path));
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not read client configuration " + path, error);
        }
    }

    /** Parses an injected document without consulting process-global configuration. */
    public static ClientConfiguration fromJson(String document) {
        JsonNode root = JsonSettings.readDocument(document, "Client");
        rejectUnknown(root, "root", "version", "client", "servers", "catalog", "logging");
        JsonSettings.requireFormatVersion(root);

        JsonNode client = requiredObject(root, "client");
        JsonNode servers = requiredObject(root, "servers");
        JsonNode catalog = optionalObject(root, "catalog");
        JsonNode logging = optionalObject(root, "logging");
        rejectUnknown(client, "client", "id", "address", "httpPort",
                "heartbeatIntervalMs", "shutdownTimeoutMs", "datacenter", "region");
        rejectUnknown(servers, "servers", "urls", "requestTimeoutMs");
        rejectUnknown(catalog, "catalog", "tenant", "namespace", "registrationRetryMinMs",
                "registrationRetryMaxMs", "contactFreshnessMs", "services");
        rejectUnknown(logging, "logging", "directory");
        List<HealthCheckDefinition> healthChecks = new ArrayList<>();
        List<ServiceDefinition> services = parseServices(catalog.get("services"), healthChecks);
        return builder()
                .clientId(requiredText(client, "id"))
                .address(optionalText(client, "address", localAddress()))
                .clientPort(optionalInt(client, "httpPort", 8080))
                .heartbeatInterval(optionalLong(client, "heartbeatIntervalMs", 30_000))
                .shutdownTimeoutMs(optionalLong(client, "shutdownTimeoutMs", 30_000))
                .datacenter(optionalText(client, "datacenter", "default"))
                .region(optionalText(client, "region", "default"))
                .serverUrls(parseServerUrls(servers.get("urls")))
                .requestTimeoutMs(optionalInt(servers, "requestTimeoutMs", 5_000))
                .tenant(optionalText(catalog, "tenant", "default"))
                .namespace(optionalText(catalog, "namespace", "default"))
                .registrationRetryMinMs(optionalLong(catalog, "registrationRetryMinMs", 250))
                .registrationRetryMaxMs(optionalLong(catalog, "registrationRetryMaxMs", 30_000))
                .contactFreshnessMs(optionalLong(catalog, "contactFreshnessMs", 90_000))
                .services(services)
                .healthChecks(healthChecks)
                .loggingDirectory(optionalText(logging, "directory", "./logs"))
                .build();
    }

    private static List<URI> parseServerUrls(JsonNode urls) {
        if (urls == null || !urls.isArray() || urls.isEmpty()) {
            throw new IllegalArgumentException("servers.urls must be a non-empty array");
        }
        Set<URI> unique = new LinkedHashSet<>();
        for (JsonNode value : urls) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException("servers.urls entries must be non-blank strings");
            }
            try {
                URI uri = new URI(value.textValue().trim());
                unique.add(normalizeServerUrl(uri, value.textValue()));
            } catch (URISyntaxException error) {
                throw new IllegalArgumentException("Malformed server URL: " + value.textValue(), error);
            }
        }
        return List.copyOf(unique);
    }

    private static URI normalizeServerUrl(URI uri, String source) {
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("Server URL must use HTTP or HTTPS: " + source);
        }
        String path = uri.getRawPath();
        if ((path != null && !path.isEmpty() && !"/".equals(path))
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "Server URL must be an origin without a path, query, or fragment: " + source);
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Server URL must not contain user information: " + source);
        }
        try {
            return new URI(uri.getScheme().toLowerCase(Locale.ROOT), null,
                    uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), null, null, null);
        } catch (URISyntaxException impossible) {
            throw new IllegalArgumentException("Malformed server URL: " + source, impossible);
        }
    }

    private static List<URI> normalizeServerUrls(List<URI> urls) {
        if (urls == null) throw new IllegalArgumentException("servers.urls is required");
        Set<URI> unique = new LinkedHashSet<>();
        for (URI uri : urls) {
            if (uri == null) throw new IllegalArgumentException("servers.urls must not contain null");
            unique.add(normalizeServerUrl(uri, uri.toString()));
        }
        return List.copyOf(unique);
    }

    private static List<ServiceDefinition> parseServices(JsonNode services, List<HealthCheckDefinition> checks) {
        if (services == null || services.isNull()) return List.of();
        if (!services.isArray()) throw new IllegalArgumentException("catalog.services must be an array");
        List<ServiceDefinition> definitions = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode service : services) {
            if (!service.isObject()) throw new IllegalArgumentException("Each catalog service must be an object");
            rejectUnknown(service, "catalog.services[]", "id", "name", "address", "port",
                    "tags", "metadata", "enabled", "checks");
            String id = requiredText(service, "id");
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate catalog service id: " + id);
            ServiceDefinition definition = new ServiceDefinition(id, requiredText(service, "name"),
                    requiredText(service, "address"), requiredInt(service, "port"),
                    stringList(service.get("tags"), "tags"), stringMap(service.get("metadata")),
                    optionalBoolean(service, "enabled", true));
            definitions.add(definition);
            checks.addAll(parseChecks(service.get("checks"), definition));
        }
        return List.copyOf(definitions);
    }

    private static List<HealthCheckDefinition> parseChecks(JsonNode checks, ServiceDefinition service) {
        if (checks == null || checks.isNull()) return List.of();
        if (!checks.isArray()) throw new IllegalArgumentException("catalog.services[].checks must be an array");
        List<HealthCheckDefinition> definitions = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode check : checks) {
            if (!check.isObject()) throw new IllegalArgumentException("Each health check must be an object");
            String id = requiredText(check, "id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate health check id " + id + " for service " + service.id());
            }
            definitions.add(parseCheck(check, id, service));
        }
        return definitions;
    }

    private static HealthCheckDefinition parseCheck(JsonNode check, String id, ServiceDefinition service) {
        String location = "catalog.services[].checks[]";
        String type = requiredText(check, "type");
        boolean required = optionalBoolean(check, "required", true);
        Duration deregisterAfter = Duration.ofMillis(optionalLong(check, "deregisterAfterMs", 0));
        return switch (type) {
            case "http" -> {
                rejectUnknown(check, location, "id", "type", "required", "url",
                        "intervalMs", "timeoutMs", "ttlMs", "deregisterAfterMs");
                CheckTiming timing = CheckTiming.parse(check);
                yield new HttpCheck(service.id(), id, checkUrl(requiredText(check, "url")),
                        timing.interval(), timing.timeout(), timing.ttl(), required, deregisterAfter);
            }
            case "tcp" -> {
                rejectUnknown(check, location, "id", "type", "required", "address", "port",
                        "intervalMs", "timeoutMs", "ttlMs", "deregisterAfterMs", "warnAfterMs");
                CheckTiming timing = CheckTiming.parse(check);
                yield new TcpCheck(service.id(), id, optionalText(check, "address", service.address()),
                        optionalInt(check, "port", service.port()),
                        timing.interval(), timing.timeout(), timing.ttl(), required, deregisterAfter,
                        Duration.ofMillis(optionalLong(check, "warnAfterMs", 0)));
            }
            case "ttl" -> {
                rejectUnknown(check, location, "id", "type", "required", "ttlMs", "deregisterAfterMs");
                if (!check.has("ttlMs")) throw new IllegalArgumentException("ttlMs is required for a ttl check");
                yield new TtlCheck(service.id(), id, Duration.ofMillis(optionalLong(check, "ttlMs", 0)), required,
                        deregisterAfter);
            }
            default -> throw new IllegalArgumentException("Unsupported health check type: " + type);
        };
    }

    private static URI checkUrl(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Malformed health check url: " + value, error);
        }
    }

    /** Probe timing: interval defaults to 10s, timeout to min(2s, interval), and ttl to three intervals. */
    private record CheckTiming(Duration interval, Duration timeout, Duration ttl) {
        static CheckTiming parse(JsonNode check) {
            long interval = optionalLong(check, "intervalMs", 10_000);
            if (interval < 1) throw new IllegalArgumentException("intervalMs must be positive");
            if (interval > Long.MAX_VALUE / 3) throw new IllegalArgumentException("intervalMs is too large");
            long timeout = optionalLong(check, "timeoutMs", Math.min(2_000, interval));
            long ttl = optionalLong(check, "ttlMs", interval * 3);
            return new CheckTiming(Duration.ofMillis(interval), Duration.ofMillis(timeout), Duration.ofMillis(ttl));
        }
    }

    private static List<String> stringList(JsonNode node, String field) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalArgumentException(field + " must be an array");
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isTextual()) throw new IllegalArgumentException(field + " entries must be strings");
            values.add(value.textValue());
        });
        return values;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        if (!node.isObject()) throw new IllegalArgumentException("metadata must be an object");
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException("metadata values must be strings");
            }
            values.put(entry.getKey(), entry.getValue().textValue());
        });
        return values;
    }

    private static String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value.textValue().trim();
    }

    private static String optionalText(JsonNode parent, String field, String fallback) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value.textValue().trim();
    }

    private static String localAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException error) {
            return "127.0.0.1";
        }
    }

    public static Builder builder() { return new Builder(); }
    public String getClientId() { return clientId; }
    public String getAddress() { return address; }
    public int getClientPort() { return clientPort; }
    public String getRegion() { return region; }
    public String getDatacenter() { return datacenter; }
    public String getServerUrl() { return serverUrls.getFirst().toString(); }
    public List<URI> getServerUrls() { return serverUrls; }
    public long getHeartbeatInterval() { return heartbeatInterval; }
    public long getShutdownTimeoutMs() { return shutdownTimeoutMs; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public long getRegistrationRetryMinMs() { return registrationRetryMinMs; }
    public long getRegistrationRetryMaxMs() { return registrationRetryMaxMs; }
    public long getContactFreshnessMs() { return contactFreshnessMs; }
    public String getTenant() { return tenant; }
    public String getNamespace() { return namespace; }
    public List<ServiceDefinition> getServices() { return services; }
    public List<HealthCheckDefinition> getHealthChecks() { return healthChecks; }
    public String getLoggingDirectory() { return loggingDirectory; }

    public static final class Builder {
        private String clientId;
        private String address = "127.0.0.1";
        private int clientPort = 8080;
        private String region = "default";
        private String datacenter = "default";
        private List<URI> serverUrls = List.of();
        private long heartbeatInterval = 30_000;
        private long shutdownTimeoutMs = 30_000;
        private int requestTimeoutMs = 5_000;
        private long registrationRetryMinMs = 250;
        private long registrationRetryMaxMs = 30_000;
        private long contactFreshnessMs = 90_000;
        private String tenant = "default";
        private String namespace = "default";
        private List<ServiceDefinition> services = List.of();
        private List<HealthCheckDefinition> healthChecks = List.of();
        private String loggingDirectory = "./logs";

        public Builder clientId(String value) { clientId = value; return this; }
        public Builder address(String value) { address = value; return this; }
        public Builder clientPort(int value) { clientPort = value; return this; }
        public Builder region(String value) { region = value; return this; }
        public Builder datacenter(String value) { datacenter = value; return this; }
        public Builder serverUrl(String value) {
            return serverUrls(parseServerUrls(JsonNodeFactory.instance.arrayNode().add(value)));
        }
        public Builder serverUrls(List<URI> value) { serverUrls = normalizeServerUrls(value); return this; }
        public Builder heartbeatInterval(long value) { heartbeatInterval = value; return this; }
        public Builder shutdownTimeoutMs(long value) { shutdownTimeoutMs = value; return this; }
        public Builder requestTimeoutMs(int value) { requestTimeoutMs = value; return this; }
        public Builder registrationRetryMinMs(long value) { registrationRetryMinMs = value; return this; }
        public Builder registrationRetryMaxMs(long value) { registrationRetryMaxMs = value; return this; }
        public Builder contactFreshnessMs(long value) { contactFreshnessMs = value; return this; }
        public Builder tenant(String value) { tenant = value; return this; }
        public Builder namespace(String value) { namespace = value; return this; }
        public Builder services(List<ServiceDefinition> value) { services = List.copyOf(value); return this; }
        public Builder healthChecks(List<HealthCheckDefinition> value) { healthChecks = List.copyOf(value); return this; }
        public Builder loggingDirectory(String value) { loggingDirectory = value; return this; }

        public ClientConfiguration build() {
            requireNonBlank("client.id", clientId);
            requireNonBlank("client.address", address);
            requireNonBlank("client.region", region);
            requireNonBlank("client.datacenter", datacenter);
            requireNonBlank("catalog.tenant", tenant);
            requireNonBlank("catalog.namespace", namespace);
            requireNonBlank("logging.directory", loggingDirectory);
            if (serverUrls.isEmpty()) throw new IllegalArgumentException("servers.urls is required");
            // Port 0 asks the system for any free port; the client registers the port it actually bound.
            if (clientPort < 0 || clientPort > 65_535) throw new IllegalArgumentException("client.httpPort is invalid");
            if (heartbeatInterval < 1) throw new IllegalArgumentException("client.heartbeatIntervalMs must be positive");
            if (shutdownTimeoutMs < 1) throw new IllegalArgumentException("client.shutdownTimeoutMs must be positive");
            if (requestTimeoutMs < 1) throw new IllegalArgumentException("servers.requestTimeoutMs must be positive");
            if (registrationRetryMinMs < 1 || registrationRetryMaxMs < 1) {
                throw new IllegalArgumentException("catalog retry intervals must be positive");
            }
            if (contactFreshnessMs < 1) {
                throw new IllegalArgumentException("catalog.contactFreshnessMs must be positive");
            }
            if (registrationRetryMinMs > registrationRetryMaxMs) {
                throw new IllegalArgumentException("catalog.registrationRetryMinMs must not exceed registrationRetryMaxMs");
            }
            Set<String> ids = new LinkedHashSet<>();
            for (ServiceDefinition service : services) {
                if (!ids.add(service.id())) throw new IllegalArgumentException("Duplicate catalog service id: " + service.id());
            }
            Set<String> checkIds = new LinkedHashSet<>();
            for (HealthCheckDefinition check : healthChecks) {
                if (!ids.contains(check.serviceId())) {
                    throw new IllegalArgumentException("Health check " + check.checkId()
                            + " refers to unknown catalog service " + check.serviceId());
                }
                if (!checkIds.add(check.serviceId() + "/" + check.checkId())) {
                    throw new IllegalArgumentException("Duplicate health check id " + check.checkId()
                            + " for service " + check.serviceId());
                }
            }
            return new ClientConfiguration(this);
        }

        private static void requireNonBlank(String field, String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        }
    }
}

package dev.mars.qraft.agent.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.mars.qraft.catalog.ServiceDefinition;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable, file-backed configuration for a Qraft discovery agent. */
public final class AgentConfiguration {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private final String agentId;
    private final String hostname;
    private final String address;
    private final int agentPort;
    private final String region;
    private final String datacenter;
    private final List<URI> controllerUrls;
    private final long heartbeatInterval;
    private final int requestTimeoutMs;
    private final long registrationRetryMinMs;
    private final long registrationRetryMaxMs;
    private final String tenant;
    private final String namespace;
    private final List<ServiceDefinition> services;
    private final String loggingDirectory;
    private final String version;

    private AgentConfiguration(Builder builder) {
        agentId = builder.agentId.trim();
        hostname = builder.hostname.trim();
        address = builder.address.trim();
        agentPort = builder.agentPort;
        region = builder.region.trim();
        datacenter = builder.datacenter.trim();
        controllerUrls = List.copyOf(builder.controllerUrls);
        heartbeatInterval = builder.heartbeatInterval;
        requestTimeoutMs = builder.requestTimeoutMs;
        registrationRetryMinMs = builder.registrationRetryMinMs;
        registrationRetryMaxMs = builder.registrationRetryMaxMs;
        tenant = builder.tenant.trim();
        namespace = builder.namespace.trim();
        services = List.copyOf(builder.services);
        loggingDirectory = builder.loggingDirectory.trim();
        version = builder.version.trim();
    }

    public static AgentConfiguration fromFile(Path path) {
        if (path == null) throw new IllegalArgumentException("configuration path is required");
        try {
            return fromJson(Files.readString(path));
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not read agent configuration " + path, error);
        }
    }

    /** Parses an injected document without consulting process-global configuration. */
    public static AgentConfiguration fromJson(String document) {
        final JsonNode root;
        try {
            root = JSON.readTree(document);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Agent configuration is not valid JSON", error);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Agent configuration must be a JSON object");
        }
        rejectUnknown(root, "root", "version", "agent", "controllers", "catalog", "logging");
        int formatVersion = requiredInt(root, "version");
        if (formatVersion != 1) {
            throw new IllegalArgumentException("Unsupported configuration version: " + formatVersion);
        }

        JsonNode agent = requiredObject(root, "agent");
        JsonNode controllers = requiredObject(root, "controllers");
        JsonNode catalog = optionalObject(root, "catalog");
        JsonNode logging = optionalObject(root, "logging");
        rejectUnknown(agent, "agent", "id", "hostname", "address", "httpPort",
                "heartbeatIntervalMs", "datacenter", "region", "version");
        rejectUnknown(controllers, "controllers", "urls", "requestTimeoutMs");
        rejectUnknown(catalog, "catalog", "tenant", "namespace", "registrationRetryMinMs",
                "registrationRetryMaxMs", "services");
        rejectUnknown(logging, "logging", "directory");
        HostIdentity local = localIdentity();
        return builder()
                .agentId(requiredText(agent, "id"))
                .hostname(optionalText(agent, "hostname", local.hostname()))
                .address(optionalText(agent, "address", local.address()))
                .agentPort(optionalInt(agent, "httpPort", 8080))
                .heartbeatInterval(optionalLong(agent, "heartbeatIntervalMs", 30_000))
                .datacenter(optionalText(agent, "datacenter", "default"))
                .region(optionalText(agent, "region", "default"))
                .version(optionalText(agent, "version", "1.0.0"))
                .controllerUrls(parseControllerUrls(controllers.get("urls")))
                .requestTimeoutMs(optionalInt(controllers, "requestTimeoutMs", 5_000))
                .tenant(optionalText(catalog, "tenant", "default"))
                .namespace(optionalText(catalog, "namespace", "default"))
                .registrationRetryMinMs(optionalLong(catalog, "registrationRetryMinMs", 250))
                .registrationRetryMaxMs(optionalLong(catalog, "registrationRetryMaxMs", 30_000))
                .services(parseServices(catalog.get("services")))
                .loggingDirectory(optionalText(logging, "directory", "./logs"))
                .build();
    }

    private static List<URI> parseControllerUrls(JsonNode urls) {
        if (urls == null || !urls.isArray() || urls.isEmpty()) {
            throw new IllegalArgumentException("controllers.urls must be a non-empty array");
        }
        Set<URI> unique = new LinkedHashSet<>();
        for (JsonNode value : urls) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException("controllers.urls entries must be non-blank strings");
            }
            try {
                URI uri = new URI(value.textValue().trim());
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null) {
                    throw new IllegalArgumentException("Controller URL must use HTTP or HTTPS: " + value.textValue());
                }
                unique.add(uri);
            } catch (URISyntaxException error) {
                throw new IllegalArgumentException("Malformed controller URL: " + value.textValue(), error);
            }
        }
        return List.copyOf(unique);
    }

    private static List<ServiceDefinition> parseServices(JsonNode services) {
        if (services == null || services.isNull()) return List.of();
        if (!services.isArray()) throw new IllegalArgumentException("catalog.services must be an array");
        List<ServiceDefinition> definitions = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode service : services) {
            if (!service.isObject()) throw new IllegalArgumentException("Each catalog service must be an object");
            rejectUnknown(service, "catalog.services[]", "id", "name", "address", "port",
                    "tags", "metadata", "enabled");
            String id = requiredText(service, "id");
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate catalog service id: " + id);
            definitions.add(new ServiceDefinition(id, requiredText(service, "name"),
                    requiredText(service, "address"), requiredInt(service, "port"),
                    stringList(service.get("tags"), "tags"), stringMap(service.get("metadata")),
                    optionalBoolean(service, "enabled", true)));
        }
        return List.copyOf(definitions);
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
        Set<String> allowed = Set.of(allowedNames);
        object.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("Unknown " + location + " setting: " + name);
            }
        });
    }

    private static HostIdentity localIdentity() {
        try {
            InetAddress local = InetAddress.getLocalHost();
            return new HostIdentity(local.getHostName(), local.getHostAddress());
        } catch (UnknownHostException error) {
            return new HostIdentity("unknown", "127.0.0.1");
        }
    }

    public static Builder builder() { return new Builder(); }
    public String getAgentId() { return agentId; }
    public String getHostname() { return hostname; }
    public String getAddress() { return address; }
    public int getAgentPort() { return agentPort; }
    public String getRegion() { return region; }
    public String getDatacenter() { return datacenter; }
    public String getControllerUrl() { return controllerUrls.getFirst().toString(); }
    public List<URI> getControllerUrls() { return controllerUrls; }
    public long getHeartbeatInterval() { return heartbeatInterval; }
    public int getHttpConnectionTimeout() { return requestTimeoutMs; }
    public int getHttpIdleTimeout() { return requestTimeoutMs; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public long getRegistrationRetryMinMs() { return registrationRetryMinMs; }
    public long getRegistrationRetryMaxMs() { return registrationRetryMaxMs; }
    public String getTenant() { return tenant; }
    public String getNamespace() { return namespace; }
    public List<ServiceDefinition> getServices() { return services; }
    public String getLoggingDirectory() { return loggingDirectory; }
    public String getVersion() { return version; }

    public static final class Builder {
        private String agentId;
        private String hostname = "unknown";
        private String address = "127.0.0.1";
        private int agentPort = 8080;
        private String region = "default";
        private String datacenter = "default";
        private List<URI> controllerUrls = List.of();
        private long heartbeatInterval = 30_000;
        private int requestTimeoutMs = 5_000;
        private long registrationRetryMinMs = 250;
        private long registrationRetryMaxMs = 30_000;
        private String tenant = "default";
        private String namespace = "default";
        private List<ServiceDefinition> services = List.of();
        private String loggingDirectory = "./logs";
        private String version = "1.0.0";

        public Builder agentId(String value) { agentId = value; return this; }
        public Builder hostname(String value) { hostname = value; return this; }
        public Builder address(String value) { address = value; return this; }
        public Builder agentPort(int value) { agentPort = value; return this; }
        public Builder region(String value) { region = value; return this; }
        public Builder datacenter(String value) { datacenter = value; return this; }
        public Builder controllerUrl(String value) {
            return controllerUrls(parseControllerUrls(JSON.createArrayNode().add(value)));
        }
        public Builder controllerUrls(List<URI> value) { controllerUrls = List.copyOf(value); return this; }
        public Builder heartbeatInterval(long value) { heartbeatInterval = value; return this; }
        public Builder httpConnectionTimeout(int value) { requestTimeoutMs = value; return this; }
        public Builder requestTimeoutMs(int value) { requestTimeoutMs = value; return this; }
        public Builder registrationRetryMinMs(long value) { registrationRetryMinMs = value; return this; }
        public Builder registrationRetryMaxMs(long value) { registrationRetryMaxMs = value; return this; }
        public Builder tenant(String value) { tenant = value; return this; }
        public Builder namespace(String value) { namespace = value; return this; }
        public Builder services(List<ServiceDefinition> value) { services = List.copyOf(value); return this; }
        public Builder loggingDirectory(String value) { loggingDirectory = value; return this; }
        public Builder version(String value) { version = value; return this; }

        public AgentConfiguration build() {
            requireNonBlank("agent.id", agentId);
            requireNonBlank("agent.hostname", hostname);
            requireNonBlank("agent.address", address);
            requireNonBlank("agent.region", region);
            requireNonBlank("agent.datacenter", datacenter);
            requireNonBlank("catalog.tenant", tenant);
            requireNonBlank("catalog.namespace", namespace);
            requireNonBlank("logging.directory", loggingDirectory);
            requireNonBlank("agent.version", version);
            if (controllerUrls.isEmpty()) throw new IllegalArgumentException("controllers.urls is required");
            if (agentPort < 1 || agentPort > 65_535) throw new IllegalArgumentException("agent.httpPort is invalid");
            if (heartbeatInterval < 1) throw new IllegalArgumentException("agent.heartbeatIntervalMs must be positive");
            if (requestTimeoutMs < 1) throw new IllegalArgumentException("controllers.requestTimeoutMs must be positive");
            if (registrationRetryMinMs < 1 || registrationRetryMaxMs < 1) {
                throw new IllegalArgumentException("catalog retry intervals must be positive");
            }
            if (registrationRetryMinMs > registrationRetryMaxMs) {
                throw new IllegalArgumentException("catalog.registrationRetryMinMs must not exceed registrationRetryMaxMs");
            }
            Set<String> ids = new LinkedHashSet<>();
            for (ServiceDefinition service : services) {
                if (!ids.add(service.id())) throw new IllegalArgumentException("Duplicate catalog service id: " + service.id());
            }
            return new AgentConfiguration(this);
        }

        private static void requireNonBlank(String field, String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        }
    }

    private record HostIdentity(String hostname, String address) { }
}

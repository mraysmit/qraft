package dev.mars.qraft.catalog;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable service registration held by the distributed catalog.
 */
public record ServiceInstance(
        String serviceId,
        String serviceName,
        String nodeId,
        String address,
        int port,
        List<String> tags,
        Map<String, String> metadata,
        ServiceHealth health,
        String tenantId,
        String namespace,
        String datacenter,
        String region,
        boolean enabled) {

    public static final String DEFAULT_SCOPE = "default";

    public ServiceInstance {
        requireText(serviceId, "serviceId");
        requireText(serviceName, "serviceName");
        requireText(nodeId, "nodeId");
        requireText(address, "address");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        health = Objects.requireNonNull(health, "health");
        tenantId = defaultScope(tenantId, "tenantId");
        namespace = defaultScope(namespace, "namespace");
        datacenter = optionalText(datacenter, "datacenter");
        region = optionalText(region, "region");
    }

    /** Source-compatible constructor for registrations written before scoped identity. */
    public ServiceInstance(String serviceId, String serviceName, String nodeId, String address,
                           int port, List<String> tags, Map<String, String> metadata,
                           ServiceHealth health) {
        this(serviceId, serviceName, nodeId, address, port, tags, metadata, health,
                DEFAULT_SCOPE, DEFAULT_SCOPE, "", "", true);
    }

    /** Jackson creator that distinguishes a missing enabled field from explicit false. */
    @JsonCreator
    public ServiceInstance(
            @JsonProperty("serviceId") String serviceId,
            @JsonProperty("serviceName") String serviceName,
            @JsonProperty("nodeId") String nodeId,
            @JsonProperty("address") String address,
            @JsonProperty("port") int port,
            @JsonProperty("tags") List<String> tags,
            @JsonProperty("metadata") Map<String, String> metadata,
            @JsonProperty("health") ServiceHealth health,
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("namespace") String namespace,
            @JsonProperty("datacenter") String datacenter,
            @JsonProperty("region") String region,
            @JsonProperty("enabled") Boolean enabled) {
        this(serviceId, serviceName, nodeId, address, port, tags, metadata, health,
                tenantId, namespace, datacenter, region, enabled == null || enabled);
    }

    public ServiceInstance withHealth(ServiceHealth newHealth) {
        return new ServiceInstance(serviceId, serviceName, nodeId, address, port, tags, metadata,
                newHealth, tenantId, namespace, datacenter, region, enabled);
    }

    public ServiceInstanceId identity() {
        return new ServiceInstanceId(tenantId, namespace, nodeId, serviceId);
    }

    public ServiceKey serviceKey() {
        return new ServiceKey(tenantId, namespace, serviceName);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static String defaultScope(String value, String field) {
        if (value == null) return DEFAULT_SCOPE;
        requireText(value, field);
        return value;
    }

    private static String optionalText(String value, String field) {
        if (value == null || value.isEmpty()) return "";
        requireText(value, field);
        return value;
    }
}

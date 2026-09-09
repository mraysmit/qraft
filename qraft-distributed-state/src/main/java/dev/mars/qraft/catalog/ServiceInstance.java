package dev.mars.qraft.catalog;

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
        ServiceHealth health) {

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
    }

    public ServiceInstance withHealth(ServiceHealth newHealth) {
        return new ServiceInstance(serviceId, serviceName, nodeId, address, port, tags, metadata, newHealth);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

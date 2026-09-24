package dev.mars.qraft.catalog;

import java.util.List;
import java.util.Map;

/** Client-owned declaration of a service that an agent should publish. */
public record ServiceDefinition(String id, String name, String address, int port,
                                List<String> tags, Map<String, String> metadata, boolean enabled) {
    public ServiceDefinition {
        id = required("service id", id);
        name = required("service name", name);
        address = required("service address", address);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("service port must be between 1 and 65535");
        }
        tags = List.copyOf(tags == null ? List.of() : tags);
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        if (tags.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("service tags must not contain blank values");
        }
        if (metadata.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null)) {
            throw new IllegalArgumentException("service metadata keys must be non-blank and values non-null");
        }
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}

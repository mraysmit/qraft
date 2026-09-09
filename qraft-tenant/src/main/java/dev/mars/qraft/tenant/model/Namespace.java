package dev.mars.qraft.tenant.model;

import java.time.Instant;
import java.util.Map;

/** A Consul-style namespace for isolating service registrations and policies. */
public record Namespace(
        String name,
        String description,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt) {

    public Namespace {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Namespace name cannot be blank");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public static Namespace named(String name) {
        return new Namespace(name, null, Map.of(), Instant.now(), null);
    }

    public Namespace withDescription(String value) {
        return new Namespace(name, value, metadata, createdAt, Instant.now());
    }

    public Namespace withMetadata(Map<String, String> value) {
        return new Namespace(name, description, value, createdAt, Instant.now());
    }
}

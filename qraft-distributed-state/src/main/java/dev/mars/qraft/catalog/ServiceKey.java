package dev.mars.qraft.catalog;

/** Scope-qualified service name used for catalog lookups. */
public record ServiceKey(String tenantId, String namespace, String serviceName) {
    public ServiceKey {
        requireText(tenantId, "tenantId");
        requireText(namespace, "namespace");
        requireText(serviceName, "serviceName");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

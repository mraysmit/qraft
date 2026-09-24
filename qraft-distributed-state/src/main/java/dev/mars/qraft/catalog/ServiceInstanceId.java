package dev.mars.qraft.catalog;

/** Durable identity of one service instance within the catalog. */
public record ServiceInstanceId(
        String tenantId,
        String namespace,
        String nodeId,
        String serviceId) {

    public ServiceInstanceId {
        requireText(tenantId, "tenantId");
        requireText(namespace, "namespace");
        requireText(nodeId, "nodeId");
        requireText(serviceId, "serviceId");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

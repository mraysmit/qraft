package dev.mars.qraft.catalog;

import java.util.Objects;

/** Durable identity of one health check attached to a service instance. */
public record ServiceCheckId(ServiceInstanceId serviceInstanceId, String checkId) {
    public ServiceCheckId {
        Objects.requireNonNull(serviceInstanceId, "serviceInstanceId");
        if (checkId == null || checkId.isBlank()) {
            throw new IllegalArgumentException("checkId must not be blank");
        }
    }
}

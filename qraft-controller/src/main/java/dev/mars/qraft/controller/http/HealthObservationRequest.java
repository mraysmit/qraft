package dev.mars.qraft.controller.http;

import dev.mars.qraft.catalog.HealthObservation;
import dev.mars.qraft.catalog.ServiceCheckId;
import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstanceId;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Agent-reported health observation or TTL renewal for one check on a registered service. */
record HealthObservationRequest(
        String serviceId,
        String checkId,
        String status,
        Long sequenceNumber,
        String observedAt,
        Long ttlMillis,
        Boolean required,
        String output) {

    static final int MAX_OUTPUT_LENGTH = 4096;

    HealthObservation toObservation(RequestContext context) {
        if (sequenceNumber == null) throw new IllegalArgumentException("sequenceNumber is required");
        if (ttlMillis == null) throw new IllegalArgumentException("ttlMillis is required");
        if (output != null && output.length() > MAX_OUTPUT_LENGTH) {
            throw new IllegalArgumentException("output must not exceed " + MAX_OUTPUT_LENGTH + " characters");
        }
        ServiceInstanceId instance = new ServiceInstanceId(
                context.tenantId(), context.namespace(), context.nodeId(), serviceId);
        return new HealthObservation(new ServiceCheckId(instance, checkId), parseStatus(status),
                sequenceNumber, parseObservedAt(observedAt), ttlMillis, required == null || required, output);
    }

    private static ServiceHealth parseStatus(String value) {
        if (value == null) throw new IllegalArgumentException("status is required");
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "PASSING" -> ServiceHealth.PASSING;
            case "WARNING" -> ServiceHealth.WARNING;
            case "CRITICAL" -> ServiceHealth.CRITICAL;
            case "MAINTENANCE" -> ServiceHealth.MAINTENANCE;
            default -> throw new IllegalArgumentException(
                    "status must be passing, warning, critical, or maintenance");
        };
    }

    private static Instant parseObservedAt(String value) {
        if (value == null) throw new IllegalArgumentException("observedAt is required");
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("observedAt must be an ISO-8601 UTC instant");
        }
    }
}

package dev.mars.qraft.controller.http;

import dev.mars.qraft.catalog.HealthCheckState;
import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstanceId;

record HealthObservationResponse(
        String serviceId,
        String checkId,
        String nodeId,
        String tenantId,
        String namespace,
        long sequenceNumber,
        ServiceHealth status,
        String deadline,
        boolean accepted) {

    static HealthObservationResponse accepted(HealthCheckState state) {
        ServiceInstanceId instance = state.checkId().serviceInstanceId();
        return new HealthObservationResponse(instance.serviceId(), state.checkId().checkId(), instance.nodeId(),
                instance.tenantId(), instance.namespace(), state.observation().sequenceNumber(),
                state.observation().status(), state.deadline().toString(), true);
    }
}

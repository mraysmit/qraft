package dev.mars.qraft.controller.http;

import dev.mars.qraft.catalog.ServiceInstance;

record ServiceRegistrationResponse(
        String serviceId,
        String serviceName,
        String nodeId,
        String tenantId,
        String namespace,
        boolean registered) {

    static ServiceRegistrationResponse from(ServiceInstance instance, boolean registered) {
        return new ServiceRegistrationResponse(instance.serviceId(), instance.serviceName(), instance.nodeId(),
                instance.tenantId(), instance.namespace(), registered);
    }
}

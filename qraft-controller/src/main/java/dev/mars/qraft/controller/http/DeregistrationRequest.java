package dev.mars.qraft.controller.http;

import dev.mars.qraft.catalog.ServiceInstanceId;

record DeregistrationRequest(String serviceId, RequestContext context) {
    ServiceInstanceId identity() {
        return new ServiceInstanceId(context.tenantId(), context.namespace(), context.nodeId(), serviceId);
    }
}

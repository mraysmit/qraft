package dev.mars.qraft.controller.http;

import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstance;

import java.util.List;
import java.util.Map;

record ServiceRegistrationRequest(
        String serviceId,
        String serviceName,
        String address,
        int port,
        List<String> tags,
        Map<String, String> metadata,
        String datacenter,
        String region,
        Boolean enabled,
        String health) {

    ServiceInstance toServiceInstance(RequestContext context) {
        return new ServiceInstance(serviceId, serviceName, context.nodeId(), address, port,
                tags == null ? List.of() : tags,
                metadata == null ? Map.of() : metadata,
                ServiceHealth.UNKNOWN,
                context.tenantId(), context.namespace(), datacenter, region,
                enabled == null || enabled);
    }
}

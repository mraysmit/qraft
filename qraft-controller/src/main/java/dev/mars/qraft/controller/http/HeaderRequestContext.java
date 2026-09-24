package dev.mars.qraft.controller.http;

import com.sun.net.httpserver.HttpExchange;
import dev.mars.qraft.catalog.ServiceInstance;

final class HeaderRequestContext implements RequestContext {
    static final String TENANT_HEADER = "X-Qraft-Tenant";
    static final String NAMESPACE_HEADER = "X-Qraft-Namespace";
    static final String NODE_HEADER = "X-Qraft-Node";

    private final String tenantId;
    private final String namespace;
    private final String nodeId;

    HeaderRequestContext(HttpExchange exchange) {
        tenantId = optionalScope(exchange, TENANT_HEADER);
        namespace = optionalScope(exchange, NAMESPACE_HEADER);
        nodeId = required(exchange, NODE_HEADER);
    }

    @Override public String tenantId() { return tenantId; }
    @Override public String namespace() { return namespace; }
    @Override public String nodeId() { return nodeId; }

    private static String optionalScope(HttpExchange exchange, String header) {
        String value = exchange.getRequestHeaders().getFirst(header);
        if (value == null) return ServiceInstance.DEFAULT_SCOPE;
        if (value.isBlank()) throw new IllegalArgumentException(header + " must not be blank");
        return value;
    }

    private static String required(HttpExchange exchange, String header) {
        String value = exchange.getRequestHeaders().getFirst(header);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(header + " is required");
        }
        return value;
    }
}

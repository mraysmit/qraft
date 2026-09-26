/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.mars.qraft.controller.http;

import com.sun.net.httpserver.HttpExchange;
import dev.mars.qraft.catalog.ServiceInstance;

/**
 * {@link RequestContext} read from the X-Qraft-Tenant, X-Qraft-Namespace, and X-Qraft-Node request
 * headers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
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

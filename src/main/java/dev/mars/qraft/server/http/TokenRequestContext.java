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

package dev.mars.qraft.server.http;

import com.sun.net.httpserver.HttpExchange;
import dev.mars.qraft.state.catalog.ServiceInstance;

import java.util.UUID;

/**
 * {@link RequestContext} read from the X-Qraft-Tenant, X-Qraft-Namespace, X-Qraft-Node, and X-Qraft-Node-ID
 * request headers. The node ID is optional, for clients from before node IDs, and when present must be a
 * canonical UUID.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
final class HeaderRequestContext implements RequestContext {
    static final String TENANT_HEADER = "X-Qraft-Tenant";
    static final String NAMESPACE_HEADER = "X-Qraft-Namespace";
    static final String NODE_HEADER = "X-Qraft-Node";
    static final String NODE_ID_HEADER = "X-Qraft-Node-ID";

    private final String tenantId;
    private final String namespace;
    private final String nodeId;
    private final String nodeUuid;

    HeaderRequestContext(HttpExchange exchange) {
        tenantId = optionalScope(exchange, TENANT_HEADER);
        namespace = optionalScope(exchange, NAMESPACE_HEADER);
        nodeId = required(exchange, NODE_HEADER);
        nodeUuid = optionalUuid(exchange, NODE_ID_HEADER);
    }

    @Override public String tenantId() { return tenantId; }
    @Override public String namespace() { return namespace; }
    @Override public String nodeId() { return nodeId; }
    @Override public String nodeUuid() { return nodeUuid; }

    /** Reads an optional header that, when present, must be a UUID in its canonical, lower-case form. */
    private static String optionalUuid(HttpExchange exchange, String header) {
        String value = exchange.getRequestHeaders().getFirst(header);
        if (value == null) return null;
        try {
            if (UUID.fromString(value).toString().equals(value)) return value;
        } catch (IllegalArgumentException notAUuid) {
            // Reported below with the canonical form it should have.
        }
        throw new IllegalArgumentException(header + " must be a canonical UUID");
    }

    /** Reads an optional scope header: absent means the default scope, and blank is rejected. */
    static String optionalScope(HttpExchange exchange, String header) {
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

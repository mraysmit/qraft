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

import dev.mars.qraft.common.Node;

import java.util.Map;
import java.util.Optional;

/**
 * JSON body of {@code PUT /v1/catalog/register}: what the node named by the request's identity registers.
 *
 * <p>The body describes the node under {@code node}, a service under {@code service}, or both, and must hold at
 * least one. A service has the body of a service registration. The body cannot name the node, which the identity
 * header does, and cannot set the status or the times, which the servers own.
 *
 * @param node    the node's description; absent when only a service is registered
 * @param service the service to register on the node; absent when only the node is registered
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-09
 * @version 1.0
 */
record CatalogRegistrationRequest(NodeDescription node, ServiceRegistrationRequest service) {

    /**
     * What a client says about its node.
     *
     * @param address    the address the node is reached at
     * @param datacenter the datacenter the node is in
     * @param region     the region the node is in
     * @param metadata   free-form metadata; absent means none
     */
    record NodeDescription(String address, String datacenter, String region, Map<String, String> metadata) {
    }

    /** The body must describe the node, a service, or both. */
    void requireSomethingToRegister() {
        if (node == null && service == null) {
            throw new IllegalArgumentException("node or service is required");
        }
    }

    /** The node this request registers, named by the request's identity; empty when only a service is registered. */
    Optional<Node> toNode(RequestContext context) {
        if (node == null) return Optional.empty();
        return Optional.of(Node.of(context.nodeId(), node.address(), node.datacenter(), node.region(),
                node.metadata()));
    }
}

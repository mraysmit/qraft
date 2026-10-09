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

import java.time.Instant;
import java.util.Map;

/**
 * One node in the answer of {@code GET /v1/catalog/nodes}. The fields are a node's, with the status as its
 * value and each time as an ISO-8601 instant, as the other answers of the API write theirs.
 *
 * @param name             the node's name
 * @param address          the address the node is reached at
 * @param datacenter       the datacenter the node is in
 * @param region           the region the node is in
 * @param metadata         the node's metadata, in key order
 * @param status           {@code registering}, {@code healthy}, or {@code unreachable}
 * @param registrationTime when the servers applied the node's registration
 * @param lastHeartbeat    when the servers applied the node's last heartbeat, or {@code null} before the first
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-09
 * @version 1.0
 */
record NodeEntry(String name, String address, String datacenter, String region, Map<String, String> metadata,
                 String status, String registrationTime, String lastHeartbeat) {

    static NodeEntry from(Node node) {
        return new NodeEntry(node.name(), node.address(), node.datacenter(), node.region(), node.metadata(),
                node.status() == null ? null : node.status().getValue(),
                instant(node.registrationTime()), instant(node.lastHeartbeat()));
    }

    private static String instant(Instant time) {
        return time == null ? null : time.toString();
    }
}

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

package dev.mars.qraft.common;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * A node in the replicated node registry: the stable identity that a client-mode process registers, and the
 * place its service instances live. It has the shape of a Consul node: a name, an address, a datacenter, a
 * region, and metadata, together with the status and the two times that the servers record for it.
 *
 * <p>A registration cannot claim the status or the times: the servers set them when the registration and each
 * heartbeat are applied. The client's version travels in the metadata, under {@link #VERSION_METADATA_KEY}.
 *
 * <p>Replicated state written by earlier versions still reads. The name is also read under the two names it
 * had before, and the fields that a node no longer has, among them the host name, the port, the version, and
 * the capabilities, are ignored.
 *
 * @param name             the node's name, which is its key in the registry
 * @param address          the address the node is reached at
 * @param datacenter       the datacenter the node is in
 * @param region           the region the node is in
 * @param metadata         free-form metadata, never {@code null}, in key order
 * @param status           the lifecycle status the servers recorded
 * @param registrationTime when the servers applied the node's registration
 * @param lastHeartbeat    when the servers applied the node's last heartbeat, or {@code null} before the first
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-09
 * @version 1.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Node(
        @JsonAlias({"clientId", "agentId"}) String name,
        String address,
        String datacenter,
        String region,
        Map<String, String> metadata,
        NodeStatus status,
        Instant registrationTime,
        Instant lastHeartbeat) {

    /** The metadata key under which a registration carries the identifier of its attempt. */
    public static final String REGISTRATION_ID_METADATA_KEY = "qraft.registrationId";

    /** The metadata key under which a client reports its version. */
    public static final String VERSION_METADATA_KEY = "qraft.version";

    public Node {
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(metadata));
    }

    /** A node as a client describes it when it registers: without a status or times. */
    public static Node of(String name, String address, String datacenter, String region,
                          Map<String, String> metadata) {
        return new Node(name, address, datacenter, region, metadata, null, null, null);
    }

    /** This node with one more metadata entry. */
    public Node withMetadata(String key, String value) {
        Map<String, String> changed = new TreeMap<>(metadata);
        changed.put(key, value);
        return new Node(name, address, datacenter, region, changed, status, registrationTime, lastHeartbeat);
    }

    /** This node with another status. */
    public Node withStatus(NodeStatus newStatus) {
        return new Node(name, address, datacenter, region, metadata, newStatus, registrationTime, lastHeartbeat);
    }

    /** This node with another last heartbeat. */
    public Node withLastHeartbeat(Instant time) {
        return new Node(name, address, datacenter, region, metadata, status, registrationTime, time);
    }

    /** This node as the servers record a registration applied at {@code time}: registering, with no heartbeat. */
    public Node registeredAt(Instant time) {
        return new Node(name, address, datacenter, region, metadata, NodeStatus.REGISTERING, time, null);
    }

    /** The node's last contact: its last heartbeat, or its registration time before the first heartbeat. */
    public Instant lastContact() {
        return lastHeartbeat != null ? lastHeartbeat : registrationTime;
    }
}

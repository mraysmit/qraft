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

package dev.mars.qraft.state;

import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.NodeStatus;
import dev.mars.qraft.raft.grpc.NodeCommandProto;
import dev.mars.qraft.raft.grpc.NodeCommandType;
import dev.mars.qraft.raft.grpc.NodeProto;
import dev.mars.qraft.raft.grpc.NodeStatusProto;

import java.time.Instant;
import java.util.Optional;

/**
 * Protobuf codec for the node registry's types: {@link NodeCommand}, {@link Node}, and {@link NodeStatus}.
 *
 * <p>It also reads what earlier versions wrote. The fields a node no longer has are skipped by the parser, a
 * removed status decodes as the status that says whether the node was in contact, and an entry that holds the
 * removed capabilities update decodes as nothing to apply.
 *
 * <p>Package-private utility class used by {@link ProtobufCommandCodec}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025
 */
final class NodeCodec {

    private NodeCodec() {
    }

    // ── Command ─────────────────────────────────────────────────

    static NodeCommandProto toProto(NodeCommand cmd) {
        NodeCommandProto.Builder builder = NodeCommandProto.newBuilder()
                .setName(cmd.name())
                .setTimestampEpochMs(cmd.timestamp().toEpochMilli());

        switch (cmd) {
            case NodeCommand.Register r -> {
                builder.setType(NodeCommandType.NODE_CMD_REGISTER);
                builder.setNode(toProto(r.node()));
            }
            case NodeCommand.Deregister ignored -> {
                builder.setType(NodeCommandType.NODE_CMD_DEREGISTER);
            }
            case NodeCommand.UpdateStatus u -> {
                builder.setType(NodeCommandType.NODE_CMD_UPDATE_STATUS);
                builder.setNewStatus(toProto(u.newStatus()));
                builder.setExpectedStatus(toProto(u.expectedStatus()));
            }
            case NodeCommand.Expire e -> {
                builder.setType(NodeCommandType.NODE_CMD_EXPIRE);
                builder.setExpectedLastContactEpochMs(e.expectedLastContact().toEpochMilli());
                builder.setReap(e.reap());
            }
            case NodeCommand.Heartbeat h -> {
                builder.setType(NodeCommandType.NODE_CMD_HEARTBEAT);
                builder.setSequenceNumber(h.sequenceNumber());
                if (h.registrationId() != null) builder.setRegistrationId(h.registrationId());
                if (h.status() != null) {
                    builder.setNewStatus(toProto(h.status()));
                }
            }
        }

        return builder.build();
    }

    /** Decodes a node command, or returns {@code null} for an entry that holds the removed capabilities update. */
    @SuppressWarnings("deprecation") // the removed command type is named here so that old entries are recognised
    static NodeCommand fromProto(NodeCommandProto proto) {
        // Decoding must never read the local clock: every replica and every replay must see one value.
        Instant timestamp = Instant.ofEpochMilli(proto.getTimestampEpochMs());
        NodeStatus newStatus = proto.getNewStatus() != NodeStatusProto.NODE_STATUS_UNSPECIFIED
                ? fromProto(proto.getNewStatus()) : null;
        return switch (proto.getType()) {
            case NODE_CMD_REGISTER -> new NodeCommand.Register(
                    proto.getName(), fromProto(proto.getNode()), timestamp);
            case NODE_CMD_DEREGISTER -> new NodeCommand.Deregister(
                    proto.getName(), timestamp);
            case NODE_CMD_UPDATE_STATUS -> new NodeCommand.UpdateStatus(
                    proto.getName(), fromProto(proto.getExpectedStatus()), newStatus, timestamp);
            // Qraft never issued this command, and a node no longer has capabilities: nothing to apply.
            case NODE_CMD_UPDATE_CAPABILITIES -> null;
            case NODE_CMD_HEARTBEAT -> new NodeCommand.Heartbeat(
                    proto.getName(), newStatus, timestamp, proto.getSequenceNumber(),
                    proto.getRegistrationId().isEmpty() ? null : proto.getRegistrationId());
            case NODE_CMD_EXPIRE -> new NodeCommand.Expire(proto.getName(),
                    Instant.ofEpochMilli(proto.getExpectedLastContactEpochMs()), proto.getReap(), timestamp);
            default -> throw new IllegalArgumentException("Unknown NodeCommandType: " + proto.getType());
        };
    }

    // ── Domain model ────────────────────────────────────────────

    private static NodeProto toProto(Node node) {
        NodeProto.Builder builder = NodeProto.newBuilder();
        Optional.ofNullable(node.name()).ifPresent(builder::setName);
        Optional.ofNullable(node.address()).ifPresent(builder::setAddress);
        Optional.ofNullable(node.status()).ifPresent(s -> builder.setStatus(toProto(s)));
        Optional.ofNullable(node.registrationTime()).ifPresent(t -> builder.setRegistrationTimeEpochMs(t.toEpochMilli()));
        Optional.ofNullable(node.lastHeartbeat()).ifPresent(t -> builder.setLastHeartbeatEpochMs(t.toEpochMilli()));
        Optional.ofNullable(node.region()).ifPresent(builder::setRegion);
        Optional.ofNullable(node.datacenter()).ifPresent(builder::setDatacenter);
        builder.putAllMetadata(node.metadata());
        return builder.build();
    }

    private static Node fromProto(NodeProto proto) {
        return new Node(
                proto.getName(),
                proto.hasAddress() ? proto.getAddress() : null,
                proto.hasDatacenter() ? proto.getDatacenter() : null,
                proto.hasRegion() ? proto.getRegion() : null,
                proto.getMetadataMap(),
                proto.getStatus() != NodeStatusProto.NODE_STATUS_UNSPECIFIED ? fromProto(proto.getStatus()) : null,
                // A decoded time comes from the entry only, never from the local clock.
                proto.getRegistrationTimeEpochMs() > 0
                        ? Instant.ofEpochMilli(proto.getRegistrationTimeEpochMs()) : null,
                proto.getLastHeartbeatEpochMs() > 0
                        ? Instant.ofEpochMilli(proto.getLastHeartbeatEpochMs()) : null);
    }

    // ── Enums ───────────────────────────────────────────────────

    private static NodeStatusProto toProto(NodeStatus status) {
        return switch (status) {
            case REGISTERING -> NodeStatusProto.NODE_STATUS_REGISTERING;
            case HEALTHY -> NodeStatusProto.NODE_STATUS_HEALTHY;
            case UNREACHABLE -> NodeStatusProto.NODE_STATUS_UNREACHABLE;
        };
    }

    @SuppressWarnings("deprecation") // the removed statuses are named here so that old entries decode
    private static NodeStatus fromProto(NodeStatusProto status) {
        return switch (status) {
            case NODE_STATUS_REGISTERING -> NodeStatus.REGISTERING;
            case NODE_STATUS_HEALTHY -> NodeStatus.HEALTHY;
            // Statuses Qraft never set, which replicated history written earlier may hold. A node that held
            // one of these was in contact.
            case NODE_STATUS_ACTIVE, NODE_STATUS_IDLE, NODE_STATUS_DEGRADED, NODE_STATUS_OVERLOADED,
                 NODE_STATUS_MAINTENANCE, NODE_STATUS_DRAINING -> NodeStatus.HEALTHY;
            case NODE_STATUS_UNREACHABLE -> NodeStatus.UNREACHABLE;
            case NODE_STATUS_FAILED, NODE_STATUS_DEREGISTERED -> NodeStatus.UNREACHABLE;
            default -> throw new IllegalArgumentException("Unknown NodeStatusProto: " + status);
        };
    }
}

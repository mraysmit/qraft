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
import dev.mars.qraft.raft.grpc.NodeStatusProto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link NodeCodec}: round trips of every node command, rejection of an unspecified type, the typed
 * command factories, and the decoding of the statuses and the command that earlier versions wrote and this
 * one never does.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 2.0
 */
@SuppressWarnings("deprecation") // removed protocol values are built here to show that old entries still decode
class NodeCodecTest {
    private static final Instant TIMESTAMP = Instant.parse("2026-02-03T04:05:06Z");

    @Test
    void roundTripsEveryNodeCommandVariant() {
        Node node = new Node("node-1", "10.0.0.1", "dc-1", "eu-west", Map.of("rack", "r7"),
                NodeStatus.HEALTHY, TIMESTAMP.minusSeconds(60), TIMESTAMP.minusSeconds(5));

        List<NodeCommand> commands = List.of(
                new NodeCommand.Register("node-1", node, TIMESTAMP),
                new NodeCommand.Deregister("node-1", TIMESTAMP),
                new NodeCommand.UpdateStatus("node-1", NodeStatus.REGISTERING, NodeStatus.HEALTHY, TIMESTAMP),
                new NodeCommand.Heartbeat("node-1", NodeStatus.HEALTHY, TIMESTAMP, 7, "reg-1"),
                new NodeCommand.Heartbeat("node-1", null, TIMESTAMP),
                new NodeCommand.Expire("node-1", TIMESTAMP.minusSeconds(5), true, TIMESTAMP));

        for (NodeCommand command : commands) {
            assertEquals(command, NodeCodec.fromProto(NodeCodec.toProto(command)), command.toString());
        }
    }

    @Test
    void aNodeWithOnlyANameRoundTripsWithItsAbsentFieldsAbsent() {
        Node bare = Node.of("node-bare", null, null, null, null);

        NodeCommand.Register decoded = assertInstanceOf(NodeCommand.Register.class,
                NodeCodec.fromProto(NodeCodec.toProto(new NodeCommand.Register("node-bare", bare, TIMESTAMP))));

        assertEquals(bare, decoded.node());
        assertNull(decoded.node().registrationTime(), "a decoded time never comes from the local clock");
    }

    @Test
    void rejectsUnspecifiedCommandType() {
        assertThrows(IllegalArgumentException.class, () -> NodeCodec.fromProto(NodeCommandProto.getDefaultInstance()));
    }

    @Test
    void factoriesProduceTypedCommands() {
        Node node = Node.of("node-1", "10.0.0.1", null, null, null);

        assertInstanceOf(NodeCommand.Register.class, NodeCommand.register(node));
        assertEquals("node-1", NodeCommand.register(node, TIMESTAMP).name());
        assertInstanceOf(NodeCommand.Deregister.class, NodeCommand.deregister("node-1"));
        assertInstanceOf(NodeCommand.UpdateStatus.class,
                NodeCommand.updateStatus("node-1", NodeStatus.REGISTERING, NodeStatus.HEALTHY));
        assertInstanceOf(NodeCommand.Heartbeat.class, NodeCommand.heartbeat("node-1"));
        assertEquals(new NodeCommand.Heartbeat("node-1", NodeStatus.HEALTHY, TIMESTAMP, 3, "reg-1"),
                NodeCommand.heartbeat("node-1", NodeStatus.HEALTHY, TIMESTAMP, 3, "reg-1"));
        assertEquals(new NodeCommand.Expire("node-1", TIMESTAMP, false, TIMESTAMP),
                NodeCommand.expire("node-1", TIMESTAMP, false, TIMESTAMP));
    }

    @Test
    void removedStatusesInReplicatedHistoryDecodeAsWhetherTheNodeWasInContact() {
        Map<NodeStatusProto, NodeStatus> removed = Map.of(
                NodeStatusProto.NODE_STATUS_ACTIVE, NodeStatus.HEALTHY,
                NodeStatusProto.NODE_STATUS_IDLE, NodeStatus.HEALTHY,
                NodeStatusProto.NODE_STATUS_DEGRADED, NodeStatus.HEALTHY,
                NodeStatusProto.NODE_STATUS_OVERLOADED, NodeStatus.HEALTHY,
                NodeStatusProto.NODE_STATUS_MAINTENANCE, NodeStatus.HEALTHY,
                NodeStatusProto.NODE_STATUS_DRAINING, NodeStatus.HEALTHY,
                NodeStatusProto.NODE_STATUS_FAILED, NodeStatus.UNREACHABLE,
                NodeStatusProto.NODE_STATUS_DEREGISTERED, NodeStatus.UNREACHABLE);

        removed.forEach((stored, expected) -> {
            NodeCommand.UpdateStatus decoded = assertInstanceOf(NodeCommand.UpdateStatus.class,
                    NodeCodec.fromProto(statusUpdate(NodeStatusProto.NODE_STATUS_HEALTHY, stored)));
            assertEquals(expected, decoded.newStatus(), stored.name());
        });
    }

    @Test
    void noCurrentStatusIsEncodedAsARemovedValue() {
        EnumSet<NodeStatusProto> written = EnumSet.noneOf(NodeStatusProto.class);
        for (NodeStatus status : NodeStatus.values()) {
            written.add(NodeCodec.toProto(new NodeCommand.UpdateStatus("node-1", status, status, TIMESTAMP))
                    .getNewStatus());
        }

        assertEquals(EnumSet.of(NodeStatusProto.NODE_STATUS_REGISTERING, NodeStatusProto.NODE_STATUS_HEALTHY,
                NodeStatusProto.NODE_STATUS_UNREACHABLE), written);
    }

    @Test
    void anEntryHoldingTheRemovedCapabilitiesUpdateDecodesToNothing() {
        NodeCommandProto stored = NodeCommandProto.newBuilder()
                .setType(NodeCommandType.NODE_CMD_UPDATE_CAPABILITIES)
                .setName("node-1")
                .setTimestampEpochMs(TIMESTAMP.toEpochMilli())
                .build();

        assertNull(NodeCodec.fromProto(stored));
        assertTrue(new QraftStateStore().apply(null) instanceof dev.mars.qraft.raft.RaftCommandResult.NoOp<?>,
                "and nothing is what the state store applies for it");
    }

    private static NodeCommandProto statusUpdate(NodeStatusProto expected, NodeStatusProto next) {
        return NodeCommandProto.newBuilder()
                .setType(NodeCommandType.NODE_CMD_UPDATE_STATUS)
                .setName("node-1")
                .setTimestampEpochMs(TIMESTAMP.toEpochMilli())
                .setExpectedStatus(expected)
                .setNewStatus(next)
                .build();
    }
}

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

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import dev.mars.qraft.raft.grpc.NodeCommandProto;
import dev.mars.qraft.raft.grpc.NodeCommandType;
import dev.mars.qraft.raft.grpc.RaftCommandMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that an entry holding a command this server does not know, as a newer server would write it, is refused
 * rather than read as a no-op, so that a server is never silently behind the replicas that applied it; that an empty
 * entry is still a no-op; and that an unknown field inside a known command is still tolerated, as the design's
 * compatibility rules allow (section 14.8).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
class UnknownCommandCodecTest {
    private final ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();

    @Test
    void anEntryHoldingACommandThisServerDoesNotKnowIsRefused() {
        byte[] fromANewerServer = withUnknownField(RaftCommandMessage.newBuilder(), 99).build().toByteArray();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> codec.deserialize(fromANewerServer));

        assertTrue(refused.getMessage().contains("field 99"), refused.getMessage());
    }

    @Test
    void anEmptyEntryIsStillANoOp() {
        assertNull(codec.deserialize(RaftCommandMessage.getDefaultInstance().toByteArray()));
        assertNull(codec.deserialize(codec.serialize(null)));
    }

    @Test
    void anUnknownFieldInsideAKnownCommandIsStillTolerated() {
        NodeCommandProto.Builder node = NodeCommandProto.newBuilder()
                .setType(NodeCommandType.NODE_CMD_DEREGISTER).setName("web-1");
        withUnknownField(node, 50);
        byte[] entry = RaftCommandMessage.newBuilder().setNodeCommand(node).build().toByteArray();

        NodeCommand decoded = assertInstanceOf(NodeCommand.class, codec.deserialize(entry));

        assertEquals("web-1", decoded.name());
    }

    private static <B extends com.google.protobuf.GeneratedMessageV3.Builder<B>> B withUnknownField(B builder,
                                                                                                   int number) {
        return builder.setUnknownFields(UnknownFieldSet.newBuilder().addField(number, UnknownFieldSet.Field
                .newBuilder().addLengthDelimited(ByteString.copyFromUtf8("from a newer server")).build()).build());
    }
}

package dev.mars.qraft.controller.state;

import dev.mars.qraft.raft.api.CommandCodec;
import com.google.protobuf.ByteString;

/**
 * Command codec adapter backed by the existing protobuf command serializer.
 */
public class ProtobufRaftCommandCodec implements CommandCodec<RaftCommand> {

    @Override
    public byte[] serialize(RaftCommand command) {
        return ProtobufCommandCodec.serialize(command).toByteArray();
    }

    @Override
    public RaftCommand deserialize(byte[] bytes) {
        return ProtobufCommandCodec.deserialize(ByteString.copyFrom(bytes));
    }
}

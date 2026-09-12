package dev.mars.qraft.controller.state;

import dev.mars.qraft.raft.api.CommandCodec;
import com.google.protobuf.ByteString;
import dev.mars.qraft.distributedstate.DistributedStateCommandCodec;

import java.util.Objects;

/**
 * Command codec adapter backed by the existing protobuf command serializer.
 */
public class ProtobufRaftCommandCodec implements CommandCodec<RaftCommand> {

    private final DistributedStateCommandCodec legacyDistributedStateCodec = new DistributedStateCommandCodec();

    @Override
    public byte[] serialize(RaftCommand command) {
        return ProtobufCommandCodec.serialize(command).toByteArray();
    }

    @Override
    public RaftCommand deserialize(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        // Releases before catalog replication encoded distributed-state WAL entries as JSON.
        // Retaining this read path allows an existing server to recover those logs during upgrade.
        if (bytes.length > 0 && bytes[0] == '{') {
            return new DistributedStateRaftCommand(legacyDistributedStateCodec.deserialize(bytes));
        }
        return ProtobufCommandCodec.deserialize(ByteString.copyFrom(bytes));
    }
}

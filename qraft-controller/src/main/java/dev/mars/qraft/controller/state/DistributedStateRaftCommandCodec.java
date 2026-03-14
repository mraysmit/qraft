package dev.mars.qraft.controller.state;

import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.distributedstate.DistributedStateCommandCodec;
import dev.mars.qraft.raft.api.CommandCodec;

/**
 * Adapter codec that bridges controller RaftCommand and distributed-state command codec.
 */
public class DistributedStateRaftCommandCodec implements CommandCodec<RaftCommand> {

    private final DistributedStateCommandCodec delegate = new DistributedStateCommandCodec();

    @Override
    public byte[] serialize(RaftCommand command) {
        if (!(command instanceof DistributedStateRaftCommand wrapped)) {
            throw new IllegalArgumentException("Unsupported command for distributed-state codec: " + command.getClass().getName());
        }
        return delegate.serialize(wrapped.delegate());
    }

    @Override
    public RaftCommand deserialize(byte[] bytes) {
        DistributedStateCommand command = delegate.deserialize(bytes);
        return new DistributedStateRaftCommand(command);
    }
}

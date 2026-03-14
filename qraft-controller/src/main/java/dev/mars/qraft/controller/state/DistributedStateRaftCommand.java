package dev.mars.qraft.controller.state;

import dev.mars.qraft.distributedstate.DistributedStateCommand;

import java.util.Objects;

/**
 * Controller-level Raft command wrapper for generic distributed-state commands.
 */
public record DistributedStateRaftCommand(DistributedStateCommand delegate) implements RaftCommand {

    public DistributedStateRaftCommand {
        Objects.requireNonNull(delegate, "delegate");
    }
}

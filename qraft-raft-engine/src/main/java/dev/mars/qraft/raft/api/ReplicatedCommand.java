package dev.mars.qraft.raft.api;

import java.io.Serializable;

/**
 * Marker interface for commands replicated through a Raft log.
 */
public interface ReplicatedCommand extends Serializable {
}

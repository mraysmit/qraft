package dev.mars.qraft.raft.api;

/**
 * Serializes and deserializes replicated commands for storage and transport.
 *
 * @param <C> command type
 */
public interface CommandCodec<C extends ReplicatedCommand> {

    byte[] serialize(C command);

    C deserialize(byte[] bytes);
}

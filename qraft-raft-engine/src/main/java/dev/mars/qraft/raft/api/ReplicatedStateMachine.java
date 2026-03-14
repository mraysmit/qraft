package dev.mars.qraft.raft.api;

/**
 * Generic state machine contract for commands committed through Raft.
 *
 * @param <C> command type
 * @param <R> apply result type
 */
public interface ReplicatedStateMachine<C extends ReplicatedCommand, R> {

    R apply(C command);

    byte[] takeSnapshot();

    void restoreSnapshot(byte[] snapshot);

    long getLastAppliedIndex();

    void setLastAppliedIndex(long index);

    void reset();
}

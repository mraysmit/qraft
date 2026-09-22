package dev.mars.qraft.controller.raft;

/**
 * Signals that a client command may have crossed a durability boundary but its
 * commitment cannot be confirmed to the caller.
 */
public final class CommandOutcomeUnknownException extends IllegalStateException {
    public CommandOutcomeUnknownException(String message) {
        super(message);
    }
}

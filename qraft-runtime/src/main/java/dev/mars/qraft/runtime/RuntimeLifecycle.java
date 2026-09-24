package dev.mars.qraft.runtime;

import java.util.concurrent.CompletableFuture;

/** The single completion and shutdown boundary for a launched Qraft mode. */
public interface RuntimeLifecycle extends AutoCloseable {
    CompletableFuture<Void> completion();

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}

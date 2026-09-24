package dev.mars.qraft.runtime;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

final class ManagedRuntimeLifecycle implements RuntimeLifecycle {
    private final Supplier<? extends CompletionStage<?>> shutdown;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private boolean closing;

    ManagedRuntimeLifecycle(Supplier<? extends CompletionStage<?>> shutdown) {
        this.shutdown = Objects.requireNonNull(shutdown, "shutdown");
    }

    @Override
    public CompletableFuture<Void> completion() {
        return completion;
    }

    @Override
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing) return completion;
        closing = true;
        try {
            CompletionStage<?> shutdownStage = Objects.requireNonNull(
                    shutdown.get(), "shutdown returned null");
            shutdownStage.whenComplete((ignored, failure) -> {
                if (failure == null) completion.complete(null);
                else completion.completeExceptionally(unwrap(failure));
            });
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
        }
        return completion;
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }
}

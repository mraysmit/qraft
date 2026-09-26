/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.mars.qraft.runtime;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * {@link RuntimeLifecycle} that runs a supplied shutdown action at most once and completes its
 * completion future with the shutdown result.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
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

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

package dev.mars.qraft.common.async;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A bounded virtual-thread executor for blocking operations. Work and its completion dispatch retain
 * the submitting thread's MDC and OpenTelemetry context.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-12
 * @version 1.0
 */
public final class BlockingTaskExecutor implements AutoCloseable {
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final ExecutorService executor;
    private final Executor completionExecutor;

    public BlockingTaskExecutor(String name, int poolSize, Executor completionExecutor) {
        if (poolSize <= 0) throw new IllegalArgumentException("poolSize must be greater than zero");
        this.completionExecutor = Objects.requireNonNull(completionExecutor, "completionExecutor");
        this.executor = Executors.newFixedThreadPool(poolSize,
                Thread.ofVirtual().name(name + "-", 0).factory());
    }

    public <T> CompletableFuture<T> execute(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> result = new CompletableFuture<>();
        CallerContext context = CallerContext.capture();
        try {
            executor.submit(() -> context.run(() -> {
                T value;
                try {
                    value = task.call();
                } catch (Throwable failure) {
                    dispatchCompletion(context, () -> result.completeExceptionally(failure), result);
                    return;
                }
                dispatchCompletion(context, () -> result.complete(value), result);
            }));
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private void dispatchCompletion(CallerContext context, Runnable completion, CompletableFuture<?> result) {
        try {
            completionExecutor.execute(() -> context.run(completion));
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException failure) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

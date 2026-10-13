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
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs blocking work with bounded parallelism and queue capacity. Work and completion dispatch retain
 * the caller's MDC and tracing context. The owner must shut this executor down before its completion
 * executor, which must execute accepted commands or reject them explicitly.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-12
 * @version 1.0
 */
public final class BlockingTaskExecutor implements AutoCloseable {
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final ThreadPoolExecutor executor;
    private final Executor completionExecutor;
    private final Set<PendingTask<?>> pending = ConcurrentHashMap.newKeySet();
    private CompletableFuture<Void> shutdown;
    private boolean forcedShutdown;
    private boolean shutdownOutcomeClaimed;

    public BlockingTaskExecutor(String name, int poolSize, Executor completionExecutor) {
        this(name, poolSize, 1000, completionExecutor);
    }

    public BlockingTaskExecutor(String name, int poolSize, int queueCapacity, Executor completionExecutor) {
        if (poolSize <= 0) throw new IllegalArgumentException("poolSize must be greater than zero");
        if (queueCapacity <= 0) throw new IllegalArgumentException("queueCapacity must be greater than zero");
        this.completionExecutor = Objects.requireNonNull(completionExecutor, "completionExecutor");
        this.executor = new ThreadPoolExecutor(poolSize, poolSize, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), Thread.ofVirtual().name(name + "-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Rejects overload and submissions after shutdown through a failed future; never runs work inline.
     * Task outcomes complete on the completion executor. Rejected dispatch or forced shutdown can
     * fail a future on the dispatching or shutdown thread, with its captured context. Cancelling the
     * returned future does not interrupt the submitted task.
     * As with any CompletableFuture, late callbacks can run on the registering thread.
     */
    public <T> CompletableFuture<T> execute(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        PendingTask<T> submitted = new PendingTask<>(task, CallerContext.capture());
        pending.add(submitted);
        submitted.result.whenComplete((value, failure) -> pending.remove(submitted));
        try {
            executor.execute(submitted);
        } catch (Throwable failure) {
            submitted.result.completeExceptionally(failure);
        }
        return submitted.result;
    }

    private final class PendingTask<T> implements Runnable {
        private final Callable<T> task;
        private final CallerContext context;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        private PendingTask(Callable<T> task, CallerContext context) {
            this.task = task;
            this.context = context;
        }

        @Override public void run() {
            context.run(() -> {
                if (result.isDone()) return;
                T value;
                try {
                    value = task.call();
                } catch (Throwable failure) {
                    finish(null, failure);
                    return;
                }
                finish(value, null);
            });
        }

        private void finish(T value, Throwable failure) {
            if (result.isDone()) return;
            try {
                Runnable completion = () -> context.run(() -> {
                    if (failure == null) result.complete(value);
                    else result.completeExceptionally(failure);
                });
                if (completionExecutor instanceof RaftStateLoop stateLoop) {
                    stateLoop.submit(completion).whenComplete((ignored, dispatchFailure) -> {
                        if (dispatchFailure != null) failDispatch(dispatchFailure, failure);
                    });
                } else {
                    completionExecutor.execute(completion);
                }
            } catch (Throwable dispatchFailure) {
                failDispatch(dispatchFailure, failure);
            }
        }

        private void failDispatch(Throwable dispatchFailure, Throwable taskFailure) {
            if (result.isDone()) return;
            if (taskFailure != null && taskFailure != dispatchFailure) dispatchFailure.addSuppressed(taskFailure);
            context.run(() -> result.completeExceptionally(dispatchFailure));
        }
    }

    /** Stops admission, drains work and delivered outcomes, and settles outstanding tasks before reporting failure. */
    public synchronized CompletableFuture<Void> shutdown() {
        if (shutdown != null) return shutdown;
        shutdown = new CompletableFuture<>();
        executor.shutdown();
        Thread.ofVirtual().name("qraft-blocking-shutdown").start(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_TIMEOUT_SECONDS);
            try {
                if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    forceShutdown(new TimeoutException("Blocking executor did not terminate within 5 seconds"));
                } else {
                    // A worker can terminate while its result is still queued on the state loop.
                    // Wait on derived outcomes, not a task future whose callbacks a waiting thread could run.
                    CompletableFuture<?>[] outcomes = pending.stream()
                            .map(task -> task.result.handle((value, failure) -> null))
                            .toArray(CompletableFuture[]::new);
                    CompletableFuture.allOf(outcomes).get(
                            Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    synchronized (this) {
                        if (forcedShutdown || shutdownOutcomeClaimed) return;
                        shutdownOutcomeClaimed = true;
                    }
                    shutdown.complete(null);
                }
            } catch (TimeoutException failure) {
                forceShutdown(new TimeoutException("Blocking executor outcomes did not settle within 5 seconds"));
            } catch (java.util.concurrent.ExecutionException failure) {
                forceShutdown(failure.getCause());
            } catch (InterruptedException failure) {
                forceShutdown(failure);
                Thread.currentThread().interrupt();
            }
        });
        return shutdown;
    }

    // Shared forced-stop path; package access lets lifecycle tests drive it without a clock delay.
    void forceShutdown(Throwable failure) {
        CompletableFuture<Void> stopped;
        synchronized (this) {
            if (shutdown == null) shutdown = new CompletableFuture<>();
            if (forcedShutdown || shutdownOutcomeClaimed) return;
            forcedShutdown = true;
            stopped = shutdown;
            executor.shutdownNow();
        }
        // Settle operations before notifying the owner, which can now close the completion loop.
        // Callbacks run outside the lifecycle lock and may request shutdown again.
        for (PendingTask<?> task : Set.copyOf(pending)) {
            task.context.run(() -> task.result.completeExceptionally(failure));
        }
        stopped.completeExceptionally(failure);
    }

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }

    /**
     * Waits for shutdown; throws CompletionException on failure. Owners call this off the worker and
     * completion threads; a callback on either thread uses the non-blocking {@link #shutdown()} instead.
     */
    @Override public void close() {
        try {
            shutdown().get();
        } catch (InterruptedException failure) {
            forceShutdown(failure);
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CompletionException(failure);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new java.util.concurrent.CompletionException(failure.getCause());
        }
    }
}

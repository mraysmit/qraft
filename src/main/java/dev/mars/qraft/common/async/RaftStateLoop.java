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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Owns the serialized Raft state thread and its timers, preserving MDC and tracing context.
 * Blocking work belongs to a separately owned BlockingTaskExecutor.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-13
 * @version 1.0
 */
public final class RaftStateLoop implements java.util.concurrent.Executor {
    private static final ThreadLocal<RaftStateLoop> CURRENT = new ThreadLocal<>();
    private final ScheduledExecutorService scheduler;
    private final AtomicLong timerIds = new AtomicLong();
    private final Map<Long, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<Void>> timerResults = new ConcurrentHashMap<>();
    private final Map<CompletableFuture<Void>, CallerContext> submissions = new ConcurrentHashMap<>();
    private CompletableFuture<Void> shutdown;

    private RaftStateLoop() {
        this(new java.util.concurrent.ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("qraft-state-loop").factory()));
    }

    /** Test seam: the supplied scheduler must serialize all tasks on exactly one thread. */
    RaftStateLoop(ScheduledExecutorService scheduler) {
        this.scheduler = java.util.Objects.requireNonNull(scheduler, "scheduler");
        if (scheduler instanceof java.util.concurrent.ScheduledThreadPoolExecutor pool) {
            pool.setRemoveOnCancelPolicy(true);
            pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            pool.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        }
    }

    public static RaftStateLoop create() { return new RaftStateLoop(); }
    public static RaftStateLoop currentContext() { return CURRENT.get(); }

    /** Queues a state transition with caller context; rejects synchronously once shutdown starts. */
    @Override public void execute(Runnable action) {
        java.util.Objects.requireNonNull(action, "action");
        CallerContext context = CallerContext.capture();
        scheduler.execute(() -> runInContext(context, action));
    }

    /**
     * Dispatches work with an observable outcome, including rejection or abandonment by a timed-out
     * shutdown. Cancellation skips work that has not started; it does not interrupt active work.
     * Normal completion runs on the state loop; forced-shutdown failure runs on its shutdown thread.
     */
    public CompletableFuture<Void> submit(Runnable action) {
        java.util.Objects.requireNonNull(action, "action");
        CompletableFuture<Void> result = new CompletableFuture<>();
        submissions.put(result, CallerContext.capture());
        result.whenComplete((value, failure) -> submissions.remove(result));
        try {
            execute(() -> {
                if (result.isDone()) return;
                try {
                    action.run();
                    result.complete(null);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    public synchronized long setTimer(long delayMs, Consumer<Long> action) {
        java.util.Objects.requireNonNull(action, "action");
        long id = timerIds.incrementAndGet();
        CallerContext context = CallerContext.capture();
        AtomicBoolean fired = new AtomicBoolean();
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            fired.set(true);
            timers.remove(id);
            runInContext(context, () -> action.accept(id));
        }, delayMs, TimeUnit.MILLISECONDS);
        timers.put(id, timer);
        // A short timer can fire before it is recorded; its own remove then ran first, so undo the record.
        if (fired.get()) timers.remove(id, timer);
        return id;
    }

    public synchronized long setPeriodic(long periodMs, Consumer<Long> action) {
        java.util.Objects.requireNonNull(action, "action");
        long id = timerIds.incrementAndGet();
        CallerContext context = CallerContext.capture();
        timers.put(id, scheduler.scheduleAtFixedRate(
                () -> runInContext(context, () -> action.accept(id)),
                periodMs, periodMs, TimeUnit.MILLISECONDS));
        return id;
    }

    /** Number of registered timers that have not fired or been cancelled; for lifecycle tests. */
    int pendingTimerCount() {
        return timers.size();
    }

    public synchronized boolean cancelTimer(long id) {
        ScheduledFuture<?> timer = timers.remove(id);
        boolean cancelled = timer != null && timer.cancel(false);
        CompletableFuture<Void> result = timerResults.remove(id);
        if (cancelled && result != null) result.cancel(false);
        return cancelled;
    }

    public synchronized CompletableFuture<Void> timer(long delayMs) {
        CompletableFuture<Void> promise = new CompletableFuture<>();
        try {
            long id = setTimer(delayMs, ignored -> promise.complete(null));
            timerResults.put(id, promise);
            promise.whenComplete((value, failure) -> {
                timerResults.remove(id, promise);
                if (promise.isCancelled()) cancelTimer(id);
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            promise.completeExceptionally(failure);
        }
        return promise;
    }

    /**
     * Stops admission, cancels timers and drains queued transitions. Completion waits for the state
     * thread to terminate; after five seconds it interrupts the thread and reports a failed shutdown.
     * May be requested from the state thread without blocking it. Owners stop blocking work first.
     */
    public synchronized CompletableFuture<Void> shutdown() {
        if (shutdown != null) return shutdown;
        shutdown = new CompletableFuture<>();
        scheduler.shutdown();
        timers.values().forEach(timer -> timer.cancel(false));
        timers.clear();
        timerResults.values().forEach(result -> result.cancel(false));
        timerResults.clear();
        Thread.ofVirtual().name("qraft-state-loop-shutdown").start(() -> {
            try {
                if (scheduler.awaitTermination(5, TimeUnit.SECONDS)) shutdown.complete(null);
                else {
                    failShutdown(new java.util.concurrent.TimeoutException(
                            "Raft state loop did not terminate within 5 seconds"));
                }
            } catch (InterruptedException failure) {
                failShutdown(failure);
                Thread.currentThread().interrupt();
            }
        });
        return shutdown;
    }

    private void failShutdown(Throwable failure) {
        scheduler.shutdownNow();
        Map.copyOf(submissions).forEach((result, context) -> context.run(() -> result.completeExceptionally(failure)));
        shutdown.completeExceptionally(failure);
    }

    public CompletableFuture<Void> close() { return shutdown(); }

    private void runInContext(CallerContext context, Runnable task) {
        RaftStateLoop previous = CURRENT.get();
        CURRENT.set(this);
        try {
            context.run(task);
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}

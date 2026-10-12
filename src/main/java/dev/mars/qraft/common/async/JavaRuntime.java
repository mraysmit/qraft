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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Single-threaded event loop with timers and virtual-thread blocking execution; propagates MDC and
 * telemetry context across dispatch.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
public final class JavaRuntime {
    private static final int DEFAULT_BLOCKING_PARALLELISM = 10;
    private static final ThreadLocal<JavaRuntime> CURRENT = new ThreadLocal<>();
    private final ScheduledExecutorService scheduler;
    private final BlockingTaskExecutor blockingTasks;
    private final AtomicLong timerIds = new AtomicLong();
    private final Map<Long, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    private JavaRuntime() {
        this(Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("qraft-state-loop").factory()));
    }

    /** Runtime whose state loop and timers run on {@code scheduler}, which must have exactly one thread. */
    JavaRuntime(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
        blockingTasks = new BlockingTaskExecutor(
                "qraft-worker", DEFAULT_BLOCKING_PARALLELISM, this::executeOnContext);
    }

    public static JavaRuntime create() { return new JavaRuntime(); }
    public static JavaRuntime currentContext() { return CURRENT.get(); }

    public void runOnContext(Consumer<Void> action) {
        executeOnContext(() -> action.accept(null));
    }

    private void executeOnContext(Runnable action) {
        CallerContext context = CallerContext.capture();
        scheduler.execute(() -> runInContext(context, action));
    }

    public long setTimer(long delayMs, Consumer<Long> action) {
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

    public long setPeriodic(long periodMs, Consumer<Long> action) {
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

    public boolean cancelTimer(long id) {
        ScheduledFuture<?> timer = timers.remove(id);
        return timer != null && timer.cancel(false);
    }

    /**
     * Runs {@code task} on a virtual thread and completes the returned future on this runtime's context, with
     * the caller's logging and tracing context. Its callbacks run there unless they are registered after it
     * completes or another thread blocked in {@code join} or {@code get} on it helps run them, as a
     * {@link CompletableFuture} allows.
     */
    public <T> CompletableFuture<T> executeBlocking(Callable<T> task) { return blockingTasks.execute(task); }

    public CompletableFuture<Void> timer(long delayMs) {
        CompletableFuture<Void> promise = new CompletableFuture<>();
        setTimer(delayMs, ignored -> promise.complete(null));
        return promise;
    }

    public CompletableFuture<Void> shutdown() {
        timers.values().forEach(timer -> timer.cancel(false));
        timers.clear();
        blockingTasks.close();
        scheduler.shutdown();
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> close() { return shutdown(); }

    private void runInContext(CallerContext context, Runnable task) {
        CURRENT.set(this);
        try {
            context.run(task);
        } finally {
            CURRENT.remove();
        }
    }
}

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

package dev.mars.qraft.controller.runtime;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
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
    private static final ThreadLocal<JavaRuntime> CURRENT = new ThreadLocal<>();
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workers;
    private final AtomicLong timerIds = new AtomicLong();
    private final Map<Long, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    private JavaRuntime() {
        this(Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("qraft-state-loop").factory()));
    }

    /** Runtime whose state loop and timers run on {@code scheduler}, which must have exactly one thread. */
    JavaRuntime(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
        workers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("qraft-worker-", 0).factory());
    }

    public static JavaRuntime create() { return new JavaRuntime(); }
    public static JavaRuntime currentContext() { return CURRENT.get(); }

    public void runOnContext(Consumer<Void> action) {
        CallerContext context = CallerContext.capture();
        scheduler.execute(() -> runInContext(context, () -> action.accept(null)));
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

    public <T> Future<T> executeBlocking(Callable<T> task) { return executeBlocking(workers, task); }
    public <T> Future<T> executeBlocking(Callable<T> task, boolean ordered) { return executeBlocking(task); }

    <T> Future<T> executeBlocking(ExecutorService executor, Callable<T> task) {
        Promise<T> promise = Promise.promise();
        CallerContext context = CallerContext.capture();
        executor.submit(() -> context.run(() -> {
            try {
                T value = task.call();
                runOnContext(ignored -> promise.complete(value));
            } catch (Throwable error) {
                runOnContext(ignored -> promise.fail(error));
            }
        }));
        return promise.future();
    }

    public WorkerExecutor createSharedWorkerExecutor(String name, int poolSize) {
        return new WorkerExecutor(this, name, poolSize);
    }

    public WorkerExecutor createSharedWorkerExecutor(String name, int poolSize, long maxExecuteTime) {
        return createSharedWorkerExecutor(name, poolSize);
    }

    public Future<Void> timer(long delayMs) {
        Promise<Void> promise = Promise.promise();
        setTimer(delayMs, ignored -> promise.complete());
        return promise.future();
    }

    public Future<Void> shutdown() {
        timers.values().forEach(timer -> timer.cancel(false));
        timers.clear();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException error) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        scheduler.shutdown();
        return Future.succeededFuture();
    }

    public Future<Void> close() { return shutdown(); }

    private void runInContext(CallerContext context, Runnable task) {
        CURRENT.set(this);
        try {
            context.run(task);
        } finally {
            CURRENT.remove();
        }
    }
}

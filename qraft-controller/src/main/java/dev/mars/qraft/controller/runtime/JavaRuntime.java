package dev.mars.qraft.controller.runtime;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class JavaRuntime {
    private static final ThreadLocal<JavaRuntime> CURRENT = new ThreadLocal<>();
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workers;
    private final AtomicLong timerIds = new AtomicLong();
    private final Map<Long, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    private JavaRuntime() {
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("qraft-state-loop").factory());
        workers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("qraft-worker-", 0).factory());
    }

    public static JavaRuntime create() { return new JavaRuntime(); }
    public static JavaRuntime currentContext() { return CURRENT.get(); }

    public void runOnContext(Consumer<Void> action) {
        Map<String, String> context = captureMdc();
        Context telemetryContext = Context.current();
        scheduler.execute(() -> runInContext(context, telemetryContext, () -> action.accept(null)));
    }

    public long setTimer(long delayMs, Consumer<Long> action) {
        long id = timerIds.incrementAndGet();
        Map<String, String> context = captureMdc();
        Context telemetryContext = Context.current();
        timers.put(id, scheduler.schedule(() -> {
            timers.remove(id);
            runInContext(context, telemetryContext, () -> action.accept(id));
        }, delayMs, TimeUnit.MILLISECONDS));
        return id;
    }

    public long setPeriodic(long periodMs, Consumer<Long> action) {
        long id = timerIds.incrementAndGet();
        Map<String, String> context = captureMdc();
        Context telemetryContext = Context.current();
        timers.put(id, scheduler.scheduleAtFixedRate(
                () -> runInContext(context, telemetryContext, () -> action.accept(id)),
                periodMs, periodMs, TimeUnit.MILLISECONDS));
        return id;
    }

    public boolean cancelTimer(long id) {
        ScheduledFuture<?> timer = timers.remove(id);
        return timer != null && timer.cancel(false);
    }

    public <T> Future<T> executeBlocking(Callable<T> task) { return executeBlocking(workers, task); }
    public <T> Future<T> executeBlocking(Callable<T> task, boolean ordered) { return executeBlocking(task); }

    <T> Future<T> executeBlocking(ExecutorService executor, Callable<T> task) {
        Promise<T> promise = Promise.promise();
        Map<String, String> context = captureMdc();
        Context telemetryContext = Context.current();
        executor.submit(() -> runWithContext(context, telemetryContext, () -> {
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

    private void runInContext(Map<String, String> context, Context telemetryContext, Runnable task) {
        CURRENT.set(this);
        try {
            runWithContext(context, telemetryContext, task);
        } finally {
            CURRENT.remove();
        }
    }

    private static Map<String, String> captureMdc() {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return context == null ? Map.of() : new HashMap<>(context);
    }

    private static void runWithContext(Map<String, String> context, Context telemetryContext, Runnable task) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try (Scope ignored = telemetryContext.makeCurrent()) {
            if (context.isEmpty()) MDC.clear();
            else MDC.setContextMap(context);
            task.run();
        } finally {
            if (previous == null || previous.isEmpty()) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }
}

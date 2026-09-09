package dev.mars.qraft.controller.runtime;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WorkerExecutor implements AutoCloseable {
    private final JavaRuntime runtime;
    private final ExecutorService executor;
    private final ExecutorService orderedExecutor;

    WorkerExecutor(JavaRuntime runtime, String name, int poolSize) {
        if (poolSize <= 0) throw new IllegalArgumentException("poolSize must be greater than zero");
        this.runtime = runtime;
        this.executor = Executors.newFixedThreadPool(poolSize,
                Thread.ofVirtual().name(name + "-", 0).factory());
        this.orderedExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name(name + "-ordered-", 0).factory());
    }

    public <T> Future<T> executeBlocking(Callable<T> task) {
        return executeBlocking(task, true);
    }

    public <T> Future<T> executeBlocking(Callable<T> task, boolean ordered) {
        return runtime.executeBlocking(ordered ? orderedExecutor : executor, task);
    }

    @Override
    public void close() {
        executor.shutdown();
        orderedExecutor.shutdown();
    }
}

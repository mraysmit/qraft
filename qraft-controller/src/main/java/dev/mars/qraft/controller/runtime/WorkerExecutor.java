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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Named virtual-thread worker pool for blocking tasks, bounded by pool size, with a single-threaded
 * lane for ordered execution.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
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

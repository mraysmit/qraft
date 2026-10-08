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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link WorkerExecutor} limits unordered concurrency to its pool size and runs ordered
 * tasks one at a time.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
class WorkerExecutorTest {

    @Test
    void configuredPoolSizeLimitsUnorderedConcurrency() throws Exception {
        assertEquals(2, maximumConcurrency(2, false), "two threads run two tasks at once, never more");
    }

    @Test
    void orderedExecutionRunsOneTaskAtATime() throws Exception {
        assertEquals(1, maximumConcurrency(4, true), "ordered tasks run one at a time despite four threads");
    }

    /**
     * Submits four tasks that each hold their thread until allowed to finish, then lets them finish one at a
     * time, each only once the expected number have started. A task can start only when a thread is free,
     * so the highest concurrency observed is exactly the executor's limit, with no sleep or time window.
     */
    private static int maximumConcurrency(int poolSize, boolean ordered) throws Exception {
        JavaRuntime runtime = JavaRuntime.create();
        WorkerExecutor executor = runtime.createSharedWorkerExecutor("concurrency-" + poolSize, poolSize);
        int limit = ordered ? 1 : poolSize;
        Semaphore finish = new Semaphore(0);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        List<Future<Void>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                tasks.add(executor.executeBlocking(() -> {
                    started.incrementAndGet();
                    maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                    try {
                        if (!finish.tryAcquire(30, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
                    } finally {
                        active.decrementAndGet();
                    }
                    return null;
                }, ordered));
            }
            for (int expected = limit; expected <= 4; expected++) {
                int target = expected;
                assertTrue(waitUntil(() -> started.get() >= target, 10, TimeUnit.SECONDS),
                        "task " + target + " starts once a thread is free");
                finish.release();
            }
            finish.release(4);
            awaitAll(tasks);
            assertEquals(4, started.get());
            return maximumActive.get();
        } finally {
            finish.release(8);
            executor.close();
            runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private static void awaitAll(List<Future<Void>> tasks) throws Exception {
        CompletableFuture<?>[] futures = tasks.stream()
                .map(task -> task.toCompletionStage().toCompletableFuture())
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }
}

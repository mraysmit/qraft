package dev.mars.qraft.controller.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerExecutorTest {

    @Test
    void configuredPoolSizeLimitsUnorderedConcurrency() throws Exception {
        JavaRuntime runtime = JavaRuntime.create();
        WorkerExecutor executor = runtime.createSharedWorkerExecutor("pool-limit", 2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        List<Future<Void>> tasks = new ArrayList<>();

        try {
            for (int i = 0; i < 4; i++) {
                tasks.add(executor.executeBlocking(() -> {
                    int current = active.incrementAndGet();
                    maximumActive.accumulateAndGet(current, Math::max);
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } finally {
                        active.decrementAndGet();
                    }
                    return null;
                }, false));
            }

            assertTrue(waitUntil(() -> maximumActive.get() >= 2, 1, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertEquals(2, maximumActive.get());
        } finally {
            release.countDown();
            awaitAll(tasks);
            executor.close();
            runtime.close();
        }
    }

    @Test
    void orderedExecutionRunsOneTaskAtATime() throws Exception {
        JavaRuntime runtime = JavaRuntime.create();
        WorkerExecutor executor = runtime.createSharedWorkerExecutor("ordered", 4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        List<Future<Void>> tasks = new ArrayList<>();

        try {
            for (int i = 0; i < 4; i++) {
                tasks.add(executor.executeBlocking(() -> {
                    started.incrementAndGet();
                    int current = active.incrementAndGet();
                    maximumActive.accumulateAndGet(current, Math::max);
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } finally {
                        active.decrementAndGet();
                    }
                    return null;
                }, true));
            }

            assertTrue(waitUntil(() -> started.get() >= 1, 1, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertEquals(1, started.get());
            assertEquals(1, maximumActive.get());
        } finally {
            release.countDown();
            awaitAll(tasks);
            executor.close();
            runtime.close();
        }
    }

    private static void awaitAll(List<Future<Void>> tasks) throws Exception {
        CompletableFuture<?>[] futures = tasks.stream()
                .map(task -> task.toCompletionStage().toCompletableFuture())
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);
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

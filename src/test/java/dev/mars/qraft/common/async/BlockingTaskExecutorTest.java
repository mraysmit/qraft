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
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests bounded virtual-thread execution and caller-context propagation. */
class BlockingTaskExecutorTest {

    @Test
    void configuredPoolSizeLimitsConcurrency() throws Exception {
        assertEquals(2, maximumConcurrency(2), "two threads run two tasks at once, never more");
    }

    @Test
    void taskAndCompletionKeepTheSubmittingContext() throws Exception {
        JavaRuntime runtime = JavaRuntime.create();
        BlockingTaskExecutor executor = new BlockingTaskExecutor(
                "context", 1, command -> runtime.runOnContext(ignored -> command.run()));
        try {
            MDC.put("requestId", "request-1");
            java.util.concurrent.CountDownLatch callbackRegistered = new java.util.concurrent.CountDownLatch(1);
            CompletableFuture<String> taskContext = executor.execute(() -> {
                if (!callbackRegistered.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the callback was never registered");
                }
                return MDC.get("requestId");
            });
            CompletableFuture<Boolean> completionContext = new CompletableFuture<>();
            taskContext.whenComplete((ignored, failure) -> completionContext.complete(
                    JavaRuntime.currentContext() == runtime && "request-1".equals(MDC.get("requestId"))));
            callbackRegistered.countDown();
            MDC.clear();

            assertTrue(completionContext.get(10, TimeUnit.SECONDS));
            assertEquals("request-1", taskContext.getNow(null));
        } finally {
            MDC.clear();
            executor.close();
            runtime.close().get(10, TimeUnit.SECONDS);
        }
    }

    private static int maximumConcurrency(int poolSize) throws Exception {
        JavaRuntime runtime = JavaRuntime.create();
        BlockingTaskExecutor executor = new BlockingTaskExecutor(
                "concurrency-" + poolSize, poolSize,
                command -> runtime.runOnContext(ignored -> command.run()));
        Semaphore finish = new Semaphore(0);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                tasks.add(executor.execute(() -> {
                    started.incrementAndGet();
                    maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                    try {
                        if (!finish.tryAcquire(30, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
                    } finally {
                        active.decrementAndGet();
                    }
                    return null;
                }));
            }
            for (int expected = poolSize; expected <= 4; expected++) {
                int target = expected;
                assertTrue(waitUntil(() -> started.get() >= target, 10, TimeUnit.SECONDS),
                        "task " + target + " starts once a thread is free");
                finish.release();
            }
            finish.release(4);
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
            assertEquals(4, started.get());
            return maximumActive.get();
        } finally {
            finish.release(8);
            executor.close();
            runtime.close().get(10, TimeUnit.SECONDS);
        }
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        return condition.getAsBoolean();
    }
}

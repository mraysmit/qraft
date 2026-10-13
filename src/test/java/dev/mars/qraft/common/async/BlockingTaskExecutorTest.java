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
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests bounded admission, shutdown outcomes and logging/tracing context on both dispatch paths.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-12
 * @version 1.0
 */
class BlockingTaskExecutorTest {
    private static final ContextKey<String> TRACE = ContextKey.named("executor-test-request");

    @Test
    void callerContextRestoresTheExecutingThreadsContextAfterSuccessAndFailure() {
        try {
            MDC.put("requestId", "captured");
            CallerContext captured;
            try (Scope ignored = Context.current().with(TRACE, "captured-trace").makeCurrent()) {
                captured = CallerContext.capture();
            }
            MDC.put("requestId", "original");
            try (Scope ignored = Context.current().with(TRACE, "original-trace").makeCurrent()) {
                Runnable check = () -> {
                    assertEquals("captured", MDC.get("requestId"));
                    assertEquals("captured-trace", Context.current().get(TRACE));
                };
                captured.run(check);
                assertEquals("original", MDC.get("requestId"));
                assertEquals("original-trace", Context.current().get(TRACE));
                IllegalStateException expected = new IllegalStateException("deliberate context failure");
                org.junit.jupiter.api.Assertions.assertSame(expected,
                        assertThrows(IllegalStateException.class, () -> captured.run(() -> {
                            check.run();
                            throw expected;
                        })));
                assertEquals("original", MDC.get("requestId"));
                assertEquals("original-trace", Context.current().get(TRACE));
            }
        } finally {
            MDC.clear();
        }
    }

    @Test
    void saturationRejectsInsteadOfRunningOnTheSubmittingThread() throws Exception {
        BlockingTaskExecutor executor = new BlockingTaskExecutor("bounded", 1, 1, Runnable::run);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            CompletableFuture<Void> active = executor.execute(() -> {
                started.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
                return null;
            });
            assertTrue(started.await(10, TimeUnit.SECONDS));
            CompletableFuture<Integer> queued = executor.execute(() -> 2);
            CompletableFuture<Integer> rejected = executor.execute(() -> {
                throw new AssertionError("a saturated pool must not run the task inline");
            });
            assertInstanceOf(java.util.concurrent.RejectedExecutionException.class,
                    assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> rejected.get(10, TimeUnit.SECONDS)).getCause());
            release.countDown();
            active.get(10, TimeUnit.SECONDS);
            assertEquals(2, queued.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.close();
        }
    }

    @Test
    void forcedShutdownSettlesActiveAndQueuedTasksAndReportsTheFailure() throws Exception {
        BlockingTaskExecutor executor = new BlockingTaskExecutor("shutdown", 1, 1, Runnable::run);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        CompletableFuture<Void> active = executor.execute(() -> {
            started.countDown();
            if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
            return null;
        });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            CompletableFuture<Integer> queued = executor.execute(() -> 2);
            CompletableFuture<Boolean> outcomesSettledAtShutdown = new CompletableFuture<>();
            executor.shutdown().whenComplete((value, failure) -> outcomesSettledAtShutdown.complete(
                    active.isDone() && queued.isDone()));
            // Drive the forced-stop branch explicitly, without waiting for a wall-clock timeout.
            executor.forceShutdown(new java.util.concurrent.TimeoutException("shutdown test"));
            assertTrue(outcomesSettledAtShutdown.get(10, TimeUnit.SECONDS),
                    "shutdown must settle accepted operations before publishing its own outcome");
            assertThrows(java.util.concurrent.ExecutionException.class, () -> active.get(10, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> queued.get(10, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> executor.shutdown().get(10, TimeUnit.SECONDS));
            assertTrue(executor.execute(() -> 3).isCompletedExceptionally());
        } finally {
            release.countDown();
            // The shutdown outcome remains failed; await actual worker termination for test isolation.
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void invalidBoundsAndMissingDependenciesFailBeforeOpeningWorkers() {
        for (int invalid : new int[]{0, -1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new BlockingTaskExecutor("invalid", invalid, 1, Runnable::run));
            assertThrows(IllegalArgumentException.class,
                    () -> new BlockingTaskExecutor("invalid", 1, invalid, Runnable::run));
        }
        assertThrows(NullPointerException.class, () -> new BlockingTaskExecutor("invalid", 1, 1, null));
        try (BlockingTaskExecutor executor = new BlockingTaskExecutor("null-task", 1, 1, Runnable::run)) {
            assertThrows(NullPointerException.class, () -> executor.execute(null));
        }
    }

    @Test
    void cancellingQueuedWorkPreventsItFromRunning() throws Exception {
        BlockingTaskExecutor executor = new BlockingTaskExecutor("cancel", 1, 1, Runnable::run);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean queuedRan = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            CompletableFuture<Void> active = executor.execute(() -> {
                started.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
                return null;
            });
            assertTrue(started.await(10, TimeUnit.SECONDS));
            CompletableFuture<Void> queued = executor.execute(() -> { queuedRan.set(true); return null; });
            assertTrue(queued.cancel(false));
            release.countDown();
            executor.shutdown().get(10, TimeUnit.SECONDS);
            active.get(10, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertFalse(queuedRan.get());
            assertTrue(queued.isCancelled());
        } finally {
            release.countDown();
            executor.close();
        }
    }

    @Test
    void configuredPoolSizeLimitsConcurrency() throws Exception {
        assertEquals(2, maximumConcurrency(2), "two threads run two tasks at once, never more");
    }

    @Test
    void taskAndCompletionKeepTheSubmittingContext() throws Exception {
        RaftStateLoop stateLoop = RaftStateLoop.create();
        BlockingTaskExecutor executor = new BlockingTaskExecutor(
                "context", 1, 1, stateLoop);
        try {
            MDC.put("requestId", "request-1");
            java.util.concurrent.CountDownLatch callbackRegistered = new java.util.concurrent.CountDownLatch(1);
            CompletableFuture<String> taskContext;
            try (Scope ignored = Context.current().with(TRACE, "trace-1").makeCurrent()) {
                taskContext = executor.execute(() -> {
                    if (!callbackRegistered.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the callback was never registered");
                    }
                    return MDC.get("requestId") + ":" + Context.current().get(TRACE);
                });
            }
            CompletableFuture<Boolean> completionContext = new CompletableFuture<>();
            taskContext.whenComplete((ignored, failure) -> completionContext.complete(
                    RaftStateLoop.currentContext() == stateLoop && "request-1".equals(MDC.get("requestId"))
                            && "trace-1".equals(Context.current().get(TRACE))));
            callbackRegistered.countDown();
            MDC.clear();

            assertTrue(completionContext.get(10, TimeUnit.SECONDS));
            assertEquals("request-1:trace-1", taskContext.getNow(null));
            assertEquals("null:null", executor.execute(() ->
                    MDC.get("requestId") + ":" + Context.current().get(TRACE)).get(10, TimeUnit.SECONDS));
        } finally {
            MDC.clear();
            executor.close();
            stateLoop.close().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void failedTaskKeepsItsCauseAndContextThroughCompletion() throws Exception {
        RaftStateLoop stateLoop = RaftStateLoop.create();
        BlockingTaskExecutor executor = new BlockingTaskExecutor("failure-context", 1, 1, stateLoop);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        IllegalStateException expected = new IllegalStateException("deliberate task failure");
        CompletableFuture<Boolean> taskContext = new CompletableFuture<>();
        CompletableFuture<Boolean> callbackContext = new CompletableFuture<>();
        try {
            CompletableFuture<Void> operation;
            MDC.put("requestId", "failure-request");
            try (Scope ignored = Context.current().with(TRACE, "failure-trace").makeCurrent()) {
                operation = executor.execute(() -> {
                    if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
                    taskContext.complete("failure-request".equals(MDC.get("requestId"))
                            && "failure-trace".equals(Context.current().get(TRACE)));
                    throw expected;
                });
            }
            operation.whenComplete((value, failure) -> callbackContext.complete(failure == expected
                    && RaftStateLoop.currentContext() == stateLoop
                    && "failure-request".equals(MDC.get("requestId"))
                    && "failure-trace".equals(Context.current().get(TRACE))));
            MDC.clear();
            release.countDown();
            assertTrue(callbackContext.get(10, TimeUnit.SECONDS));
            assertTrue(taskContext.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            MDC.clear();
            executor.close();
            stateLoop.close().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectedCompletionDispatchFailsTheFutureAndRetainsTheTaskFailure() throws Exception {
        java.util.concurrent.RejectedExecutionException rejection =
                new java.util.concurrent.RejectedExecutionException("completion executor stopped");
        IllegalStateException taskFailure = new IllegalStateException("deliberate task failure");
        try (BlockingTaskExecutor executor = new BlockingTaskExecutor("dispatch", 1, 1,
                command -> { throw rejection; })) {
            CompletableFuture<Void> result = executor.execute(() -> { throw taskFailure; });
            Throwable actual = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> result.get(10, TimeUnit.SECONDS)).getCause();
            org.junit.jupiter.api.Assertions.assertSame(rejection, actual);
            org.junit.jupiter.api.Assertions.assertArrayEquals(new Throwable[]{taskFailure}, actual.getSuppressed());
        }
    }

    @Test
    void gracefulShutdownDrainsAcceptedWorkAndRejectsNewSubmissions() throws Exception {
        BlockingTaskExecutor executor = new BlockingTaskExecutor("drain", 1, 1, Runnable::run);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            CompletableFuture<Void> active = executor.execute(() -> {
                started.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
                return null;
            });
            assertTrue(started.await(10, TimeUnit.SECONDS));
            CompletableFuture<Integer> queued = executor.execute(() -> 2);
            CompletableFuture<Void> stopped = executor.shutdown();
            org.junit.jupiter.api.Assertions.assertFalse(stopped.isDone());
            assertTrue(executor.execute(() -> 3).isCompletedExceptionally());
            release.countDown();
            stopped.get(10, TimeUnit.SECONDS);
            active.get(10, TimeUnit.SECONDS);
            assertEquals(2, queued.get(10, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertSame(stopped, executor.shutdown());
        } finally {
            release.countDown();
            executor.close();
        }
    }

    @Test
    void gracefulShutdownWaitsForTheAcceptedResultsCompletionDispatch() throws Exception {
        CompletableFuture<Runnable> queuedCompletion = new CompletableFuture<>();
        BlockingTaskExecutor executor = new BlockingTaskExecutor("completion-drain", 1, 1,
                command -> queuedCompletion.complete(command));
        Runnable deliver = null;
        try {
            CompletableFuture<Integer> operation = executor.execute(() -> 42);
            deliver = queuedCompletion.get(10, TimeUnit.SECONDS);
            CompletableFuture<Void> stopped = executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertFalse(operation.isDone());
            org.junit.jupiter.api.Assertions.assertFalse(stopped.isDone(),
                    "worker termination alone does not settle the operation's dispatched result");
            deliver.run();
            stopped.get(10, TimeUnit.SECONDS);
            assertEquals(42, operation.get(10, TimeUnit.SECONDS));
        } finally {
            if (deliver != null) deliver.run();
            executor.close();
        }
    }

    @Test
    void aStoppedStateLoopRejectsCompletionWithoutLosingTheTaskFailure() throws Exception {
        RaftStateLoop loop = RaftStateLoop.create();
        loop.shutdown().get(10, TimeUnit.SECONDS);
        IllegalStateException expected = new IllegalStateException("deliberate failure after loop stop");
        try (BlockingTaskExecutor executor = new BlockingTaskExecutor("stopped-loop", 1, 1, loop)) {
            CompletableFuture<Void> result = executor.execute(() -> { throw expected; });
            Throwable failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> result.get(10, TimeUnit.SECONDS)).getCause();
            assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, failure);
            org.junit.jupiter.api.Assertions.assertArrayEquals(new Throwable[]{expected}, failure.getSuppressed());
        }
    }

    private static int maximumConcurrency(int poolSize) throws Exception {
        RaftExecutorsFixture runtime = RaftExecutorsFixture.create();
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
                    maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                    started.incrementAndGet();
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

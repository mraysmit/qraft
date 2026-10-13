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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.mars.qraft.common.concurrent.Completions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests {@link RaftStateLoop} context serialization, bounded virtual-thread blocking execution, timers
 * (including one that fires before its registration is recorded), and MDC propagation.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-13
 * @version 1.0
 */
class RaftStateLoopTest {
    private RaftExecutorsFixture runtime;

    @BeforeEach
    void setUp() {
        runtime = RaftExecutorsFixture.create();
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.shutdown().get(10, TimeUnit.SECONDS);
    }

    @Test
    void shutdownWaitsForQueuedTransitionsAndCancelsOutstandingTimerFutures() throws Exception {
        RaftStateLoop loop = RaftStateLoop.create();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean queuedTransitionRan = new AtomicBoolean();
        try {
            CompletableFuture<Void> timer = loop.timer(60_000);
            loop.execute(() -> {
                entered.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("never released");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            loop.execute(() -> queuedTransitionRan.set(true));
            CompletableFuture<Void> stopped = loop.shutdown();
            assertFalse(stopped.isDone(), "the active state transition has not returned");
            assertTrue(timer.isCancelled());
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> loop.execute(() -> {}));
            assertTrue(loop.timer(1).isCompletedExceptionally());
            release.countDown();
            stopped.get(10, TimeUnit.SECONDS);
            assertTrue(queuedTransitionRan.get());
            assertSame(stopped, loop.shutdown());
        } finally {
            release.countDown();
            loop.shutdown().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void shutdownCanBeRequestedFromTheStateThread() throws Exception {
        RaftStateLoop loop = RaftStateLoop.create();
        CompletableFuture<CompletableFuture<Void>> requested = new CompletableFuture<>();
        try {
            loop.execute(() -> requested.complete(loop.shutdown()));
            requested.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
        } finally {
            loop.shutdown().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void timedOutShutdownFailsInsteadOfReportingSuccess() throws Exception {
        RaftStateLoop loop = new RaftStateLoop(new TimedOutShutdownSchedulerFixture());
        assertInstanceOf(java.util.concurrent.TimeoutException.class,
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> loop.shutdown().get(10, TimeUnit.SECONDS)).getCause());
    }

    @Test
    void timedOutShutdownPublishesItsFailureOnlyAfterSettlingQueuedSubmissions() throws Exception {
        TimedOutShutdownSchedulerFixture scheduler = new TimedOutShutdownSchedulerFixture(true);
        RaftStateLoop loop = new RaftStateLoop(scheduler);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            loop.execute(() -> {
                entered.countDown();
                try { release.await(30, TimeUnit.SECONDS); }
                catch (InterruptedException forcedStop) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            CompletableFuture<Void> queued = loop.submit(() -> {});
            CompletableFuture<Boolean> settledAtShutdown = new CompletableFuture<>();
            loop.shutdown().whenComplete((value, failure) -> settledAtShutdown.complete(queued.isDone()));
            scheduler.allowTimeout.countDown();
            assertTrue(settledAtShutdown.get(10, TimeUnit.SECONDS),
                    "shutdown must settle abandoned submissions before publishing its failure");
            assertInstanceOf(java.util.concurrent.TimeoutException.class,
                    assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> queued.get(10, TimeUnit.SECONDS)).getCause());
        } finally {
            release.countDown();
            scheduler.allowTimeout.countDown();
            loop.shutdown().handle((value, failure) -> null).get(10, TimeUnit.SECONDS);
            assertTrue(scheduler.awaitRealTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void submitRetainsTheOriginalFailureAndTheLoopContinuesAfterIt() throws Exception {
        RaftStateLoop loop = runtime.stateLoop();
        IllegalStateException expected = new IllegalStateException("deliberate state action failure");
        CompletableFuture<Void> failed = loop.submit(() -> { throw expected; });
        assertSame(expected, assertThrows(java.util.concurrent.ExecutionException.class,
                () -> failed.get(10, TimeUnit.SECONDS)).getCause());
        loop.submit(() -> assertSame(loop, RaftStateLoop.currentContext())).get(10, TimeUnit.SECONDS);
    }

    @Test
    void cancellingAQueuedSubmissionPreventsItsActionFromRunning() throws Exception {
        RaftStateLoop loop = runtime.stateLoop();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean ran = new AtomicBoolean();
        try {
            loop.execute(() -> {
                entered.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("never released");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            CompletableFuture<Void> cancelled = loop.submit(() -> ran.set(true));
            assertTrue(cancelled.cancel(false));
            release.countDown();
            loop.submit(() -> {}).get(10, TimeUnit.SECONDS);
            assertFalse(ran.get());
            assertTrue(cancelled.isCancelled());
        } finally {
            release.countDown();
        }
    }

    @Test
    void submitAfterShutdownFailsWithRejectionAndCancellingATimerRemovesIt() throws Exception {
        RaftStateLoop loop = runtime.stateLoop();
        CompletableFuture<Void> timer = loop.timer(60_000);
        assertEquals(1, loop.pendingTimerCount());
        assertTrue(timer.cancel(false));
        assertEquals(0, loop.pendingTimerCount());
        loop.shutdown().get(10, TimeUnit.SECONDS);
        assertInstanceOf(java.util.concurrent.RejectedExecutionException.class,
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> loop.submit(() -> {}).get(10, TimeUnit.SECONDS)).getCause());
        assertThrows(NullPointerException.class, () -> loop.submit(null));
    }

    @Test
    void timedOutLoopShutdownFailsABlockingResultWhoseCompletionWasQueued() throws Exception {
        TimedOutShutdownSchedulerFixture scheduler = new TimedOutShutdownSchedulerFixture();
        RaftStateLoop loop = new RaftStateLoop(scheduler);
        BlockingTaskExecutor workers = new BlockingTaskExecutor("abandoned-completion", 1, 1, loop);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            loop.execute(() -> {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException expectedOnForcedStop) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            CompletableFuture<Integer> operation = workers.execute(() -> 42);
            assertTrue(scheduler.secondDispatch.await(10, TimeUnit.SECONDS),
                    "the worker result is queued behind the blocked state transition");
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> loop.shutdown().get(10, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.TimeoutException.class,
                    assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> operation.get(10, TimeUnit.SECONDS)).getCause());
        } finally {
            release.countDown();
            workers.close();
            loop.shutdown();
            assertTrue(scheduler.awaitRealTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void timerKeepsTheSubmittingTracingContext() throws Exception {
        RaftStateLoop loop = new RaftStateLoop(new FiresBeforeScheduleReturnsFixture());
        io.opentelemetry.context.ContextKey<String> key =
                io.opentelemetry.context.ContextKey.named("state-loop-test");
        CompletableFuture<String> observed = new CompletableFuture<>();
        try {
            try (io.opentelemetry.context.Scope ignored = io.opentelemetry.context.Context.current()
                    .with(key, "timer-trace").makeCurrent()) {
                loop.setTimer(0, id -> observed.complete(io.opentelemetry.context.Context.current().get(key)));
            }
            assertEquals("timer-trace", observed.get(10, TimeUnit.SECONDS));
            CompletableFuture<String> restored = new CompletableFuture<>();
            loop.execute(() -> restored.complete(io.opentelemetry.context.Context.current().get(key)));
            assertNull(restored.get(10, TimeUnit.SECONDS));
        } finally {
            loop.shutdown().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void periodicTimerKeepsCallerContextAndCancellationRemovesItsSchedule() throws Exception {
        ManualPeriodicSchedulerFixture scheduler = new ManualPeriodicSchedulerFixture();
        RaftStateLoop loop = new RaftStateLoop(scheduler);
        io.opentelemetry.context.ContextKey<String> key =
                io.opentelemetry.context.ContextKey.named("periodic-loop-test");
        CompletableFuture<Boolean> observed = new CompletableFuture<>();
        try {
            MDC.put("requestId", "periodic-request");
            long timer;
            try (io.opentelemetry.context.Scope ignored = io.opentelemetry.context.Context.current()
                    .with(key, "periodic-trace").makeCurrent()) {
                timer = loop.setPeriodic(37, id -> observed.complete(
                        RaftStateLoop.currentContext() == loop
                                && "periodic-request".equals(MDC.get("requestId"))
                                && "periodic-trace".equals(io.opentelemetry.context.Context.current().get(key))));
            }
            MDC.put("requestId", "outside");
            scheduler.action.run();
            assertTrue(observed.get(10, TimeUnit.SECONDS));
            assertEquals("outside", MDC.get("requestId"));
            assertNull(io.opentelemetry.context.Context.current().get(key));
            assertNull(RaftStateLoop.currentContext());
            assertEquals(37, scheduler.initialDelayMs);
            assertEquals(37, scheduler.periodMs);
            assertTrue(loop.cancelTimer(timer));
            assertTrue(scheduler.handle.isCancelled());
            assertTrue(scheduler.getQueue().isEmpty());
            assertEquals(0, loop.pendingTimerCount());
        } finally {
            MDC.clear();
            loop.shutdown().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void runOnContextUsesSerializedRuntimeThread() throws Exception {
        CompletableFuture<String> observedThread = new CompletableFuture<>();

        runtime.runOnContext(ignored -> {
            assertSame(runtime.stateLoop(), RaftStateLoop.currentContext());
            observedThread.complete(Thread.currentThread().getName());
        });

        assertEquals("qraft-state-loop", observedThread.get(10, TimeUnit.SECONDS));
    }

    @Test
    void executeBlockingUsesVirtualThreadAndCompletesOnRuntimeContext() throws Exception {
        CompletableFuture<Boolean> callbackOnRuntime = new CompletableFuture<>();
        java.util.concurrent.CountDownLatch callbackRegistered = new java.util.concurrent.CountDownLatch(1);

        // The task waits until the callback is registered; otherwise a completion that wins the race runs
        // the late-registered callback inline on the test thread instead of on the runtime context.
        CompletableFuture<Boolean> operation = runtime.executeBlocking(() -> {
            if (!callbackRegistered.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the callback was never registered");
            }
            return Thread.currentThread().isVirtual();
        });
        operation.whenComplete(Completions.onSuccess(ignored -> callbackOnRuntime.complete(RaftStateLoop.currentContext() == runtime.stateLoop())));
        callbackRegistered.countDown();

        // Wait on the callback, never on the operation: a thread blocked in get() on the operation's future
        // helps complete it and may run the callback itself (reproduced 2 in 2,000 runs).
        assertTrue(callbackOnRuntime.get(10, TimeUnit.SECONDS));
        assertTrue(operation.getNow(false));
    }

    @Test
    void timerCompletesAfterDelay() throws Exception {
        long started = System.nanoTime();

        runtime.timer(30).get(10, TimeUnit.SECONDS);

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 20);
    }

    @Test
    void aTimerThatFiresBeforeItsRegistrationIsRecordedLeavesNoRegistrationBehind() throws Exception {
        RaftExecutorsFixture racing = new RaftExecutorsFixture(new FiresBeforeScheduleReturnsFixture());
        try {
            java.util.concurrent.atomic.AtomicInteger fired = new java.util.concurrent.atomic.AtomicInteger();

            racing.setTimer(0, ignored -> fired.incrementAndGet());

            assertEquals(1, fired.get(), "the scheduler ran the timer before setTimer could record it");
            assertEquals(0, racing.pendingTimerCount(),
                    "a timer that fires before its registration is recorded must not leave a stale entry");
        } finally {
            racing.shutdown().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void cancelledTimerIsRemovedFromTheSchedulerSoItCanNeverRun() throws Exception {
        java.util.concurrent.ScheduledThreadPoolExecutor scheduler = new java.util.concurrent.ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        RaftExecutorsFixture timed = new RaftExecutorsFixture(scheduler);
        try {
            AtomicBoolean invoked = new AtomicBoolean();
            long timerId = timed.setTimer(60_000, ignored -> invoked.set(true));
            assertEquals(1, scheduler.getQueue().size());

            assertTrue(timed.cancelTimer(timerId));

            assertEquals(0, scheduler.getQueue().size(), "a cancelled timer leaves nothing that could still run");
            assertEquals(0, timed.pendingTimerCount());
            assertFalse(invoked.get());
            assertFalse(timed.cancelTimer(timerId), "a timer is cancelled at most once");
        } finally {
            timed.shutdown().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void blockingTaskRunsOnNamedVirtualThread() throws Exception {
        String threadName = runtime.executeBlocking(() -> Thread.currentThread().getName())
                .get(10, TimeUnit.SECONDS);

        assertTrue(threadName.startsWith("qraft-worker-"));
    }

    @Test
    void propagatesAndRestoresMdcAcrossRuntimeDispatch() throws Exception {
        MDC.put("nodeId", "node-a");
        CompletableFuture<String> first = new CompletableFuture<>();
        runtime.runOnContext(ignored -> first.complete(MDC.get("nodeId")));
        MDC.clear();

        assertEquals("node-a", first.get(10, TimeUnit.SECONDS));

        CompletableFuture<String> second = new CompletableFuture<>();
        runtime.runOnContext(ignored -> second.complete(MDC.get("nodeId")));
        assertNull(second.get(10, TimeUnit.SECONDS));
    }

    @Test
    void propagatesMdcThroughVirtualThreadCompletion() throws Exception {
        MDC.put("requestId", "request-1");
        CompletableFuture<String> operation = runtime.executeBlocking(() -> MDC.get("requestId"));
        MDC.clear();

        assertEquals("request-1", operation.get(10, TimeUnit.SECONDS));
    }

    /**
     * Test executor fixture that runs a scheduled task before returning its handle to exercise the scheduling race.
     *
     * <p>Runs each scheduled task to completion before {@code schedule} returns, the widest timer race.
     */
    private static final class FiresBeforeScheduleReturnsFixture extends java.util.concurrent.ScheduledThreadPoolExecutor {
        FiresBeforeScheduleReturnsFixture() {
            super(1);
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            java.util.concurrent.ScheduledFuture<?> scheduled = super.schedule(command, 0, TimeUnit.NANOSECONDS);
            try {
                scheduled.get(10, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError("the scheduled task did not complete", failure);
            }
            return scheduled;
        }
    }

    /** Test scheduler fixture driving the shutdown timeout branch without a wall-clock delay. */
    private static final class TimedOutShutdownSchedulerFixture extends java.util.concurrent.ScheduledThreadPoolExecutor {
        private final java.util.concurrent.atomic.AtomicInteger dispatches = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.CountDownLatch secondDispatch = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch allowTimeout;
        TimedOutShutdownSchedulerFixture() { this(false); }
        TimedOutShutdownSchedulerFixture(boolean gated) {
            super(1);
            allowTimeout = new java.util.concurrent.CountDownLatch(gated ? 1 : 0);
        }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            if (!allowTimeout.await(30, TimeUnit.SECONDS)) throw new InterruptedException("timeout gate never released");
            return false;
        }
        @Override public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            java.util.concurrent.ScheduledFuture<?> scheduled = super.schedule(command, delay, unit);
            if (dispatches.incrementAndGet() == 2) secondDispatch.countDown();
            return scheduled;
        }
        boolean awaitRealTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return super.awaitTermination(timeout, unit);
        }
    }

    /** Test scheduler fixture exposing a periodic callback for explicit firing, with a real cancellable handle. */
    private static final class ManualPeriodicSchedulerFixture extends java.util.concurrent.ScheduledThreadPoolExecutor {
        private Runnable action;
        private long initialDelayMs;
        private long periodMs;
        private java.util.concurrent.ScheduledFuture<?> handle;
        ManualPeriodicSchedulerFixture() { super(1); }
        @Override public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            action = command;
            initialDelayMs = unit.toMillis(initialDelay);
            periodMs = unit.toMillis(period);
            // The test fires action itself. The real schedule only supplies cancellation and queue behavior.
            handle = super.scheduleAtFixedRate(command, 60_000, 60_000, TimeUnit.MILLISECONDS);
            return handle;
        }
    }
}

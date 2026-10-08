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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link JavaRuntime} context serialization, virtual-thread blocking execution, timers
 * (including one that fires before its registration is recorded), worker executors, and MDC propagation.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
class JavaRuntimeTest {
    private JavaRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.shutdown().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void runOnContextUsesSerializedRuntimeThread() throws Exception {
        CompletableFuture<String> observedThread = new CompletableFuture<>();

        runtime.runOnContext(ignored -> {
            assertSame(runtime, JavaRuntime.currentContext());
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
        Future<Boolean> operation = runtime.executeBlocking(() -> {
            if (!callbackRegistered.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the callback was never registered");
            }
            return Thread.currentThread().isVirtual();
        });
        operation.onSuccess(ignored -> callbackOnRuntime.complete(JavaRuntime.currentContext() == runtime));
        callbackRegistered.countDown();

        // Wait on the callback, never on the operation: a thread blocked in get() on the operation's future
        // helps complete it and may run the callback itself (reproduced 2 in 2,000 runs).
        assertTrue(callbackOnRuntime.get(10, TimeUnit.SECONDS));
        assertTrue(operation.toCompletionStage().toCompletableFuture().getNow(false));
    }

    @Test
    void timerCompletesAfterDelay() throws Exception {
        long started = System.nanoTime();

        runtime.timer(30).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 20);
    }

    @Test
    void aTimerThatFiresBeforeItsRegistrationIsRecordedLeavesNoRegistrationBehind() throws Exception {
        JavaRuntime racing = new JavaRuntime(new FiresBeforeScheduleReturnsFixture());
        try {
            java.util.concurrent.atomic.AtomicInteger fired = new java.util.concurrent.atomic.AtomicInteger();

            racing.setTimer(0, ignored -> fired.incrementAndGet());

            assertEquals(1, fired.get(), "the scheduler ran the timer before setTimer could record it");
            assertEquals(0, racing.pendingTimerCount(),
                    "a timer that fires before its registration is recorded must not leave a stale entry");
        } finally {
            racing.shutdown().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void cancelledTimerIsRemovedFromTheSchedulerSoItCanNeverRun() throws Exception {
        java.util.concurrent.ScheduledThreadPoolExecutor scheduler = new java.util.concurrent.ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        JavaRuntime timed = new JavaRuntime(scheduler);
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
            timed.shutdown().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void workerExecutorRunsBlockingTask() throws Exception {
        WorkerExecutor worker = runtime.createSharedWorkerExecutor("storage-test", 1);
        try {
            String threadName = worker.executeBlocking(() -> Thread.currentThread().getName())
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(threadName.startsWith("storage-test-"));
        } finally {
            worker.close();
        }
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
        Future<String> operation = runtime.executeBlocking(() -> MDC.get("requestId"));
        MDC.clear();

        assertEquals("request-1", operation.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
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
}

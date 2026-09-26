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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link JavaRuntime} context serialization, virtual-thread blocking execution, timers,
 * worker executors, and MDC propagation.
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
        runtime.shutdown().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void runOnContextUsesSerializedRuntimeThread() throws Exception {
        CompletableFuture<String> observedThread = new CompletableFuture<>();

        runtime.runOnContext(ignored -> {
            assertSame(runtime, JavaRuntime.currentContext());
            observedThread.complete(Thread.currentThread().getName());
        });

        assertEquals("qraft-state-loop", observedThread.get(2, TimeUnit.SECONDS));
    }

    @Test
    void executeBlockingUsesVirtualThreadAndCompletesOnRuntimeContext() throws Exception {
        CompletableFuture<Boolean> callbackOnRuntime = new CompletableFuture<>();

        Future<Boolean> operation = runtime.executeBlocking(() -> Thread.currentThread().isVirtual())
                .onSuccess(ignored -> callbackOnRuntime.complete(JavaRuntime.currentContext() == runtime));

        assertTrue(operation.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertTrue(callbackOnRuntime.get(2, TimeUnit.SECONDS));
    }

    @Test
    void timerCompletesAfterDelay() throws Exception {
        long started = System.nanoTime();

        runtime.timer(30).toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 20);
    }

    @Test
    void cancelledTimerDoesNotRun() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        long timerId = runtime.setTimer(30, ignored -> invoked.set(true));

        assertTrue(runtime.cancelTimer(timerId));
        Thread.sleep(80);

        assertFalse(invoked.get());
    }

    @Test
    void workerExecutorRunsBlockingTask() throws Exception {
        WorkerExecutor worker = runtime.createSharedWorkerExecutor("storage-test", 1);
        try {
            String threadName = worker.executeBlocking(() -> Thread.currentThread().getName())
                    .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
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

        assertEquals("node-a", first.get(2, TimeUnit.SECONDS));

        CompletableFuture<String> second = new CompletableFuture<>();
        runtime.runOnContext(ignored -> second.complete(MDC.get("nodeId")));
        assertNull(second.get(2, TimeUnit.SECONDS));
    }

    @Test
    void propagatesMdcThroughVirtualThreadCompletion() throws Exception {
        MDC.put("requestId", "request-1");
        Future<String> operation = runtime.executeBlocking(() -> MDC.get("requestId"));
        MDC.clear();

        assertEquals("request-1", operation.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS));
    }
}

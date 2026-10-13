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

package dev.mars.qraft.server.lifecycle;

import dev.mars.qraft.common.async.RaftStateLoop;
import dev.mars.qraft.testing.fault.InjectedFaultFixture;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.BEST_EFFORT_SHUTDOWN_HOOK_TIMEOUT;
import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.CRITICAL_SHUTDOWN_HOOK_TIMEOUT;
import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.SHUTDOWN_HOOK_FAILURE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ShutdownCoordinator} graceful shutdown functionality.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-01-30
 * @version 1.1
 */
@DisplayName("ShutdownCoordinator Tests")
class ShutdownCoordinatorTest {
    private RaftStateLoop stateLoop;

    @BeforeEach
    void setUp() {
        stateLoop = RaftStateLoop.create();
    }

    @AfterEach
    void tearDown() throws Exception {
        stateLoop.shutdown().get(10, TimeUnit.SECONDS);
    }

    @Nested
    @DisplayName("State Management")
    class StateManagementTests {

        @Test
        @DisplayName("Should start in RUNNING state")
        void shouldStartInRunningState() {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(stateLoop);

            assertEquals(ShutdownCoordinator.State.RUNNING, coordinator.getState());
            assertTrue(coordinator.isAcceptingWork());
            assertFalse(coordinator.isShutdownRequested());
        }

        @Test
        @DisplayName("Should transition to DRAINING on shutdown")
        void shouldTransitionToDrainingOnShutdown() throws Exception {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(stateLoop, 30_000, 30_000);
            CompletableFuture<Void> drain = new CompletableFuture<>();
            coordinator.onDrain("held-drain", () -> drain);

            CompletableFuture<Void> shutdown = coordinator.shutdown();

            assertEquals(ShutdownCoordinator.State.DRAINING, coordinator.getState());
            assertFalse(coordinator.isAcceptingWork());
            assertTrue(coordinator.isShutdownRequested());
            assertFalse(shutdown.isDone());

            drain.complete(null);
            shutdown.get(10, TimeUnit.SECONDS);

            assertEquals(ShutdownCoordinator.State.STOPPED, coordinator.getState());
            assertFalse(coordinator.isAcceptingWork());
            assertTrue(coordinator.isShutdownRequested());
        }

        @Test
        @DisplayName("Should reject work after shutdown requested")
        void shouldRejectWorkAfterShutdownRequested() throws Exception {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(stateLoop);

            // Initiate shutdown (don't wait for completion)
            CompletableFuture<Void> shutdown = coordinator.shutdown();

            // Should immediately stop accepting work
            assertTrue(coordinator.isShutdownRequested());
            assertFalse(coordinator.isAcceptingWork());
            shutdown.get(10, TimeUnit.SECONDS);
        }
    }

    @Nested
    @DisplayName("Hook Execution")
    class HookExecutionTests {

        @Test
        @DisplayName("Should execute drain hooks in order")
        void shouldExecuteDrainHooksInOrder() throws Exception {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(stateLoop);
            List<String> executionOrder = new ArrayList<>();

            coordinator.onDrain("drain-1", () -> {
                executionOrder.add("drain-1");
                return CompletableFuture.completedFuture(null);
            });
            coordinator.onDrain("drain-2", () -> {
                executionOrder.add("drain-2");
                return CompletableFuture.completedFuture(null);
            });

            coordinator.shutdown().get(10, TimeUnit.SECONDS);

            assertEquals(List.of("drain-1", "drain-2"), executionOrder);
        }

        @Test
        @DisplayName("Should execute all phases in order")
        void shouldExecuteAllPhasesInOrder() throws Exception {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(stateLoop);
            List<String> executionOrder = new ArrayList<>();

            coordinator.onDrain("drain", () -> {
                executionOrder.add("DRAIN");
                return CompletableFuture.completedFuture(null);
            });
            coordinator.onAwaitCompletion("await", () -> {
                executionOrder.add("AWAIT_COMPLETION");
                return CompletableFuture.completedFuture(null);
            });
            coordinator.onServiceStop("stop", () -> {
                executionOrder.add("STOP_SERVICES");
                return CompletableFuture.completedFuture(null);
            });
            coordinator.onResourceClose("close", () -> {
                executionOrder.add("CLOSE_RESOURCES");
                return CompletableFuture.completedFuture(null);
            });

            coordinator.shutdown().get(10, TimeUnit.SECONDS);

            assertEquals(
                    List.of("DRAIN", "AWAIT_COMPLETION", "STOP_SERVICES", "CLOSE_RESOURCES"),
                    executionOrder
            );
        }

        @Test
        @DisplayName("Should continue on hook failure")
        void shouldContinueOnHookFailure() throws Exception {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(stateLoop);
            AtomicInteger callCount = new AtomicInteger(0);

            coordinator.onDrain("failing", () -> {
                callCount.incrementAndGet();
                return CompletableFuture.failedFuture(new InjectedFaultFixture(SHUTDOWN_HOOK_FAILURE, "Simulated failure"));
            });
            coordinator.onDrain("succeeding", () -> {
                callCount.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            coordinator.shutdown().get(10, TimeUnit.SECONDS);

            // Both hooks should be called despite first failure
            assertEquals(2, callCount.get());
            assertEquals(ShutdownCoordinator.State.STOPPED, coordinator.getState());
        }
    }

    @Nested
    @DisplayName("Idempotent Shutdown")
    class IdempotentShutdownTests {

        @Test
        @DisplayName("Should be idempotent - multiple calls return same result")
        void shouldBeIdempotent() throws Exception {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(stateLoop);
            AtomicInteger drainCallCount = new AtomicInteger(0);

            coordinator.onDrain("counter", () -> {
                drainCallCount.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            // Call shutdown multiple times
            CompletableFuture<Void> first = coordinator.shutdown();
            CompletableFuture<Void> second = coordinator.shutdown();
            CompletableFuture<Void> third = coordinator.shutdown();

            assertSame(first, second);
            assertSame(first, third);

            first.get(10, TimeUnit.SECONDS);

            // Drain hook should only be called once
            assertEquals(1, drainCallCount.get());
        }
    }

    @Nested
    @DisplayName("Timeout Handling")
    class TimeoutHandlingTests {

        @Test
        @DisplayName("Should timeout slow hooks")
        void shouldTimeoutSlowHooks() throws Exception {
            IntentionalErrorsHelper.expect(BEST_EFFORT_SHUTDOWN_HOOK_TIMEOUT, 1);
            // Very short timeout
            ShutdownCoordinator coordinator = new ShutdownCoordinator(stateLoop, 50, 50);
            AtomicInteger completedCount = new AtomicInteger(0);

            // Slow hook that won't complete in time
            coordinator.onDrain("slow", CompletableFuture::new);

            // Fast hook that should still run
            coordinator.onDrain("fast", () -> {
                completedCount.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            coordinator.shutdown().get(10, TimeUnit.SECONDS);

            // Should complete despite slow hook timing out
            assertEquals(ShutdownCoordinator.State.STOPPED, coordinator.getState());
            // Fast hook should still have run
            assertEquals(1, completedCount.get());
        }

        @Test
        @DisplayName("Timing out a hook must not complete its source operation")
        void timeoutDoesNotMutateSourceHookFuture() throws Exception {
            IntentionalErrorsHelper.expect(BEST_EFFORT_SHUTDOWN_HOOK_TIMEOUT, 1);
            ShutdownCoordinator coordinator = new ShutdownCoordinator(stateLoop, 50, 50);
            CompletableFuture<Void> sourceOperation = new CompletableFuture<>();
            AtomicInteger laterHooks = new AtomicInteger();

            coordinator.onDrain("slow", () -> sourceOperation);
            coordinator.onDrain("later", () -> {
                laterHooks.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            coordinator.shutdown().get(10, TimeUnit.SECONDS);

            assertEquals(ShutdownCoordinator.State.STOPPED, coordinator.getState());
            assertEquals(1, laterHooks.get());
            assertFalse(sourceOperation.isDone(),
                    "coordinator timeout must not corrupt the hook's shared completion state");
            sourceOperation.complete(null);
            assertTrue(sourceOperation.isDone() && !sourceOperation.isCompletedExceptionally());
        }

        @Test
        @DisplayName("A critical service timeout must fail shutdown without closing later resources")
        void criticalServiceTimeoutStopsShutdownProgression() {
            IntentionalErrorsHelper.expect(CRITICAL_SHUTDOWN_HOOK_TIMEOUT, 2);
            ShutdownCoordinator coordinator = new ShutdownCoordinator(stateLoop, 50, 50);
            CompletableFuture<Void> nodeStop = new CompletableFuture<>();
            AtomicInteger laterServiceStops = new AtomicInteger();
            AtomicInteger resourceCloses = new AtomicInteger();

            coordinator.onCriticalServiceStop("node-stop", () -> nodeStop);
            coordinator.onServiceStop("later-service", () -> {
                laterServiceStops.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            coordinator.onResourceClose("storage", () -> {
                resourceCloses.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            CompletableFuture<Void> shutdown = coordinator.shutdown();
            assertSame(shutdown, coordinator.shutdown(),
                    "all callers must observe the same critical shutdown outcome");
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> shutdown.get(10, TimeUnit.SECONDS));

            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertEquals(ShutdownCoordinator.State.FAILED, coordinator.getState());
            assertEquals(0, laterServiceStops.get());
            assertEquals(0, resourceCloses.get(),
                    "resource closure is unsafe while the node may still own persistence work");
            assertFalse(nodeStop.isDone());
            nodeStop.complete(null);
        }

        @Test
        @DisplayName("A synchronously throwing critical hook must become the shared shutdown failure")
        void synchronousCriticalHookFailureIsReportedAsynchronously() {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(stateLoop);
            InjectedFaultFixture failure = new InjectedFaultFixture(
                    SHUTDOWN_HOOK_FAILURE, "node stop failed before returning");

            coordinator.onCriticalServiceStop("node-stop", () -> {
                throw failure;
            });

            CompletableFuture<Void> shutdown = assertDoesNotThrow(coordinator::shutdown);
            ExecutionException outcome = assertThrows(ExecutionException.class,
                    () -> shutdown.get(10, TimeUnit.SECONDS));

            assertSame(failure, outcome.getCause());
            assertEquals(ShutdownCoordinator.State.FAILED, coordinator.getState());
            assertSame(shutdown, coordinator.shutdown());
        }
    }

    @Nested
    @DisplayName("Shutdown With No Hooks")
    class NoHooksTests {

        @Test
        @DisplayName("Should complete successfully with no hooks registered")
        void shouldCompleteWithNoHooks() throws Exception {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(stateLoop);

            coordinator.shutdown().get(10, TimeUnit.SECONDS);

            assertEquals(ShutdownCoordinator.State.STOPPED, coordinator.getState());
        }
    }

}

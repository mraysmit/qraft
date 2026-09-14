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

package dev.mars.qraft.controller.lifecycle;

import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.runtime.Promise;
import dev.mars.qraft.controller.support.JavaRuntimeExtension;
import dev.mars.qraft.controller.support.JavaTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ShutdownCoordinator} graceful shutdown functionality.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-01-30
 */
@ExtendWith(JavaRuntimeExtension.class)
@DisplayName("ShutdownCoordinator Tests")
class ShutdownCoordinatorTest {

    @Nested
    @DisplayName("State Management")
    class StateManagementTests {

        @Test
        @DisplayName("Should start in RUNNING state")
        void shouldStartInRunningState(JavaRuntime vertx) {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(vertx);
            
            assertEquals(ShutdownCoordinator.State.RUNNING, coordinator.getState());
            assertTrue(coordinator.isAcceptingWork());
            assertFalse(coordinator.isShutdownRequested());
        }

        @Test
        @DisplayName("Should transition to DRAINING on shutdown")
        void shouldTransitionToDrainingOnShutdown(JavaRuntime vertx, JavaTestContext ctx) {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(vertx, 100, 100);
            
            coordinator.shutdown()
                    .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                        assertEquals(ShutdownCoordinator.State.STOPPED, coordinator.getState());
                        assertFalse(coordinator.isAcceptingWork());
                        assertTrue(coordinator.isShutdownRequested());
                        ctx.completeNow();
                    })));
        }

        @Test
        @DisplayName("Should reject work after shutdown requested")
        void shouldRejectWorkAfterShutdownRequested(JavaRuntime vertx) {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(vertx);
            
            // Initiate shutdown (don't wait for completion)
            coordinator.shutdown();
            
            // Should immediately stop accepting work
            assertTrue(coordinator.isShutdownRequested());
        }
    }

    @Nested
    @DisplayName("Hook Execution")
    class HookExecutionTests {

        @Test
        @DisplayName("Should execute drain hooks in order")
        void shouldExecuteDrainHooksInOrder(JavaRuntime vertx, JavaTestContext ctx) {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(vertx, 5000, 5000);
            List<String> executionOrder = new ArrayList<>();
            
            coordinator.onDrain("drain-1", () -> {
                executionOrder.add("drain-1");
                return Future.succeededFuture();
            });
            coordinator.onDrain("drain-2", () -> {
                executionOrder.add("drain-2");
                return Future.succeededFuture();
            });
            
            coordinator.shutdown()
                    .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                        assertEquals(List.of("drain-1", "drain-2"), executionOrder);
                        ctx.completeNow();
                    })));
        }

        @Test
        @DisplayName("Should execute all phases in order")
        void shouldExecuteAllPhasesInOrder(JavaRuntime vertx, JavaTestContext ctx) {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(vertx, 5000, 5000);
            List<String> executionOrder = new ArrayList<>();
            
            coordinator.onDrain("drain", () -> {
                executionOrder.add("DRAIN");
                return Future.succeededFuture();
            });
            coordinator.onAwaitCompletion("await", () -> {
                executionOrder.add("AWAIT_COMPLETION");
                return Future.succeededFuture();
            });
            coordinator.onServiceStop("stop", () -> {
                executionOrder.add("STOP_SERVICES");
                return Future.succeededFuture();
            });
            coordinator.onResourceClose("close", () -> {
                executionOrder.add("CLOSE_RESOURCES");
                return Future.succeededFuture();
            });
            
            coordinator.shutdown()
                    .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                        assertEquals(
                                List.of("DRAIN", "AWAIT_COMPLETION", "STOP_SERVICES", "CLOSE_RESOURCES"),
                                executionOrder
                        );
                        ctx.completeNow();
                    })));
        }

        @Test
        @DisplayName("Should continue on hook failure")
        void shouldContinueOnHookFailure(JavaRuntime vertx, JavaTestContext ctx) {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(vertx, 5000, 5000);
            AtomicInteger callCount = new AtomicInteger(0);
            
            coordinator.onDrain("failing", () -> {
                callCount.incrementAndGet();
                System.out.println("[EXPECTED-TEST-FAILURE] Scenario=shutdown-hook failure message=Simulated failure");
                return Future.failedFuture("Simulated failure");
            });
            coordinator.onDrain("succeeding", () -> {
                callCount.incrementAndGet();
                return Future.succeededFuture();
            });
            
            coordinator.shutdown()
                    .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                        // Both hooks should be called despite first failure
                        assertEquals(2, callCount.get());
                        assertEquals(ShutdownCoordinator.State.STOPPED, coordinator.getState());
                        ctx.completeNow();
                    })));
        }
    }

    @Nested
    @DisplayName("Idempotent Shutdown")
    class IdempotentShutdownTests {

        @Test
        @DisplayName("Should be idempotent - multiple calls return same result")
        void shouldBeIdempotent(JavaRuntime vertx, JavaTestContext ctx) {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(vertx, 100, 100);
            AtomicInteger drainCallCount = new AtomicInteger(0);
            
            coordinator.onDrain("counter", () -> {
                drainCallCount.incrementAndGet();
                return Future.succeededFuture();
            });
            
            // Call shutdown multiple times
            Future<Void> first = coordinator.shutdown();
            Future<Void> second = coordinator.shutdown();
            Future<Void> third = coordinator.shutdown();

            assertSame(first, second);
            assertSame(first, third);
            
            Future.all(first, second, third)
                    .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                        // Drain hook should only be called once
                        assertEquals(1, drainCallCount.get());
                        ctx.completeNow();
                    })));
        }
    }

    @Nested
    @DisplayName("Timeout Handling")
    class TimeoutHandlingTests {

        @Test
        @DisplayName("Should timeout slow hooks")
        void shouldTimeoutSlowHooks(JavaRuntime vertx, JavaTestContext ctx) {
            // Very short timeout
            ShutdownCoordinator coordinator = new ShutdownCoordinator(vertx, 50, 50);
            AtomicInteger completedCount = new AtomicInteger(0);
            
            // Slow hook that won't complete in time
            coordinator.onDrain("slow", () -> {
                return vertx.timer(5000).mapEmpty(); // 5 second delay
            });
            
            // Fast hook that should still run
            coordinator.onDrain("fast", () -> {
                completedCount.incrementAndGet();
                return Future.succeededFuture();
            });
            
            coordinator.shutdown()
                    .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                        // Should complete despite slow hook timing out
                        assertEquals(ShutdownCoordinator.State.STOPPED, coordinator.getState());
                        // Fast hook should still have run
                        assertEquals(1, completedCount.get());
                        ctx.completeNow();
                    })));
        }

        @Test
        @DisplayName("Timing out a hook must not complete its source operation")
        void timeoutDoesNotMutateSourceHookFuture(JavaRuntime vertx, JavaTestContext ctx) {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(vertx, 50, 50);
            Promise<Void> sourceOperation = Promise.promise();
            AtomicInteger laterHooks = new AtomicInteger();

            coordinator.onDrain("slow", sourceOperation::future);
            coordinator.onDrain("later", () -> {
                laterHooks.incrementAndGet();
                return Future.succeededFuture();
            });

            coordinator.shutdown()
                    .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                        assertEquals(ShutdownCoordinator.State.STOPPED, coordinator.getState());
                        assertEquals(1, laterHooks.get());
                        assertFalse(sourceOperation.future().isComplete(),
                                "coordinator timeout must not corrupt the hook's shared completion state");
                        sourceOperation.complete();
                        assertTrue(sourceOperation.future().succeeded());
                        ctx.completeNow();
                    })));
        }

        @Test
        @DisplayName("A critical service timeout must fail shutdown without closing later resources")
        void criticalServiceTimeoutStopsShutdownProgression(JavaRuntime vertx, JavaTestContext ctx) {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(vertx, 50, 50);
            Promise<Void> nodeStop = Promise.promise();
            AtomicInteger laterServiceStops = new AtomicInteger();
            AtomicInteger resourceCloses = new AtomicInteger();

            coordinator.onCriticalServiceStop("node-stop", nodeStop::future);
            coordinator.onServiceStop("later-service", () -> {
                laterServiceStops.incrementAndGet();
                return Future.succeededFuture();
            });
            coordinator.onResourceClose("storage", () -> {
                resourceCloses.incrementAndGet();
                return Future.succeededFuture();
            });

            Future<Void> shutdown = coordinator.shutdown();
            assertSame(shutdown, coordinator.shutdown(),
                    "all callers must observe the same critical shutdown outcome");
            shutdown.onComplete(ctx.failing(error -> ctx.verify(() -> {
                assertInstanceOf(TimeoutException.class, error);
                assertEquals(ShutdownCoordinator.State.FAILED, coordinator.getState());
                assertEquals(0, laterServiceStops.get());
                assertEquals(0, resourceCloses.get(),
                        "resource closure is unsafe while the node may still own persistence work");
                assertFalse(nodeStop.future().isComplete());
                nodeStop.complete();
                ctx.completeNow();
            })));
        }

        @Test
        @DisplayName("A synchronously throwing critical hook must become the shared shutdown failure")
        void synchronousCriticalHookFailureIsReportedAsynchronously(JavaRuntime vertx, JavaTestContext ctx) {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(vertx, 50, 50);
            IllegalStateException failure = new IllegalStateException("node stop failed before returning");

            coordinator.onCriticalServiceStop("node-stop", () -> {
                throw failure;
            });

            Future<Void> shutdown = assertDoesNotThrow(coordinator::shutdown);
            shutdown.onComplete(ctx.failing(error -> ctx.verify(() -> {
                assertSame(failure, error);
                assertEquals(ShutdownCoordinator.State.FAILED, coordinator.getState());
                assertSame(shutdown, coordinator.shutdown());
                ctx.completeNow();
            })));
        }
    }

    @Nested
    @DisplayName("Shutdown With No Hooks")
    class NoHooksTests {

        @Test
        @DisplayName("Should complete successfully with no hooks registered")
        void shouldCompleteWithNoHooks(JavaRuntime vertx, JavaTestContext ctx) {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(vertx);
            
            coordinator.shutdown()
                    .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                        assertEquals(ShutdownCoordinator.State.STOPPED, coordinator.getState());
                        ctx.completeNow();
                    })));
        }
    }

    @Nested
    @DisplayName("Fluent API")
    class FluentApiTests {

        @Test
        @DisplayName("Should support fluent chaining")
        void shouldSupportFluentChaining(JavaRuntime vertx) {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(vertx)
                    .onDrain("drain", () -> Future.succeededFuture())
                    .onAwaitCompletion("await", () -> Future.succeededFuture())
                    .onServiceStop("stop", () -> Future.succeededFuture())
                    .onResourceClose("close", () -> Future.succeededFuture());
            
            // Should not throw
            assertNotNull(coordinator);
            assertEquals(ShutdownCoordinator.State.RUNNING, coordinator.getState());
        }
    }
}

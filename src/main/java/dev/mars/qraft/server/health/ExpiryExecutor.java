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

package dev.mars.qraft.server.health;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The thread a server's health expiry and management-token installer run on, and how it stops.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
public final class ExpiryExecutor {
    private ExpiryExecutor() {
    }

    /**
     * A single daemon thread named {@code threadName}, which drops its delayed and periodic tasks when it is shut down,
     * so that stopping it waits only for a task already running.
     */
    public static ScheduledThreadPoolExecutor create(String threadName) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().name(threadName).daemon(true).factory());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    /**
     * Stops {@code executor} without interrupting a task that is running, as {@code RaftStateLoop} stops. An evaluation
     * only plans and proposes asynchronously, so it ends quickly; an interrupt could land anywhere in it, including
     * inside a log write, where an interruptible file channel closes for good. A task still running after
     * {@code timeout} is interrupted as a last resort, and the returned future then fails with a
     * {@link TimeoutException}.
     */
    public static CompletableFuture<Void> stop(ScheduledThreadPoolExecutor executor, Duration timeout) {
        executor.shutdown();
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        Thread.ofVirtual().name("qraft-health-expiry-shutdown").start(() -> {
            try {
                if (executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                    stopped.complete(null);
                } else {
                    executor.shutdownNow();
                    stopped.completeExceptionally(new TimeoutException(
                            "Health expiry did not stop within " + timeout.toMillis() + " ms"));
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                stopped.completeExceptionally(e);
                Thread.currentThread().interrupt();
            }
        });
        return stopped;
    }
}

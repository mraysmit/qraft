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

package dev.mars.qraft.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounds how long a caller waits for an asynchronous result without running the caller's work on a
 * timer thread that other timeouts share.
 * <p>
 * {@link CompletableFuture#orTimeout} completes on the JDK's JVM-wide delay scheduler, so whatever is chained
 * after that timeout runs there, and a callback that blocks, for example a shutdown step closing an
 * executor, delays every other timeout in the process. Here the timer thread only hands each expiry to a
 * new virtual thread, which completes the bounded result. Production code bounds waits with this class
 * rather than {@code orTimeout} or {@code completeOnTimeout}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public final class Deadlines {
    private static final Deadlines SHARED = new Deadlines("qraft-deadlines");

    private final ScheduledThreadPoolExecutor timer;
    private final ThreadFactory expiryThreads;

    Deadlines(String threadName) {
        timer = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().name(threadName).daemon(true).factory());
        timer.setRemoveOnCancelPolicy(true);
        expiryThreads = Thread.ofVirtual().name(threadName + "-expiry-", 0).factory();
    }

    /**
     * Returns a future that completes like {@code source}, or fails with {@link TimeoutException} if
     * {@code source} has not completed within {@code timeout}.
     * <p>
     * Completing, failing, or cancelling the returned future never affects {@code source}, which other callers
     * may share. After a timeout, dependents of the returned future run on a new virtual thread. Otherwise they
     * run on the thread that completes {@code source}, or on the caller's thread if it is already complete. The
     * pending expiry is released as soon as the returned future completes by any route.
     */
    public static <T> CompletableFuture<T> bound(CompletionStage<T> source, long timeout, TimeUnit unit) {
        return SHARED.within(source, timeout, unit);
    }

    <T> CompletableFuture<T> within(CompletionStage<T> source, long timeout, TimeUnit unit) {
        CompletableFuture<T> result = source.toCompletableFuture().copy();
        if (result.isDone()) return result;
        ScheduledFuture<?> expiry = timer.schedule(() -> expiryThreads.newThread(
                () -> result.completeExceptionally(new TimeoutException())).start(), timeout, unit);
        result.whenComplete((value, error) -> expiry.cancel(false));
        return result;
    }

    /** Expiries still waiting for their deadline. */
    int pendingExpiries() {
        return timer.getQueue().size();
    }

    void shutdownNow() {
        timer.shutdownNow();
    }
}

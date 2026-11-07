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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how the expiry thread stops. A running evaluation is left to finish rather than interrupted: Jenkins build 64
 * showed an interrupt landing while the thread logged a node reap, inside the test configuration's prudent file
 * appender, whose interruptible file lock then closed the channel and lost the log lines after it.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class ExpiryExecutorTest {
    private static final Duration GENEROUS = Duration.ofSeconds(30);

    @Test
    void aRunningTaskFinishesWithoutBeingInterrupted() throws Exception {
        ScheduledThreadPoolExecutor executor = ExpiryExecutor.create("expiry-test");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        executor.execute(() -> {
            started.countDown();
            try {
                release.await();
                interrupted.set(Thread.currentThread().isInterrupted());
                finished.set(true);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));

        CompletableFuture<Void> stopped = ExpiryExecutor.stop(executor, GENEROUS);
        release.countDown();
        stopped.get(10, TimeUnit.SECONDS);

        assertFalse(interrupted.get(), "a running evaluation is not interrupted");
        assertTrue(finished.get());
        assertTrue(executor.isTerminated());
    }

    @Test
    void aDelayedTaskIsDroppedSoTheStopDoesNotWaitForIt() throws Exception {
        ScheduledThreadPoolExecutor executor = ExpiryExecutor.create("expiry-test");
        AtomicBoolean ran = new AtomicBoolean();
        executor.schedule(() -> ran.set(true), 1, TimeUnit.HOURS);

        ExpiryExecutor.stop(executor, GENEROUS).get(10, TimeUnit.SECONDS);

        assertTrue(executor.isTerminated());
        assertFalse(ran.get());
    }

    @Test
    void aTaskThatOutlastsTheBoundIsInterruptedAndTheStopFails() throws Exception {
        ScheduledThreadPoolExecutor executor = ExpiryExecutor.create("expiry-test");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));

        CompletableFuture<Void> stopped = ExpiryExecutor.stop(executor, Duration.ofMillis(50));

        ExecutionException failure = assertThrows(ExecutionException.class, () -> stopped.get(10, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertTrue(interrupted.await(10, TimeUnit.SECONDS), "the last resort is an interrupt");
    }
}

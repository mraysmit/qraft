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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link Deadlines} bounds a wait without affecting the source, never lets work chained
 * after one timeout delay another, and releases an expiry as soon as it is no longer needed.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
class DeadlinesTest {
    private final Deadlines deadlines = new Deadlines("deadlines-test");

    @AfterEach
    void tearDown() {
        deadlines.shutdownNow();
    }

    @Test
    void anIncompleteSourceFailsWithATimeoutAndIsLeftUntouched() {
        CompletableFuture<String> source = new CompletableFuture<>();

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> deadlines.within(source, 1, TimeUnit.MILLISECONDS).get(10, TimeUnit.SECONDS));

        assertInstanceOf(TimeoutException.class, error.getCause());
        assertFalse(source.isDone(), "observing a deadline must not complete a future other callers share");
    }

    @Test
    void aSourceThatCompletesFirstGivesItsValueAndReleasesTheExpiry() throws Exception {
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> bounded = deadlines.within(source, 1, TimeUnit.HOURS);
        assertEquals(1, deadlines.pendingExpiries());

        source.complete("done");

        assertEquals("done", bounded.get(10, TimeUnit.SECONDS));
        assertEquals(0, deadlines.pendingExpiries(),
                "an hour-long expiry must not stay queued, holding the future, after the result arrived");
    }

    @Test
    void aSourceThatFailsFirstGivesItsFailureAndReleasesTheExpiry() {
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> bounded = deadlines.within(source, 1, TimeUnit.HOURS);
        IllegalStateException failure = new IllegalStateException("rejected");

        source.completeExceptionally(failure);

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> bounded.get(10, TimeUnit.SECONDS));
        assertSame(failure, error.getCause());
        assertEquals(0, deadlines.pendingExpiries());
    }

    @Test
    void anAlreadyCompleteSourceSchedulesNothing() throws Exception {
        CompletableFuture<String> bounded = deadlines.within(CompletableFuture.completedFuture("ready"),
                1, TimeUnit.HOURS);

        assertEquals("ready", bounded.getNow(null));
        assertEquals(0, deadlines.pendingExpiries());
    }

    @Test
    void cancellingTheBoundedCopyReleasesTheExpiryAndLeavesTheSourceAlone() {
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> bounded = deadlines.within(source, 1, TimeUnit.HOURS);

        bounded.cancel(false);

        assertEquals(0, deadlines.pendingExpiries());
        assertFalse(source.isDone());
    }

    @Test
    void workBlockedAfterOneTimeoutDoesNotStallAnotherTimeout() throws Exception {
        CountDownLatch firstCallbackBlocked = new CountDownLatch(1);
        CountDownLatch releaseFirstCallback = new CountDownLatch(1);
        CountDownLatch secondTimedOut = new CountDownLatch(1);
        try {
            deadlines.within(new CompletableFuture<>(), 1, TimeUnit.MILLISECONDS).whenComplete((value, error) -> {
                firstCallbackBlocked.countDown();
                awaitQuietly(releaseFirstCallback);
            });
            assertTrue(firstCallbackBlocked.await(10, TimeUnit.SECONDS));

            deadlines.within(new CompletableFuture<>(), 1, TimeUnit.MILLISECONDS)
                    .whenComplete((value, error) -> secondTimedOut.countDown());

            assertTrue(secondTimedOut.await(10, TimeUnit.SECONDS),
                    "a callback blocked after one timeout must not hold up the delivery of any other timeout");
        } finally {
            releaseFirstCallback.countDown();
        }
    }

    @Test
    void theSharedInstanceBoundsAWait() {
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> Deadlines.bound(new CompletableFuture<>(), 1, TimeUnit.MILLISECONDS)
                        .get(10, TimeUnit.SECONDS));

        assertInstanceOf(TimeoutException.class, error.getCause());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

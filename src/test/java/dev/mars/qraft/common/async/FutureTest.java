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

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests {@link Future} composition, recovery, timeout, cleanup, completion state, and callback
 * failure reporting.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
class FutureTest {

    @Test
    void allPreservesVoidAndValueResults() {
        Future<List<Object>> combined = Future.all(
                Future.succeededFuture(),
                Future.succeededFuture("ready"));

        assertEquals(java.util.Arrays.asList(null, "ready"),
                combined.toCompletionStage().toCompletableFuture().join());
    }

    @Test
    void composeAndMapTransformSuccessfulValue() {
        String result = Future.succeededFuture(20)
                .compose(value -> Future.succeededFuture(value + 1))
                .map(value -> "v" + value)
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals("v21", result);
    }

    @Test
    void recoverReceivesOriginalFailure() {
        IllegalStateException failure = new IllegalStateException("broken");

        String result = Future.<String>failedFuture(failure)
                .recover(error -> Future.succeededFuture(error.getMessage()))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals("broken", result);
    }

    @Test
    void reportsCompletionStateAndFailureCause() {
        Promise<String> promise = Promise.promise();
        assertFalse(promise.future().isComplete());

        IllegalArgumentException failure = new IllegalArgumentException("invalid");
        promise.fail(failure);

        assertTrue(promise.future().isComplete());
        assertTrue(promise.future().failed());
        assertSame(failure, promise.future().cause());
    }

    @Test
    void timeoutFailsIncompleteFuture() {
        Promise<Void> promise = Promise.promise();

        CompletionException error = assertThrows(CompletionException.class,
                () -> promise.future().timeout(25, TimeUnit.MILLISECONDS)
                        .toCompletionStage().toCompletableFuture().join());

        assertInstanceOf(TimeoutException.class, error.getCause());
    }

    @Test
    void workBlockedAfterOneTimeoutDoesNotStallAnotherTimeout() throws Exception {
        CountDownLatch firstCallbackBlocked = new CountDownLatch(1);
        CountDownLatch releaseFirstCallback = new CountDownLatch(1);
        CountDownLatch secondTimedOut = new CountDownLatch(1);
        try {
            Promise.<String>promise().future().timeout(1, TimeUnit.MILLISECONDS).onFailure(error -> {
                firstCallbackBlocked.countDown();
                awaitQuietly(releaseFirstCallback);
            });
            assertTrue(firstCallbackBlocked.await(10, TimeUnit.SECONDS));

            Promise.<String>promise().future().timeout(1, TimeUnit.MILLISECONDS)
                    .onFailure(error -> secondTimedOut.countDown());

            assertTrue(secondTimedOut.await(10, TimeUnit.SECONDS),
                    "a callback blocked after one timeout must not hold up the delivery of any other timeout");
        } finally {
            releaseFirstCallback.countDown();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void timeoutDoesNotCompleteOrFailTheSourceFuture() {
        Promise<Void> source = Promise.promise();

        CompletionException error = assertThrows(CompletionException.class,
                () -> source.future().timeout(25, TimeUnit.MILLISECONDS)
                        .toCompletionStage().toCompletableFuture().join());

        assertInstanceOf(TimeoutException.class, error.getCause());
        assertFalse(source.future().isComplete(),
                "observing a timeout must not mutate the shared source future");

        source.complete();
        assertTrue(source.future().succeeded());
    }

    @Test
    void eventuallyRunsCleanupAndPreservesValue() {
        AtomicBoolean cleaned = new AtomicBoolean();

        String result = Future.succeededFuture("value")
                .eventually(() -> {
                    cleaned.set(true);
                    return Future.succeededFuture();
                })
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertTrue(cleaned.get());
        assertEquals("value", result);
    }

    @Test
    void onSuccessReportsCallbackFailureToTheExecutingThread() {
        Thread thread = Thread.currentThread();
        Thread.UncaughtExceptionHandler original = thread.getUncaughtExceptionHandler();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        IllegalStateException failure = new IllegalStateException("callback failed");
        thread.setUncaughtExceptionHandler((ignored, error) -> reported.set(error));
        try {
            Future.succeededFuture("value").onSuccess(ignored -> {
                throw failure;
            });
        } finally {
            thread.setUncaughtExceptionHandler(original);
        }

        assertSame(failure, reported.get());
    }

}

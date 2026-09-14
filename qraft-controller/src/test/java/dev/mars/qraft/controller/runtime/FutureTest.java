package dev.mars.qraft.controller.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

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
}

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

package dev.mars.qraft.common.concurrent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Which failure {@link Completions#unwrap} reads from what a stage hands its callbacks.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-12
 * @version 1.0
 */
class CompletionsTest {

    @Test
    void aFailurePassedOnByAnEarlierStageIsReadWithoutItsWrapper() {
        IOException failure = new IOException("disk");
        Throwable seen = CompletableFuture.<String>failedFuture(failure)
                .thenApply(String::length)
                .handle((length, error) -> error)
                .join();

        assertSame(failure, Completions.unwrap(seen));
    }

    @Test
    void aFailureThatIsNotWrappedIsReadAsItIs() {
        IOException failure = new IOException("disk");

        assertSame(failure, Completions.unwrap(failure));
    }

    @Test
    void aWrapperWithoutACauseIsTheFailure() {
        CompletionException bare = new CompletionException("no cause", null);

        assertSame(bare, Completions.unwrap(bare));
    }

    @Test
    void noFailureIsReadAsNone() {
        assertNull(Completions.unwrap(null));
    }

    @Test
    void anUnwrappedChainGivesItsCallbacksTheFailureItself() {
        IOException failure = new IOException("disk");
        CompletableFuture<Integer> chain = CompletableFuture.<String>failedFuture(failure).thenApply(String::length);

        Throwable seen = Completions.unwrapped(chain).handle((length, error) -> error).join();

        assertSame(failure, seen);
    }

    @Test
    void anUnwrappedChainCompletesWithItsValueAndLeavesItsSourceAlone() {
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> unwrapped = Completions.unwrapped(source);

        unwrapped.cancel(false);
        assertFalse(source.isDone(), "cancelling the result does not reach the source");

        source.complete("value");
        assertEquals("value", Completions.unwrapped(source).join());
    }

    @Test
    void anUnwrappingHandlerIsGivenTheFailureItself() {
        IOException failure = new IOException("disk");
        CompletableFuture<String> chain = CompletableFuture.<String>failedFuture(failure).thenApply(String::trim);

        assertSame(failure, chain.handle((value, error) -> error)
                .thenApply(Completions.unwrapping(error -> error)).join());
        assertEquals("disk", chain.exceptionally(Completions.unwrapping(Throwable::getMessage)).join());
        assertEquals("disk", chain.exceptionallyCompose(Completions.unwrapping(
                error -> CompletableFuture.completedFuture(error.getMessage()))).join());
    }

    @Test
    void theSuccessCallbackRunsOnlyForAValueAndTheFailureCallbackOnlyForAFailure() {
        IOException failure = new IOException("disk");
        AtomicReference<Object> seen = new AtomicReference<>();
        CompletableFuture<String> succeeded = CompletableFuture.completedFuture("value");
        CompletableFuture<Integer> failed = CompletableFuture.<String>failedFuture(failure).thenApply(String::length);

        succeeded.whenComplete(Completions.onFailure(seen::set));
        failed.whenComplete(Completions.onSuccess(seen::set));
        assertNull(seen.get(), "neither callback runs for the other outcome");

        succeeded.whenComplete(Completions.onSuccess(seen::set));
        assertEquals("value", seen.get());
        failed.whenComplete(Completions.onFailure(seen::set));
        assertSame(failure, seen.get());

        succeeded.whenComplete(Completions.outcome(value -> seen.set("success " + value), seen::set));
        assertEquals("success value", seen.get());
        failed.whenComplete(Completions.outcome(value -> seen.set("success " + value), seen::set));
        assertSame(failure, seen.get());
    }

    @Test
    void aSuccessCallbackThatThrowsIsReportedToItsThreadsHandler() throws Exception {
        IllegalStateException thrown = new IllegalStateException("callback");
        AtomicReference<Throwable> reported = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            CompletableFuture.completedFuture("value").whenComplete(Completions.onSuccess(value -> {
                throw thrown;
            }));
            CompletableFuture.completedFuture("value").whenComplete(Completions.outcome(value -> {
                throw thrown;
            }, error -> { }));
        });
        thread.setUncaughtExceptionHandler((ignored, error) -> reported.set(error));
        thread.start();
        thread.join(10_000);

        assertSame(thrown, reported.get());
    }
}

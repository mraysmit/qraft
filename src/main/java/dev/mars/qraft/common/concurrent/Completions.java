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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reads the failure of a {@link java.util.concurrent.CompletionStage}.
 * <p>
 * A stage that fails because an earlier stage failed hands its callbacks a {@link CompletionException}
 * around the failure, while a stage that was failed directly hands them the failure itself. Code that
 * logs a failure, tests its type, or passes it on reads it through {@link #unwrap}. A method that hands a
 * chain of stages to its caller ends it with {@link #unwrapped}, so that the caller's callbacks see the
 * failure itself.
 * <p>
 * The other methods make callbacks for {@code whenComplete}, {@code exceptionally}, and
 * {@code exceptionallyCompose} that are handed the failure already unwrapped.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-12
 * @version 1.0
 */
public final class Completions {

    private Completions() {
    }

    /** The failure that {@code error} carries: its cause if it is a {@link CompletionException} with one. */
    public static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    /**
     * A future that completes as {@code stage} does, and that fails with the failure {@code stage} carries,
     * read through {@link #unwrap}. Completing or cancelling it never affects {@code stage}.
     */
    public static <T> CompletableFuture<T> unwrapped(CompletionStage<T> stage) {
        CompletableFuture<T> result = new CompletableFuture<>();
        stage.whenComplete((value, error) -> {
            if (error == null) result.complete(value);
            else result.completeExceptionally(unwrap(error));
        });
        return result;
    }

    /** A handler for {@code exceptionally} or {@code exceptionallyCompose} that is given the failure itself. */
    public static <R> Function<Throwable, R> unwrapping(Function<? super Throwable, ? extends R> handler) {
        return error -> handler.apply(unwrap(error));
    }

    /**
     * A callback for {@code whenComplete} that runs {@code action} with the value of a stage that succeeded.
     * A failure of {@code action} goes to the uncaught-exception handler of the thread it ran on, as nothing
     * reads the stage that {@code whenComplete} returns.
     */
    public static <T> BiConsumer<T, Throwable> onSuccess(Consumer<? super T> action) {
        return (value, error) -> {
            if (error == null) runReporting(action, value);
        };
    }

    /** A callback for {@code whenComplete} that runs {@code action} with the failure itself, if the stage failed. */
    public static <T> BiConsumer<T, Throwable> onFailure(Consumer<? super Throwable> action) {
        return (value, error) -> {
            if (error != null) action.accept(unwrap(error));
        };
    }

    /** A callback for {@code whenComplete} that is {@link #onSuccess} and {@link #onFailure} in one. */
    public static <T> BiConsumer<T, Throwable> outcome(Consumer<? super T> onSuccess,
                                                       Consumer<? super Throwable> onFailure) {
        return (value, error) -> {
            if (error == null) runReporting(onSuccess, value);
            else onFailure.accept(unwrap(error));
        };
    }

    private static <T> void runReporting(Consumer<? super T> action, T value) {
        try {
            action.accept(value);
        } catch (Throwable callbackFailure) {
            Thread thread = Thread.currentThread();
            Thread.UncaughtExceptionHandler handler = thread.getUncaughtExceptionHandler();
            if (handler != null) handler.uncaughtException(thread, callbackFailure);
        }
    }
}

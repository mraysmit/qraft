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

package dev.mars.qraft.controller.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Composable asynchronous result backed by a {@link java.util.concurrent.CompletionStage}, with
 * map, compose, recover, timeout, and completion callbacks.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
public final class Future<T> implements AsyncResult<T> {
    private final CompletableFuture<T> delegate;

    Future(CompletableFuture<T> delegate) {
        this.delegate = delegate;
    }

    public static <T> Future<T> succeededFuture() {
        return succeededFuture(null);
    }

    public static <T> Future<T> succeededFuture(T value) {
        return new Future<>(CompletableFuture.completedFuture(value));
    }

    public static <T> Future<T> failedFuture(String message) {
        return failedFuture(new IllegalStateException(message));
    }

    public static <T> Future<T> failedFuture(Throwable error) {
        return new Future<>(CompletableFuture.failedFuture(error));
    }

    public static <T> Future<T> fromCompletionStage(CompletionStage<T> stage) {
        return new Future<>(stage.toCompletableFuture());
    }

    public static Future<List<Object>> all(Future<?>... futures) {
        return all(Arrays.asList(futures));
    }

    public static Future<List<Object>> all(List<? extends Future<?>> futures) {
        CompletableFuture<?>[] delegates = futures.stream()
                .map(Future::toCompletableFuture)
                .toArray(CompletableFuture[]::new);
        return new Future<>(CompletableFuture.allOf(delegates)
                .thenApply(ignored -> {
                    List<Object> results = new java.util.ArrayList<>(futures.size());
                    futures.forEach(future -> results.add(future.result()));
                    return java.util.Collections.unmodifiableList(results);
                }));
    }

    public <U> Future<U> compose(Function<? super T, Future<U>> next) {
        return new Future<>(delegate.thenCompose(value -> next.apply(value).delegate));
    }

    public <U> Future<U> map(Function<? super T, ? extends U> mapper) {
        return new Future<>(delegate.thenApply(mapper));
    }

    public <U> Future<U> map(U value) {
        return map(ignored -> value);
    }

    public Future<Void> mapEmpty() {
        return map(ignored -> null);
    }

    public Future<T> recover(Function<Throwable, Future<T>> recovery) {
        CompletableFuture<T> recovered = delegate.handle((value, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture(value);
            }
            return recovery.apply(unwrap(error)).delegate;
        }).thenCompose(Function.identity());
        return new Future<>(recovered);
    }

    public Future<T> otherwise(Function<Throwable, ? extends T> recovery) {
        return new Future<>(delegate.exceptionally(error -> recovery.apply(unwrap(error))));
    }

    public Future<T> eventually(Supplier<Future<?>> cleanup) {
        CompletableFuture<T> result = new CompletableFuture<>();
        delegate.whenComplete((value, error) -> {
            Future<?> cleanupFuture;
            try {
                cleanupFuture = cleanup.get();
            } catch (Throwable cleanupError) {
                result.completeExceptionally(cleanupError);
                return;
            }
            cleanupFuture.delegate.whenComplete((ignored, cleanupError) -> {
                if (error != null) result.completeExceptionally(unwrap(error));
                else if (cleanupError != null) result.completeExceptionally(unwrap(cleanupError));
                else result.complete(value);
            });
        });
        return new Future<>(result);
    }

    public Future<T> timeout(long timeout, TimeUnit unit) {
        return new Future<>(delegate.copy().orTimeout(timeout, unit));
    }

    public Future<T> onSuccess(Consumer<? super T> action) {
        delegate.whenComplete((value, error) -> {
            if (error != null) return;
            try {
                action.accept(value);
            } catch (Throwable callbackFailure) {
                reportCallbackFailure(callbackFailure);
            }
        });
        return this;
    }

    public Future<T> onFailure(Consumer<Throwable> action) {
        delegate.whenComplete((value, error) -> {
            if (error != null) action.accept(unwrap(error));
        });
        return this;
    }

    public Future<T> onComplete(Consumer<AsyncResult<T>> action) {
        delegate.whenComplete((value, error) -> action.accept(new CompletedResult<>(value, error)));
        return this;
    }

    public CompletionStage<T> toCompletionStage() {
        return delegate;
    }

    public boolean isComplete() {
        return delegate.isDone();
    }

    CompletableFuture<T> toCompletableFuture() {
        return delegate;
    }

    @Override
    public boolean succeeded() {
        return delegate.isDone() && !delegate.isCompletedExceptionally() && !delegate.isCancelled();
    }

    @Override
    public boolean failed() {
        return delegate.isCompletedExceptionally() || delegate.isCancelled();
    }

    @Override
    public T result() {
        return succeeded() ? delegate.getNow(null) : null;
    }

    @Override
    public Throwable cause() {
        if (!failed()) return null;
        try {
            delegate.join();
            return null;
        } catch (CompletionException error) {
            return unwrap(error);
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private static void reportCallbackFailure(Throwable error) {
        Thread thread = Thread.currentThread();
        Thread.UncaughtExceptionHandler handler = thread.getUncaughtExceptionHandler();
        if (handler != null) handler.uncaughtException(thread, error);
    }

    private record CompletedResult<T>(T result, Throwable error) implements AsyncResult<T> {
        @Override public boolean succeeded() { return error == null; }
        @Override public boolean failed() { return error != null; }
        @Override public Throwable cause() { return error == null ? null : unwrap(error); }
    }
}

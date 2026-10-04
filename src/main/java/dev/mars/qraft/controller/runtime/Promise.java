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

import java.util.concurrent.CompletableFuture;

/**
 * Writable side of a {@link Future}: completes or fails it exactly once.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
public final class Promise<T> {
    private final CompletableFuture<T> delegate = new CompletableFuture<>();
    private final Future<T> future = new Future<>(delegate);

    private Promise() {}

    public static <T> Promise<T> promise() { return new Promise<>(); }
    public Future<T> future() { return future; }
    public void complete() { delegate.complete(null); }
    public void complete(T value) { delegate.complete(value); }
    public boolean tryComplete() { return delegate.complete(null); }
    public boolean tryComplete(T value) { return delegate.complete(value); }
    public void fail(String message) { fail(new IllegalStateException(message)); }
    public void fail(Throwable error) { delegate.completeExceptionally(error); }
    public boolean tryFail(Throwable error) { return delegate.completeExceptionally(error); }
    public boolean tryFail(String message) { return tryFail(new IllegalStateException(message)); }
}

package dev.mars.qraft.controller.runtime;

import java.util.concurrent.CompletableFuture;

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

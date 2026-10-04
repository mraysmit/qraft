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

package dev.mars.qraft.agent;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stops owned executors and HTTP clients and reports when they have actually terminated.
 *
 * <p>{@code shutdownNow} only interrupts; a task that is mid-run keeps running until it returns. The
 * wait therefore runs on its own virtual thread, so a caller is never blocked, and a pool thread that
 * triggers shutdown cannot deadlock by waiting for itself. It simply waits out the bound. The returned
 * future completes {@code true} once everything has terminated, or {@code false} at the bound.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
final class Quiescence {
    private Quiescence() {
    }

    static CompletableFuture<Boolean> shutdownNowAndAwait(List<? extends ExecutorService> executors,
                                                          List<HttpClient> clients, Duration bound) {
        executors.forEach(ExecutorService::shutdownNow);
        clients.forEach(HttpClient::shutdownNow);
        CompletableFuture<Boolean> terminated = new CompletableFuture<>();
        Thread.ofVirtual().name("qraft-agent-quiescence").start(() -> {
            long deadline = System.nanoTime() + bound.toNanos();
            try {
                boolean all = true;
                for (ExecutorService executor : executors) {
                    all &= executor.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS);
                }
                for (HttpClient client : clients) {
                    all &= client.awaitTermination(Duration.ofNanos(remaining(deadline)));
                }
                terminated.complete(all);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                terminated.complete(false);
            } catch (RuntimeException failure) {
                terminated.completeExceptionally(failure);
            }
        });
        return terminated;
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }
}

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

package dev.mars.qraft.controller.raft;

import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bounded waits shared by the Raft tests. The bounds only diagnose a hang: a future that fails ends the wait
 * at once with a {@link java.util.concurrent.CompletionException} carrying its cause, as {@code join} does.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-28
 * @version 1.0
 */
public final class RaftAwait {
    private static final Duration DEFAULT = Duration.ofSeconds(10);

    private RaftAwait() {
    }

    /** Waits up to 10 seconds for {@code future}. */
    public static <T> T await(Future<T> future) {
        return await(future, DEFAULT);
    }

    /** Waits up to {@code bound} for {@code future}; use a bound longer than any wait the future itself holds. */
    public static <T> T await(Future<T> future, Duration bound) {
        return future.timeout(bound.toMillis(), TimeUnit.MILLISECONDS).toCompletionStage().toCompletableFuture().join();
    }

    /** Waits until every task already queued on {@code runtime}'s state loop has run. */
    public static void awaitStateLoop(JavaRuntime runtime) {
        CompletableFuture<Void> marker = new CompletableFuture<>();
        runtime.runOnContext(ignored -> marker.complete(null));
        try {
            marker.get(DEFAULT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted awaiting the state loop", interrupted);
        } catch (Exception error) {
            throw new AssertionError("state-loop marker did not run", error);
        }
    }
}

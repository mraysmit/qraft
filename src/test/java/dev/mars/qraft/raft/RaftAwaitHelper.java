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

package dev.mars.qraft.raft;

import dev.mars.qraft.common.async.Future;
import dev.mars.qraft.common.async.JavaRuntime;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Test helper providing bounded waits for asynchronous operations in Raft tests.
 * The bounds only diagnose a hang: a future that fails ends the wait
 * at once with a {@link java.util.concurrent.CompletionException} carrying its cause, as {@code join} does.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-28
 * @version 1.0
 */
public final class RaftAwaitHelper {
    private static final Duration DEFAULT = Duration.ofSeconds(10);

    private RaftAwaitHelper() {
    }

    /** Waits up to 10 seconds for {@code future}. */
    public static <T> T await(Future<T> future) {
        return await(future, DEFAULT);
    }

    /** Waits up to {@code bound} for {@code future}; use a bound longer than any wait the future itself holds. */
    public static <T> T await(Future<T> future, Duration bound) {
        return future.timeout(bound.toMillis(), TimeUnit.MILLISECONDS).toCompletionStage().toCompletableFuture().join();
    }

    /** The index and term of a node's last log entry. */
    public record LogEnd(long index, long term) {
    }

    /**
     * Reads the index and term of {@code node}'s last log entry together, on its state loop. Two separate reads
     * from the test thread can straddle an entry the loop is adding, such as a new leader's no-op, and pair the
     * old index with the new term.
     */
    public static LogEnd logEnd(JavaRuntime runtime, RaftNode node) {
        CompletableFuture<LogEnd> end = new CompletableFuture<>();
        runtime.runOnContext(ignored -> end.complete(new LogEnd(node.getLastLogIndex(), node.getLastLogTerm())));
        try {
            return end.get(DEFAULT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted reading the log end", interrupted);
        } catch (Exception error) {
            throw new AssertionError("the state loop did not read the log end", error);
        }
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

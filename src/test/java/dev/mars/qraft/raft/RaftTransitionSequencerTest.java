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
import dev.mars.qraft.common.async.Promise;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mars.qraft.raft.RaftAwaitHelper.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RaftTransitionSequencer} serialization, failure fencing, bounded and reserved
 * admission, draining, the persistence ownership guard, and per-transition logging and tracing context.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-13
 * @version 1.0
 */
class RaftTransitionSequencerTest {
    private JavaRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.shutdown().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void serializesTheWholeTransitionAndAppliesCompletionOnTheStateLoop() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime, 8);
        Promise<String> firstGate = Promise.promise();
        List<String> history = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean firstCompletionOnStateLoop = new AtomicBoolean();

        Future<String> first = sequencer.submit(
                "first",
                RaftTransitionSequencer.FailurePolicy.CONTINUE,
                () -> {
                    history.add("first-start");
                    assertSame(runtime, JavaRuntime.currentContext());
                    return firstGate.future();
                },
                result -> {
                    firstCompletionOnStateLoop.set(JavaRuntime.currentContext() == runtime);
                    history.add("first-complete");
                    return result;
                });
        Future<String> second = sequencer.submit("second", () -> {
            history.add("second-start");
            assertSame(runtime, JavaRuntime.currentContext());
            return Future.succeededFuture("second-result");
        });
        awaitHistory(history, List.of("first-start"));
        assertFalse(second.isComplete(), "second transition started while the first was in flight");

        CompletableFuture.runAsync(() -> firstGate.complete("first-result")).get(10, TimeUnit.SECONDS);

        assertEquals("first-result", await(first));
        assertEquals("second-result", await(second));
        assertTrue(firstCompletionOnStateLoop.get());
        assertEquals(List.of("first-start", "first-complete", "second-start"), history);
    }

    @Test
    void nonFatalFailureCompletesOnceAndReleasesTheNextTransition() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime, 4);
        IOException failure = new IOException("recoverable transition failure");
        AtomicInteger completions = new AtomicInteger();

        Future<Void> first = sequencer.submit("first", () -> Future.failedFuture(failure));
        first.onComplete(ignored -> completions.incrementAndGet());
        Future<String> second = sequencer.submit("second", () -> Future.succeededFuture("ran"));

        CompletionException thrown = assertThrows(CompletionException.class, () -> await(first));
        assertSame(failure, thrown.getCause());
        assertEquals("ran", await(second));
        assertEquals(1, completions.get());
        assertFalse(sequencer.isFenced());
    }

    @Test
    void fatalFailureFencesTheSequencerAndRejectsQueuedAndNewWork() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime, 4);
        Promise<Void> gate = Promise.promise();
        IOException failure = new IOException("durability uncertain");
        AtomicBoolean queuedStarted = new AtomicBoolean();

        Future<Void> first = sequencer.submit(
                "fatal", RaftTransitionSequencer.FailurePolicy.FENCE, () -> gate.future());
        Future<Void> queued = sequencer.submit("queued", () -> {
            queuedStarted.set(true);
            return Future.succeededFuture();
        });
        gate.fail(failure);

        assertSame(failure, assertThrows(CompletionException.class, () -> await(first)).getCause());
        assertInstanceOf(RaftTransitionSequencer.FencedException.class,
                assertThrows(CompletionException.class, () -> await(queued)).getCause());
        assertFalse(queuedStarted.get());
        assertTrue(sequencer.isFenced());

        Future<Void> rejected = sequencer.submit("too-late", Future::succeededFuture);
        assertInstanceOf(RaftTransitionSequencer.FencedException.class,
                assertThrows(CompletionException.class, () -> await(rejected)).getCause());
    }

    @Test
    void classifiedPreWriteFailureDoesNotFenceLaterWork() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime, 4);
        IllegalStateException rejection = new IllegalStateException("pre-write rejection");

        Future<String> rejected = sequencer.submit(
                "pre-write",
                RaftTransitionSequencer.FailurePolicy.FENCE,
                error -> error != rejection,
                () -> Future.<String>failedFuture(rejection),
                value -> value);
        assertSame(rejection,
                assertThrows(CompletionException.class, () -> await(rejected)).getCause());

        Future<String> later = sequencer.submit(
                "later", () -> Future.succeededFuture("accepted"));
        assertEquals("accepted", await(later));
        assertFalse(sequencer.isFenced());
    }

    @Test
    void boundedAdmissionCountsTheActiveTransition() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime, 3);
        Promise<Void> gate = Promise.promise();

        Future<Void> active = sequencer.submit("active", () -> gate.future());
        Future<Void> queued = sequencer.submit("queued", Future::succeededFuture);
        Future<Void> rejected = sequencer.submit("overflow", Future::succeededFuture);

        assertInstanceOf(RaftTransitionSequencer.QueueFullException.class,
                assertThrows(CompletionException.class, () -> await(rejected)).getCause());
        assertFalse(queued.isComplete());

        gate.complete();
        await(active);
        await(queued);
    }

    @Test
    void normalTrafficCannotConsumeCapacityReservedForEssentialRaftWork() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime, 2);
        Promise<Void> gate = Promise.promise();

        Future<Void> active = sequencer.submit("client-active", () -> gate.future());
        Future<Void> rejected = sequencer.submit("client-overflow", Future::succeededFuture);
        Future<String> essential = sequencer.submitEssential(
                "higher-term-rpc",
                RaftTransitionSequencer.FailurePolicy.CONTINUE,
                () -> Future.succeededFuture("accepted"),
                value -> value);

        assertInstanceOf(RaftTransitionSequencer.QueueFullException.class,
                assertThrows(CompletionException.class, () -> await(rejected)).getCause());
        assertFalse(essential.isComplete());

        gate.complete();
        await(active);
        assertEquals("accepted", await(essential));
    }

    @Test
    void drainRejectsNewWorkAndWaitsForAllAcceptedTransitions() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime, 4);
        Promise<Void> gate = Promise.promise();

        Future<Void> active = sequencer.submit("active", () -> gate.future());
        Future<Void> queued = sequencer.submit("queued", Future::succeededFuture);
        Future<Void> drained = sequencer.drain();
        Future<Void> rejected = sequencer.submit("after-drain", Future::succeededFuture);

        assertInstanceOf(RaftTransitionSequencer.DrainingException.class,
                assertThrows(CompletionException.class, () -> await(rejected)).getCause());
        assertFalse(drained.isComplete());

        gate.complete();
        await(active);
        await(queued);
        await(drained);
        assertTrue(sequencer.isDraining());
    }

    @Test
    void alreadyCompletedTransitionsDoNotDrainRecursively() throws Exception {
        int transitionCount = 10_000;
        // Normal admission holds an eighth of the capacity in reserve; size it so the whole burst is admitted.
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime, 2 * transitionCount);
        CompletableFuture<List<Future<Integer>>> submitted = new CompletableFuture<>();
        AtomicInteger started = new AtomicInteger();

        // Submitting from one state-loop task queues every transition before the first can finish,
        // which is the deepest chain of already-completed transitions.
        runtime.runOnContext(ignored -> {
            List<Future<Integer>> results = new ArrayList<>(transitionCount);
            for (int index = 0; index < transitionCount; index++) {
                int result = index;
                results.add(sequencer.submit("sync-" + index, () -> {
                    started.incrementAndGet();
                    return Future.succeededFuture(result);
                }));
            }
            submitted.complete(results);
        });
        List<Future<Integer>> results = submitted.get(10, TimeUnit.SECONDS);

        assertEquals(transitionCount - 1, await(results.getLast()));
        assertEquals(transitionCount, started.get());
    }

    @Test
    void persistenceOwnershipGuardRejectsBypassAndAcceptsTheActiveTransition() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime, 4);
        CompletableFuture<Throwable> bypassFailure = new CompletableFuture<>();
        runtime.runOnContext(ignored -> {
            try {
                sequencer.assertActiveTransition();
                bypassFailure.complete(null);
            } catch (Throwable error) {
                bypassFailure.complete(error);
            }
        });

        Throwable rejected = bypassFailure.get(10, TimeUnit.SECONDS);
        assertInstanceOf(IllegalStateException.class, rejected);
        assertTrue(rejected.getMessage().contains("without transition ownership"));

        Future<Void> owned = sequencer.submit("owned-persistence", () -> {
            sequencer.assertActiveTransition();
            return Future.succeededFuture();
        });
        await(owned);
    }

    @Test
    void eachTransitionRunsInTheLoggingAndTracingContextItWasSubmittedIn() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime, 8);
        ContextKey<String> trace = ContextKey.named("trace");
        Promise<String> firstPersisted = Promise.promise();
        List<String> seen = Collections.synchronizedList(new ArrayList<>());

        Future<String> first = submitIn("request-a", Context.root().with(trace, "trace-a"), () ->
                sequencer.submit("first", RaftTransitionSequencer.FailurePolicy.CONTINUE, () -> {
                    seen.add("first-start " + observed(trace));
                    return firstPersisted.future();
                }, value -> {
                    seen.add("first-apply " + observed(trace));
                    return value;
                }));
        Future<String> second = submitIn("request-b", Context.root().with(trace, "trace-b"), () ->
                sequencer.submit("second", RaftTransitionSequencer.FailurePolicy.CONTINUE, () -> {
                    seen.add("second-start " + observed(trace));
                    return Future.succeededFuture("b");
                }, value -> {
                    seen.add("second-apply " + observed(trace));
                    return value;
                }));
        awaitHistory(seen, List.of("first-start request-a/trace-a"));

        // Storage completes on a thread carrying its own context, as the write-ahead-log thread does.
        Thread storage = Thread.ofPlatform().start(() -> submitIn("storage", Context.root().with(trace, "trace-storage"),
                () -> firstPersisted.complete("a")));

        assertEquals("a", await(first));
        assertEquals("b", await(second));
        storage.join(10_000);
        assertEquals(List.of("first-start request-a/trace-a", "first-apply request-a/trace-a",
                "second-start request-b/trace-b", "second-apply request-b/trace-b"), seen);
    }

    private static <T> T submitIn(String requestId, Context tracing, java.util.function.Supplier<T> submission) {
        try (Scope ignored = tracing.makeCurrent()) {
            MDC.put("requestId", requestId);
            return submission.get();
        } finally {
            MDC.clear();
        }
    }

    private static void submitIn(String requestId, Context tracing, Runnable submission) {
        submitIn(requestId, tracing, () -> {
            submission.run();
            return null;
        });
    }

    private static String observed(ContextKey<String> trace) {
        return MDC.get("requestId") + "/" + Context.current().get(trace);
    }

    private static void awaitHistory(List<String> history, List<String> expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (history.equals(expected)) return;
            Thread.onSpinWait();
        }
        assertEquals(expected, history);
    }
}

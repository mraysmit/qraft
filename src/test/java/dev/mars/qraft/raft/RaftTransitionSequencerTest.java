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

import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.common.async.RaftStateLoop;
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
    private RaftExecutorsFixture runtime;

    @BeforeEach
    void setUp() {
        runtime = RaftExecutorsFixture.create();
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.shutdown().get(10, TimeUnit.SECONDS);
    }

    @Test
    void serializesTheWholeTransitionAndAppliesCompletionOnTheStateLoop() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime.stateLoop(), 8);
        CompletableFuture<String> firstGate = new CompletableFuture<>();
        List<String> history = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean firstCompletionOnStateLoop = new AtomicBoolean();

        CompletableFuture<String> first = sequencer.submit(
                "first",
                RaftTransitionSequencer.FailurePolicy.CONTINUE,
                () -> {
                    history.add("first-start");
                    assertSame(runtime.stateLoop(), RaftStateLoop.currentContext());
                    return firstGate;
                },
                result -> {
                    firstCompletionOnStateLoop.set(RaftStateLoop.currentContext() == runtime.stateLoop());
                    history.add("first-complete");
                    return result;
                });
        CompletableFuture<String> second = sequencer.submit("second", () -> {
            history.add("second-start");
            assertSame(runtime.stateLoop(), RaftStateLoop.currentContext());
            return CompletableFuture.completedFuture("second-result");
        });
        awaitHistory(history, List.of("first-start"));
        assertFalse(second.isDone(), "second transition started while the first was in flight");

        CompletableFuture.runAsync(() -> firstGate.complete("first-result")).get(10, TimeUnit.SECONDS);

        assertEquals("first-result", await(first));
        assertEquals("second-result", await(second));
        assertTrue(firstCompletionOnStateLoop.get());
        assertEquals(List.of("first-start", "first-complete", "second-start"), history);
    }

    @Test
    void nonFatalFailureCompletesOnceAndReleasesTheNextTransition() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime.stateLoop(), 4);
        IOException failure = new IOException("recoverable transition failure");
        AtomicInteger completions = new AtomicInteger();

        CompletableFuture<Void> first = sequencer.submit("first", () -> CompletableFuture.failedFuture(failure));
        first.whenComplete((value, error) -> completions.incrementAndGet());
        CompletableFuture<String> second = sequencer.submit("second", () -> CompletableFuture.completedFuture("ran"));

        CompletionException thrown = assertThrows(CompletionException.class, () -> await(first));
        assertSame(failure, thrown.getCause());
        assertEquals("ran", await(second));
        assertEquals(1, completions.get());
        assertFalse(sequencer.isFenced());
    }

    @Test
    void fatalFailureFencesTheSequencerAndRejectsQueuedAndNewWork() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime.stateLoop(), 4);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        IOException failure = new IOException("durability uncertain");
        AtomicBoolean queuedStarted = new AtomicBoolean();

        CompletableFuture<Void> first = sequencer.submit(
                "fatal", RaftTransitionSequencer.FailurePolicy.FENCE, () -> gate);
        CompletableFuture<Void> queued = sequencer.submit("queued", () -> {
            queuedStarted.set(true);
            return CompletableFuture.completedFuture(null);
        });
        gate.completeExceptionally(failure);

        assertSame(failure, assertThrows(CompletionException.class, () -> await(first)).getCause());
        assertInstanceOf(RaftTransitionSequencer.FencedException.class,
                assertThrows(CompletionException.class, () -> await(queued)).getCause());
        assertFalse(queuedStarted.get());
        assertTrue(sequencer.isFenced());

        CompletableFuture<Void> rejected = sequencer.submit("too-late", () -> CompletableFuture.completedFuture(null));
        assertInstanceOf(RaftTransitionSequencer.FencedException.class,
                assertThrows(CompletionException.class, () -> await(rejected)).getCause());
    }

    @Test
    void classifiedPreWriteFailureDoesNotFenceLaterWork() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime.stateLoop(), 4);
        IllegalStateException rejection = new IllegalStateException("pre-write rejection");

        CompletableFuture<String> rejected = sequencer.submit(
                "pre-write",
                RaftTransitionSequencer.FailurePolicy.FENCE,
                error -> error != rejection,
                () -> CompletableFuture.<String>failedFuture(rejection),
                value -> value);
        assertSame(rejection,
                assertThrows(CompletionException.class, () -> await(rejected)).getCause());

        CompletableFuture<String> later = sequencer.submit(
                "later", () -> CompletableFuture.completedFuture("accepted"));
        assertEquals("accepted", await(later));
        assertFalse(sequencer.isFenced());
    }

    @Test
    void boundedAdmissionCountsTheActiveTransition() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime.stateLoop(), 3);
        CompletableFuture<Void> gate = new CompletableFuture<>();

        CompletableFuture<Void> active = sequencer.submit("active", () -> gate);
        CompletableFuture<Void> queued = sequencer.submit("queued", () -> CompletableFuture.completedFuture(null));
        CompletableFuture<Void> rejected = sequencer.submit("overflow", () -> CompletableFuture.completedFuture(null));

        assertInstanceOf(RaftTransitionSequencer.QueueFullException.class,
                assertThrows(CompletionException.class, () -> await(rejected)).getCause());
        assertFalse(queued.isDone());

        gate.complete(null);
        await(active);
        await(queued);
    }

    @Test
    void normalTrafficCannotConsumeCapacityReservedForEssentialRaftWork() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime.stateLoop(), 2);
        CompletableFuture<Void> gate = new CompletableFuture<>();

        CompletableFuture<Void> active = sequencer.submit("client-active", () -> gate);
        CompletableFuture<Void> rejected = sequencer.submit("client-overflow", () -> CompletableFuture.completedFuture(null));
        CompletableFuture<String> essential = sequencer.submitEssential(
                "higher-term-rpc",
                RaftTransitionSequencer.FailurePolicy.CONTINUE,
                () -> CompletableFuture.completedFuture("accepted"),
                value -> value);

        assertInstanceOf(RaftTransitionSequencer.QueueFullException.class,
                assertThrows(CompletionException.class, () -> await(rejected)).getCause());
        assertFalse(essential.isDone());

        gate.complete(null);
        await(active);
        assertEquals("accepted", await(essential));
    }

    @Test
    void drainRejectsNewWorkAndWaitsForAllAcceptedTransitions() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime.stateLoop(), 4);
        CompletableFuture<Void> gate = new CompletableFuture<>();

        CompletableFuture<Void> active = sequencer.submit("active", () -> gate);
        CompletableFuture<Void> queued = sequencer.submit("queued", () -> CompletableFuture.completedFuture(null));
        CompletableFuture<Void> drained = sequencer.drain();
        CompletableFuture<Void> rejected = sequencer.submit("after-drain", () -> CompletableFuture.completedFuture(null));

        assertInstanceOf(RaftTransitionSequencer.DrainingException.class,
                assertThrows(CompletionException.class, () -> await(rejected)).getCause());
        assertFalse(drained.isDone());

        gate.complete(null);
        await(active);
        await(queued);
        await(drained);
        assertTrue(sequencer.isDraining());
    }

    @Test
    void alreadyCompletedTransitionsDoNotDrainRecursively() throws Exception {
        int transitionCount = 10_000;
        // Normal admission holds an eighth of the capacity in reserve; size it so the whole burst is admitted.
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime.stateLoop(), 2 * transitionCount);
        CompletableFuture<List<CompletableFuture<Integer>>> submitted = new CompletableFuture<>();
        AtomicInteger started = new AtomicInteger();

        // Submitting from one state-loop task queues every transition before the first can finish,
        // which is the deepest chain of already-completed transitions.
        runtime.runOnContext(ignored -> {
            List<CompletableFuture<Integer>> results = new ArrayList<>(transitionCount);
            for (int index = 0; index < transitionCount; index++) {
                int result = index;
                results.add(sequencer.submit("sync-" + index, () -> {
                    started.incrementAndGet();
                    return CompletableFuture.completedFuture(result);
                }));
            }
            submitted.complete(results);
        });
        List<CompletableFuture<Integer>> results = submitted.get(10, TimeUnit.SECONDS);

        assertEquals(transitionCount - 1, await(results.getLast()));
        assertEquals(transitionCount, started.get());
    }

    @Test
    void persistenceOwnershipGuardRejectsBypassAndAcceptsTheActiveTransition() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime.stateLoop(), 4);
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

        CompletableFuture<Void> owned = sequencer.submit("owned-persistence", () -> {
            sequencer.assertActiveTransition();
            return CompletableFuture.completedFuture(null);
        });
        await(owned);
    }

    @Test
    void eachTransitionRunsInTheLoggingAndTracingContextItWasSubmittedIn() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime.stateLoop(), 8);
        ContextKey<String> trace = ContextKey.named("trace");
        CompletableFuture<String> firstPersisted = new CompletableFuture<>();
        List<String> seen = Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<String> first = submitIn("request-a", Context.root().with(trace, "trace-a"), () ->
                sequencer.submit("first", RaftTransitionSequencer.FailurePolicy.CONTINUE, () -> {
                    seen.add("first-start " + observed(trace));
                    return firstPersisted;
                }, value -> {
                    seen.add("first-apply " + observed(trace));
                    return value;
                }));
        CompletableFuture<String> second = submitIn("request-b", Context.root().with(trace, "trace-b"), () ->
                sequencer.submit("second", RaftTransitionSequencer.FailurePolicy.CONTINUE, () -> {
                    seen.add("second-start " + observed(trace));
                    return CompletableFuture.completedFuture("b");
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

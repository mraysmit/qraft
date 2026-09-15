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
import dev.mars.qraft.controller.runtime.Promise;
import dev.mars.qraft.controller.testsupport.RemediationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RemediationTest(phase = "1", scenarioPrefix = "RAFT-SEQUENCER")
class RaftTransitionSequencerTest {
    private JavaRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.shutdown().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
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

        CompletableFuture.runAsync(() -> firstGate.complete("first-result")).get(2, TimeUnit.SECONDS);

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
    void boundedAdmissionCountsTheActiveTransition() throws Exception {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime, 2);
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
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime, transitionCount);
        List<Future<Integer>> results = new ArrayList<>(transitionCount);
        AtomicInteger started = new AtomicInteger();

        for (int index = 0; index < transitionCount; index++) {
            int result = index;
            results.add(sequencer.submit("sync-" + index, () -> {
                started.incrementAndGet();
                return Future.succeededFuture(result);
            }));
        }

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

        Throwable rejected = bypassFailure.get(2, TimeUnit.SECONDS);
        assertInstanceOf(IllegalStateException.class, rejected);
        assertTrue(rejected.getMessage().contains("without transition ownership"));

        Future<Void> owned = sequencer.submit("owned-persistence", () -> {
            sequencer.assertActiveTransition();
            return Future.succeededFuture();
        });
        await(owned);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.timeout(5, TimeUnit.SECONDS).toCompletionStage().toCompletableFuture().join();
    }

    private static void awaitHistory(List<String> history, List<String> expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (history.equals(expected)) return;
            Thread.onSpinWait();
        }
        assertEquals(expected, history);
    }
}

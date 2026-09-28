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

import dev.mars.qraft.controller.runtime.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the manual timer fake. A fire happens on the runtime context after every task already queued
 * there, so a timer armed by a transition in progress (a new leader arms its heartbeat after it has
 * announced its leadership) is found rather than missed. The caller learns synchronously whether a
 * timer was armed.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-28
 * @version 1.0
 */
class ManualRaftTimersTest {
    private final JavaRuntime runtime = JavaRuntime.create();
    private final ManualRaftTimers timers = new ManualRaftTimers(runtime);
    private final List<String> fired = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void aPeriodicFireWaitsForATimerArmedByATaskAlreadyQueued() throws Exception {
        assertFiresTimerArmedBehindABusyContext(
                () -> timers.setPeriodic(200, id -> fired.add("heartbeat")), () -> timers.firePeriodic(200));

        assertEquals(List.of("heartbeat"), fired);
    }

    @Test
    void anElectionFireWaitsForATimerArmedByATaskAlreadyQueued() throws Exception {
        assertFiresTimerArmedBehindABusyContext(
                () -> timers.setTimer(1_000, id -> fired.add("election")), timers::fireElectionTimeout);

        assertEquals(List.of("election"), fired);
    }

    @Test
    void armedTimersAreCountedBehindATaskAlreadyQueued() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runtime.runOnContext(ignored -> {
            holding.countDown();
            await(release);
        });
        assertTrue(holding.await(10, TimeUnit.SECONDS), "the context is held");
        runtime.runOnContext(ignored -> {
            timers.setPeriodic(200, id -> { });
            timers.setTimer(1_000, id -> { });
        });
        CompletableFuture<List<Object>> seen = CompletableFuture.supplyAsync(
                () -> List.of(timers.hasPeriodic(200), timers.oneShotCount()));
        release.countDown();

        assertEquals(List.of(true, 1), seen.get(10, TimeUnit.SECONDS),
                "the timers a queued task arms are seen once it has run");
        assertFalse(timers.hasPeriodic(500), "only a timer of the given period counts");
    }

    @Test
    void theFireHasRunWhenTheCallReturns() {
        timers.setPeriodic(200, id -> fired.add("heartbeat"));

        timers.firePeriodic(200);

        assertEquals(List.of("heartbeat"), fired, "the fire is complete before the caller continues");
    }

    @Test
    void firingWithNothingArmedFailsTheCaller() {
        timers.setPeriodic(500, id -> fired.add("snapshot"));

        IllegalStateException noHeartbeat = assertThrows(IllegalStateException.class, () -> timers.firePeriodic(200));
        IllegalStateException noElection = assertThrows(IllegalStateException.class, timers::fireElectionTimeout);

        assertEquals("no periodic timer of 200 ms is armed", noHeartbeat.getMessage());
        assertEquals("no election timer is armed", noElection.getMessage());
        assertEquals(List.of(), fired, "a timer of another period is not fired");
    }

    @Test
    void whatAFiredTimerThrowsReachesTheCaller() {
        timers.setPeriodic(200, id -> { throw new AssertionError("heartbeat failed"); });
        timers.setTimer(1_000, id -> { throw new IllegalArgumentException("election failed"); });

        AssertionError error = assertThrows(AssertionError.class, () -> timers.firePeriodic(200));
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, timers::fireElectionTimeout);

        assertEquals("heartbeat failed", error.getMessage());
        assertEquals("election failed", exception.getMessage());
    }

    @Test
    void aCancelledTimerIsNotFired() {
        long heartbeat = timers.setPeriodic(200, id -> fired.add("heartbeat"));
        long election = timers.setTimer(1_000, id -> fired.add("election"));

        assertTrue(timers.cancelTimer(heartbeat));
        assertTrue(timers.cancelTimer(election));

        assertThrows(IllegalStateException.class, () -> timers.firePeriodic(200));
        assertThrows(IllegalStateException.class, timers::fireElectionTimeout);
        assertEquals(List.of(), fired);
    }

    @Test
    void aPeriodicStaysArmedAfterFiringAndAnElectionTimeoutDoesNot() {
        timers.setPeriodic(200, id -> fired.add("heartbeat"));
        timers.setTimer(1_000, id -> fired.add("election"));

        timers.firePeriodic(200);
        timers.firePeriodic(200);
        timers.fireElectionTimeout();

        assertEquals(List.of("heartbeat", "heartbeat", "election"), fired);
        assertThrows(IllegalStateException.class, timers::fireElectionTimeout, "a one-shot fires once");
    }

    /**
     * Holds the context with one task and queues {@code arm} behind it, then fires from another thread. The
     * fire must wait for the queued task instead of finding nothing armed.
     */
    private void assertFiresTimerArmedBehindABusyContext(Runnable arm, Runnable fire) throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runtime.runOnContext(ignored -> {
            holding.countDown();
            await(release);
        });
        assertTrue(holding.await(10, TimeUnit.SECONDS), "the context is held");
        runtime.runOnContext(ignored -> arm.run());
        CompletableFuture<Void> firing = new CompletableFuture<>();
        Thread firer = Thread.ofPlatform().start(() -> {
            try {
                fire.run();
                firing.complete(null);
            } catch (RuntimeException failure) {
                firing.completeExceptionally(failure);
            }
        });

        // Either the fire gave up at once (nothing armed yet) or it is waiting behind the held context.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!firing.isDone() && firer.getState() != Thread.State.TIMED_WAITING
                && firer.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        release.countDown();

        firing.get(10, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

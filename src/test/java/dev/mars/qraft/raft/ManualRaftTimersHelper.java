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

import dev.mars.qraft.common.async.JavaRuntime;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Test helper implementing {@link RaftTimerScheduler} so Raft tests control when timers fire.
 * Timers fire only when a test asks, on the node's state loop. A node's
 * only one-shot timer is its election timeout; its periodic timers are the heartbeat and snapshot checks,
 * told apart by period. Elections therefore happen exactly when, and on exactly the node, a test decides.
 *
 * <p>A fire runs on the state loop after every task already queued there and returns once it has run. A
 * timer armed by a transition still in progress is therefore found: a new leader announces its leadership
 * before it arms its heartbeat, so a test woken by the announcement would otherwise look too early. A fire
 * with nothing armed fails the caller. Never fire from the state loop itself.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-28
 * @version 1.2
 */
public final class ManualRaftTimersHelper implements RaftTimerScheduler {
    private final JavaRuntime runtime;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, Scheduled> oneShots = new ConcurrentHashMap<>();
    private final Map<Long, Scheduled> periodics = new ConcurrentHashMap<>();
    private final AtomicLong clock;

    public ManualRaftTimersHelper(JavaRuntime runtime) {
        this(runtime, new AtomicLong());
    }

    ManualRaftTimersHelper(JavaRuntime runtime, AtomicLong clock) {
        this.runtime = runtime;
        this.clock = clock;
    }

    @Override public long nanoTime() { return clock.get(); }

    /** Advances time without delivering any callbacks, to exercise a lease boundary. */
    public void advanceTime(long millis) { clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis)); }

    @Override
    public long setTimer(long delayMs, Consumer<Long> action) {
        long id = ids.incrementAndGet();
        oneShots.put(id, new Scheduled(delayMs, action));
        return id;
    }

    @Override
    public long setPeriodic(long periodMs, Consumer<Long> action) {
        long id = ids.incrementAndGet();
        periodics.put(id, new Scheduled(periodMs, action));
        return id;
    }

    @Override
    public boolean cancelTimer(long id) {
        return oneShots.remove(id) != null || periodics.remove(id) != null;
    }

    /** Fires the armed election timeout, as if the node heard from no leader for a whole timeout. */
    public void fireElectionTimeout() {
        onStateLoop(() -> {
            long id = oneShots.keySet().stream().min(Long::compareTo)
                    .orElseThrow(() -> new IllegalStateException("no election timer is armed"));
            Scheduled timer = oneShots.remove(id);
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(timer.periodMs()));
            timer.action().accept(id);
        });
    }

    /** Fires one round of the periodic timer with {@code periodMs}, such as a leader's heartbeat. */
    public void firePeriodic(long periodMs) {
        onStateLoop(() -> {
            Map.Entry<Long, Scheduled> timer = periodics.entrySet().stream()
                    .filter(entry -> entry.getValue().periodMs() == periodMs).findFirst()
                    .orElseThrow(() -> new IllegalStateException("no periodic timer of " + periodMs + " ms is armed"));
            timer.getValue().action().accept(timer.getKey());
        });
    }

    /** Whether a periodic timer with {@code periodMs} is armed, once the tasks already queued have run. */
    public boolean hasPeriodic(long periodMs) {
        return valueOnStateLoop(() -> periodics.values().stream().anyMatch(timer -> timer.periodMs() == periodMs));
    }

    /** How many one-shot timers are armed, once the tasks already queued have run. */
    public int oneShotCount() {
        return valueOnStateLoop(oneShots::size);
    }

    /** Runs {@code fire} behind the tasks already queued on the state loop and waits for it. */
    private void onStateLoop(Runnable fire) {
        valueOnStateLoop(() -> {
            fire.run();
            return null;
        });
    }

    /** Evaluates {@code step} behind the tasks already queued on the state loop and returns its result. */
    private <T> T valueOnStateLoop(Supplier<T> step) {
        CompletableFuture<T> fired = new CompletableFuture<>();
        runtime.runOnContext(ignored -> {
            try {
                fired.complete(step.get());
            } catch (Throwable failure) {
                fired.completeExceptionally(failure);
            }
        });
        try {
            return fired.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            // What the timer's action threw reaches the caller unchanged, an assertion error included.
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof RuntimeException exception) throw exception;
            throw new IllegalStateException(failure.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting a timer fire", interrupted);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("the state loop did not run the fire within 10 s", timeout);
        }
    }

    private record Scheduled(long periodMs, Consumer<Long> action) { }
}

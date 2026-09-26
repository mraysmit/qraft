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

package dev.mars.qraft.agent.health;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Runs one probe check at a fixed delay. The next attempt is scheduled only after the current one
 * completes or times out, so a check never overlaps itself, and a timeout cancels the in-flight probe.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
final class ProbeCheckRunner implements HealthCheckRunner {
    private final ProbeCheck check;
    private final Probe probe;
    private final CheckScheduler scheduler;
    private final Clock clock;
    private final CheckResultListener listener;
    private boolean started;
    private boolean stopped;
    private CheckScheduler.Cancellable nextRun;
    private Attempt current;

    ProbeCheckRunner(ProbeCheck check, Probe probe, CheckScheduler scheduler, Clock clock,
                     CheckResultListener listener) {
        this.check = Objects.requireNonNull(check, "check");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public synchronized void start() {
        if (started || stopped) return;
        started = true;
        nextRun = scheduler.schedule(this::runOnce, Duration.ZERO);
    }

    @Override
    public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        if (nextRun != null) nextRun.cancel();
        nextRun = null;
        Attempt abandoned = current;
        current = null;
        if (abandoned != null) abandoned.close();
    }

    private void runOnce() {
        Attempt attempt = new Attempt();
        synchronized (this) {
            if (stopped) return;
            nextRun = null;
            current = attempt;
            attempt.timeout = scheduler.schedule(() -> finish(attempt, new Probe.Outcome(CheckStatus.CRITICAL,
                    "Check timed out after " + check.timeout().toMillis() + " ms")), check.timeout());
        }
        CompletableFuture<Probe.Outcome> outcome;
        try {
            outcome = Objects.requireNonNull(probe.run(), "probe outcome");
        } catch (RuntimeException failure) {
            outcome = CompletableFuture.completedFuture(unexpected(failure));
        }
        synchronized (this) {
            if (current != attempt) {
                outcome.cancel(true);
                return;
            }
            attempt.probe = outcome;
        }
        outcome.whenComplete((result, failure) ->
                finish(attempt, failure == null ? result : unexpected(failure)));
    }

    private synchronized void finish(Attempt attempt, Probe.Outcome outcome) {
        if (stopped || current != attempt) return;
        current = null;
        attempt.close();
        // Schedule before notifying so an observer of this result can already rely on the next attempt.
        nextRun = scheduler.schedule(this::runOnce, check.interval());
        listener.onResult(check, new CheckResult(outcome.status(), outcome.output(), clock.instant()));
    }

    private static Probe.Outcome unexpected(Throwable failure) {
        return new Probe.Outcome(CheckStatus.CRITICAL, "Check failed: " + Probe.describe(failure));
    }

    private static final class Attempt {
        private CheckScheduler.Cancellable timeout;
        private CompletableFuture<?> probe;

        void close() {
            if (timeout != null) timeout.cancel();
            if (probe != null) probe.cancel(true);
        }
    }
}

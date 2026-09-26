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
import java.util.Objects;

/**
 * Holds the status the local process reports for a TTL check. Each report is delivered immediately and
 * re-arms a single local expiry; a missed renewal produces one critical result until the next report.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
final class TtlCheckRunner implements HealthCheckRunner, LocalStatusReporter {
    private final TtlCheck check;
    private final CheckScheduler scheduler;
    private final Clock clock;
    private final CheckResultListener listener;
    private boolean started;
    private boolean stopped;
    private CheckScheduler.Cancellable expiry;
    private long generation;

    TtlCheckRunner(TtlCheck check, CheckScheduler scheduler, Clock clock, CheckResultListener listener) {
        this.check = Objects.requireNonNull(check, "check");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public synchronized void start() {
        if (started || stopped) return;
        started = true;
        armExpiry("No status reported within " + check.ttl().toMillis() + " ms");
    }

    @Override
    public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        disarmExpiry();
    }

    @Override
    public synchronized boolean report(CheckStatus status, String output) {
        Objects.requireNonNull(status, "status");
        if (!started || stopped) return false;
        armExpiry("TTL of " + check.ttl().toMillis() + " ms expired without a status report");
        listener.onResult(check, new CheckResult(status, output, clock.instant()));
        return true;
    }

    private void armExpiry(String message) {
        disarmExpiry();
        long armed = ++generation;
        expiry = scheduler.schedule(() -> expire(armed, message), check.ttl());
    }

    private void disarmExpiry() {
        if (expiry != null) expiry.cancel();
        expiry = null;
    }

    private synchronized void expire(long armed, String message) {
        // A cancelled expiry that already started must not override a newer renewal.
        if (stopped || armed != generation || expiry == null) return;
        expiry = null;
        listener.onResult(check, new CheckResult(CheckStatus.CRITICAL, message, clock.instant()));
    }
}

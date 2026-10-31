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

package dev.mars.qraft.client.health;

import java.time.Clock;
import java.util.Objects;

/**
 * Reports a service's maintenance at start and again every third of the check's TTL, for as long as it runs. Reporting
 * once would not do: a service removed from the catalog and registered again comes back without its checks' state, and
 * the publisher renews an observation only when it is given a result.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-31
 * @version 1.0
 */
final class MaintenanceCheckRunner implements HealthCheckRunner {
    private final MaintenanceCheck check;
    private final CheckScheduler scheduler;
    private final Clock clock;
    private final CheckResultListener listener;
    private boolean started;
    private boolean stopped;
    private CheckScheduler.Cancellable next;

    MaintenanceCheckRunner(MaintenanceCheck check, CheckScheduler scheduler, Clock clock,
                           CheckResultListener listener) {
        this.check = Objects.requireNonNull(check, "check");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public synchronized void start() {
        if (started || stopped) return;
        started = true;
        report();
    }

    @Override
    public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        if (next != null) next.cancel();
        next = null;
    }

    private synchronized void report() {
        if (stopped) return;
        listener.onResult(check, new CheckResult(CheckStatus.MAINTENANCE, check.reason(), clock.instant()));
        next = scheduler.schedule(this::report, check.ttl().dividedBy(3));
    }
}

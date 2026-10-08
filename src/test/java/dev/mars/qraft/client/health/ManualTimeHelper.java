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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Test helper providing a controllable clock and scheduler for agent health-check tests.
 * Tasks run on the caller's thread only when the test advances time.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
final class ManualTimeHelper extends Clock implements CheckScheduler {
    private final List<TaskHelper> tasks = new ArrayList<>();
    private Instant now;
    private long nextOrder;

    ManualTimeHelper(Instant start) {
        now = start;
    }

    @Override
    public synchronized Cancellable schedule(Runnable action, Duration delay) {
        TaskHelper task = new TaskHelper(now.plus(delay), nextOrder++, action);
        tasks.add(task);
        return () -> {
            synchronized (ManualTimeHelper.this) {
                tasks.remove(task);
            }
        };
    }

    /** Moves time forward, running every task that falls due in deadline order. */
    void advance(Duration duration) {
        Instant target;
        synchronized (this) {
            target = now.plus(duration);
        }
        while (true) {
            TaskHelper due;
            synchronized (this) {
                due = tasks.stream()
                        .filter(task -> !task.deadline.isAfter(target))
                        .min(Comparator.comparing((TaskHelper task) -> task.deadline).thenComparingLong(task -> task.order))
                        .orElse(null);
                if (due == null) {
                    now = target;
                    return;
                }
                tasks.remove(due);
                now = due.deadline;
            }
            due.action.run();
        }
    }

    /** Steps the clock back, as a wall clock corrected by time synchronization can; no task runs. */
    synchronized void rewind(Duration duration) {
        now = now.minus(duration);
    }

    /** Runs tasks that are already due without moving time. */
    void runDue() {
        advance(Duration.ZERO);
    }

    synchronized int pendingTasks() {
        return tasks.size();
    }

    @Override public synchronized Instant instant() { return now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }

    /** Internal test scheduling helper that tracks work for the enclosing scheduler or transport. */
    private record TaskHelper(Instant deadline, long order, Runnable action) {
    }
}

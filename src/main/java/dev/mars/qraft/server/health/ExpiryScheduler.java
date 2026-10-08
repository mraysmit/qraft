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

package dev.mars.qraft.server.health;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Injected delay scheduling for leader-owned expiry, so evaluation timing can be driven deterministically.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
@FunctionalInterface
public interface ExpiryScheduler {
    Cancellable schedule(Runnable task, Duration delay);

    @FunctionalInterface
    interface Cancellable {
        void cancel();
    }

    /** Production scheduler backed by a caller-owned executor. */
    static ExpiryScheduler of(ScheduledExecutorService executor) {
        Objects.requireNonNull(executor, "executor");
        return (task, delay) -> {
            ScheduledFuture<?> scheduled = executor.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
            return () -> scheduled.cancel(false);
        };
    }
}

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

package dev.mars.qraft.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Test helper implementing {@link ScheduledExecutorService} for agent tests that control scheduled work.
 * Time moves only when a test calls {@link #advance}. Due tasks
 * run on the advancing thread in due-time order; fixed-rate tasks are rescheduled after each run. The
 * pending task count proves exactly that nothing is scheduled, without waiting to observe nothing.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
final class ManualScheduledExecutorHelper extends AbstractExecutorService implements ScheduledExecutorService {
    private final List<TaskHelper<?>> tasks = new ArrayList<>();
    private long nowNanos;
    private long sequence;
    private boolean shutdown;

    /** Runs every task due within {@code duration} of the current time, then sets time to the end. */
    void advance(Duration duration) {
        long target;
        synchronized (this) {
            target = nowNanos + duration.toNanos();
        }
        while (true) {
            TaskHelper<?> next;
            synchronized (this) {
                next = tasks.stream().filter(task -> task.dueNanos <= target)
                        .min(Comparator.comparingLong((TaskHelper<?> task) -> task.dueNanos)
                                .thenComparingLong(task -> task.sequence))
                        .orElse(null);
                if (next == null) {
                    nowNanos = target;
                    return;
                }
                tasks.remove(next);
                nowNanos = Math.max(nowNanos, next.dueNanos);
            }
            next.run();
        }
    }

    /** The number of tasks scheduled and not yet run or cancelled. */
    synchronized int pendingCount() {
        return tasks.size();
    }

    /** How long from the current time until the earliest pending task is due. */
    synchronized Duration nextDelay() {
        return Duration.ofNanos(tasks.stream().mapToLong(task -> task.dueNanos).min()
                .orElseThrow(() -> new IllegalStateException("nothing is scheduled")) - nowNanos);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return enqueue(() -> {
            command.run();
            return null;
        }, delay, 0, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return enqueue(callable, delay, 0, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                  TimeUnit unit) {
        if (period <= 0) throw new IllegalArgumentException("period must be positive");
        return enqueue(() -> {
            command.run();
            return null;
        }, initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                     TimeUnit unit) {
        return scheduleAtFixedRate(command, initialDelay, delay, unit);
    }

    @Override
    public void execute(Runnable command) {
        schedule(command, 0, TimeUnit.NANOSECONDS);
    }

    @Override
    public synchronized void shutdown() {
        shutdown = true;
    }

    @Override
    public synchronized List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> pending = new ArrayList<>(tasks);
        tasks.clear();
        return pending;
    }

    @Override
    public synchronized boolean isShutdown() {
        return shutdown;
    }

    @Override
    public synchronized boolean isTerminated() {
        return shutdown;
    }

    @Override
    public synchronized boolean awaitTermination(long timeout, TimeUnit unit) {
        return shutdown;
    }

    private synchronized <V> TaskHelper<V> enqueue(Callable<V> action, long delay, long period, TimeUnit unit) {
        if (shutdown) throw new RejectedExecutionException("executor is shut down");
        TaskHelper<V> task = new TaskHelper<>(action, nowNanos + unit.toNanos(Math.max(0, delay)),
                unit.toNanos(period), sequence++);
        tasks.add(task);
        return task;
    }

    private synchronized void reschedule(TaskHelper<?> task) {
        if (shutdown || task.isCancelled()) return;
        task.dueNanos += task.periodNanos;
        tasks.add(task);
    }

    private synchronized void cancel(TaskHelper<?> task) {
        tasks.remove(task);
    }

    /** Internal test scheduling helper that tracks work for the enclosing scheduler or transport. */
    private final class TaskHelper<V> implements ScheduledFuture<V>, Runnable {
        private final Callable<V> action;
        private final long periodNanos;
        private final long sequence;
        private final CompletableFuture<V> result = new CompletableFuture<>();
        private long dueNanos;

        private TaskHelper(Callable<V> action, long dueNanos, long periodNanos, long sequence) {
            this.action = action;
            this.dueNanos = dueNanos;
            this.periodNanos = periodNanos;
            this.sequence = sequence;
        }

        @Override
        public void run() {
            try {
                V value = action.call();
                if (periodNanos > 0) reschedule(this);
                else result.complete(value);
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        }

        @Override
        public long getDelay(TimeUnit unit) {
            synchronized (ManualScheduledExecutorHelper.this) {
                return unit.convert(dueNanos - nowNanos, TimeUnit.NANOSECONDS);
            }
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            ManualScheduledExecutorHelper.this.cancel(this);
            return result.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return result.isCancelled();
        }

        @Override
        public boolean isDone() {
            return result.isDone();
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            return result.get();
        }

        @Override
        public V get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return result.get(timeout, unit);
        }
    }
}

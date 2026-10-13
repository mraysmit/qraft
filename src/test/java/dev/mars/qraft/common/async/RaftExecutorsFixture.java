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

package dev.mars.qraft.common.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/**
 * Test fixture owning a state loop and blocking executor. Raft tests borrow each dependency explicitly
 * and close the fixture after stopping their nodes. Convenience dispatch methods keep existing
 * protocol fixtures readable while production uses the two executors directly.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-13
 * @version 1.0
 */
public final class RaftExecutorsFixture {
    private final RaftStateLoop stateLoop;
    private final BlockingTaskExecutor blockingTasks;
    private CompletableFuture<Void> shutdown;

    private RaftExecutorsFixture() { this(RaftStateLoop.create()); }
    RaftExecutorsFixture(ScheduledExecutorService scheduler) { this(new RaftStateLoop(scheduler)); }
    private RaftExecutorsFixture(RaftStateLoop stateLoop) {
        this.stateLoop = stateLoop;
        blockingTasks = new BlockingTaskExecutor("qraft-worker", 10, 1000, stateLoop);
    }
    public static RaftExecutorsFixture create() { return new RaftExecutorsFixture(); }
    public RaftStateLoop stateLoop() { return stateLoop; }
    public BlockingTaskExecutor blockingTasks() { return blockingTasks; }
    public void runOnContext(Consumer<Void> action) { stateLoop.execute(() -> action.accept(null)); }
    public long setTimer(long delayMs, Consumer<Long> action) { return stateLoop.setTimer(delayMs, action); }
    public long setPeriodic(long periodMs, Consumer<Long> action) { return stateLoop.setPeriodic(periodMs, action); }
    public boolean cancelTimer(long id) { return stateLoop.cancelTimer(id); }
    public CompletableFuture<Void> timer(long delayMs) { return stateLoop.timer(delayMs); }
    int pendingTimerCount() { return stateLoop.pendingTimerCount(); }
    public <T> CompletableFuture<T> executeBlocking(java.util.concurrent.Callable<T> task) {
        return blockingTasks.execute(task);
    }
    public synchronized CompletableFuture<Void> shutdown() {
        if (shutdown == null) {
            shutdown = blockingTasks.shutdown().handle((ignored, failure) -> failure).thenCompose(workerFailure ->
                    stateLoop.shutdown().handle((ignored, loopFailure) -> {
                        Throwable failure = workerFailure == null ? loopFailure : workerFailure;
                        if (workerFailure != null && loopFailure != null) workerFailure.addSuppressed(loopFailure);
                        if (failure != null) throw new CompletionException(failure);
                        return null;
                    }));
        }
        return shutdown;
    }
    public CompletableFuture<Void> close() { return shutdown(); }
}

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

import dev.mars.qraft.controller.runtime.AsyncResult;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.runtime.Promise;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Establishes one total order for asynchronous Raft state transitions.
 *
 * <p>A transition owns its complete prepare, persist, and apply lifecycle. Its
 * supplier starts on the node's state loop, and its result is always applied on
 * that same loop before the next supplier starts. Storage futures may complete
 * on any thread without moving the application step off the state loop.
 */
final class RaftTransitionSequencer {
    enum FailurePolicy {
        CONTINUE,
        FENCE
    }

    private enum State {
        OPEN,
        DRAINING,
        FENCED
    }

    static final class QueueFullException extends RejectedExecutionException {
        QueueFullException(int capacity) {
            super("Raft transition queue capacity " + capacity + " has been reached");
        }
    }

    static final class DrainingException extends RejectedExecutionException {
        DrainingException() {
            super("Raft transition sequencer is draining");
        }
    }

    static final class FencedException extends IllegalStateException {
        FencedException(Throwable cause) {
            super("Raft transition sequencer is fenced", cause);
        }
    }

    private final JavaRuntime runtime;
    private final int capacity;
    private final Queue<Transition<?>> queue = new ArrayDeque<>();
    private volatile State state = State.OPEN;
    private Transition<?> active;
    private Promise<Void> drainPromise;

    RaftTransitionSequencer(JavaRuntime runtime, int capacity) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (capacity < 1) throw new IllegalArgumentException("capacity must be at least one");
        this.capacity = capacity;
    }

    <T> Future<T> submit(String name, Supplier<Future<T>> action) {
        return submit(name, FailurePolicy.CONTINUE, action);
    }

    <T> Future<T> submit(String name, FailurePolicy failurePolicy, Supplier<Future<T>> action) {
        return submit(name, failurePolicy, action, Function.identity());
    }

    <P, T> Future<T> submit(String name, FailurePolicy failurePolicy,
                            Supplier<Future<P>> prepareAndPersist,
                            Function<? super P, ? extends T> apply) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(failurePolicy, "failurePolicy");
        Objects.requireNonNull(prepareAndPersist, "prepareAndPersist");
        Objects.requireNonNull(apply, "apply");

        Transition<T> transition = new Transition<>(name, failurePolicy,
                prepareAndPersist, value -> apply.apply(cast(value)));
        dispatch(() -> admit(transition), transition.result);
        return transition.result.future();
    }

    Future<Void> drain() {
        Promise<Void> requested = Promise.promise();
        Runnable beginDrain = () -> {
            if (drainPromise == null) drainPromise = Promise.promise();
            Future<Void> sharedDrain = drainPromise.future();
            sharedDrain.onComplete(result -> completeFrom(result, requested));
            if (state == State.OPEN) state = State.DRAINING;
            completeDrainIfIdle();
        };
        if (JavaRuntime.currentContext() == runtime) beginDrain.run();
        else dispatch(beginDrain, requested);
        return requested.future();
    }

    boolean isDraining() {
        return state == State.DRAINING;
    }

    boolean isFenced() {
        return state == State.FENCED;
    }

    private <T> void admit(Transition<T> transition) {
        assertStateLoop();
        if (state == State.DRAINING) {
            transition.result.fail(new DrainingException());
            return;
        }
        if (state == State.FENCED) {
            transition.result.fail(new FencedException(null));
            return;
        }
        if (outstandingCount() >= capacity) {
            transition.result.fail(new QueueFullException(capacity));
            return;
        }
        queue.add(transition);
        startNextIfIdle();
    }

    private void startNextIfIdle() {
        assertStateLoop();
        if (active != null || state == State.FENCED) return;

        Transition<?> next = queue.poll();
        if (next == null) {
            completeDrainIfIdle();
            return;
        }
        active = next;
        start(next);
    }

    private <T> void start(Transition<T> transition) {
        Future<?> operation;
        try {
            operation = Objects.requireNonNull(
                    transition.action.get(), "transition action returned null");
        } catch (Throwable error) {
            dispatch(() -> finish(transition, null, error), transition.result);
            return;
        }
        operation.onComplete(result -> dispatch(
                () -> finish(transition, result.result(), result.cause()), transition.result));
    }

    private <T> void finish(Transition<T> transition, Object value, Throwable error) {
        assertStateLoop();
        if (active != transition) return;

        active = null;
        if (error == null) {
            try {
                transition.result.tryComplete(transition.apply.apply(value));
            } catch (Throwable applyError) {
                error = applyError;
            }
        }
        if (error != null) {
            if (transition.failurePolicy == FailurePolicy.FENCE) {
                fenceQueuedTransitions(error);
            }
            transition.result.tryFail(error);
        }

        completeDrainIfIdle();
        startNextIfIdle();
    }

    private void fenceQueuedTransitions(Throwable cause) {
        state = State.FENCED;
        Transition<?> queued;
        while ((queued = queue.poll()) != null) {
            queued.result.tryFail(new FencedException(cause));
        }
    }

    private void completeDrainIfIdle() {
        if (drainPromise != null && active == null && queue.isEmpty()) {
            drainPromise.tryComplete();
        }
    }

    private int outstandingCount() {
        return queue.size() + (active == null ? 0 : 1);
    }

    private void assertStateLoop() {
        if (JavaRuntime.currentContext() != runtime) {
            throw new IllegalStateException("Raft transition accessed outside its owning state loop");
        }
    }

    private void dispatch(Runnable action, Promise<?> rejectionTarget) {
        try {
            runtime.runOnContext(ignored -> action.run());
        } catch (RejectedExecutionException error) {
            rejectionTarget.tryFail(error);
        }
    }

    private static <T> void completeFrom(AsyncResult<T> source, Promise<T> target) {
        if (source.succeeded()) target.tryComplete(source.result());
        else target.tryFail(source.cause());
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    private static final class Transition<T> {
        private final String name;
        private final FailurePolicy failurePolicy;
        private final Supplier<? extends Future<?>> action;
        private final Function<Object, T> apply;
        private final Promise<T> result = Promise.promise();

        private Transition(String name, FailurePolicy failurePolicy,
                           Supplier<? extends Future<?>> action, Function<Object, T> apply) {
            this.name = name;
            this.failurePolicy = failurePolicy;
            this.action = action;
            this.apply = apply;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}

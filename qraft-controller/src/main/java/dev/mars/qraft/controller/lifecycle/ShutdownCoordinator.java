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

package dev.mars.qraft.controller.lifecycle;

import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.runtime.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Coordinates graceful shutdown of controller components.
 * 
 * <p>Shutdown sequence:
 * <ol>
 *   <li>Enter drain mode - stop accepting new requests</li>
 *   <li>Wait for active operations to complete (with timeout)</li>
 *   <li>Stop services in reverse order of startup</li>
 *   <li>Close resources</li>
 * </ol>
 * 
 * <p>Components register themselves with hooks that are called during shutdown.
 * The coordinator ensures orderly shutdown even under load.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-01-30
 */
public class ShutdownCoordinator {
    
    private static final Logger logger = LoggerFactory.getLogger(ShutdownCoordinator.class);
    
    /**
     * Shutdown phases executed in order.
     */
    public enum Phase {
        /** Stop accepting new work */
        DRAIN,
        /** Wait for active work to complete */
        AWAIT_COMPLETION,
        /** Stop services */
        STOP_SERVICES,
        /** Close resources */
        CLOSE_RESOURCES
    }
    
    /**
     * Current shutdown state.
     */
    public enum State {
        /** Normal operation */
        RUNNING,
        /** Draining - not accepting new work */
        DRAINING,
        /** Shutting down services */
        SHUTTING_DOWN,
        /** Shutdown complete */
        STOPPED,
        /** Shutdown could not complete without violating a critical component boundary */
        FAILED
    }
    
    private final JavaRuntime runtime;
    private final long drainTimeoutMs;
    private final long shutdownTimeoutMs;
    
    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private final Promise<Void> shutdownCompletion = Promise.promise();
    
    private final List<ShutdownHook> drainHooks = new ArrayList<>();
    private final List<ShutdownHook> completionHooks = new ArrayList<>();
    private final List<ShutdownHook> serviceStopHooks = new ArrayList<>();
    private final List<ShutdownHook> resourceCloseHooks = new ArrayList<>();
    
    /**
     * Creates a shutdown coordinator with specified timeouts.
     *
     * @param runtime the Java runtime instance
     * @param drainTimeoutMs maximum time to wait for drain (stop accepting new work)
     * @param shutdownTimeoutMs maximum time to wait for active operations to complete
     */
    public ShutdownCoordinator(JavaRuntime runtime, long drainTimeoutMs, long shutdownTimeoutMs) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.drainTimeoutMs = drainTimeoutMs;
        this.shutdownTimeoutMs = shutdownTimeoutMs;
    }
    
    /**
     * Creates a shutdown coordinator with default timeouts (5s drain, 30s shutdown).
     *
     * @param runtime the Java runtime instance
     */
    public ShutdownCoordinator(JavaRuntime runtime) {
        this(runtime, 5000, 30000);
    }
    
    /**
     * Gets the current shutdown state.
     *
     * @return the current state
     */
    public State getState() {
        return state.get();
    }
    
    /**
     * Checks if the coordinator is accepting new work.
     *
     * @return true if still accepting work, false if draining or shutdown
     */
    public boolean isAcceptingWork() {
        return state.get() == State.RUNNING;
    }
    
    /**
     * Checks if shutdown has been requested.
     *
     * @return true if shutdown() has been called
     */
    public boolean isShutdownRequested() {
        return shutdownRequested.get();
    }
    
    /**
     * Registers a hook to be called during the drain phase.
     * Drain hooks should stop components from accepting new work.
     *
     * @param name descriptive name for logging
     * @param hook the hook to execute
     * @return this coordinator for chaining
     */
    public ShutdownCoordinator onDrain(String name, Supplier<Future<Void>> hook) {
        drainHooks.add(new ShutdownHook(name, hook, false));
        return this;
    }
    
    /**
     * Registers a hook to be called to wait for active work completion.
     * These hooks should return a Future that completes when active work finishes.
     *
     * @param name descriptive name for logging
     * @param hook the hook to execute
     * @return this coordinator for chaining
     */
    public ShutdownCoordinator onAwaitCompletion(String name, Supplier<Future<Void>> hook) {
        completionHooks.add(new ShutdownHook(name, hook, false));
        return this;
    }
    
    /**
     * Registers a hook to stop a service.
     *
     * @param name descriptive name for logging
     * @param hook the hook to execute
     * @return this coordinator for chaining
     */
    public ShutdownCoordinator onServiceStop(String name, Supplier<Future<Void>> hook) {
        serviceStopHooks.add(new ShutdownHook(name, hook, false));
        return this;
    }

    /**
     * Registers a service stop whose failure or timeout makes it unsafe to
     * continue to later service and resource-close hooks.
     *
     * @param name descriptive name for logging
     * @param hook the hook to execute
     * @return this coordinator for chaining
     */
    public ShutdownCoordinator onCriticalServiceStop(String name, Supplier<Future<Void>> hook) {
        serviceStopHooks.add(new ShutdownHook(name, hook, true));
        return this;
    }
    
    /**
     * Registers a hook to close resources.
     *
     * @param name descriptive name for logging
     * @param hook the hook to execute
     * @return this coordinator for chaining
     */
    public ShutdownCoordinator onResourceClose(String name, Supplier<Future<Void>> hook) {
        resourceCloseHooks.add(new ShutdownHook(name, hook, false));
        return this;
    }
    
    /**
     * Initiates graceful shutdown.
     * 
     * <p>This method is idempotent - calling it multiple times is safe.
     *
     * @return a Future that completes when shutdown is finished
     */
    public Future<Void> shutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) {
            logger.info("Shutdown already requested, waiting for completion");
            return shutdownCompletion.future();
        }
        
        logger.info("Initiating graceful shutdown (drain={}ms, timeout={}ms)", 
                drainTimeoutMs, shutdownTimeoutMs);
        
        executeDrainPhase()
                .compose(v -> executeAwaitCompletionPhase())
                .compose(v -> executeServiceStopPhase())
                .compose(v -> executeResourceClosePhase())
                .onComplete(result -> {
                    if (result.succeeded()) {
                        state.set(State.STOPPED);
                        logger.info("Graceful shutdown completed");
                        shutdownCompletion.tryComplete();
                    } else {
                        state.set(State.FAILED);
                        logger.error("Shutdown failed before resources could be closed safely: {}",
                                result.cause().getMessage(), result.cause());
                        shutdownCompletion.tryFail(result.cause());
                    }
                });
        return shutdownCompletion.future();
    }
    
    private Future<Void> executeDrainPhase() {
        if (!state.compareAndSet(State.RUNNING, State.DRAINING)) {
            logger.debug("Not in RUNNING state, skipping drain phase");
            return Future.succeededFuture();
        }
        
        logger.info("Phase 1/4: DRAIN - Stopping acceptance of new work");
        return executeHooks(Phase.DRAIN, drainHooks, drainTimeoutMs);
    }
    
    private Future<Void> executeAwaitCompletionPhase() {
        logger.info("Phase 2/4: AWAIT_COMPLETION - Waiting for active operations");
        return executeHooks(Phase.AWAIT_COMPLETION, completionHooks, shutdownTimeoutMs);
    }
    
    private Future<Void> executeServiceStopPhase() {
        state.set(State.SHUTTING_DOWN);
        logger.info("Phase 3/4: STOP_SERVICES - Stopping services");
        return executeHooks(Phase.STOP_SERVICES, serviceStopHooks, shutdownTimeoutMs);
    }
    
    private Future<Void> executeResourceClosePhase() {
        logger.info("Phase 4/4: CLOSE_RESOURCES - Closing resources");
        return executeHooks(Phase.CLOSE_RESOURCES, resourceCloseHooks, shutdownTimeoutMs);
    }
    
    private Future<Void> executeHooks(Phase phase, List<ShutdownHook> hooks, long timeoutMs) {
        if (hooks.isEmpty()) {
            logger.debug("No hooks registered for phase {}", phase);
            return Future.succeededFuture();
        }
        
        logger.debug("Executing {} hooks for phase {}", hooks.size(), phase);
        
        // Execute hooks sequentially with timeout per hook
        Future<Void> chain = Future.succeededFuture();
        for (ShutdownHook hook : hooks) {
            chain = chain.compose(v -> executeHookWithTimeout(hook, timeoutMs));
        }
        return chain;
    }
    
    private Future<Void> executeHookWithTimeout(ShutdownHook hook, long timeoutMs) {
        logger.debug("Executing shutdown hook: {}", hook.name());

        Future<Void> operation;
        try {
            operation = Objects.requireNonNull(hook.hook().get(),
                    "shutdown hook returned null: " + hook.name());
        } catch (Throwable error) {
            operation = Future.failedFuture(error);
        }

        Future<Void> timed = operation
                .timeout(timeoutMs, TimeUnit.MILLISECONDS)
                .onSuccess(v -> logger.debug("Hook completed: {}", hook.name()))
                .onFailure(err -> logHookFailure(hook, timeoutMs, err));
        if (hook.critical()) {
            return timed;
        }
        return timed.recover(err -> Future.succeededFuture());
    }

    private void logHookFailure(ShutdownHook hook, long timeoutMs, Throwable error) {
        if (error instanceof TimeoutException) {
            logger.error("{} shutdown hook '{}' timed out after {} ms",
                    hook.critical() ? "Critical" : "Best-effort", hook.name(), timeoutMs);
        } else {
            logger.error("{} shutdown hook '{}' failed with {}",
                    hook.critical() ? "Critical" : "Best-effort", hook.name(),
                    error.getClass().getSimpleName(), error);
        }
    }
    
    /**
     * Represents a named shutdown hook.
     */
    private record ShutdownHook(String name, Supplier<Future<Void>> hook, boolean critical) {
    }
}

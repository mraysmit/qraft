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

import dev.mars.qraft.common.AgentInfo;
import dev.mars.qraft.state.catalog.HealthCheckState;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.state.AgentCommand;
import dev.mars.qraft.state.CatalogCommand;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Evaluates health-check and node membership deadlines and proposes explicit expiry commands, only
 * while this server is the Raft leader. Followers never expire state from local timers; every change reaches them as a committed
 * command.
 *
 * <p>Gaining leadership records the time from which the {@link HealthExpiryEvaluator} grace period is
 * measured and arms one evaluation timer. Each evaluation proposes the due commands that are not already
 * in flight and re-arms itself for the sooner of the evaluation interval and the next due instant. Losing
 * leadership or closing cancels the timer and forgets in-flight proposals; completions that arrive from
 * an earlier leadership term are ignored. Proposals are submitted outside this instance's lock.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public final class LeaderHealthExpiry implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(LeaderHealthExpiry.class);

    private final Supplier<List<HealthCheckState>> checks;
    private final Supplier<? extends Collection<AgentInfo>> nodes;
    private final NodeExpiryPolicy nodePolicy;
    private final NodeExpiryEvaluator nodeEvaluator = new NodeExpiryEvaluator();
    private final Function<RaftCommand, CompletableFuture<RaftCommandResult<?>>> proposer;
    private final ExpiryScheduler scheduler;
    private final Clock clock;
    private final Duration interval;
    private final HealthExpiryEvaluator evaluator = new HealthExpiryEvaluator();
    /** In-flight proposals by identity; node commands are keyed without their proposal time. */
    private final Set<Object> inFlight = new HashSet<>();
    private boolean closed;
    private long term;
    private Instant leaderSince;
    private ExpiryScheduler.Cancellable timer;
    private volatile Runnable detach = () -> { };

    /** Health-check expiry only; node membership is never expired. */
    public LeaderHealthExpiry(Supplier<List<HealthCheckState>> checks,
                              Function<RaftCommand, CompletableFuture<RaftCommandResult<?>>> proposer,
                              ExpiryScheduler scheduler, Clock clock, Duration interval) {
        this(checks, List::of, null, proposer, scheduler, clock, interval);
    }

    /**
     * Health-check and node membership expiry; a {@code null} node policy disables node expiry.
     */
    public LeaderHealthExpiry(Supplier<List<HealthCheckState>> checks,
                              Supplier<? extends Collection<AgentInfo>> nodes, NodeExpiryPolicy nodePolicy,
                              Function<RaftCommand, CompletableFuture<RaftCommandResult<?>>> proposer,
                              ExpiryScheduler scheduler, Clock clock, Duration interval) {
        this.checks = Objects.requireNonNull(checks, "checks");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.nodePolicy = nodePolicy;
        this.proposer = Objects.requireNonNull(proposer, "proposer");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("interval must be positive");
    }

    /**
     * Creates an expiry component that follows {@code node}'s leadership. The node's state-change
     * listener is removed again when the component is closed.
     */
    public static LeaderHealthExpiry attach(RaftNode node, Supplier<List<HealthCheckState>> checks,
                                            Function<RaftCommand, CompletableFuture<RaftCommandResult<?>>> proposer,
                                            ExpiryScheduler scheduler, Clock clock, Duration interval) {
        return attach(node, checks, List::of, null, proposer, scheduler, clock, interval);
    }

    /** As {@link #attach(RaftNode, Supplier, Function, ExpiryScheduler, Clock, Duration)}, with node expiry. */
    public static LeaderHealthExpiry attach(RaftNode node, Supplier<List<HealthCheckState>> checks,
                                            Supplier<? extends Collection<AgentInfo>> nodes,
                                            NodeExpiryPolicy nodePolicy,
                                            Function<RaftCommand, CompletableFuture<RaftCommandResult<?>>> proposer,
                                            ExpiryScheduler scheduler, Clock clock, Duration interval) {
        Objects.requireNonNull(node, "node");
        LeaderHealthExpiry expiry = new LeaderHealthExpiry(checks, nodes, nodePolicy, proposer, scheduler, clock,
                interval);
        Consumer<RaftNode.State> listener = state -> expiry.leadershipChanged(state == RaftNode.State.LEADER);
        node.addStateChangeListener(listener);
        expiry.detach = () -> node.removeStateChangeListener(listener);
        // A node that became leader before the listener was added is not notified again.
        expiry.leadershipChanged(node.isLeader());
        return expiry;
    }

    /** Starts evaluation on gaining leadership and stops it on losing leadership. Repeats are ignored. */
    public synchronized void leadershipChanged(boolean leader) {
        if (closed) return;
        if (leader) {
            if (leaderSince != null) return;
            term++;
            leaderSince = clock.instant();
            arm(Duration.ZERO, term);
        } else {
            if (leaderSince == null) return;
            term++;
            leaderSince = null;
            stopEvaluation();
        }
    }

    /** Stops evaluation permanently. */
    @Override
    public void close() {
        detach.run();
        synchronized (this) {
            closed = true;
            stopEvaluation();
        }
    }

    private void arm(Duration delay, long armedTerm) {
        timer = scheduler.schedule(() -> evaluate(armedTerm), delay);
    }

    private void stopEvaluation() {
        if (timer != null) timer.cancel();
        timer = null;
        inFlight.clear();
    }

    private void evaluate(long armedTerm) {
        List<RaftCommand> toPropose = new ArrayList<>();
        synchronized (this) {
            if (closed || armedTerm != term || leaderSince == null) return;
            timer = null;
            Instant now = clock.instant();
            ExpiryPlan checkPlan = evaluator.plan(checks.get(), now, leaderSince);
            checkPlan.commands().stream().filter(command -> inFlight.add(key(command))).forEach(toPropose::add);
            Instant nextDue = checkPlan.nextDue().orElse(null);
            if (nodePolicy != null) {
                NodeExpiryPlan nodePlan = nodeEvaluator.plan(List.copyOf(nodes.get()), now, leaderSince, nodePolicy);
                nodePlan.commands().stream().filter(command -> inFlight.add(key(command))).forEach(toPropose::add);
                Instant nodeDue = nodePlan.nextDue().orElse(null);
                if (nodeDue != null && (nextDue == null || nodeDue.isBefore(nextDue))) nextDue = nodeDue;
            }
            Duration next = nextDue == null ? interval : Duration.between(now, nextDue);
            arm(next.compareTo(interval) < 0 ? next : interval, armedTerm);
        }
        toPropose.forEach(command -> propose(command, armedTerm));
    }

    private static Object key(RaftCommand command) {
        return command instanceof AgentCommand.Expire node
                ? new NodeExpiryKey(node.agentId(), node.expectedLastContact(), node.reap())
                : command;
    }

    private record NodeExpiryKey(String agentId, Instant expectedLastContact, boolean reap) {
    }

    private void propose(RaftCommand command, long armedTerm) {
        switch (command) {
            case CatalogCommand.ExpireHealth check -> LOGGER.info(
                    "Proposing health {}: check={}, sequence={}, deadline={}",
                    check.deregisterService() ? "deregistration" : "expiry",
                    check.checkId(), check.expectedSequenceNumber(), check.expectedDeadline());
            case AgentCommand.Expire node -> LOGGER.info("Proposing node {}: node={}, lastContact={}",
                    node.reap() ? "reap with its services" : "unreachable marking",
                    node.agentId(), node.expectedLastContact());
            default -> LOGGER.info("Proposing expiry command {}", command);
        }
        CompletableFuture<RaftCommandResult<?>> reply;
        try {
            reply = Objects.requireNonNull(proposer.apply(command), "proposal result");
        } catch (RuntimeException failure) {
            reply = CompletableFuture.failedFuture(failure);
        }
        reply.whenComplete((result, failure) -> {
            synchronized (this) {
                if (armedTerm == term) inFlight.remove(key(command));
            }
            if (failure != null) {
                LOGGER.warn("Expiry proposal failed and will be re-evaluated: command={}, reason={}",
                        key(command), Objects.toString(failure.getMessage(), failure.toString()));
            }
        });
    }
}

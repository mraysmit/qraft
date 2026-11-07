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

package dev.mars.qraft.server.acl;

import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.server.health.ExpiryScheduler;
import dev.mars.qraft.state.AclCommand;
import dev.mars.qraft.state.acl.AclToken;
import dev.mars.qraft.state.acl.AclView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Deletes expired tokens while this server leads (ACL list, Phase 8), after Consul's {@code reapExpiredACLTokens} and
 * on the pattern of {@code LeaderHealthExpiry}:
 *
 * <ul>
 *   <li>at each interval, the tokens whose expiry is not after the leader's clock are deleted through the replicated
 *       {@code DeleteExpiredTokens} command, stamped with that time, so every replica deletes the same ones;</li>
 *   <li>in batches of at most {@value #BATCH_SIZE}, one after another, so no one entry grows without bound;</li>
 *   <li>only while this server leads; a proposal that fails is left to the next interval.</li>
 * </ul>
 *
 * <p>An expired token is already refused by every server's resolver before it is deleted; deleting it keeps the state
 * small. A token with an expiry never counts for the lock-out guard, so a deletion cannot be refused as a lock-out.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
public final class AclTokenExpiry implements AutoCloseable {
    /** As many tokens as one deletion takes. */
    public static final int BATCH_SIZE = 256;

    private static final Logger LOG = LoggerFactory.getLogger(AclTokenExpiry.class);

    private final AclView acl;
    private final Function<RaftCommand, CompletableFuture<RaftCommandResult<?>>> proposer;
    private final ExpiryScheduler scheduler;
    private final Clock clock;
    private final Duration interval;
    private final int batchSize;
    private Runnable detach = () -> { };
    private boolean leader;
    private boolean closed;
    private long term;
    private ExpiryScheduler.Cancellable timer;

    AclTokenExpiry(AclView acl, Function<RaftCommand, CompletableFuture<RaftCommandResult<?>>> proposer,
                   ExpiryScheduler scheduler, Clock clock, Duration interval, int batchSize) {
        this.acl = Objects.requireNonNull(acl, "acl");
        this.proposer = Objects.requireNonNull(proposer, "proposer");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("interval must be positive");
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
        this.batchSize = batchSize;
    }

    /** An expiry that follows {@code node}'s leadership; closing it removes the node's state-change listener. */
    public static AclTokenExpiry attach(RaftNode node, AclView acl,
                                        Function<RaftCommand, CompletableFuture<RaftCommandResult<?>>> proposer,
                                        ExpiryScheduler scheduler, Clock clock, Duration interval) {
        AclTokenExpiry expiry = new AclTokenExpiry(acl, proposer, scheduler, clock, interval, BATCH_SIZE);
        Consumer<RaftNode.State> listener = state -> expiry.leadershipChanged(state == RaftNode.State.LEADER);
        node.addStateChangeListener(listener);
        expiry.detach = () -> node.removeStateChangeListener(listener);
        // A node that became leader before the listener was added is not notified again.
        expiry.leadershipChanged(node.isLeader());
        return expiry;
    }

    /** Deletes expired tokens from now on while {@code nowLeader}; repeats are ignored. */
    public synchronized void leadershipChanged(boolean nowLeader) {
        if (closed || nowLeader == leader) return;
        leader = nowLeader;
        term++;
        cancel();
        if (nowLeader) arm(Duration.ZERO, term);
    }

    @Override
    public void close() {
        detach.run();
        synchronized (this) {
            closed = true;
            leader = false;
            term++;
            cancel();
        }
    }

    private void arm(Duration delay, long armedTerm) {
        timer = scheduler.schedule(() -> evaluate(armedTerm), delay);
    }

    private void evaluate(long armedTerm) {
        Instant now;
        synchronized (this) {
            if (closed || !leader || armedTerm != term) return;
            timer = null;
            now = clock.instant();
        }
        deleteBatch(armedTerm, now).whenComplete((ignored, failure) -> {
            synchronized (this) {
                if (!closed && leader && armedTerm == term) arm(interval, armedTerm);
            }
        });
    }

    /** Deletes the expired tokens as of {@code now}, a batch at a time, until none is left or a proposal fails. */
    private CompletableFuture<Void> deleteBatch(long armedTerm, Instant now) {
        synchronized (this) {
            if (closed || !leader || armedTerm != term) return CompletableFuture.completedFuture(null);
        }
        List<String> expired = acl.tokens().stream()
                .filter(token -> token.expirationTime() != null && !token.expirationTime().isAfter(now))
                .map(AclToken::accessorId).limit(batchSize).toList();
        if (expired.isEmpty()) return CompletableFuture.completedFuture(null);
        CompletableFuture<RaftCommandResult<?>> outcome;
        try {
            outcome = proposer.apply(AclCommand.deleteExpiredTokens(now, expired));
        } catch (RuntimeException e) {
            outcome = CompletableFuture.failedFuture(e);
        }
        return outcome.handle((result, failure) -> {
            if (failure == null && result instanceof RaftCommandResult.Success<?>) {
                LOG.info("Deleted {} expired ACL token(s)", expired.size());
                return true;
            }
            LOG.warn("Expired ACL tokens could not be deleted, and are tried again at the next interval: {}",
                    failure != null ? Objects.toString(failure.getMessage(), failure.toString()) : result);
            return false;
        }).thenCompose(deleted -> deleted && expired.size() == batchSize ? deleteBatch(armedTerm, now)
                : CompletableFuture.completedFuture(null));
    }

    private void cancel() {
        if (timer != null) timer.cancel();
        timer = null;
    }
}

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

package dev.mars.qraft.server.session;

import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.server.health.ExpiryScheduler;
import dev.mars.qraft.state.session.Session;
import dev.mars.qraft.state.session.SessionCommand;
import dev.mars.qraft.state.session.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Session TTLs on the leader (sessions list, decision 2, Phase 2), after Consul's {@code session_ttl.go}:
 *
 * <ul>
 *   <li>a session with a TTL lapses {@value #TTL_MULTIPLIER} times its TTL after the later of its creation and the
 *       start of this server's leadership, or after its last renewal;</li>
 *   <li>renewals touch only this server's memory and are not replicated, so a follower refuses them, and a new leader
 *       gives every session the full {@value #TTL_MULTIPLIER} times its TTL again;</li>
 *   <li>at each interval, every lapsed session is destroyed through the replicated {@code Destroy} command stamped with
 *       the leader's time and the {@code ttl} cause, so every replica releases or deletes its keys and starts their
 *       lock delay alike, and records why; a proposal that fails is tried again at the next interval;</li>
 *   <li>a session without a TTL never lapses; it ends when it is destroyed or its node fails or leaves.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class SessionTtlExpiry implements AutoCloseable {
    /** How many TTLs a session lives without renewal, as Consul's {@code SessionTTLMultiplier}. */
    public static final int TTL_MULTIPLIER = 2;

    private static final Logger LOG = LoggerFactory.getLogger(SessionTtlExpiry.class);

    private final SessionState sessions;
    private final Function<RaftCommand, CompletableFuture<RaftCommandResult<?>>> proposer;
    private final ExpiryScheduler scheduler;
    private final Clock clock;
    private final Duration interval;
    /** When each tracked session lapses, while this server leads. */
    private final Map<String, Instant> deadlines = new HashMap<>();
    /** When each session was last renewed in this leadership, for the administrative interface alone. */
    private final Map<String, Instant> renewals = new HashMap<>();
    private Runnable detach = () -> { };
    private boolean leader;
    private boolean closed;
    private long term;
    private Instant leaderSince;
    private ExpiryScheduler.Cancellable timer;

    SessionTtlExpiry(SessionState sessions, Function<RaftCommand, CompletableFuture<RaftCommandResult<?>>> proposer,
                     ExpiryScheduler scheduler, Clock clock, Duration interval) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.proposer = Objects.requireNonNull(proposer, "proposer");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("interval must be positive");
    }

    /** An expiry that follows {@code node}'s leadership; closing it removes the node's state-change listener. */
    public static SessionTtlExpiry attach(RaftNode node, SessionState sessions,
                                          Function<RaftCommand, CompletableFuture<RaftCommandResult<?>>> proposer,
                                          ExpiryScheduler scheduler, Clock clock, Duration interval) {
        SessionTtlExpiry expiry = new SessionTtlExpiry(sessions, proposer, scheduler, clock, interval);
        Consumer<RaftNode.State> listener = state -> expiry.leadershipChanged(state == RaftNode.State.LEADER);
        node.addStateChangeListener(listener);
        expiry.detach = () -> node.removeStateChangeListener(listener);
        // A node that became leader before the listener was added is not notified again.
        expiry.leadershipChanged(node.isLeader());
        return expiry;
    }

    /** Tracks TTLs from now on while {@code nowLeader}; repeats are ignored. */
    public synchronized void leadershipChanged(boolean nowLeader) {
        if (closed || nowLeader == leader) return;
        leader = nowLeader;
        term++;
        cancel();
        deadlines.clear();
        renewals.clear();
        leaderSince = nowLeader ? clock.instant() : null;
        if (nowLeader) arm(Duration.ZERO, term);
    }

    /**
     * Renews the session named {@code id}: it lapses {@value #TTL_MULTIPLIER} times its TTL from now. Empty when there
     * is no such session.
     *
     * @throws IllegalStateException when this server does not lead, as only the leader keeps the timers
     */
    public synchronized Optional<Session> renew(String id) {
        if (closed || !leader) throw new IllegalStateException("Only the leader renews sessions");
        Optional<Session> session = sessions.get(id);
        if (session.isEmpty()) {
            deadlines.remove(id);
            renewals.remove(id);
            return session;
        }
        if (hasTtl(session.get())) {
            Instant now = clock.instant();
            deadlines.put(id, now.plus(lifetime(session.get())));
            renewals.put(id, now);
        }
        return session;
    }

    /**
     * A session's timer on this leader (UI-6 Sessions, Locks and Watches list, decision 2): when it was last renewed in
     * this leadership, or null, and when it lapses unless renewed.
     */
    public record Timer(Instant renewedAt, Instant expiresAt) {
        public Timer {
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /** Each session with a TTL's timer while this server leads, by ID; empty when it does not lead. */
    public synchronized Optional<Map<String, Timer>> timers() {
        if (closed || !leader) return Optional.empty();
        Map<String, Timer> timers = new HashMap<>();
        for (Session session : sessions.list()) {
            if (!hasTtl(session)) continue;
            // A session not yet checked gets the deadline its first check will give it.
            Instant expiresAt = deadlines.getOrDefault(session.id(),
                    later(session.createTime(), leaderSince).plus(lifetime(session)));
            timers.put(session.id(), new Timer(renewals.get(session.id()), expiresAt));
        }
        return Optional.of(Map.copyOf(timers));
    }

    @Override
    public void close() {
        detach.run();
        synchronized (this) {
            closed = true;
            leader = false;
            term++;
            cancel();
            deadlines.clear();
            renewals.clear();
        }
    }

    private void arm(Duration delay, long armedTerm) {
        timer = scheduler.schedule(() -> evaluate(armedTerm), delay);
    }

    private void evaluate(long armedTerm) {
        Instant now;
        List<String> lapsed = new ArrayList<>();
        synchronized (this) {
            if (closed || !leader || armedTerm != term) return;
            timer = null;
            now = clock.instant();
            Set<String> present = new HashSet<>();
            for (Session session : sessions.list()) {
                if (!hasTtl(session)) continue;
                present.add(session.id());
                Instant deadline = deadlines.computeIfAbsent(session.id(), id -> later(session.createTime(),
                        leaderSince).plus(lifetime(session)));
                if (!deadline.isAfter(now)) lapsed.add(session.id());
            }
            deadlines.keySet().retainAll(present);
            renewals.keySet().retainAll(present);
        }
        destroy(armedTerm, lapsed, 0, now).whenComplete((ignored, failure) -> {
            synchronized (this) {
                if (!closed && leader && armedTerm == term) arm(interval, armedTerm);
            }
        });
    }

    /** Destroys the lapsed sessions from {@code from} on, one after another. */
    private CompletableFuture<Void> destroy(long armedTerm, List<String> lapsed, int from, Instant now) {
        synchronized (this) {
            if (from >= lapsed.size() || closed || !leader || armedTerm != term) {
                return CompletableFuture.completedFuture(null);
            }
        }
        String id = lapsed.get(from);
        CompletableFuture<RaftCommandResult<?>> outcome;
        try {
            outcome = proposer.apply(SessionCommand.lapse(id, now));
        } catch (RuntimeException e) {
            outcome = CompletableFuture.failedFuture(e);
        }
        return outcome.handle((result, failure) -> {
            if (failure == null && result instanceof RaftCommandResult.Success<?>) {
                LOG.info("Session {} lapsed without renewal and was destroyed", id);
                synchronized (this) {
                    deadlines.remove(id);
                    renewals.remove(id);
                }
            } else {
                LOG.warn("Lapsed session {} could not be destroyed, and is tried again at the next interval: {}", id,
                        failure != null ? Objects.toString(failure.getMessage(), failure.toString()) : result);
            }
            return null;
        }).thenCompose(ignored -> destroy(armedTerm, lapsed, from + 1, now));
    }

    private static boolean hasTtl(Session session) {
        return session.ttl() != null && !session.ttl().isZero() && !session.ttl().isNegative();
    }

    private static Duration lifetime(Session session) {
        return session.ttl().multipliedBy(TTL_MULTIPLIER);
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) return b;
        return b == null || a.isAfter(b) ? a : b;
    }

    private void cancel() {
        if (timer != null) timer.cancel();
        timer = null;
    }
}

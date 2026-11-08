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

package dev.mars.qraft.client.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Leader election on a key (client library list, decision 5), Consul's lock recipe: a contender creates a session
 * with its TTL and tries to acquire the key with its value; while another session holds the key it waits on blocking
 * reads of it, trying again when the key changes or after {@code retryWait}. While it leads it renews its session every
 * half TTL, and reads the key in between with consistent reads, which see its own acquisition; a renewal that finds the session gone, a read that finds the key no longer
 * held by its session, or a call that fails, ends its leadership: it cannot be sure it still leads. It then destroys
 * that session, if it can, and campaigns again with a new one.
 *
 * <p>The listener hears {@code elected} and {@code lost} in turn, on the election's own virtual thread. {@link #close()}
 * stops the campaign, releases the key if it is held, telling the listener {@code lost}, and destroys the session.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class LeaderElection implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderElection.class);
    private static final AtomicLong ELECTIONS = new AtomicLong();
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(10);

    private final QraftApi api;
    private final String key;
    private final byte[] value;
    private final String node;
    private final Duration ttl;
    private final Duration lockDelay;
    private final Duration retryWait;
    private final Listener listener;
    private volatile boolean closed;
    private volatile boolean leading;
    private volatile String session;
    private Thread campaign;

    private LeaderElection(Builder builder) {
        this.api = builder.api;
        this.key = builder.key;
        this.value = builder.value;
        this.node = builder.node;
        this.ttl = builder.ttl;
        this.lockDelay = builder.lockDelay;
        this.retryWait = builder.retryWait;
        this.listener = builder.listener;
    }

    private void begin() {
        campaign = Thread.ofVirtual().name("qraft-leader-election-" + ELECTIONS.incrementAndGet()).start(this::run);
    }

    /** Whether this contender holds the key as far as it last knew. */
    public boolean isLeader() {
        return leading;
    }

    /** The session this contender campaigns or leads with, while it has one. */
    public Optional<String> session() {
        return Optional.ofNullable(session);
    }

    /** Stops campaigning: releases the key if it is held, and destroys the session. Waits for both, briefly. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Thread running = campaign;
        if (running == null) return;
        running.interrupt();
        try {
            if (!running.join(CLOSE_WAIT)) {
                LOG.warn("The leader election on {} did not stop within {}", key, CLOSE_WAIT);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        long index = 0;
        try {
            while (!closed) {
                try {
                    if (session == null) {
                        SessionOptions options = SessionOptions.named("leader election on " + key).ttl(ttl);
                        if (node != null) options = options.node(node);
                        if (lockDelay != null) options = options.lockDelay(lockDelay);
                        session = api.sessions().create(options);
                        index = 0;
                    }
                    if (!leading) {
                        if (api.keyValue().acquire(key, value, session)) {
                            leading = true;
                            tell(true);
                            continue;
                        }
                        index = api.keyValue().get(key, QueryOptions.waitPast(index, retryWait)).index();
                        continue;
                    }
                    // A leader's check must see its own acquisition, which a server behind the leader may not have
                    // applied yet, so it reads consistently; a campaign's reads need not.
                    Indexed<Optional<KvEntry>> read = api.keyValue().get(key, new QueryOptions(index,
                            ttl.dividedBy(2), QueryOptions.Consistency.CONSISTENT));
                    index = read.index();
                    boolean held = read.value().map(entry -> session.equals(entry.session())).orElse(false);
                    if (!held || !api.sessions().renew(session)) {
                        abandon(held ? "its session is gone" : "the key is no longer held by its session");
                    }
                } catch (QraftApiException failed) {
                    if (closed) break;
                    if (!leading && failed.code().equals("invalid_session")) {
                        // The session ended while this contender campaigned with it: it campaigns with a new one.
                        session = null;
                        continue;
                    }
                    LOG.warn("The leader election on {} failed a call and will try again: {}", key,
                            failed.getMessage());
                    if (leading) abandon("a call failed while it led");
                    pause();
                }
            }
        } finally {
            Thread.interrupted();
            stop();
        }
    }

    /** Ends this contender's leadership and its session, so it campaigns again with a new one. */
    private void abandon(String why) {
        LOG.info("Leadership of {} lost: {}", key, why);
        if (leading) {
            leading = false;
            tell(false);
        }
        String ended = session;
        session = null;
        try {
            api.sessions().destroy(ended);
        } catch (QraftApiException failed) {
            LOG.info("The ended session {} of the election on {} was not destroyed, and lapses with its TTL: {}",
                    ended, key, failed.getMessage());
        }
    }

    private void stop() {
        String ended = session;
        if (ended == null) return;
        try {
            if (leading) {
                leading = false;
                api.keyValue().release(key, value, ended);
                tell(false);
            }
            api.sessions().destroy(ended);
        } catch (QraftApiException failed) {
            LOG.warn("The leader election on {} could not release its key or destroy session {}, which lapses with "
                    + "its TTL: {}", key, ended, failed.getMessage());
            if (leading) {
                leading = false;
                tell(false);
            }
        } finally {
            session = null;
        }
    }

    private void tell(boolean elected) {
        try {
            if (elected) listener.elected();
            else listener.lost();
        } catch (RuntimeException failure) {
            LOG.warn("The leader election listener on {} failed: {}", key, failure.toString());
        }
    }

    private void pause() {
        try {
            TimeUnit.MILLISECONDS.sleep(retryWait.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return "LeaderElection[key=" + key + ", value=" + Arrays.toString(value) + "]";
    }

    /** What a contender is told, one call at a time, on the election's own thread. */
    public interface Listener {
        /** This contender now holds the key. */
        default void elected() { }

        /** This contender no longer holds the key, or can no longer be sure it does. */
        default void lost() { }
    }

    public static Builder builder(QraftApi api, String key) {
        return new Builder(api, key);
    }

    public static final class Builder {
        private final QraftApi api;
        private final String key;
        private byte[] value = new byte[0];
        private String node;
        private Duration ttl = Duration.ofSeconds(15);
        private Duration lockDelay;
        private Duration retryWait = Duration.ofSeconds(5);
        private Listener listener = new Listener() { };

        private Builder(QraftApi api, String key) {
            this.api = Objects.requireNonNull(api, "api");
            this.key = Objects.requireNonNull(key, "key");
            if (key.isBlank()) throw new IllegalArgumentException("A key is required");
        }

        /** What the leader writes to the key, such as its address. */
        public Builder value(byte[] value) {
            this.value = Objects.requireNonNull(value, "value").clone();
            return this;
        }

        /** The node the session is bound to: a client's local API fills it with its own, and a server requires it. */
        public Builder node(String node) {
            this.node = Objects.requireNonNull(node, "node");
            return this;
        }

        /** The session's TTL, 10 seconds to 24 hours; 15 seconds by default. The leader renews it every half TTL. */
        public Builder ttl(Duration ttl) {
            this.ttl = Objects.requireNonNull(ttl, "ttl");
            return this;
        }

        /** The session's lock delay; the server's 15 seconds by default. */
        public Builder lockDelay(Duration lockDelay) {
            this.lockDelay = Objects.requireNonNull(lockDelay, "lockDelay");
            return this;
        }

        /** How long a contender waits on the key before trying again, and after a call that failed; 5 seconds. */
        public Builder retryWait(Duration retryWait) {
            if (retryWait.isZero() || retryWait.isNegative()) {
                throw new IllegalArgumentException("retryWait must be positive: " + retryWait);
            }
            this.retryWait = retryWait;
            return this;
        }

        public Builder listener(Listener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /** Starts campaigning on a thread of the election's own. */
        public LeaderElection start() {
            LeaderElection election = new LeaderElection(this);
            election.begin();
            return election;
        }
    }
}

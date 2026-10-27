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

package dev.mars.qraft.raft;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * How long a leader waits before it tries again to send a snapshot to a peer that did not take the last one. The
 * delay starts at a base value for the first failure and doubles with each consecutive failure to the same peer,
 * up to a cap, so a stopped peer is tried less and less often instead of at every opportunity. A delivered
 * snapshot, or a new leadership, starts it over, and a peer that leaves the configuration is forgotten. Time comes
 * from the injected clock, in nanoseconds.
 *
 * <p>Not thread-safe: the leader's state loop is its only caller.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
final class SnapshotRetryBackoff {
    private final long baseNanos;
    private final long capNanos;
    private final LongSupplier clock;
    private final Map<String, Integer> consecutiveFailures = new HashMap<>();
    private final Map<String, Long> retryAfterNanos = new HashMap<>();

    /**
     * @param baseNanos the delay after a peer's first failure, positive
     * @param capNanos the longest delay, not below {@code baseNanos}
     * @param clock monotonic time in nanoseconds
     */
    SnapshotRetryBackoff(long baseNanos, long capNanos, LongSupplier clock) {
        if (baseNanos < 1) throw new IllegalArgumentException("The base delay must be positive");
        if (capNanos < baseNanos) throw new IllegalArgumentException("The cap must not be below the base delay");
        this.baseNanos = baseNanos;
        this.capNanos = capNanos;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Whether the delay after {@code peer}'s last failure has not yet passed. */
    boolean inBackoff(String peer) {
        Long retryAfter = retryAfterNanos.get(peer);
        return retryAfter != null && clock.getAsLong() < retryAfter;
    }

    /** Records another failure to send to {@code peer} and returns the delay before the next attempt. */
    long failed(String peer) {
        int failures = consecutiveFailures.merge(peer, 1, (known, one) -> known == Integer.MAX_VALUE ? known : known + 1);
        long delay = delayNanos(failures);
        retryAfterNanos.put(peer, clock.getAsLong() + delay);
        return delay;
    }

    /** Forgets {@code peer}'s failures, as after a snapshot it took. */
    void succeeded(String peer) {
        consecutiveFailures.remove(peer);
        retryAfterNanos.remove(peer);
    }

    /** Forgets every peer not in {@code peers}, as when a server leaves the configuration. */
    void retainOnly(Set<String> peers) {
        consecutiveFailures.keySet().retainAll(peers);
        retryAfterNanos.keySet().retainAll(peers);
    }

    /** Forgets every peer, as when this server's leadership begins or ends. */
    void clear() {
        consecutiveFailures.clear();
        retryAfterNanos.clear();
    }

    private long delayNanos(int failures) {
        long delay = baseNanos;
        for (int doubling = 1; doubling < failures && delay < capNanos; doubling++) {
            delay = delay > capNanos / 2 ? capNanos : delay * 2;
        }
        return Math.min(delay, capNanos);
    }
}

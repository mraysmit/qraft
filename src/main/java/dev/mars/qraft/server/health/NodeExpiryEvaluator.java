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

import dev.mars.qraft.common.ClientInfo;
import dev.mars.qraft.common.ClientStatus;
import dev.mars.qraft.state.ClientCommand;
import dev.mars.qraft.state.QraftStateStore;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides which registered nodes are due to be marked unreachable or reaped. It has no side effects and
 * reads no clock; the caller supplies the leader's current time.
 *
 * <p>A node's last contact is its last server-stamped heartbeat, or its registration time before the
 * first heartbeat. Like health checks, a node is due only at the later of {@code lastContact + ttl} and
 * {@code leaderSince + ttl}, so a failover never mass-expires nodes that could not reach a leader.
 * An unreachable node is due for reaping {@code reapAfter} after that effective deadline. Commands name
 * the stored last contact, so a heartbeat or re-registration committed first makes them no-ops.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public final class NodeExpiryEvaluator {

    public NodeExpiryPlan plan(Collection<ClientInfo> nodes, Instant now, Instant leaderSince, NodeExpiryPolicy policy) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaderSince, "leaderSince");
        Objects.requireNonNull(policy, "policy");
        List<ClientCommand.Expire> due = new ArrayList<>();
        Instant nextDue = null;
        List<ClientInfo> ordered = nodes.stream().sorted(Comparator.comparing(ClientInfo::getClientId)).toList();
        for (ClientInfo node : ordered) {
            Instant lastContact = QraftStateStore.lastContact(node);
            if (lastContact == null) continue;
            Instant effectiveDeadline = later(plus(lastContact, policy.ttl()), plus(leaderSince, policy.ttl()));
            boolean reap = node.getStatus() == ClientStatus.UNREACHABLE;
            if (reap && policy.reapAfter().isZero()) continue;
            Instant dueAt = reap ? plus(effectiveDeadline, policy.reapAfter()) : effectiveDeadline;
            if (dueAt.equals(Instant.MAX)) continue;
            if (now.isBefore(dueAt)) {
                if (nextDue == null || dueAt.isBefore(nextDue)) nextDue = dueAt;
            } else {
                due.add((ClientCommand.Expire) ClientCommand.expire(node.getClientId(), lastContact, reap, now));
            }
        }
        return new NodeExpiryPlan(due, Optional.ofNullable(nextDue));
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    /** Adds a duration, saturating at {@link Instant#MAX}, which the evaluator treats as never. */
    private static Instant plus(Instant instant, Duration duration) {
        if (instant.equals(Instant.MAX)) return Instant.MAX;
        try {
            return instant.plus(duration);
        } catch (ArithmeticException | DateTimeException beyondRepresentableTime) {
            return Instant.MAX;
        }
    }
}

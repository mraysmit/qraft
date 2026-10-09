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

import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.NodeStatus;
import dev.mars.qraft.state.NodeCommand;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the side-effect-free {@link NodeExpiryEvaluator}: a silent node falls due exactly one TTL after
 * its last contact, a new leader grants a full TTL, an unreachable node is reaped only after the reap
 * delay, and ordering and time arithmetic are deterministic.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
class NodeExpiryEvaluatorTest {
    private static final Instant REGISTERED = Instant.parse("2026-09-26T12:00:00Z");
    private static final Instant LONG_LEADER = REGISTERED.minusSeconds(3_600);
    private static final NodeExpiryPolicy POLICY = new NodeExpiryPolicy(Duration.ofSeconds(90), Duration.ofSeconds(60));

    private final NodeExpiryEvaluator evaluator = new NodeExpiryEvaluator();

    @Test
    void aSilentNodeFallsDueOneTtlAfterItsLastContact() {
        Node registeredOnly = node("client-1", null, NodeStatus.REGISTERING);
        Node heartbeating = node("client-2", REGISTERED.plusSeconds(30), NodeStatus.HEALTHY);
        Instant now = REGISTERED.plusSeconds(90);

        NodeExpiryPlan plan = evaluator.plan(List.of(heartbeating, registeredOnly), now, LONG_LEADER, POLICY);

        assertEquals(List.of(NodeCommand.expire("client-1", REGISTERED, false, now)), plan.commands(),
                "before its first heartbeat a node's last contact is its registration time");
        assertEquals(Optional.of(REGISTERED.plusSeconds(120)), plan.nextDue());
        assertEquals(List.of(), evaluator.plan(List.of(registeredOnly), now.minusMillis(1), LONG_LEADER, POLICY)
                .commands());
    }

    @Test
    void aNewLeaderGrantsEveryNodeAFullTtl() {
        Node silent = node("client-1", REGISTERED, NodeStatus.HEALTHY);
        Instant elected = REGISTERED.plusSeconds(600);

        NodeExpiryPlan withinGrace = evaluator.plan(List.of(silent), elected.plusSeconds(89), elected, POLICY);
        NodeExpiryPlan afterGrace = evaluator.plan(List.of(silent), elected.plusSeconds(90), elected, POLICY);

        assertEquals(List.of(), withinGrace.commands());
        assertEquals(Optional.of(elected.plusSeconds(90)), withinGrace.nextDue());
        assertEquals(List.of(NodeCommand.expire("client-1", REGISTERED, false, elected.plusSeconds(90))),
                afterGrace.commands(), "the command names the stored last contact, not the grace deadline");
    }

    @Test
    void anUnreachableNodeIsReapedOnlyAfterTheReapDelayAndNeverWhenItIsZero() {
        Node unreachable = node("client-1", REGISTERED, NodeStatus.UNREACHABLE);
        Instant reapAt = REGISTERED.plusSeconds(150);

        assertEquals(List.of(), evaluator.plan(List.of(unreachable), reapAt.minusMillis(1), LONG_LEADER, POLICY)
                .commands(), "an unreachable node is not marked again");
        assertEquals(List.of(NodeCommand.expire("client-1", REGISTERED, true, reapAt)),
                evaluator.plan(List.of(unreachable), reapAt, LONG_LEADER, POLICY).commands());

        NodeExpiryPlan neverReap = evaluator.plan(List.of(unreachable), reapAt.plus(Duration.ofDays(30)), LONG_LEADER,
                new NodeExpiryPolicy(Duration.ofSeconds(90), Duration.ZERO));
        assertEquals(List.of(), neverReap.commands());
        assertEquals(Optional.empty(), neverReap.nextDue());
    }

    @Test
    void dueNodesAreOrderedByIdentifier() {
        Instant now = REGISTERED.plusSeconds(200);

        NodeExpiryPlan plan = evaluator.plan(List.of(node("c", REGISTERED, NodeStatus.HEALTHY),
                node("a", REGISTERED, NodeStatus.HEALTHY), node("b", REGISTERED, NodeStatus.HEALTHY)),
                now, LONG_LEADER, POLICY);

        assertEquals(List.of("a", "b", "c"), plan.commands().stream().map(NodeCommand.Expire::name).toList());
    }

    @Test
    void extremeDurationsSaturateInsteadOfOverflowing() {
        NodeExpiryPolicy huge = new NodeExpiryPolicy(Duration.ofSeconds(Long.MAX_VALUE / 2), Duration.ZERO);

        NodeExpiryPlan plan = evaluator.plan(List.of(node("client-1", REGISTERED, NodeStatus.HEALTHY)),
                REGISTERED.plusSeconds(60), REGISTERED, huge);

        assertEquals(List.of(), plan.commands());
        assertEquals(Optional.empty(), plan.nextDue());
    }

    @Test
    void thePolicyRequiresAPositiveTtlAndANonNegativeReapDelay() {
        assertThrows(IllegalArgumentException.class, () -> new NodeExpiryPolicy(Duration.ZERO, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new NodeExpiryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(-1)));
    }

    private static Node node(String name, Instant lastHeartbeat, NodeStatus status) {
        return new Node(name, "127.0.0.1", null, null, Map.of(), status, REGISTERED, lastHeartbeat);
    }
}

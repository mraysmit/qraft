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

package dev.mars.qraft.controller.health;

import dev.mars.qraft.agent.AgentInfo;
import dev.mars.qraft.agent.AgentStatus;
import dev.mars.qraft.catalog.HealthCheckState;
import dev.mars.qraft.catalog.HealthObservation;
import dev.mars.qraft.catalog.ServiceCheckId;
import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstanceId;
import dev.mars.qraft.controller.state.AgentCommand;
import dev.mars.qraft.controller.state.CatalogCommand;
import dev.mars.qraft.controller.state.RaftCommand;
import dev.mars.qraft.controller.state.RaftCommandResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that {@link LeaderHealthExpiry} evaluates and proposes only while leader, never duplicates an
 * in-flight proposal, retries failures, cancels on step-down, restarts the grace period on re-election,
 * and stops permanently when closed.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
class LeaderHealthExpiryTest {
    private static final Instant START = Instant.parse("2026-09-26T12:00:00Z");
    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private static final ServiceCheckId CHECK = new ServiceCheckId(
            new ServiceInstanceId("default", "default", "node-1", "web"), "ttl");

    private final ManualExpiryTime time = new ManualExpiryTime(START);
    private final AtomicReference<List<HealthCheckState>> checks = new AtomicReference<>(List.of());
    private final List<RaftCommand> proposed = new CopyOnWriteArrayList<>();
    private final List<CompletableFuture<RaftCommandResult<?>>> replies = new CopyOnWriteArrayList<>();
    private final LeaderHealthExpiry expiry = new LeaderHealthExpiry(checks::get, command -> {
        proposed.add(command);
        CompletableFuture<RaftCommandResult<?>> reply = new CompletableFuture<>();
        replies.add(reply);
        return reply;
    }, time, time, INTERVAL);

    @Test
    void aFollowerNeverEvaluatesOrProposes() {
        checks.set(List.of(state(1, START.minusSeconds(3_600), false)));

        expiry.leadershipChanged(false);
        time.advance(Duration.ofMinutes(10));

        assertEquals(List.of(), proposed);
        assertEquals(0, time.pendingTasks(), "a follower schedules no expiry work");
    }

    @Test
    void aLeaderProposesADueExpiryOnceAndNotAgainWhileItIsInFlight() {
        HealthCheckState state = state(4, START, false);
        checks.set(List.of(state));
        expiry.leadershipChanged(true);

        time.advance(Duration.ofSeconds(29));
        assertEquals(List.of(), proposed, "the deadline and the new-leader grace period have not passed");
        time.advance(Duration.ofSeconds(1));
        assertEquals(List.of(CatalogCommand.expire(CHECK, 4, START.plusSeconds(30), false)), proposed);

        time.advance(Duration.ofSeconds(5));
        assertEquals(1, proposed.size(), "an in-flight proposal is not duplicated");

        checks.set(List.of(state.asExpired()));
        replies.getFirst().complete(new RaftCommandResult.Success<>(state.asExpired()));
        time.advance(Duration.ofSeconds(5));
        assertEquals(1, proposed.size(), "a committed expiry is not proposed again");
    }

    @Test
    void aRenewalThatWinsTheRaceLeavesNothingToRetry() {
        checks.set(List.of(state(4, START, false)));
        expiry.leadershipChanged(true);
        time.advance(Duration.ofSeconds(30));

        checks.set(List.of(state(5, START.plusSeconds(29), false)));
        replies.getFirst().complete(new RaftCommandResult.NoOp<>());
        time.advance(Duration.ofSeconds(10));

        assertEquals(1, proposed.size(), "the renewed check is not due until its new deadline");
        time.advance(Duration.ofSeconds(19));
        assertEquals(CatalogCommand.expire(CHECK, 5, START.plusSeconds(59), false), proposed.get(1));
    }

    @Test
    void aFailedOrThrowingProposalIsRetriedOnTheNextEvaluation() {
        checks.set(List.of(state(4, START, false)));
        expiry.leadershipChanged(true);
        time.advance(Duration.ofSeconds(30));

        replies.getFirst().completeExceptionally(new IllegalStateException("not leader"));
        time.advance(INTERVAL);
        assertEquals(2, proposed.size());

        LeaderHealthExpiry throwing = new LeaderHealthExpiry(checks::get, command -> {
            proposed.add(command);
            throw new IllegalStateException("submission rejected");
        }, time, time, INTERVAL);
        throwing.leadershipChanged(true);
        time.advance(Duration.ofSeconds(30));
        int afterFirstThrow = proposed.size();
        time.advance(INTERVAL);
        assertEquals(afterFirstThrow + 1, proposed.size(), "a throwing proposer does not stop evaluation");
        throwing.close();
    }

    @Test
    void stepDownCancelsEvaluationAndReElectionRestartsTheGracePeriod() {
        checks.set(List.of(state(4, START, false)));
        expiry.leadershipChanged(true);
        time.advance(Duration.ofSeconds(30));
        assertEquals(1, proposed.size());

        expiry.leadershipChanged(false);
        assertEquals(0, time.pendingTasks());
        replies.getFirst().complete(new RaftCommandResult.NoOp<>());
        time.advance(Duration.ofMinutes(5));
        assertEquals(1, proposed.size(), "no evaluation runs after stepping down");

        expiry.leadershipChanged(true);
        time.advance(Duration.ofSeconds(29));
        assertEquals(1, proposed.size(), "the new term grants a full TTL before expiring");
        time.advance(Duration.ofSeconds(1));
        assertEquals(2, proposed.size());
        assertEquals(CatalogCommand.expire(CHECK, 4, START.plusSeconds(30), false), proposed.get(1),
                "the command still names the stored deadline");
    }

    @Test
    void repeatedLeadershipNotificationsDoNotRestartTheGracePeriod() {
        checks.set(List.of(state(4, START, false)));
        expiry.leadershipChanged(true);
        time.advance(Duration.ofSeconds(20));

        expiry.leadershipChanged(true);
        time.advance(Duration.ofSeconds(10));

        assertEquals(1, proposed.size());
        assertEquals(1, time.pendingTasks(), "exactly one evaluation timer is armed");
    }

    @Test
    void evaluationWakesAtTheNextDueInstantWhenItPrecedesTheInterval() {
        LeaderHealthExpiry slow = new LeaderHealthExpiry(checks::get, command -> {
            proposed.add(command);
            return new CompletableFuture<>();
        }, time, time, Duration.ofMinutes(1));
        checks.set(List.of(state(4, START.minusSeconds(20), false)));
        slow.leadershipChanged(true);
        time.runDue();

        assertEquals(START.plusSeconds(30), time.nextTaskAt(), "the grace period ends before the next interval");
        time.advance(Duration.ofSeconds(30));
        assertEquals(1, proposed.size());
        slow.close();
    }

    @Test
    void silentNodesAreExpiredOnlyByTheLeaderAndNeverProposedTwiceWhileInFlight() {
        AgentInfo silent = new AgentInfo("agent-1", "host", "127.0.0.1", 8080);
        silent.setRegistrationTime(START);
        silent.setStatus(AgentStatus.HEALTHY);
        LeaderHealthExpiry withNodes = new LeaderHealthExpiry(checks::get, () -> List.of(silent),
                new NodeExpiryPolicy(Duration.ofSeconds(90), Duration.ofSeconds(60)), command -> {
                    proposed.add(command);
                    CompletableFuture<RaftCommandResult<?>> reply = new CompletableFuture<>();
                    replies.add(reply);
                    return reply;
                }, time, time, INTERVAL);

        time.advance(Duration.ofMinutes(5));
        assertEquals(List.of(), proposed, "a follower never expires nodes");

        withNodes.leadershipChanged(true);
        time.advance(Duration.ofSeconds(89));
        assertEquals(List.of(), proposed, "the new leader grants a full TTL");
        time.advance(Duration.ofSeconds(1));
        assertEquals(List.of(AgentCommand.expire("agent-1", START, false, START.plusSeconds(390))), proposed);

        time.advance(Duration.ofSeconds(10));
        assertEquals(1, proposed.size(), "commands differing only in their proposal time are the same expiry");

        replies.getFirst().complete(new RaftCommandResult.Success<>(silent));
        silent.setStatus(AgentStatus.UNREACHABLE);
        time.advance(Duration.ofSeconds(49));
        assertEquals(1, proposed.size(), "the reap delay runs from the grace-adjusted deadline");
        time.advance(Duration.ofSeconds(1));
        assertEquals(AgentCommand.expire("agent-1", START, true, START.plusSeconds(450)), proposed.get(1));
        withNodes.close();
    }

    @Test
    void closeIsTerminal() {
        checks.set(List.of(state(4, START, false)));
        expiry.leadershipChanged(true);

        expiry.close();
        expiry.leadershipChanged(true);
        time.advance(Duration.ofMinutes(5));

        assertEquals(List.of(), proposed);
        assertEquals(0, time.pendingTasks());
    }

    private static HealthCheckState state(long sequence, Instant acceptedAt, boolean expired) {
        HealthObservation observation = new HealthObservation(CHECK, ServiceHealth.PASSING, sequence,
                acceptedAt, 30_000, true, "");
        return new HealthCheckState(observation, acceptedAt, acceptedAt.plusSeconds(30), expired);
    }
}

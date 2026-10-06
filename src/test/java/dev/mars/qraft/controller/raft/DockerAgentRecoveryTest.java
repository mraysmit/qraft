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

import dev.mars.qraft.testing.fault.ExpectedDockerErrors;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.containers.ComposeContainer;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mars.qraft.controller.raft.DockerHealthApi.agentNode;
import static dev.mars.qraft.controller.raft.DockerHealthApi.anyCheckExpired;
import static dev.mars.qraft.controller.raft.DockerHealthApi.check;
import static dev.mars.qraft.controller.raft.DockerHealthApi.httpSequence;
import static dev.mars.qraft.controller.raft.DockerHealthApi.instanceCount;
import static dev.mars.qraft.controller.raft.DockerHealthApi.leaderIndex;
import static dev.mars.qraft.controller.raft.DockerHealthApi.passingWeb;
import static dev.mars.qraft.controller.raft.DockerHealthApi.passingWithBothChecks;
import static dev.mars.qraft.controller.raft.DockerHealthApi.raftStatus;
import static dev.mars.qraft.controller.raft.DockerHealthApi.registerFiller;
import static dev.mars.qraft.controller.raft.DockerHealthApi.snapshotLastIndex;
import static dev.mars.qraft.controller.raft.DockerHealthApi.status;
import static dev.mars.qraft.controller.raft.DockerHealthApi.webEntry;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Docker tests of recovery with a client-mode agent container that uses the restart profile: 15-second
 * check TTLs, a 30-second deregistration delay, and 5-second controller contact freshness.
 *
 * <p>All three server containers are killed for longer than the check TTL while the agent runs. The agent
 * stays live and becomes unready. It is then frozen with {@code docker pause}, so everything the restarted
 * servers hold can only have come from their own WAL and snapshots, and nothing can renew the checks
 * whose deadlines passed during the outage. Every server recovers the node, the service, and its checks
 * without expiring them, because the new leader grants a full TTL. Unfreezing the agent then resumes
 * renewals and readiness.
 *
 * <p>A killed follower misses committed health state and then installs the leader's snapshot. With the agent
 * frozen and filler entries written until the leader's snapshot covers every health observation and passes
 * the follower's log, the restarted follower can learn the checks only from the installed snapshot, and it
 * must then hold exactly the leader's health state.
 *
 * <p>A crashed agent that starts again registers the same node and service identities: no server ever
 * shows its instance removed or duplicated, and its sequence numbers continue above the pre-crash values. A
 * gracefully stopped agent deregisters its service, and registers it again when it starts.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
@Tag("docker")
@ExpectedDockerErrors({
        dev.mars.qraft.testing.fault.IntentionalError.RAFT_PEER_UNREACHABLE,
        dev.mars.qraft.testing.fault.IntentionalError.RAFT_SNAPSHOT_TRANSFER_INTERRUPTED
})
// Each test starts its own cluster and then waits on bounded conditions; the method budget exceeds their sum,
// so a failure reports the condition that was not met rather than the module's default method timeout.
@Timeout(value = 10, unit = TimeUnit.MINUTES)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("shared-docker-clusters")
class DockerAgentRecoveryTest {
    private static final Duration CHECK_TTL = Duration.ofSeconds(15);
    private static final List<String> SERVERS = List.of("controller1", "controller2", "controller3");

    @Test
    void aWholeClusterCrashOutlastingTheCheckTtlRecoversFromDiskAndRenewsInsteadOfExpiring() throws Exception {
        ComposeContainer cluster = SharedDockerCluster.startIsolatedThreeNodeClusterWithRestartAgent();
        boolean agentPaused = false;
        try {
            List<String> servers = SharedDockerCluster.getNodeEndpoints(cluster, 3);
            String agent = SharedDockerCluster.getServiceEndpoint(cluster, "agent");
            await().atMost(Duration.ofSeconds(60)).until(() ->
                    servers.stream().allMatch(DockerHealthApi::passingWithBothChecks)
                            && status(agent + "/health/ready") == 200);
            await().atMost(Duration.ofSeconds(60)).until(() ->
                    servers.stream().allMatch(server -> snapshotLastIndex(server) > 0));
            JsonNode node = agentNode(servers.getFirst());
            assertNotNull(node);
            String registrationTime = node.path("registrationTime").asText();
            long sequenceBeforeCrash = servers.stream().mapToLong(DockerHealthApi::httpSequence).max().orElseThrow();

            long crashedAt = System.nanoTime();
            for (String server : SERVERS) SharedDockerCluster.killContainer(cluster, server);

            await().atMost(Duration.ofSeconds(30)).until(() -> status(agent + "/health/ready") == 503);
            assertEquals(200, status(agent + "/health/live"), "losing every server never affects liveness");

            // Freeze the agent: from here on, the servers can only learn what they recover from disk.
            SharedDockerCluster.pauseContainer(cluster, "agent");
            agentPaused = true;
            outlast(crashedAt, CHECK_TTL.plusSeconds(2));
            for (String server : SERVERS) SharedDockerCluster.startContainer(cluster, server);

            AtomicBoolean sawExpiry = new AtomicBoolean();
            await().atMost(Duration.ofSeconds(120)).until(() -> {
                recordExpiry(servers, sawExpiry);
                return leaderIndex(servers) >= 0
                        && servers.stream().allMatch(server -> recovered(server, registrationTime, sequenceBeforeCrash))
                        && servers.stream().map(server -> check(server, "http")).distinct().count() == 1;
            });
            assertFalse(sawExpiry.get(),
                    "a check whose deadline passed during the outage waits a full TTL under the new leader");
            long recoveredSequence = httpSequence(servers.getFirst());

            SharedDockerCluster.unpauseContainer(cluster, "agent");
            agentPaused = false;
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                recordExpiry(servers, sawExpiry);
                return status(agent + "/health/ready") == 200
                        && servers.stream().allMatch(server -> passingWithBothChecks(server)
                                && httpSequence(server) > recoveredSequence && instanceCount(server) == 1);
            });
            assertFalse(sawExpiry.get(), "renewals resumed before any server expired a check");
        } finally {
            if (agentPaused) SharedDockerCluster.unpauseContainer(cluster, "agent");
            SharedDockerCluster.stopAndCapture(cluster);
        }
    }

    @Test
    void aKilledFollowerInstallsTheLeadersSnapshotAndThenHoldsTheLeadersHealthState() throws Exception {
        ComposeContainer cluster = SharedDockerCluster.startIsolatedThreeNodeClusterWithRestartAgent();
        boolean agentPaused = false;
        try {
            List<String> servers = SharedDockerCluster.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> leaderIndex(servers) >= 0
                    && servers.stream().allMatch(DockerHealthApi::passingWithBothChecks));
            int leaderIndex = leaderIndex(servers);
            String leader = servers.get(leaderIndex);
            int followerIndex = (leaderIndex + 1) % servers.size();
            String follower = servers.get(followerIndex);
            long followerLastLogIndex = raftStatus(follower, "lastLogIndex");
            SharedDockerCluster.killContainer(cluster, SERVERS.get(followerIndex));

            // Freeze the agent, so no observation can reach the follower through the log after it restarts.
            SharedDockerCluster.pauseContainer(cluster, "agent");
            agentPaused = true;
            await().atMost(Duration.ofSeconds(30)).until(() ->
                    raftStatus(leader, "commitIndex") == raftStatus(leader, "lastLogIndex"));
            long lastHealthEntry = raftStatus(leader, "lastLogIndex");
            AtomicInteger fillers = new AtomicInteger();
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                if (snapshotLastIndex(leader) > Math.max(lastHealthEntry, followerLastLogIndex)) return true;
                registerFiller(leader, fillers.incrementAndGet());
                return false;
            });

            SharedDockerCluster.startContainer(cluster, SERVERS.get(followerIndex));
            await().atMost(Duration.ofSeconds(90)).until(() -> {
                JsonNode expected = webEntry(leader);
                return raftStatus(follower, "snapshotLastIndex") > followerLastLogIndex
                        && expected != null && expected.path("checks").size() == 2
                        && expected.equals(webEntry(follower))
                        && Objects.equals(passingWeb(leader), passingWeb(follower));
            });

            SharedDockerCluster.unpauseContainer(cluster, "agent");
            agentPaused = false;
            await().atMost(Duration.ofSeconds(60)).until(() ->
                    servers.stream().allMatch(DockerHealthApi::passingWithBothChecks));
        } finally {
            if (agentPaused) SharedDockerCluster.unpauseContainer(cluster, "agent");
            SharedDockerCluster.stopAndCapture(cluster);
        }
    }

    @Test
    void aCrashedAgentRestartsUnderTheSameIdentityWithoutItsInstanceEverBeingRemovedOrDuplicated()
            throws Exception {
        ComposeContainer cluster = SharedDockerCluster.startIsolatedThreeNodeClusterWithRestartAgent();
        try {
            List<String> servers = SharedDockerCluster.getNodeEndpoints(cluster, 3);
            String agent = SharedDockerCluster.getServiceEndpoint(cluster, "agent");
            // The identity is deliberately not pinned here: a restart under a new identity must show up as a
            // second node and instance, not as a failure to start.
            await().atMost(Duration.ofSeconds(60)).until(() ->
                    servers.stream().allMatch(DockerAgentRecoveryTest::onePassingInstance)
                            && status(agent + "/health/ready") == 200);

            SharedDockerCluster.killContainer(cluster, "agent");
            // Record the committed sequence once every server agrees, so no observation from the killed process
            // can still be in flight and later pass for one from the new process.
            long sequenceBeforeRestart = awaitAgreedHttpCheck(servers).path("sequenceNumber").asLong();
            SharedDockerCluster.startContainer(cluster, "agent");

            AtomicBoolean sawRemovedOrDuplicated = new AtomicBoolean();
            await().atMost(Duration.ofSeconds(90)).until(() -> {
                for (String server : servers) {
                    int instances = instanceCount(server);
                    if (instances == 0 || instances > 1) sawRemovedOrDuplicated.set(true);
                }
                return status(agent + "/health/ready") == 200
                        && servers.stream().allMatch(server -> onePassingInstance(server)
                                && httpSequence(server) > sequenceBeforeRestart
                                && agentCount(server) == 1);
            });
            assertFalse(sawRemovedOrDuplicated.get(),
                    "a restarted agent keeps its identity, so its one instance is never removed or duplicated");
        } finally {
            SharedDockerCluster.stopAndCapture(cluster);
        }
    }

    @Test
    void aGracefullyStoppedAgentDeregistersItsServiceAndRegistersItAgainWhenItStarts() throws Exception {
        ComposeContainer cluster = SharedDockerCluster.startIsolatedThreeNodeClusterWithRestartAgent();
        try {
            List<String> servers = SharedDockerCluster.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() ->
                    servers.stream().allMatch(DockerHealthApi::passingWithBothChecks));

            // docker stop returns once the agent has exited, so its graceful deregistration has committed.
            SharedDockerCluster.stopContainer(cluster, "agent");
            await().atMost(Duration.ofSeconds(20)).until(() ->
                    servers.stream().allMatch(server -> instanceCount(server) == 0));

            SharedDockerCluster.startContainer(cluster, "agent");
            await().atMost(Duration.ofSeconds(90)).until(() -> servers.stream().allMatch(server ->
                    passingWithBothChecks(server) && instanceCount(server) == 1));
        } finally {
            SharedDockerCluster.stopAndCapture(cluster);
        }
    }

    /** Exactly one {@code web} instance, of any node, is discoverable as passing with both checks passing. */
    private static boolean onePassingInstance(String server) {
        JsonNode entries = DockerHealthApi.passingWeb(server);
        if (entries == null || entries.size() != 1) return false;
        JsonNode checks = entries.get(0).path("checks");
        return checks.size() == 2 && "PASSING".equals(checks.get(0).path("status").asText())
                && "PASSING".equals(checks.get(1).path("status").asText());
    }

    /** The {@code http} check once every server holds the same committed state for it. */
    private static JsonNode awaitAgreedHttpCheck(List<String> servers) {
        java.util.concurrent.atomic.AtomicReference<JsonNode> agreed = new java.util.concurrent.atomic.AtomicReference<>();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            List<JsonNode> checks = servers.stream().map(server -> check(server, "http")).toList();
            if (checks.stream().anyMatch(Objects::isNull) || checks.stream().distinct().count() != 1) return false;
            agreed.set(checks.getFirst());
            return true;
        });
        return agreed.get();
    }

    /** Number of registered nodes, or -1 when the server does not answer. */
    private static int agentCount(String server) {
        JsonNode agents = DockerHealthApi.get(server + "/api/v1/agents");
        return agents == null ? -1 : agents.size();
    }

    /** The node, one service instance, and both checks as they were before the crash, or newer. */
    private static boolean recovered(String server, String registrationTime, long sequenceBeforeCrash) {
        JsonNode node = agentNode(server);
        JsonNode tcp = check(server, "tcp");
        return node != null && registrationTime.equals(node.path("registrationTime").asText())
                && instanceCount(server) == 1
                && httpSequence(server) >= sequenceBeforeCrash
                && Objects.nonNull(tcp);
    }

    private static void recordExpiry(List<String> servers, AtomicBoolean sawExpiry) {
        for (String server : servers) {
            if (anyCheckExpired(server)) sawExpiry.set(true);
        }
    }

    /** The scenario needs the outage to outlast the check TTL; this wait defines it rather than synchronizing. */
    private static void outlast(long sinceNanos, Duration duration) throws InterruptedException {
        long remaining = sinceNanos + duration.toNanos() - System.nanoTime();
        if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
    }
}

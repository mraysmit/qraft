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

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.containers.ComposeContainer;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.mars.qraft.controller.raft.DockerHealthApi.agentNode;
import static dev.mars.qraft.controller.raft.DockerHealthApi.anyCheckExpired;
import static dev.mars.qraft.controller.raft.DockerHealthApi.check;
import static dev.mars.qraft.controller.raft.DockerHealthApi.httpSequence;
import static dev.mars.qraft.controller.raft.DockerHealthApi.instanceCount;
import static dev.mars.qraft.controller.raft.DockerHealthApi.leaderIndex;
import static dev.mars.qraft.controller.raft.DockerHealthApi.passingWithBothChecks;
import static dev.mars.qraft.controller.raft.DockerHealthApi.snapshotLastIndex;
import static dev.mars.qraft.controller.raft.DockerHealthApi.status;
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
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
@Tag("docker")
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
            cluster.stop();
        }
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

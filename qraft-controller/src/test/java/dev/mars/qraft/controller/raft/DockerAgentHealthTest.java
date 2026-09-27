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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.containers.ComposeContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.mars.qraft.controller.raft.DockerHealthApi.httpSequence;
import static dev.mars.qraft.controller.raft.DockerHealthApi.instanceCount;
import static dev.mars.qraft.controller.raft.DockerHealthApi.leaderIndex;
import static dev.mars.qraft.controller.raft.DockerHealthApi.passingWithBothChecks;
import static dev.mars.qraft.controller.raft.DockerHealthApi.webEntry;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker tests in which three server containers and one client-mode agent container run from the same
 * image. The agent's configuration declares HTTP and TCP checks against itself. Every server serves the
 * published checks through {@code passing} discovery, and publication survives the loss of the leader
 * container. Stopping the agent deregisters its service; killing it lets the leader expire its checks and
 * then deregister the service. An agent cut off from every server stays live and becomes unready while the
 * leader expires and deregisters its service, and it registers the service again when the partition heals.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
@Tag("docker")
// Each test starts its own cluster and then waits on bounded conditions; the method budget exceeds their sum,
// so a failure reports the condition that was not met rather than the module's default method timeout.
@Timeout(value = 10, unit = TimeUnit.MINUTES)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("shared-docker-clusters")
class DockerAgentHealthTest {

    @Test
    void anAgentContainersChecksReachEveryServerSurviveTheLeaderLossAndDeregisterOnGracefulStop() {
        ComposeContainer cluster = SharedDockerCluster.startIsolatedThreeNodeClusterWithAgent();
        try {
            List<String> endpoints = SharedDockerCluster.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> leaderIndex(endpoints) >= 0);
            await().atMost(Duration.ofSeconds(60)).until(() ->
                    endpoints.stream().allMatch(DockerHealthApi::passingWithBothChecks));

            int leader = leaderIndex(endpoints);
            long sequenceBeforeLoss = httpSequence(endpoints.get(leader));
            SharedDockerCluster.killContainer(cluster, "controller" + (leader + 1));
            List<String> survivors = new ArrayList<>(endpoints);
            survivors.remove(leader);

            await().atMost(Duration.ofSeconds(60)).until(() -> leaderIndex(survivors) >= 0
                    && survivors.stream().allMatch(endpoint -> passingWithBothChecks(endpoint)
                            && httpSequence(endpoint) > sequenceBeforeLoss));

            // Expiry would also remove the instance, but only after holding its checks critical for
            // deregisterAfterMs (5 s), far longer than a poll. A graceful stop deregisters directly, so no
            // server may ever show the checks expired.
            SharedDockerCluster.stopContainer(cluster, "agent");
            AtomicBoolean sawExpiry = new AtomicBoolean();
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).until(() -> {
                if (survivors.stream().anyMatch(DockerAgentHealthTest::anyCheckExpired)) sawExpiry.set(true);
                return survivors.stream().allMatch(endpoint -> instanceCount(endpoint) == 0);
            });
            assertFalse(sawExpiry.get(), "a graceful stop deregisters the service; it must not be left to expire");
        } finally {
            cluster.stop();
        }
    }

    @Test
    void aKilledAgentContainersChecksExpireAndTheLeaderThenDeregistersItsService() {
        ComposeContainer cluster = SharedDockerCluster.startIsolatedThreeNodeClusterWithAgent();
        try {
            List<String> endpoints = SharedDockerCluster.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() ->
                    endpoints.stream().allMatch(DockerHealthApi::passingWithBothChecks));

            SharedDockerCluster.killContainer(cluster, "agent");

            AtomicBoolean sawExpiry = new AtomicBoolean();
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                if (endpoints.stream().allMatch(DockerAgentHealthTest::expiredAndCritical)) sawExpiry.set(true);
                return sawExpiry.get() && endpoints.stream().allMatch(endpoint -> instanceCount(endpoint) == 0);
            });
            assertTrue(sawExpiry.get(), "every server showed the unrenewed checks expired before deregistration");
        } finally {
            cluster.stop();
        }
    }

    @Test
    void anAgentCutOffFromEveryServerIsExpiredAndDeregisteredThenRejoinsWhenThePartitionHeals() throws Exception {
        ComposeContainer cluster = SharedDockerCluster.startIsolatedThreeNodeClusterWithAgent();
        boolean partitioned = false;
        try {
            List<String> servers = SharedDockerCluster.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() ->
                    servers.stream().allMatch(DockerHealthApi::passingWithBothChecks)
                            && "200".equals(agentStatus(cluster, "/health/ready")));

            SharedDockerCluster.partitionContainer(cluster, "agent");
            partitioned = true;

            AtomicBoolean sawExpiry = new AtomicBoolean();
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                if (servers.stream().allMatch(DockerAgentHealthTest::expiredAndCritical)) sawExpiry.set(true);
                return sawExpiry.get() && servers.stream().allMatch(server -> instanceCount(server) == 0)
                        && "503".equals(agentStatus(cluster, "/health/ready"));
            });
            assertTrue("200".equals(agentStatus(cluster, "/health/live")), "a partition never affects liveness");

            SharedDockerCluster.restoreContainerNetwork(cluster, "agent");
            partitioned = false;
            await().atMost(Duration.ofSeconds(90)).until(() ->
                    servers.stream().allMatch(server -> passingWithBothChecks(server) && instanceCount(server) == 1)
                            && "200".equals(agentStatus(cluster, "/health/ready")));
        } finally {
            if (partitioned) SharedDockerCluster.restoreContainerNetwork(cluster, "agent");
            cluster.stop();
        }
    }

    /** The agent's own HTTP status for {@code path}, read inside its container because it may be partitioned. */
    private static String agentStatus(ComposeContainer cluster, String path) throws Exception {
        return SharedDockerCluster.execInService(cluster, "agent", "curl", "-s", "-o", "/dev/null",
                "-w", "%{http_code}", "--max-time", "5", "http://127.0.0.1:8080" + path).trim();
    }

    private static boolean anyCheckExpired(String endpoint) {
        JsonNode entry = webEntry(endpoint);
        if (entry == null) return false;
        for (JsonNode check : entry.path("checks")) {
            if (check.path("expired").asBoolean()) return true;
        }
        return false;
    }

    private static boolean expiredAndCritical(String endpoint) {
        JsonNode entry = webEntry(endpoint);
        if (entry == null) return false;
        boolean allExpired = entry.path("checks").size() == 2;
        for (JsonNode check : entry.path("checks")) allExpired &= check.path("expired").asBoolean();
        return allExpired && "CRITICAL".equals(entry.path("service").path("health").asText());
    }
}

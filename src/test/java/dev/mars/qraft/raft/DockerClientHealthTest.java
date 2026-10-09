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

import dev.mars.qraft.common.ClientStatus;

import dev.mars.qraft.testing.fault.ExpectedDockerErrorsHelper;
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

import static dev.mars.qraft.raft.DockerHealthApiHelper.httpSequence;
import static dev.mars.qraft.raft.DockerHealthApiHelper.instanceCount;
import static dev.mars.qraft.raft.DockerHealthApiHelper.leaderIndex;
import static dev.mars.qraft.raft.DockerHealthApiHelper.passingWithBothChecks;
import static dev.mars.qraft.raft.DockerHealthApiHelper.webEntry;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker tests in which three server containers and one client-mode client container run from the same
 * image. The client's configuration declares HTTP and TCP checks against itself. Every server serves the
 * published checks through {@code passing} discovery, and publication survives the loss of the leader
 * container. Stopping the client deregisters its service; killing it lets the leader expire its checks and
 * then deregister the service. A client cut off from every server stays live and becomes unready while the
 * leader expires and deregisters its service, and it registers the service again when the partition heals.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
@Tag("docker")
@ExpectedDockerErrorsHelper(dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE)
// Each test starts its own cluster and then waits on bounded conditions; the method budget exceeds their sum,
// so a failure reports the condition that was not met rather than the module's default method timeout.
@Timeout(value = 10, unit = TimeUnit.MINUTES)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("shared-docker-clusters")
class DockerClientHealthTest {

    @Test
    void aClientContainersChecksReachEveryServerSurviveTheLeaderLossAndDeregisterOnGracefulStop() {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeClusterWithClient();
        try {
            List<String> endpoints = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> leaderIndex(endpoints) >= 0);
            await().atMost(Duration.ofSeconds(60)).until(() ->
                    endpoints.stream().allMatch(DockerHealthApiHelper::passingWithBothChecks));

            int leader = leaderIndex(endpoints);
            long sequenceBeforeLoss = httpSequence(endpoints.get(leader));
            SharedDockerClusterFixture.killContainer(cluster, "server" + (leader + 1));
            List<String> survivors = new ArrayList<>(endpoints);
            survivors.remove(leader);

            await().atMost(Duration.ofSeconds(60)).until(() -> leaderIndex(survivors) >= 0
                    && survivors.stream().allMatch(endpoint -> passingWithBothChecks(endpoint)
                            && httpSequence(endpoint) > sequenceBeforeLoss));

            // Expiry would also remove the instance, but only after holding its checks critical for
            // deregisterAfterMs (5 s), far longer than a poll. A graceful stop deregisters directly, so no
            // server may ever show the checks expired.
            SharedDockerClusterFixture.stopContainer(cluster, "client");
            AtomicBoolean sawExpiry = new AtomicBoolean();
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).until(() -> {
                if (survivors.stream().anyMatch(DockerClientHealthTest::anyCheckExpired)) sawExpiry.set(true);
                return survivors.stream().allMatch(endpoint -> instanceCount(endpoint) == 0);
            });
            assertFalse(sawExpiry.get(), "a graceful stop deregisters the service; it must not be left to expire");
        } finally {
            SharedDockerClusterFixture.stopAndCapture(cluster);
        }
    }

    @Test
    void aKilledClientContainersChecksExpireAndTheLeaderThenDeregistersItsService() {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeClusterWithClient();
        try {
            List<String> endpoints = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() ->
                    endpoints.stream().allMatch(DockerHealthApiHelper::passingWithBothChecks));

            SharedDockerClusterFixture.killContainer(cluster, "client");

            AtomicBoolean sawExpiry = new AtomicBoolean();
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                if (endpoints.stream().allMatch(DockerClientHealthTest::expiredAndCritical)) sawExpiry.set(true);
                return sawExpiry.get() && endpoints.stream().allMatch(endpoint -> instanceCount(endpoint) == 0);
            });
            assertTrue(sawExpiry.get(), "every server showed the unrenewed checks expired before deregistration");
        } finally {
            SharedDockerClusterFixture.stopAndCapture(cluster);
        }
    }

    @Test
    void aClientCutOffFromEveryServerIsExpiredAndDeregisteredThenRejoinsWhenThePartitionHeals() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeClusterWithClient();
        boolean partitioned = false;
        try {
            List<String> servers = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() ->
                    servers.stream().allMatch(DockerHealthApiHelper::passingWithBothChecks)
                            && "200".equals(clientStatus(cluster, "/health/ready")));

            SharedDockerClusterFixture.partitionContainer(cluster, "client");
            partitioned = true;

            AtomicBoolean sawExpiry = new AtomicBoolean();
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                if (servers.stream().allMatch(DockerClientHealthTest::expiredAndCritical)) sawExpiry.set(true);
                return sawExpiry.get() && servers.stream().allMatch(server -> instanceCount(server) == 0)
                        && "503".equals(clientStatus(cluster, "/health/ready"));
            });
            assertEquals("200", clientStatus(cluster, "/health/live"), "a partition never affects liveness");

            SharedDockerClusterFixture.restoreContainerNetwork(cluster, "client");
            partitioned = false;
            await().atMost(Duration.ofSeconds(90)).until(() ->
                    servers.stream().allMatch(server -> passingWithBothChecks(server) && instanceCount(server) == 1)
                            && "200".equals(clientStatus(cluster, "/health/ready")));
        } finally {
            if (partitioned) SharedDockerClusterFixture.restoreContainerNetwork(cluster, "client");
            SharedDockerClusterFixture.stopAndCapture(cluster);
        }
    }

    /** The client's own HTTP status for {@code path}, read inside its container because it may be partitioned. */
    private static String clientStatus(ComposeContainer cluster, String path) throws Exception {
        return SharedDockerClusterFixture.execInService(cluster, "client", "curl", "-s", "-o", "/dev/null",
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

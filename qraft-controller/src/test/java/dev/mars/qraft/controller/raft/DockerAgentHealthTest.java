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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.mars.qraft.controller.raft.DockerHealthApi.httpSequence;
import static dev.mars.qraft.controller.raft.DockerHealthApi.instanceCount;
import static dev.mars.qraft.controller.raft.DockerHealthApi.leaderIndex;
import static dev.mars.qraft.controller.raft.DockerHealthApi.passingWithBothChecks;
import static dev.mars.qraft.controller.raft.DockerHealthApi.webEntry;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker tests in which three server containers and one client-mode agent container run from the same
 * image. The agent's configuration declares HTTP and TCP checks against itself. Every server serves the
 * published checks through {@code passing} discovery, and publication survives the loss of the leader
 * container. Stopping the agent deregisters its service; killing it lets the leader expire its checks and
 * then deregister the service.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
@Tag("docker")
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

            SharedDockerCluster.stopContainer(cluster, "agent");
            await().atMost(Duration.ofSeconds(60)).until(() ->
                    survivors.stream().allMatch(endpoint -> instanceCount(endpoint) == 0));
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

    private static boolean expiredAndCritical(String endpoint) {
        JsonNode entry = webEntry(endpoint);
        if (entry == null) return false;
        boolean allExpired = entry.path("checks").size() == 2;
        for (JsonNode check : entry.path("checks")) allExpired &= check.path("expired").asBoolean();
        return allExpired && "CRITICAL".equals(entry.path("service").path("health").asText());
    }
}

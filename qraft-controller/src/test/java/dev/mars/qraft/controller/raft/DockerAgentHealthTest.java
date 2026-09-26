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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.containers.ComposeContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void anAgentContainersChecksReachEveryServerSurviveTheLeaderLossAndDeregisterOnGracefulStop() {
        ComposeContainer cluster = SharedDockerCluster.startIsolatedThreeNodeClusterWithAgent();
        try {
            List<String> endpoints = SharedDockerCluster.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> leaderIndex(endpoints) >= 0);
            await().atMost(Duration.ofSeconds(60)).until(() -> endpoints.stream().allMatch(this::passingWithBothChecks));

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
            await().atMost(Duration.ofSeconds(60)).until(() -> endpoints.stream().allMatch(this::passingWithBothChecks));

            SharedDockerCluster.killContainer(cluster, "agent");

            AtomicBoolean sawExpiry = new AtomicBoolean();
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                if (endpoints.stream().allMatch(this::expiredAndCritical)) sawExpiry.set(true);
                return sawExpiry.get() && endpoints.stream().allMatch(endpoint -> instanceCount(endpoint) == 0);
            });
            assertTrue(sawExpiry.get(), "every server showed the unrenewed checks expired before deregistration");
        } finally {
            cluster.stop();
        }
    }

    /** The agent's {@code web} instance is discoverable as passing, with both of its checks passing. */
    private boolean passingWithBothChecks(String endpoint) {
        JsonNode entries = get(endpoint + "/v1/health/service/web?passing");
        if (entries == null || entries.size() != 1) return false;
        JsonNode checks = entries.get(0).path("checks");
        return "docker-agent".equals(entries.get(0).path("service").path("nodeId").asText())
                && checks.size() == 2
                && "PASSING".equals(checks.get(0).path("status").asText())
                && "PASSING".equals(checks.get(1).path("status").asText());
    }

    private boolean expiredAndCritical(String endpoint) {
        JsonNode entries = get(endpoint + "/v1/health/service/web");
        if (entries == null || entries.size() != 1) return false;
        JsonNode entry = entries.get(0);
        boolean allExpired = entry.path("checks").size() == 2;
        for (JsonNode check : entry.path("checks")) allExpired &= check.path("expired").asBoolean();
        return allExpired && "CRITICAL".equals(entry.path("service").path("health").asText());
    }

    private long httpSequence(String endpoint) {
        JsonNode entries = get(endpoint + "/v1/health/service/web");
        if (entries == null || entries.size() != 1) return -1;
        for (JsonNode check : entries.get(0).path("checks")) {
            if ("http".equals(check.path("checkId").asText())) return check.path("sequenceNumber").asLong();
        }
        return -1;
    }

    private int instanceCount(String endpoint) {
        JsonNode entries = get(endpoint + "/v1/health/service/web");
        return entries == null ? -1 : entries.size();
    }

    /** Index of the only reachable server reporting itself leader, or -1 while there is not exactly one. */
    private static int leaderIndex(List<String> endpoints) {
        int leader = -1;
        for (int index = 0; index < endpoints.size(); index++) {
            JsonNode status = get(endpoints.get(index) + "/raft/status");
            if (status != null && "LEADER".equals(status.path("state").asText())) {
                if (leader >= 0) return -1;
                leader = index;
            }
        }
        return leader;
    }

    private static JsonNode get(String uri) {
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(uri))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? JSON.readTree(response.body()) : null;
        } catch (Exception unreachable) {
            return null;
        }
    }
}

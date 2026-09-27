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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.testcontainers.containers.ComposeContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the shared three-server container cluster starts with every server passing its health check
 * and elects exactly one leader. Failover and partitions are covered by {@link DockerDurableRestartTest}
 * and {@link DockerRunningPartitionTest}.
 *
 * <p>Requires Docker. Excluded from the default build; run with
 * {@code mvn test -Dgroups=docker -Dtest.excludedGroups=}.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 1.0
 * @since 2025-08-20
 */
@Tag("docker")
@Execution(ExecutionMode.CONCURRENT)
public class DockerRaftClusterTest {

    private static final Logger logger = Logger.getLogger(DockerRaftClusterTest.class.getName());
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ComposeContainer environment = SharedDockerCluster.getThreeNodeCluster();

    private List<String> nodeEndpoints;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        logger.info("Starting test: " + testInfo.getDisplayName());
        nodeEndpoints = SharedDockerCluster.getNodeEndpoints(environment, 3);
        // Testcontainers already verified /health returns 200 for all nodes
        logger.info("All nodes are healthy and ready for testing");
    }

    @AfterEach
    void tearDown(TestInfo testInfo) {
        logger.info("Completed test: " + testInfo.getDisplayName());
    }

    @Test
    void testClusterStartupAndHealthCheck() {
        // Verify all nodes are running and healthy
        for (int i = 0; i < nodeEndpoints.size(); i++) {
            final int nodeIndex = i;
            String endpoint = nodeEndpoints.get(i);

            assertDoesNotThrow(() -> {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint + "/health"))
                        .timeout(Duration.ofSeconds(5))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());

                JsonNode healthData = objectMapper.readTree(response.body());
                assertEquals("passing", healthData.get("status").asText());

                logger.info("Node " + (nodeIndex + 1) + " health check passed");
            });
        }
    }

    @Test
    void testLeaderElection() {
        // Wait for leader election to complete with diagnostic logging
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(2))
                .conditionEvaluationListener(condition -> {
                    if (!condition.isSatisfied()) {
                        logger.info("Waiting for leader election to complete...");
                    }
                })
                .until(this::hasExactlyOneLeader);

        // Verify exactly one leader exists
        int leaderCount = 0;
        String leaderId = null;
        
        for (int i = 0; i < nodeEndpoints.size(); i++) {
            try {
                String nodeState = getNodeState(i);
                if ("LEADER".equals(nodeState)) {
                    leaderCount++;
                    leaderId = "controller" + (i + 1);
                }
            } catch (Exception e) {
                logger.warning("Failed to get state for node " + (i + 1) + ": " + e.getMessage());
            }
        }

        assertEquals(1, leaderCount, "Exactly one leader should be elected");
        assertNotNull(leaderId, "Leader ID should be identified");
        logger.info("Leader elected: " + leaderId);
    }

    // Helper methods

    private boolean allNodesHealthy() {
        for (String endpoint : nodeEndpoints) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint + "/health"))
                        .timeout(Duration.ofSeconds(5))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private boolean hasExactlyOneLeader() {
        int leaderCount = 0;
        StringBuilder stateLog = new StringBuilder();
        for (int i = 0; i < nodeEndpoints.size(); i++) {
            try {
                String state = getNodeState(i);
                stateLog.append("controller").append(i + 1).append("=").append(state).append(" ");
                if ("LEADER".equals(state)) {
                    leaderCount++;
                }
            } catch (Exception e) {
                // Node might not be ready yet or HTTP error
                stateLog.append("controller").append(i + 1).append("=ERROR(").append(e.getClass().getSimpleName()).append(") ");
                logger.warning("Node " + (i + 1) + " error: " + e.getMessage());
            }
        }
        boolean result = leaderCount == 1;
        logger.info("Cluster state: " + stateLog + "| leaders=" + leaderCount + " | result=" + result);
        return result;
    }

    private String getNodeState(int nodeIndex) throws Exception {
        String endpoint = nodeEndpoints.get(nodeIndex);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/raft/status"))
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            JsonNode statusData = objectMapper.readTree(response.body());
            return statusData.get("state").asText();
        } else {
            throw new RuntimeException("Failed to get node state: HTTP " + response.statusCode());
        }
    }
}

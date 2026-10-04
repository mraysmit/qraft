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
import com.fasterxml.jackson.databind.node.MissingNode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests that the shared three-server container cluster starts with every server passing its health check
 * and elects exactly one leader, which every server names as leader in the leader's own term. Failover and partitions are covered by {@link DockerDurableRestartTest}
 * and {@link DockerRunningPartitionTest}.
 *
 * <p>Requires Docker. Excluded from the default build; run with
 * {@code mvn test -Dgroups=docker -Dtest.excludedGroups=}.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 1.1
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
    void everyServerReportsPassingHealth() {
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
    void theClusterElectsOneLeaderThatEveryServerFollowsInTheSameTerm() {
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> agreedLeader(statuses()) != null);

        List<JsonNode> statuses = statuses();
        String leader = agreedLeader(statuses);
        assertNotNull(leader, "one leader, named by every server in one term: " + statuses);
        logger.info("Leader elected: " + leader + " " + statuses);
    }

    // Helper methods

    /**
     * The leader's ID when exactly one server leads and every server names it as leader in the leader's term;
     * otherwise null.
     */
    private static String agreedLeader(List<JsonNode> statuses) {
        List<JsonNode> leaders = statuses.stream()
                .filter(status -> "LEADER".equals(status.path("state").asText()))
                .toList();
        if (leaders.size() != 1) return null;
        String leaderId = leaders.getFirst().path("nodeId").asText();
        long term = leaders.getFirst().path("term").asLong();
        boolean followed = statuses.stream().allMatch(status ->
                leaderId.equals(status.path("leaderId").asText()) && status.path("term").asLong() == term);
        return followed ? leaderId : null;
    }

    /** Each server's {@code /raft/status}; a server that cannot answer counts as a missing status. */
    private List<JsonNode> statuses() {
        List<JsonNode> statuses = new ArrayList<>();
        for (String endpoint : nodeEndpoints) {
            try {
                HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create(endpoint + "/raft/status"))
                        .timeout(Duration.ofSeconds(5))
                        .build(), HttpResponse.BodyHandlers.ofString());
                statuses.add(response.statusCode() == 200
                        ? objectMapper.readTree(response.body())
                        : MissingNode.getInstance());
            } catch (Exception unavailable) {
                logger.warning(endpoint + " status unavailable: " + unavailable.getMessage());
                statuses.add(MissingNode.getInstance());
            }
        }
        return statuses;
    }
}

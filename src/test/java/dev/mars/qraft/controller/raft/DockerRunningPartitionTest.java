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

import dev.mars.qraft.testing.fault.ExpectedDockerErrorsHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.ComposeContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests a three-server cluster in containers when one server is cut off from the others while its process
 * keeps running: no restart, so it keeps whatever role and term it had.
 *
 * <ul>
 * <li>A partitioned leader steps down and refuses writes. Meanwhile the majority elects a leader and
 * commits. On heal every server converges on the majority's history, without the refused write.</li>
 * <li>A partitioned follower keeps campaigning but never leads, and reports unready. The majority keeps
 * serving, and the follower catches up on heal.</li>
 * </ul>
 *
 * <p>The partitioned server is observed from inside its own container, since its published port is cut
 * off with its networks.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
@Tag("docker")
@ExpectedDockerErrorsHelper(dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class DockerRunningPartitionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static ComposeContainer cluster;
    private static List<String> endpoints;

    @BeforeAll
    static void startCluster() {
        cluster = SharedDockerClusterFixture.startIsolatedThreeNodeCluster();
        endpoints = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
    }

    @AfterAll
    static void stopCluster() {
        if (cluster != null) SharedDockerClusterFixture.stopAndCapture(cluster);
    }

    @Test
    void aPartitionedLeaderStepsDownAndTheMajorityCommitsWithoutIt() throws Exception {
        int leader = awaitSingleLeader();
        String leaderService = service(leader);
        List<String> majority = without(leader);
        String before = "before-" + System.nanoTime();
        register(endpoints.get(leader), before);
        await().atMost(Duration.ofSeconds(30)).until(() -> everyNodeHas(endpoints, before));

        SharedDockerClusterFixture.partitionContainer(cluster, leaderService);
        String during = "during-" + System.nanoTime();
        String refused = "refused-" + System.nanoTime();
        try {
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                    .until(() -> !"LEADER".equals(insideStatus(leaderService).path("state").asText()));
            assertEquals(503, insideRegister(leaderService, refused),
                    "a leader that lost its majority must not accept a write");
            assertEquals(503, insideReadiness(leaderService), "a server cut off from its majority is unready");

            await().atMost(Duration.ofSeconds(30)).until(() -> leaders(majority) == 1);
            register(currentLeader(majority), during);
            await().atMost(Duration.ofSeconds(30)).until(() -> everyNodeHas(majority, during));
        } finally {
            SharedDockerClusterFixture.restoreContainerNetwork(cluster, leaderService);
        }

        await().atMost(Duration.ofSeconds(60)).until(() -> leaders(endpoints) == 1);
        await().atMost(Duration.ofSeconds(60)).until(() -> everyNodeHas(endpoints, during));
        assertTrue(everyNodeHas(endpoints, before));
        for (String endpoint : endpoints) {
            assertFalse(has(endpoint, refused), "the refused write must not reach " + endpoint);
        }
    }

    @Test
    void aPartitionedFollowerCampaignsButNeverLeadsWhileTheMajorityServes() throws Exception {
        int leader = awaitSingleLeader();
        int follower = (leader + 1) % endpoints.size();
        String followerService = service(follower);
        List<String> majority = without(follower);
        long termBefore = insideStatus(followerService).path("term").asLong();

        SharedDockerClusterFixture.partitionContainer(cluster, followerService);
        String during = "while-partitioned-" + System.nanoTime();
        List<String> followerStates = new ArrayList<>();
        try {
            // Two elections of its own prove it is campaigning; every observation is recorded.
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
                JsonNode status = insideStatus(followerService);
                followerStates.add(status.path("state").asText());
                return status.path("term").asLong() >= termBefore + 2;
            });
            assertFalse(followerStates.contains("LEADER"), "a single server is never a majority: " + followerStates);
            assertNotEquals(200, insideReadiness(followerService));

            register(currentLeader(majority), during);
            await().atMost(Duration.ofSeconds(30)).until(() -> everyNodeHas(majority, during));
        } finally {
            SharedDockerClusterFixture.restoreContainerNetwork(cluster, followerService);
        }

        await().atMost(Duration.ofSeconds(60)).until(() -> leaders(endpoints) == 1);
        await().atMost(Duration.ofSeconds(60)).until(() -> everyNodeHas(endpoints, during));
        await().atMost(Duration.ofSeconds(60)).until(() -> ready(endpoints.get(follower)));
    }

    private static int awaitSingleLeader() {
        await().atMost(Duration.ofSeconds(90)).until(() -> endpoints.stream().allMatch(DockerRunningPartitionTest::ready));
        await().atMost(Duration.ofSeconds(60)).until(() -> leaders(endpoints) == 1);
        for (int index = 0; index < endpoints.size(); index++) {
            if (isLeader(endpoints.get(index))) return index;
        }
        throw new AssertionError("the leader changed while being located");
    }

    private static String service(int index) {
        return "controller" + (index + 1);
    }

    private static List<String> without(int index) {
        List<String> others = new ArrayList<>(endpoints);
        others.remove(index);
        return others;
    }

    private static long leaders(List<String> reachable) {
        return reachable.stream().filter(DockerRunningPartitionTest::isLeader).count();
    }

    private static String currentLeader(List<String> reachable) {
        return reachable.stream().filter(DockerRunningPartitionTest::isLeader).findFirst()
                .orElseThrow(() -> new AssertionError("no leader among " + reachable));
    }

    private static boolean isLeader(String endpoint) {
        try {
            return "LEADER".equals(JSON.readTree(get(endpoint + "/raft/status").body()).path("state").asText());
        } catch (Exception unreachable) {
            return false;
        }
    }

    private static boolean ready(String endpoint) {
        try {
            return get(endpoint + "/health/ready").statusCode() == 200;
        } catch (Exception unreachable) {
            return false;
        }
    }

    private static boolean everyNodeHas(List<String> reachable, String serviceName) {
        return reachable.stream().allMatch(endpoint -> has(endpoint, serviceName));
    }

    private static boolean has(String endpoint, String serviceName) {
        try {
            HttpResponse<String> response = get(endpoint + "/v1/catalog/service/" + serviceName);
            return response.statusCode() == 200 && JSON.readTree(response.body()).size() == 1;
        } catch (Exception unreachable) {
            return false;
        }
    }

    private static void register(String endpoint, String serviceName) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/v1/agent/service/register"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Qraft-Node", "partition-test")
                .PUT(HttpRequest.BodyPublishers.ofString(registration(serviceName)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }

    private static String registration(String serviceName) {
        return "{\"serviceId\":\"" + serviceName + "-1\",\"serviceName\":\"" + serviceName
                + "\",\"address\":\"127.0.0.1\",\"port\":8080}";
    }

    private static HttpResponse<String> get(String uri) throws Exception {
        return HTTP.send(HttpRequest.newBuilder().uri(URI.create(uri)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode insideStatus(String service) throws Exception {
        return JSON.readTree(SharedDockerClusterFixture.execInService(cluster, service,
                "curl", "-s", "--max-time", "5", "http://localhost:8080/raft/status"));
    }

    private static int insideReadiness(String service) throws Exception {
        return Integer.parseInt(SharedDockerClusterFixture.execInService(cluster, service,
                "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "5",
                "http://localhost:8080/health/ready").trim());
    }

    private static int insideRegister(String service, String serviceName) throws Exception {
        return Integer.parseInt(SharedDockerClusterFixture.execInService(cluster, service,
                "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "15",
                "-X", "PUT", "-H", "Content-Type: application/json", "-H", "X-Qraft-Node: partition-test",
                "--data", registration(serviceName), "http://localhost:8080/v1/agent/service/register").trim());
    }
}

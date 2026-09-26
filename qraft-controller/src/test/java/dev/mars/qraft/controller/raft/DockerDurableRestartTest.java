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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker tests for durable restart of a three-node cluster: catalog, term and snapshot survival,
 * killed follower and leader recovery, corrupt-follower readiness, and volume ownership.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-22
 * @version 1.0
 */
@Tag("docker")
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("shared-docker-clusters")
class DockerDurableRestartTest {
    private static final String TEST_TENANT = "platform";
    private static final String TEST_NAMESPACE = "restart";
    private static final String TEST_NODE = "restart-test";
    private static final Map<String, String> REGISTRATION_HEADERS = Map.of(
            "X-Qraft-Tenant", TEST_TENANT,
            "X-Qraft-Namespace", TEST_NAMESPACE,
            "X-Qraft-Node", TEST_NODE);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ComposeContainer CLUSTER = SharedDockerCluster.getThreeNodeCluster();

    @Test
    void committedCatalogAndTermSurviveWholeClusterRestart() throws Exception {
        List<String> endpoints = SharedDockerCluster.getNodeEndpoints(CLUSTER, 3);
        String serviceName = "restart-" + System.nanoTime();
        List<String> serviceIds = List.of(serviceName + "-1", serviceName + "-2", serviceName + "-3");

        await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
        for (int i = 0; i < serviceIds.size(); i++) {
            registerOnLeader(endpoints, serviceIds.get(i), serviceName, 8100 + i);
        }
        await().atMost(Duration.ofSeconds(30))
                .until(() -> everyNodeContains(endpoints, serviceName, serviceIds));
        long termBeforeRestart = maximumTerm(endpoints);

        SharedDockerCluster.restartCluster(CLUSTER, 3);

        await().atMost(Duration.ofSeconds(90)).until(() -> allNodesReady(endpoints));
        await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
        await().atMost(Duration.ofSeconds(30))
                .until(() -> everyNodeContains(endpoints, serviceName, serviceIds));
        assertTrue(maximumTerm(endpoints) >= termBeforeRestart,
                "the recovered cluster must not move its durable term backwards");
    }

    @Test
    void snapshotAndPostSnapshotWalSuffixSurviveWholeClusterRestart() throws Exception {
        List<String> endpoints = SharedDockerCluster.getNodeEndpoints(CLUSTER, 3);
        String serviceName = "snapshot-restart-" + System.nanoTime();
        List<String> snapshottedIds = List.of(
                serviceName + "-1", serviceName + "-2", serviceName + "-3",
                serviceName + "-4", serviceName + "-5");

        await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
        for (int i = 0; i < snapshottedIds.size(); i++) {
            registerOnLeader(endpoints, snapshottedIds.get(i), serviceName, 8200 + i);
        }
        await().atMost(Duration.ofSeconds(30))
                .until(() -> everyNodeContains(endpoints, serviceName, snapshottedIds));
        await().atMost(Duration.ofSeconds(30))
                .until(() -> maximumSnapshotIndex(endpoints) >= 5);

        String suffixId = serviceName + "-suffix";
        registerOnLeader(endpoints, suffixId, serviceName, 8299);
        List<String> allIds = new java.util.ArrayList<>(snapshottedIds);
        allIds.add(suffixId);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> everyNodeContains(endpoints, serviceName, allIds));

        SharedDockerCluster.restartCluster(CLUSTER, 3);

        await().atMost(Duration.ofSeconds(90)).until(() -> allNodesReady(endpoints));
        await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
        await().atMost(Duration.ofSeconds(30))
                .until(() -> everyNodeContains(endpoints, serviceName, allIds));
        assertTrue(maximumSnapshotIndex(endpoints) >= 5,
                "restart must retain a published snapshot rather than rebuilding only from a full WAL");
    }

    @Test
    void killedFollowerReplaysMissedCommitAfterRestart() throws Exception {
        List<String> endpoints = SharedDockerCluster.getNodeEndpoints(CLUSTER, 3);
        await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
        int leaderIndex = leaderIndex(endpoints);
        int followerIndex = (leaderIndex + 1) % endpoints.size();
        String followerService = "controller" + (followerIndex + 1);
        String serviceName = "follower-rejoin-" + System.nanoTime();
        String serviceId = serviceName + "-1";

        SharedDockerCluster.killContainer(CLUSTER, followerService);
        try {
            registerOnLeader(endpoints, serviceId, serviceName, 8301);
            List<String> liveEndpoints = endpoints.stream()
                    .filter(endpoint -> !endpoint.equals(endpoints.get(followerIndex)))
                    .toList();
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> everyNodeContains(liveEndpoints, serviceName, List.of(serviceId)));
        } finally {
            SharedDockerCluster.startContainer(CLUSTER, followerService);
        }

        await().atMost(Duration.ofSeconds(60))
                .until(() -> nodeReady(endpoints.get(followerIndex)));
        await().atMost(Duration.ofSeconds(30))
                .until(() -> everyNodeContains(endpoints, serviceName, List.of(serviceId)));
        assertEquals("FOLLOWER", status(endpoints.get(followerIndex)).path("state").asText());
    }

    @Test
    void killedLeaderIsReplacedAndRejoinsWithCompleteCatalog() throws Exception {
        List<String> endpoints = SharedDockerCluster.getNodeEndpoints(CLUSTER, 3);
        await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
        int oldLeaderIndex = leaderIndex(endpoints);
        String oldLeaderService = "controller" + (oldLeaderIndex + 1);
        String serviceName = "leader-rejoin-" + System.nanoTime();
        String serviceId = serviceName + "-1";

        SharedDockerCluster.killContainer(CLUSTER, oldLeaderService);
        try {
            await().atMost(Duration.ofSeconds(60))
                    .until(() -> oneLeaderAmongTwoReachable(endpoints));
            registerOnLeader(endpoints, serviceId, serviceName, 8401);
        } finally {
            SharedDockerCluster.startContainer(CLUSTER, oldLeaderService);
        }

        await().atMost(Duration.ofSeconds(60))
                .until(() -> nodeReady(endpoints.get(oldLeaderIndex)));
        await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
        await().atMost(Duration.ofSeconds(30))
                .until(() -> everyNodeContains(endpoints, serviceName, List.of(serviceId)));
        await().atMost(Duration.ofSeconds(30)).until(() ->
                "FOLLOWER".equals(status(endpoints.get(oldLeaderIndex)).path("state").asText()));
    }

    @Test
    void retryAfterLeaderCrashConvergesToOneCatalogInstance() throws Exception {
        List<String> endpoints = SharedDockerCluster.getNodeEndpoints(CLUSTER, 3);
        await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
        int oldLeaderIndex = leaderIndex(endpoints);
        String oldLeaderService = "controller" + (oldLeaderIndex + 1);
        String serviceName = "crash-retry-" + System.nanoTime();
        String serviceId = serviceName + "-1";
        String body = registrationBody(serviceId, serviceName, 8501);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoints.get(oldLeaderIndex) + "/v1/agent/service/register"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Qraft-Tenant", TEST_TENANT)
                .header("X-Qraft-Namespace", TEST_NAMESPACE)
                .header("X-Qraft-Node", TEST_NODE)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();

        CompletableFuture<HttpResponse<String>> firstAttempt =
                HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        SharedDockerCluster.killContainer(CLUSTER, oldLeaderService);
        try {
            try {
                firstAttempt.get(6, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // A response is deliberately not guaranteed across the crash boundary.
            }
            await().atMost(Duration.ofSeconds(60))
                    .until(() -> oneLeaderAmongTwoReachable(endpoints));
            registerOnLeader(endpoints, serviceId, serviceName, 8501);
        } finally {
            SharedDockerCluster.startContainer(CLUSTER, oldLeaderService);
        }

        await().atMost(Duration.ofSeconds(60)).until(() -> allNodesReady(endpoints));
        await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
        await().atMost(Duration.ofSeconds(30))
                .until(() -> everyNodeContainsExactlyOnce(endpoints, serviceName, serviceId,
                        TEST_TENANT, TEST_NAMESPACE, TEST_NODE));
    }

    @Test
    void corruptFollowerStaysLiveButUnreadyWhileHealthyQuorumServes() throws Exception {
        ComposeContainer cluster = SharedDockerCluster.startIsolatedThreeNodeCluster();
        try {
            List<String> endpoints = SharedDockerCluster.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
            int leaderIndex = leaderIndex(endpoints);
            int corruptIndex = (leaderIndex + 1) % endpoints.size();
            String corruptService = "controller" + (corruptIndex + 1);
            String serviceName = "corruption-" + System.nanoTime();
            String serviceId = serviceName + "-1";
            registerOnLeader(endpoints, serviceId, serviceName, 8601);
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> everyNodeContains(endpoints, serviceName, List.of(serviceId)));

            SharedDockerCluster.stopContainer(cluster, corruptService);
            SharedDockerCluster.overwriteVolumeFileByte(
                    cluster, corruptService, "/app/data/raft.log", 0);
            SharedDockerCluster.startContainer(cluster, corruptService);

            List<String> healthyEndpoints = endpoints.stream()
                    .filter(endpoint -> !endpoint.equals(endpoints.get(corruptIndex)))
                    .toList();
            await().atMost(Duration.ofSeconds(60))
                    .until(() -> everyNodeContains(healthyEndpoints, serviceName, List.of(serviceId)));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                try {
                    HttpResponse<String> response = send(
                            endpoints.get(corruptIndex) + "/health/ready", "GET", null);
                    return response.statusCode() == 503 && response.body().contains("fenced");
                } catch (Exception ignored) {
                    return false;
                }
            });
            String logs = cluster.getContainerByServiceName(corruptService).orElseThrow().getLogs();
            assertTrue(logs.contains("raft.log") && logs.contains("corrupt at byte"), logs);
        } finally {
            cluster.stop();
        }
    }

    @Test
    void secondContainerCannotOwnAnActiveNodeVolume() throws Exception {
        List<String> endpoints = SharedDockerCluster.getNodeEndpoints(CLUSTER, 3);
        await().atMost(Duration.ofSeconds(60)).until(() -> allNodesReady(endpoints));
        await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
        String serviceName = "lock-owner-" + System.nanoTime();
        String serviceId = serviceName + "-1";
        registerOnLeader(endpoints, serviceId, serviceName, 8701);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> everyNodeContains(endpoints, serviceName, List.of(serviceId)));

        SharedDockerCluster.DockerCommandResult contender =
                SharedDockerCluster.runStorageLockContender(CLUSTER, "controller1");

        assertTrue(contender.exitCode() != 0, contender.output());
        assertTrue(contender.output().contains("/app/data"), contender.output());
        assertTrue(contender.output().toLowerCase().contains("lock"), contender.output());
        assertTrue(nodeReady(endpoints.getFirst()), "original volume owner must remain ready");
        assertTrue(everyNodeContains(endpoints, serviceName, List.of(serviceId)),
                "lock contender must not disturb the serving cluster");
    }

    @Test
    void partitionedFollowerCatchesUpBySnapshotAndSurvivesRestart() throws Exception {
        List<String> endpoints = SharedDockerCluster.getNodeEndpoints(CLUSTER, 3);
        await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
        int leaderIndex = leaderIndex(endpoints);
        int followerIndex = (leaderIndex + 1) % endpoints.size();
        String leaderService = "controller" + (leaderIndex + 1);
        String followerService = "controller" + (followerIndex + 1);
        List<String> majorityEndpoints = endpoints.stream()
                .filter(endpoint -> !endpoint.equals(endpoints.get(followerIndex)))
                .toList();
        long snapshotBefore = maximumSnapshotIndex(majorityEndpoints);
        String serviceName = "partition-snapshot-" + System.nanoTime();
        List<String> serviceIds = new java.util.ArrayList<>();

        SharedDockerCluster.isolateContainerNetwork(CLUSTER, followerService);
        try {
            for (int i = 0; i < 6; i++) {
                String serviceId = serviceName + "-" + i;
                serviceIds.add(serviceId);
                registerOnLeader(majorityEndpoints, serviceId, serviceName, 8800 + i);
            }
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> everyNodeContains(majorityEndpoints, serviceName, serviceIds));
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> maximumSnapshotIndex(majorityEndpoints) > snapshotBefore);
        } finally {
            SharedDockerCluster.restoreContainerNetwork(CLUSTER, followerService);
        }

        await().atMost(Duration.ofSeconds(90)).until(() -> allNodesReady(endpoints));
        await().atMost(Duration.ofSeconds(60))
                .until(() -> everyNodeContains(endpoints, serviceName, serviceIds));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            String leaderLogs = CLUSTER.getContainerByServiceName(leaderService).orElseThrow().getLogs();
            String followerLogs = CLUSTER.getContainerByServiceName(followerService).orElseThrow().getLogs();
            String diagnostic = "LEADER LOGS:\n" + leaderLogs + "\nFOLLOWER LOGS:\n" + followerLogs;
            assertTrue(leaderLogs.contains("Sending InstallSnapshot to lagging follower " + followerService),
                    diagnostic);
            // The isolated follower can advance its term and replace the original leader on
            // reconnection, so the receiver's durable install is the stable completion signal.
            assertTrue(followerLogs.contains("Snapshot installed: snapshotLastIndex="), diagnostic);
        });

        SharedDockerCluster.stopContainer(CLUSTER, followerService);
        SharedDockerCluster.startContainer(CLUSTER, followerService);
        await().atMost(Duration.ofSeconds(60))
                .until(() -> nodeReady(endpoints.get(followerIndex)));
        await().atMost(Duration.ofSeconds(30))
                .until(() -> everyNodeContains(endpoints, serviceName, serviceIds));
        assertTrue(status(endpoints.get(followerIndex)).path("snapshotLastIndex").asLong() > snapshotBefore);
    }

    private static void registerOnLeader(
            List<String> endpoints, String serviceId, String serviceName, int port) throws Exception {
        String body = registrationBody(serviceId, serviceName, port);
        for (String endpoint : endpoints) {
            try {
                HttpResponse<String> response = send(endpoint + "/v1/agent/service/register", "PUT", body,
                        REGISTRATION_HEADERS);
                if (response.statusCode() == 200) return;
                assertEquals(503, response.statusCode(), response.body());
            } catch (java.io.IOException ignored) {
                // A deliberately killed node is unavailable while the surviving quorum elects a leader.
            }
        }
        throw new AssertionError("no leader accepted registration " + serviceId);
    }

    private static String registrationBody(String serviceId, String serviceName, int port) {
        return """
                {"serviceId":"%s","serviceName":"%s",
                 "address":"127.0.0.1","port":%d,"tags":["restart"],
                 "metadata":{"scenario":"whole-cluster-restart"},"health":"PASSING"}
                """.formatted(serviceId, serviceName, port);
    }

    private static boolean everyNodeContains(
            List<String> endpoints, String serviceName, List<String> serviceIds) {
        return endpoints.stream().allMatch(endpoint -> {
            try {
                HttpResponse<String> response = send(
                        endpoint + "/v1/catalog/service/" + serviceName, "GET", null);
                return response.statusCode() == 200
                        && serviceIds.stream().allMatch(response.body()::contains);
            } catch (Exception ignored) {
                return false;
            }
        });
    }

    private static boolean everyNodeContainsExactlyOnce(
            List<String> endpoints, String serviceName, String serviceId,
            String tenantId, String namespace, String nodeId) {
        return endpoints.stream().allMatch(endpoint -> {
            try {
                HttpResponse<String> response = send(
                        endpoint + "/v1/catalog/service/" + serviceName, "GET", null);
                if (response.statusCode() != 200) return false;
                JsonNode instances = JSON.readTree(response.body());
                int matches = 0;
                for (JsonNode instance : instances) {
                    if (serviceId.equals(instance.path("serviceId").asText())
                            && tenantId.equals(instance.path("tenantId").asText())
                            && namespace.equals(instance.path("namespace").asText())
                            && nodeId.equals(instance.path("nodeId").asText())) matches++;
                }
                return matches == 1;
            } catch (Exception ignored) {
                return false;
            }
        });
    }

    private static boolean allNodesReady(List<String> endpoints) {
        return endpoints.stream().allMatch(DockerDurableRestartTest::nodeReady);
    }

    private static boolean nodeReady(String endpoint) {
        try {
            return send(endpoint + "/health/ready", "GET", null).statusCode() == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean exactlyOneLeader(List<String> endpoints) {
        int leaders = 0;
        for (String endpoint : endpoints) {
            try {
                JsonNode status = status(endpoint);
                if ("LEADER".equals(status.path("state").asText())) leaders++;
            } catch (Exception ignored) {
                return false;
            }
        }
        return leaders == 1;
    }

    private static int leaderIndex(List<String> endpoints) throws Exception {
        for (int i = 0; i < endpoints.size(); i++) {
            if ("LEADER".equals(status(endpoints.get(i)).path("state").asText())) return i;
        }
        throw new AssertionError("cluster has no leader");
    }

    private static boolean oneLeaderAmongTwoReachable(List<String> endpoints) {
        int reachable = 0;
        int leaders = 0;
        for (String endpoint : endpoints) {
            try {
                JsonNode nodeStatus = status(endpoint);
                reachable++;
                if ("LEADER".equals(nodeStatus.path("state").asText())) leaders++;
            } catch (Exception ignored) {
                // The killed node is expected to be unreachable.
            }
        }
        return reachable == 2 && leaders == 1;
    }

    private static long maximumTerm(List<String> endpoints) throws Exception {
        long maximum = -1;
        for (String endpoint : endpoints) {
            maximum = Math.max(maximum, status(endpoint).path("term").asLong(-1));
        }
        assertTrue(maximum >= 0, "raft status must expose the current term");
        return maximum;
    }

    private static long maximumSnapshotIndex(List<String> endpoints) throws Exception {
        long maximum = -1;
        for (String endpoint : endpoints) {
            maximum = Math.max(maximum, status(endpoint).path("snapshotLastIndex").asLong(-1));
        }
        return maximum;
    }

    private static JsonNode status(String endpoint) throws Exception {
        HttpResponse<String> response = send(endpoint + "/raft/status", "GET", null);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static HttpResponse<String> send(
            String uri, String method, String body) throws Exception {
        return send(uri, method, body, Map.of());
    }

    private static HttpResponse<String> send(
            String uri, String method, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(5));
        headers.forEach(request::header);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

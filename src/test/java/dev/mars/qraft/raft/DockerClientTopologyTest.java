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

import dev.mars.qraft.testing.fault.ExpectedDockerErrorsHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.mars.qraft.raft.DockerHealthApiHelper.get;
import static dev.mars.qraft.raft.DockerHealthApiHelper.httpSequence;
import static dev.mars.qraft.raft.DockerHealthApiHelper.instanceCount;
import static dev.mars.qraft.raft.DockerHealthApiHelper.leaderIndex;
import static dev.mars.qraft.raft.DockerHealthApiHelper.passingWithBothChecks;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Docker tests of clients and servers in topologies the single-client tests do not reach:
 * <ul>
 *   <li>a second client container registers the same local service ID as the first, reaching the leader
 *       through an offline first seed and then a follower, and each client's instance is its own;</li>
 *   <li>a client keeps publishing while the running leader is cut off from the network, and its checks
 *       never expire;</li>
 *   <li>every server is restarted in turn while the client publishes, and the service is never expired or
 *       removed.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-28
 * @version 1.0
 */
@Tag("docker")
@ExpectedDockerErrorsHelper({
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE,
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_SNAPSHOT_TRANSFER_INTERRUPTED
})
// Each test starts its own cluster and then waits on bounded conditions; the method budget exceeds their sum.
@Timeout(value = 10, unit = TimeUnit.MINUTES)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("shared-docker-clusters")
class DockerClientTopologyTest {
    private static final String FIRST_CLIENT = "docker-client";
    private static final String SECOND_CLIENT = "docker-client-2";

    @Test
    void aSecondClientWithTheSameServiceIdRegistersItsOwnInstanceThroughAnOfflineThenFollowerSeed() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeClusterWithClient();
        try {
            List<String> servers = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> leaderIndex(servers) >= 0
                    && servers.stream().allMatch(DockerHealthApiHelper::passingWithBothChecks));
            int leader = leaderIndex(servers);
            int follower = (leader + 1) % 3;
            List<String> seeds = List.of("http://offline-seed:8080", "http://server" + (follower + 1) + ":8080",
                    "http://server" + (leader + 1) + ":8080");

            try (SharedDockerClusterFixture.DetachedClient second = SharedDockerClusterFixture.startDetachedClient(
                    cluster, "client2", clientJson(SECOND_CLIENT, "client2", seeds))) {
                await().atMost(Duration.ofSeconds(60)).until(() -> servers.stream()
                        .allMatch(server -> Set.of(FIRST_CLIENT, SECOND_CLIENT).equals(webNodes(server))));

                second.stopGracefully();

                await().atMost(Duration.ofSeconds(60)).until(() -> servers.stream()
                        .allMatch(server -> Set.of(FIRST_CLIENT).equals(webNodes(server))));
                await().atMost(Duration.ofSeconds(30)).until(() ->
                        servers.stream().allMatch(DockerHealthApiHelper::passingWithBothChecks));
            }
        } finally {
            SharedDockerClusterFixture.stopAndCapture(cluster);
        }
    }

    @Test
    void aClientKeepsPublishingWhileTheRunningLeaderIsCutOff() {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeClusterWithClient();
        String cutOff = null;
        try {
            List<String> servers = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> leaderIndex(servers) >= 0
                    && servers.stream().allMatch(DockerHealthApiHelper::passingWithBothChecks));
            int leader = leaderIndex(servers);
            long sequenceBefore = httpSequence(servers.get(leader));
            List<String> majority = new ArrayList<>(servers);
            majority.remove(leader);

            // The old leader keeps running: it is disconnected, not stopped or restarted.
            cutOff = "server" + (leader + 1);
            SharedDockerClusterFixture.partitionContainer(cluster, cutOff);

            // Sequence numbers are seeded from the client's clock in milliseconds, so a sequence three seconds
            // past the one before the partition shows publications continuing through it.
            // A disconnect can leave established connections working, so the partition is only trusted once the
            // old leader itself, read inside its container, has stepped down for lost quorum.
            String isolated = cutOff;
            AtomicBoolean sawExpiry = new AtomicBoolean();
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(200)).until(() -> {
                if (majority.stream().anyMatch(DockerHealthApiHelper::anyCheckExpired)) sawExpiry.set(true);
                return !"LEADER".equals(ownState(cluster, isolated)) && leaderIndex(majority) >= 0
                        && majority.stream().allMatch(server ->
                                passingWithBothChecks(server) && httpSequence(server) > sequenceBefore + 3_000);
            });
            assertFalse(sawExpiry.get(), "the client's publications reach the majority throughout the partition");

            long majorityAtHeal = httpSequence(majority.getFirst());
            SharedDockerClusterFixture.restoreContainerNetwork(cluster, cutOff);
            cutOff = null;
            await().atMost(Duration.ofSeconds(90)).until(() -> leaderIndex(servers) >= 0
                    && servers.stream().allMatch(server -> passingWithBothChecks(server) && instanceCount(server) == 1)
                    && httpSequence(servers.get(leader)) >= majorityAtHeal);
        } finally {
            if (cutOff != null) SharedDockerClusterFixture.restoreContainerNetwork(cluster, cutOff);
            SharedDockerClusterFixture.stopAndCapture(cluster);
        }
    }

    @Test
    void restartingEveryServerInTurnNeverExpiresOrRemovesTheClientsService() {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeClusterWithClient();
        try {
            List<String> servers = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> leaderIndex(servers) >= 0
                    && servers.stream().allMatch(DockerHealthApiHelper::passingWithBothChecks));
            AtomicBoolean sawLoss = new AtomicBoolean();

            for (int restarted = 0; restarted < servers.size(); restarted++) {
                String service = "server" + (restarted + 1);
                List<String> running = new ArrayList<>(servers);
                running.remove(restarted);

                SharedDockerClusterFixture.stopContainer(cluster, service);
                await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
                    watchForLoss(running, sawLoss);
                    return leaderIndex(running) >= 0 && running.stream().allMatch(DockerHealthApiHelper::passingWithBothChecks);
                });
                SharedDockerClusterFixture.startContainer(cluster, service);
                await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(200)).until(() -> {
                    watchForLoss(running, sawLoss);
                    return leaderIndex(servers) >= 0 && servers.stream().allMatch(DockerHealthApiHelper::passingWithBothChecks);
                });
            }

            assertFalse(sawLoss.get(), "no running server ever showed the service expired or removed");
            servers.forEach(server -> assertEquals(1, instanceCount(server), server));
        } finally {
            SharedDockerClusterFixture.stopAndCapture(cluster);
        }
    }

    /** A server's own Raft state, read inside its container because its published port may be cut off. */
    private static String ownState(ComposeContainer cluster, String service) {
        try {
            String status = SharedDockerClusterFixture.execInService(cluster, service, "curl", "-s", "--max-time", "2",
                    "http://127.0.0.1:8080/raft/status");
            return new ObjectMapper().readTree(status).path("state").asText();
        } catch (Exception unanswered) {
            return "UNKNOWN";
        }
    }

    /** Records whether any running server shows the client's checks expired or its instance gone. */
    private static void watchForLoss(List<String> running, AtomicBoolean sawLoss) {
        for (String server : running) {
            if (DockerHealthApiHelper.anyCheckExpired(server) || instanceCount(server) == 0) sawLoss.set(true);
        }
    }

    /** The node IDs of every {@code web} instance a server lists, or an empty set when it does not answer. */
    private static Set<String> webNodes(String server) {
        JsonNode instances = get(server + "/v1/catalog/service/web");
        Set<String> nodes = new TreeSet<>();
        if (instances != null) instances.forEach(instance -> nodes.add(instance.path("nodeId").asText()));
        return nodes;
    }

    /** A client document like the acceptance client's: the same {@code web} service and checks. */
    private static String clientJson(String clientId, String address, List<String> seeds) {
        return """
                {"version":1,
                 "client":{"id":"%s","hostname":"%s","address":"%s","httpPort":8080,
                          "heartbeatIntervalMs":1000,"shutdownTimeoutMs":8000},
                 "servers":{"urls":[%s],"requestTimeoutMs":3000},
                 "catalog":{"registrationRetryMinMs":100,"registrationRetryMaxMs":1000,"contactFreshnessMs":5000,
                   "services":[{"id":"web","name":"web","address":"%s","port":8080,
                     "checks":[
                       {"id":"http","type":"http","url":"http://127.0.0.1:8080/health/live",
                        "intervalMs":1000,"timeoutMs":1000,"ttlMs":5000,"deregisterAfterMs":5000},
                       {"id":"tcp","type":"tcp","address":"127.0.0.1","intervalMs":1000,"timeoutMs":1000,
                        "ttlMs":5000}]}]},
                 "logging":{"directory":"/app/logs"}}
                """.formatted(clientId, clientId, address,
                String.join(",", seeds.stream().map(seed -> "\"" + seed + "\"").toList()), address);
    }
}

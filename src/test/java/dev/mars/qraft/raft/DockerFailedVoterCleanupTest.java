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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.containers.ComposeContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.DockerClusterApiHelper.cleanUp;
import static dev.mars.qraft.raft.DockerClusterApiHelper.clusterLogs;
import static dev.mars.qraft.raft.DockerClusterApiHelper.configuration;
import static dev.mars.qraft.raft.DockerClusterApiHelper.configurationOrEmpty;
import static dev.mars.qraft.raft.DockerClusterApiHelper.everyNodeHas;
import static dev.mars.qraft.raft.DockerClusterApiHelper.exactlyOneLeader;
import static dev.mars.qraft.raft.DockerClusterApiHelper.joinerConfiguration;
import static dev.mars.qraft.raft.DockerClusterApiHelper.ready;
import static dev.mars.qraft.raft.DockerClusterApiHelper.register;
import static dev.mars.qraft.raft.DockerClusterApiHelper.status;
import static dev.mars.qraft.raft.DockerClusterApiHelper.statusOrNull;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automatic cleanup of a failed voter after a replacement is promoted, in a real three-server cluster in containers
 * (membership Step 8), with the acceptance servers' default policy: a voter absent for 60 seconds is eligible, a
 * pass runs every 10 seconds, and cleanup never leaves fewer than three voters.
 * <ol>
 *   <li>A follower is stopped. Once its absence passes the grace, the leader finds it eligible, but removing it would
 *       leave two voters, below the minimum of three, so it stays in the configuration and the cluster carries on;</li>
 *   <li>a replacement, server4, with its own name and address, joins as a non-voter. While it is one, the failed
 *       voter is still listed;</li>
 *   <li>when the leader has promoted server4 to a voter, that commit requests a cleanup pass, and the failed voter is
 *       removed at once: the configuration is the two retained servers and server4, all voters;</li>
 *   <li>the removal outlives restarts: after each retained compose server is stopped and started in turn, the
 *       configuration is unchanged, and registrations from before and after reach every server.</li>
 * </ol>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
@Tag("docker")
@ExpectedDockerErrorsHelper({
        // The failed voter is stopped for the whole test, so the leader cannot reach it, its name does not resolve,
        // and the snapshot that the leader keeps sending to the lagging peer cannot be delivered.
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE,
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_NAME_UNRESOLVED,
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_SNAPSHOT_TRANSFER_INTERRUPTED
})
// Each wait is bounded; their worst-case sum is about fifteen minutes, so the method budget is twenty-five and a
// failure reports the condition that was not met rather than the module's default method timeout.
@Timeout(value = 25, unit = TimeUnit.MINUTES)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("shared-docker-clusters")
class DockerFailedVoterCleanupTest {

    @Test
    void aFailedVoterStaysUntilItsReplacementIsPromotedAndThenIsRemoved() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeCluster();
        String volume4 = SharedDockerClusterFixture.createVolume("cleanup4");
        List<SharedDockerClusterFixture.DetachedServer> added = new ArrayList<>();
        try {
            List<String> endpoints = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
            await().atMost(Duration.ofSeconds(30)).until(() -> endpoints.stream()
                    .allMatch(endpoint -> configurationOrEmpty(endpoint).size() == 3));
            register(endpoints, "before-failure");
            await().atMost(Duration.ofSeconds(30)).until(() -> everyNodeHas(endpoints, "before-failure"));

            // A follower fails and stays down.
            int leaderIndex = DockerHealthApiHelper.leaderIndex(endpoints);
            int victimIndex = (leaderIndex + 1) % 3;
            String victim = "server" + (victimIndex + 1);
            String victimId = status(endpoints.get(victimIndex)).path("serverId").asText();
            List<String> retained = new ArrayList<>(endpoints);
            retained.remove(victimIndex);
            List<String> retainedServices = new ArrayList<>(List.of("server1", "server2", "server3"));
            retainedServices.remove(victim);
            Set<String> retainedIds = new HashSet<>(configurationOrEmpty(endpoints.get(leaderIndex)).keySet());
            assertTrue(retainedIds.remove(victimId));
            SharedDockerClusterFixture.stopContainer(cluster, victim);

            // Past the grace the leader finds it eligible, but three voters is the minimum: it is not removed.
            String considered = "eligibleServerIds=[" + victimId + "], voters=3, removalBudget=1, minVoters=3";
            await().atMost(Duration.ofSeconds(150)).until(() -> clusterLogs(cluster, added).contains(considered));
            String removalOfVictim = "Automatic cleanup committed removal of failed voter " + victimId;
            await().during(Duration.ofSeconds(25)).atMost(Duration.ofSeconds(60)).until(() ->
                    Boolean.TRUE.equals(configurationOrEmpty(retained.get(0)).get(victimId))
                            && !clusterLogs(cluster, added).contains(removalOfVictim)
                            && exactlyOneLeader(retained));
            assertEquals(200, register(retained, "while-failed"), "the cluster carries on with two of three voters");
            await().atMost(Duration.ofSeconds(30)).until(() -> everyNodeHas(retained, "while-failed"));

            // The replacement joins as a non-voter, and the failed voter is still listed while it is one.
            SharedDockerClusterFixture.DetachedServer server4 = SharedDockerClusterFixture.startDetachedServer(
                    cluster, "server4", joinerConfiguration(4), "-v", volume4 + ":/app/data");
            added.add(server4);
            await().atMost(Duration.ofSeconds(60)).until(() -> statusOrNull(server4.endpoint(), "serverId") != null);
            String id4 = status(server4.endpoint()).path("serverId").asText();
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).until(() ->
                    configurationOrEmpty(retained.get(0)).containsKey(id4));
            Map<String, Boolean> joining = configurationOrEmpty(retained.get(0));
            assertTrue(joining.containsKey(victimId), "the failed voter is listed beside the joining server");

            // The promotion commits, and its early cleanup pass removes the failed voter.
            await().atMost(Duration.ofSeconds(120)).until(() -> {
                Map<String, Boolean> now = configurationOrEmpty(retained.get(0));
                return !now.containsKey(victimId) && Boolean.TRUE.equals(now.get(id4));
            });
            await().atMost(Duration.ofSeconds(30)).until(() -> clusterLogs(cluster, added).contains(removalOfVictim)
                    && clusterLogs(cluster, added).contains("Promoted server4 to a voter"));
            Set<String> expected = new HashSet<>(retainedIds);
            expected.add(id4);
            List<String> everyServer = new ArrayList<>(retained);
            everyServer.add(server4.endpoint());
            await().atMost(Duration.ofSeconds(30)).until(() -> everyServer.stream()
                    .allMatch(endpoint -> configurationOrEmpty(endpoint).keySet().equals(expected)));
            Map<String, Boolean> voters = configuration(retained.get(0));
            assertTrue(voters.values().stream().allMatch(Boolean::booleanValue), "all three vote: " + voters);
            await().atMost(Duration.ofSeconds(60)).until(() -> everyNodeHas(everyServer, "before-failure"));

            // The removal outlives restarts of the retained compose servers, one at a time.
            for (int i = 0; i < retainedServices.size(); i++) {
                String service = retainedServices.get(i);
                String endpoint = retained.get(i);
                SharedDockerClusterFixture.stopContainer(cluster, service);
                SharedDockerClusterFixture.startContainer(cluster, service);
                await().atMost(Duration.ofSeconds(90)).until(() -> ready(endpoint) && exactlyOneLeader(everyServer));
            }
            assertEquals(200, register(everyServer, "after-restarts"));
            await().atMost(Duration.ofSeconds(60)).until(() -> everyNodeHas(everyServer, "after-restarts"));
            assertTrue(everyNodeHas(everyServer, "while-failed"));
            for (String endpoint : everyServer) {
                assertEquals(expected, configuration(endpoint).keySet(), "the removal persists on " + endpoint);
            }
            assertFalse(status(server4.endpoint()).path("fenced").asBoolean());
        } finally {
            cleanUp(cluster, added, volume4);
        }
    }
}

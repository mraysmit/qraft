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
import static dev.mars.qraft.raft.DockerClusterApiHelper.configurationOrEmpty;
import static dev.mars.qraft.raft.DockerClusterApiHelper.everyNodeHas;
import static dev.mars.qraft.raft.DockerClusterApiHelper.exactlyOneLeader;
import static dev.mars.qraft.raft.DockerClusterApiHelper.growToFiveVoters;
import static dev.mars.qraft.raft.DockerClusterApiHelper.ready;
import static dev.mars.qraft.raft.DockerClusterApiHelper.register;
import static dev.mars.qraft.raft.DockerClusterApiHelper.status;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Periodic automatic cleanup in a real cluster of five voters in containers (membership Step 8), with the
 * acceptance servers' default policy: a voter absent for 60 seconds is eligible, a pass runs every 10 seconds, and
 * cleanup never leaves fewer than three voters.
 * <ol>
 *   <li>A returning peer restarts its failure clock: a follower is stopped for 40 seconds, started, and stopped for
 *       40 more. Its absences add up to more than the grace but neither reaches it, so the leader never finds it
 *       eligible and it stays a voter;</li>
 *   <li>a different follower is then stopped for good. For 30 seconds, inside the grace, it stays in the
 *       configuration. Once it has been absent for 60 seconds a pass finds it eligible, and with no join to prompt it,
 *       the leader removes it: four voters remain, the removal budget and the minimum respected, and a write is
 *       accepted and replicated.</li>
 * </ol>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
@Tag("docker")
@ExpectedDockerErrorsHelper({
        // The stopped followers are unreachable, their names do not resolve meanwhile, and a snapshot sent to a
        // peer that lags behind the leader's last one cannot be delivered.
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE,
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_NAME_UNRESOLVED,
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_SNAPSHOT_TRANSFER_INTERRUPTED
})
// Each wait is bounded; their worst-case sum is about fifteen minutes, so the method budget is twenty-five and a
// failure reports the condition that was not met rather than the module's default method timeout.
@Timeout(value = 25, unit = TimeUnit.MINUTES)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("shared-docker-clusters")
class DockerPeriodicCleanupTest {

    @Test
    void aReturningPeerIsNotRemovedButAFailedVoterIsRemovedWithoutAJoin() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeCluster();
        String volume4 = SharedDockerClusterFixture.createVolume("periodic4");
        String volume5 = SharedDockerClusterFixture.createVolume("periodic5");
        List<SharedDockerClusterFixture.DetachedServer> added = new ArrayList<>();
        try {
            List<String> endpoints = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
            await().atMost(Duration.ofSeconds(30)).until(() -> endpoints.stream()
                    .allMatch(endpoint -> configurationOrEmpty(endpoint).size() == 3));
            growToFiveVoters(cluster, volume4, volume5, endpoints.get(0), added);
            List<String> everyServer = new ArrayList<>(endpoints);
            added.forEach(server -> everyServer.add(server.endpoint()));
            register(endpoints, "before-failures");
            await().atMost(Duration.ofSeconds(60)).until(() -> everyNodeHas(everyServer, "before-failures"));

            // Both failures are followers at the start. Leadership may still change when one returns: a restarted
            // voter that has not yet heard a heartbeat campaigns, and the leader steps down on the higher term in its
            // answer. The failure clocks are the current leader's, so this test reads the configuration from a server
            // that is never stopped and does not assume that the leader stays the same.
            int leaderIndex = DockerHealthApiHelper.leaderIndex(endpoints);
            int returningIndex = (leaderIndex + 1) % 3;
            int failedIndex = (leaderIndex + 2) % 3;
            String returning = "server" + (returningIndex + 1);
            String failed = "server" + (failedIndex + 1);
            String returningId = status(endpoints.get(returningIndex)).path("serverId").asText();
            String failedId = status(endpoints.get(failedIndex)).path("serverId").asText();
            Set<String> fiveIds = new HashSet<>(configurationOrEmpty(endpoints.get(leaderIndex)).keySet());
            assertEquals(5, fiveIds.size());

            // A returning peer restarts its failure clock: two absences of 40 seconds are never one of 60.
            for (int absence = 1; absence <= 2; absence++) {
                SharedDockerClusterFixture.stopContainer(cluster, returning);
                await().during(Duration.ofSeconds(40)).atMost(Duration.ofSeconds(60)).until(() ->
                        Boolean.TRUE.equals(configurationOrEmpty(endpoints.get(leaderIndex)).get(returningId)));
                SharedDockerClusterFixture.startContainer(cluster, returning);
                await().atMost(Duration.ofSeconds(90)).until(() -> ready(endpoints.get(returningIndex)));
            }
            assertFalse(eligible(clusterLogs(cluster, added), returningId),
                    "the returning peer was never absent for the whole grace");
            assertEquals(fiveIds, configurationOrEmpty(endpoints.get(leaderIndex)).keySet());

            // A voter that stays down is removed by a periodic pass, with no join to prompt it.
            SharedDockerClusterFixture.stopContainer(cluster, failed);
            await().during(Duration.ofSeconds(30)).atMost(Duration.ofSeconds(60)).until(() ->
                    Boolean.TRUE.equals(configurationOrEmpty(endpoints.get(leaderIndex)).get(failedId)));
            String removal = "Automatic cleanup committed removal of failed voter " + failedId;
            await().atMost(Duration.ofSeconds(150)).until(() -> clusterLogs(cluster, added).contains(removal));
            Set<String> fourIds = new HashSet<>(fiveIds);
            fourIds.remove(failedId);
            List<String> retained = new ArrayList<>(everyServer);
            retained.remove(endpoints.get(failedIndex));
            await().atMost(Duration.ofSeconds(60)).until(() -> retained.stream()
                    .allMatch(endpoint -> configurationOrEmpty(endpoint).keySet().equals(fourIds)));
            Map<String, Boolean> voters = configurationOrEmpty(endpoints.get(leaderIndex));
            assertTrue(voters.values().stream().allMatch(Boolean::booleanValue), "four voters remain: " + voters);
            assertTrue(clusterLogs(cluster, added).contains("eligibleServerIds=[" + failedId + "], voters=5, "
                    + "removalBudget=2, minVoters=3"), "the pass saw five voters, a budget of two and a minimum of three");

            assertEquals(200, register(retained, "after-removal"));
            await().atMost(Duration.ofSeconds(60)).until(() -> everyNodeHas(retained, "after-removal"));
            assertTrue(everyNodeHas(retained, "before-failures"));
        } finally {
            cleanUp(cluster, added, volume4, volume5);
        }
    }

    /** Whether any cleanup pass in {@code logs} found {@code serverId} eligible. */
    private static boolean eligible(String logs, String serverId) {
        return logs.lines().anyMatch(line -> line.contains("Failed-voter cleanup: eligibleServerIds=")
                && line.contains(serverId));
    }
}

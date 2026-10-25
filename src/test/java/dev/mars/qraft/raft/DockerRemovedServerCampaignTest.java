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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.testing.fault.ExpectedDockerErrorsHelper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.containers.ComposeContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.DockerClusterApiHelper.cleanUp;
import static dev.mars.qraft.raft.DockerClusterApiHelper.configurationOrEmpty;
import static dev.mars.qraft.raft.DockerClusterApiHelper.countOccurrences;
import static dev.mars.qraft.raft.DockerClusterApiHelper.everyNodeHas;
import static dev.mars.qraft.raft.DockerClusterApiHelper.exactlyOneLeader;
import static dev.mars.qraft.raft.DockerClusterApiHelper.growToFiveVoters;
import static dev.mars.qraft.raft.DockerClusterApiHelper.logsOf;
import static dev.mars.qraft.raft.DockerClusterApiHelper.register;
import static dev.mars.qraft.raft.DockerClusterApiHelper.removePeer;
import static dev.mars.qraft.raft.DockerClusterApiHelper.status;
import static dev.mars.qraft.raft.DockerClusterApiHelper.statusOrNull;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A removed server that keeps campaigning cannot disrupt a live cluster, and a genuine loss of the leader still
 * permits failover, in a real cluster of five voters in containers (membership Step 8):
 * <ol>
 *   <li>a follower is cut off from every network and removed by the authenticated operator command through the
 *       servers that remain. It never hears of its removal, so it keeps its old configuration and, with no leader to
 *       hear from, asks for pre-votes without end. No quorum would vote for it, so it takes no term: it stays in the
 *       cluster's term;</li>
 *   <li>its network is restored. Its pre-vote requests now reach the four voters, which refuse them, so the cluster
 *       keeps its leader and its term, accepts writes, and the removed server never leads;</li>
 *   <li>the leader is killed. The three servers that remain, a majority of the four voters, elect another leader,
 *       the removed server still does not lead, and a new write reaches all three.</li>
 * </ol>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
@Tag("docker")
@ExpectedDockerErrorsHelper({
        // The partitioned and killed servers are unreachable, their names do not resolve meanwhile, and a snapshot
        // sent to a peer that lags behind the leader's last one cannot be delivered.
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE,
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_NAME_UNRESOLVED,
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_SNAPSHOT_TRANSFER_INTERRUPTED
})
// Each wait is bounded; their worst-case sum is about twelve minutes, so the method budget is twenty and a failure
// reports the condition that was not met rather than the module's default method timeout.
@Timeout(value = 20, unit = TimeUnit.MINUTES)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("shared-docker-clusters")
class DockerRemovedServerCampaignTest {
    private static final String OPERATOR_TOKEN = "acceptance-operator-token";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aRemovedServerThatKeepsCampaigningCannotDisruptTheClusterOrPreventFailover() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeCluster();
        String volume4 = SharedDockerClusterFixture.createVolume("campaign4");
        String volume5 = SharedDockerClusterFixture.createVolume("campaign5");
        Path tokenFile = Files.createTempFile("qraft-operator-token-", ".txt");
        List<SharedDockerClusterFixture.DetachedServer> added = new ArrayList<>();
        boolean partitioned = false;
        String removed = null;
        try {
            List<String> endpoints = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
            await().atMost(Duration.ofSeconds(30)).until(() -> endpoints.stream()
                    .allMatch(endpoint -> configurationOrEmpty(endpoint).size() == 3));
            growToFiveVoters(cluster, volume4, volume5, endpoints.get(0), added);
            List<String> voterEndpoints = new ArrayList<>(endpoints);
            added.forEach(server -> voterEndpoints.add(server.endpoint()));
            register(endpoints, "before-removal");
            await().atMost(Duration.ofSeconds(60)).until(() -> everyNodeHas(voterEndpoints, "before-removal"));

            // A follower is cut off, then removed by the servers that remain.
            int leaderIndex = DockerHealthApiHelper.leaderIndex(endpoints);
            String leader = "server" + (leaderIndex + 1);
            int removedIndex = (leaderIndex + 1) % 3;
            int viaIndex = (leaderIndex + 2) % 3;
            removed = "server" + (removedIndex + 1);
            String via = "server" + (viaIndex + 1);
            String removedId = status(endpoints.get(removedIndex)).path("serverId").asText();
            Set<String> fiveIds = new HashSet<>(configurationOrEmpty(endpoints.get(leaderIndex)).keySet());
            Set<String> fourIds = new HashSet<>(fiveIds);
            assertTrue(fourIds.remove(removedId));
            List<String> liveVoters = new ArrayList<>(voterEndpoints);
            liveVoters.remove(endpoints.get(removedIndex));

            SharedDockerClusterFixture.partitionContainer(cluster, removed);
            partitioned = true;
            SharedDockerClusterFixture.DockerCommandResult removal = removePeer(cluster, removed, via, tokenFile,
                    OPERATOR_TOKEN);
            assertTrue(removal.exited(), removal.output());
            assertEquals(0, removal.exitCode(), removal.output());
            await().atMost(Duration.ofSeconds(60)).until(() -> liveVoters.stream()
                    .allMatch(endpoint -> configurationOrEmpty(endpoint).keySet().equals(fourIds)));

            // Cut off, it hears from no leader and keeps asking for pre-votes. Nobody would vote for it, so it takes
            // no term and stays in the cluster's.
            long clusterTerm = status(endpoints.get(leaderIndex)).path("term").asLong();
            String removedAtStart = removed;
            String asking = "Asking for pre-votes for node " + removedAtStart + " ";
            await().atMost(Duration.ofSeconds(90)).until(() ->
                    countOccurrences(logsOf(cluster, removedAtStart), asking) >= 2);
            assertEquals(clusterTerm, termInside(cluster, removed), "it asks without taking a term");
            assertNotEquals("LEADER", stateInside(cluster, removed), "a server without a quorum never leads");

            // Its network returns, and its campaigns reach the four voters: nothing changes for them.
            SharedDockerClusterFixture.restoreContainerNetwork(cluster, removed);
            partitioned = false;
            long termBefore = status(endpoints.get(leaderIndex)).path("term").asLong();
            String leaderId = status(endpoints.get(leaderIndex)).path("serverId").asText();
            await().during(Duration.ofSeconds(20)).atMost(Duration.ofSeconds(60)).until(() ->
                    exactlyOneLeader(List.of(endpoints.get(leaderIndex)))
                            && Long.toString(termBefore).equals(statusOrNull(endpoints.get(leaderIndex), "term"))
                            && leaderId.equals(statusOrNull(endpoints.get(leaderIndex), "serverId"))
                            && !"LEADER".equals(statusOrNull(endpoints.get(removedIndex), "state")));
            assertEquals(200, register(liveVoters, "while-it-campaigns"));
            await().atMost(Duration.ofSeconds(60)).until(() -> everyNodeHas(liveVoters, "while-it-campaigns"));

            // A genuine loss of the leader still permits failover among the three that remain.
            SharedDockerClusterFixture.killContainer(cluster, leader);
            List<String> survivors = new ArrayList<>(liveVoters);
            survivors.remove(endpoints.get(leaderIndex));
            assertEquals(3, survivors.size());
            await().atMost(Duration.ofSeconds(90)).until(() -> exactlyOneLeader(survivors));
            assertNotEquals("LEADER", statusOrNull(endpoints.get(removedIndex), "state"),
                    "the removed server does not take over");
            assertEquals(200, register(survivors, "after-failover"));
            await().atMost(Duration.ofSeconds(60)).until(() -> everyNodeHas(survivors, "after-failover"));
            assertTrue(everyNodeHas(survivors, "before-removal"));
            assertTrue(everyNodeHas(survivors, "while-it-campaigns"));
        } finally {
            if (partitioned) SharedDockerClusterFixture.restoreContainerNetwork(cluster, removed);
            Files.deleteIfExists(tokenFile);
            cleanUp(cluster, added, volume4, volume5);
        }
    }

    /** The term of the server in {@code service}, read from inside its container, as it has no published port. */
    private static long termInside(ComposeContainer cluster, String service) {
        try {
            return JSON.readTree(SharedDockerClusterFixture.execInService(cluster, service,
                    "curl", "-s", "http://localhost:8080/raft/status")).path("term").asLong(-1);
        } catch (Exception unavailable) {
            return -1;
        }
    }

    private static String stateInside(ComposeContainer cluster, String service) throws Exception {
        return JSON.readTree(SharedDockerClusterFixture.execInService(cluster, service,
                "curl", "-s", "http://localhost:8080/raft/status")).path("state").asText();
    }
}

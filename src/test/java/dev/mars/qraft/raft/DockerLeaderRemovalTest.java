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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.DockerClusterApiHelper.cleanUp;
import static dev.mars.qraft.raft.DockerClusterApiHelper.configuration;
import static dev.mars.qraft.raft.DockerClusterApiHelper.configurationOrEmpty;
import static dev.mars.qraft.raft.DockerClusterApiHelper.everyNodeHas;
import static dev.mars.qraft.raft.DockerClusterApiHelper.exactlyOneLeader;
import static dev.mars.qraft.raft.DockerClusterApiHelper.ready;
import static dev.mars.qraft.raft.DockerClusterApiHelper.register;
import static dev.mars.qraft.raft.DockerClusterApiHelper.removePeer;
import static dev.mars.qraft.raft.DockerClusterApiHelper.status;
import static dev.mars.qraft.raft.DockerClusterApiHelper.statusOrNull;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A leader removes itself, in a real three-server cluster in containers (membership Step 8): the authenticated
 * {@code operator raft remove-peer} command names the current leader, which commits its own removal and steps
 * down. The former leader then reports {@code removed} and is not ready, never leads again, and the two retained
 * voters elect one leader between them. Every registration made before the removal survives, and a new one is
 * accepted by the retained cluster.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
@Tag("docker")
// Each wait is bounded; their worst-case sum is about nine minutes, so the method budget is fifteen and a failure
// reports the condition that was not met rather than the module's default method timeout.
@Timeout(value = 15, unit = TimeUnit.MINUTES)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("shared-docker-clusters")
class DockerLeaderRemovalTest {
    private static final String OPERATOR_TOKEN = "acceptance-operator-token";

    @Test
    void aLeaderThatRemovesItselfStepsDownAndTheRetainedVotersElectAnother() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeCluster();
        Path tokenFile = Files.createTempFile("qraft-operator-token-", ".txt");
        try {
            List<String> endpoints = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
            await().atMost(Duration.ofSeconds(30)).until(() -> endpoints.stream()
                    .allMatch(endpoint -> configurationOrEmpty(endpoint).size() == 3));
            register(endpoints, "before-removal");
            await().atMost(Duration.ofSeconds(30)).until(() -> everyNodeHas(endpoints, "before-removal"));

            int leaderIndex = DockerHealthApiHelper.leaderIndex(endpoints);
            String leader = "server" + (leaderIndex + 1);
            String leaderEndpoint = endpoints.get(leaderIndex);
            String leaderId = status(leaderEndpoint).path("serverId").asText();
            int viaIndex = (leaderIndex + 1) % 3;
            String via = "server" + (viaIndex + 1);
            List<String> retained = new ArrayList<>(endpoints);
            retained.remove(leaderIndex);
            Set<String> retainedIds = new HashSet<>(configurationOrEmpty(endpoints.get(viaIndex)).keySet());
            assertTrue(retainedIds.remove(leaderId));

            // The command goes through a follower, which forwards it to the leader that the request names.
            SharedDockerClusterFixture.DockerCommandResult removal = removePeer(cluster, leader, via, tokenFile,
                    OPERATOR_TOKEN);
            assertTrue(removal.exited(), removal.output());
            assertEquals(0, removal.exitCode(), removal.output());

            // The leader's own removal commits, and it steps down: removed, not ready, and never leading again.
            await().atMost(Duration.ofSeconds(30)).until(() -> "true".equals(statusOrNull(leaderEndpoint, "removed")));
            assertFalse(ready(leaderEndpoint), "a removed server is live but not ready");
            assertNotEquals("LEADER", status(leaderEndpoint).path("state").asText());

            // The two retained voters elect one leader between them, and the configuration is theirs alone.
            await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(retained));
            await().atMost(Duration.ofSeconds(30)).until(() -> retained.stream()
                    .allMatch(endpoint -> configurationOrEmpty(endpoint).keySet().equals(retainedIds)));
            Map<String, Boolean> voters = configuration(retained.get(0));
            assertTrue(voters.values().stream().allMatch(Boolean::booleanValue), "both retained servers vote");

            // What was registered before the removal survives; the retained cluster accepts and replicates more.
            assertTrue(everyNodeHas(retained, "before-removal"), "the registration survives the removal");
            assertEquals(200, register(retained, "after-removal"));
            await().atMost(Duration.ofSeconds(30)).until(() -> everyNodeHas(retained, "after-removal"));

            // The former leader stays out: it does not lead again while the others carry on.
            await().during(Duration.ofSeconds(10)).atMost(Duration.ofSeconds(30)).until(() ->
                    exactlyOneLeader(retained)
                            && !"LEADER".equals(statusOrNull(leaderEndpoint, "state"))
                            && configurationOrEmpty(retained.get(0)).keySet().equals(retainedIds));
        } finally {
            Files.deleteIfExists(tokenFile);
            cleanUp(cluster, List.of());
        }
    }
}

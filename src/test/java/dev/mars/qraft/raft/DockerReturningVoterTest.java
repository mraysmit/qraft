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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.DockerClusterApiHelper.cleanUp;
import static dev.mars.qraft.raft.DockerClusterApiHelper.everyNodeHas;
import static dev.mars.qraft.raft.DockerClusterApiHelper.exactlyOneLeader;
import static dev.mars.qraft.raft.DockerClusterApiHelper.ready;
import static dev.mars.qraft.raft.DockerClusterApiHelper.register;
import static dev.mars.qraft.raft.DockerClusterApiHelper.status;
import static dev.mars.qraft.raft.DockerClusterApiHelper.statusOrNull;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A voter that returns after a long stop cannot depose a healthy leader, in a real three-server cluster in
 * containers (Pre-Vote, Raft thesis section 9.6). A follower is stopped for 70 seconds, long enough for the leader's
 * connection to it to be backing off, which is how a restarted follower once went unheard long enough to run four
 * elections and unseat the leader (Jenkins build 35, and the periodic cleanup scenario). It is started again, and for
 * 40 seconds the cluster keeps its leader and its term, on every server, the returning one included.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
@Tag("docker")
@ExpectedDockerErrorsHelper({
        // The stopped follower is unreachable, its name does not resolve meanwhile, and a snapshot sent to a peer
        // that lags behind the leader's last one cannot be delivered.
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE,
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_NAME_UNRESOLVED,
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_SNAPSHOT_TRANSFER_INTERRUPTED
})
// Each wait is bounded; their worst-case sum is about eight minutes, so the method budget is fifteen and a failure
// reports the condition that was not met rather than the module's default method timeout.
@Timeout(value = 15, unit = TimeUnit.MINUTES)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("shared-docker-clusters")
class DockerReturningVoterTest {

    @Test
    void aFollowerReturningAfterALongStopLeavesTheLeaderAndTheTermAlone() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeCluster();
        try {
            List<String> endpoints = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
            register(endpoints, "before-the-stop");
            await().atMost(Duration.ofSeconds(30)).until(() -> everyNodeHas(endpoints, "before-the-stop"));

            int leaderIndex = DockerHealthApiHelper.leaderIndex(endpoints);
            String leaderEndpoint = endpoints.get(leaderIndex);
            String term = String.valueOf(status(leaderEndpoint).path("term").asLong());
            int followerIndex = (leaderIndex + 1) % 3;
            String follower = "server" + (followerIndex + 1);
            String followerEndpoint = endpoints.get(followerIndex);
            List<String> live = endpoints.stream().filter(endpoint -> !endpoint.equals(followerEndpoint)).toList();

            // Long enough for the leader's connection to the stopped follower to be backing off.
            SharedDockerClusterFixture.stopContainer(cluster, follower);
            await().during(Duration.ofSeconds(70)).atMost(Duration.ofSeconds(110)).until(() ->
                    exactlyOneLeader(live) && "LEADER".equals(statusOrNull(leaderEndpoint, "state"))
                            && term.equals(statusOrNull(leaderEndpoint, "term")));

            SharedDockerClusterFixture.startContainer(cluster, follower);
            await().atMost(Duration.ofSeconds(90)).until(() -> ready(followerEndpoint));

            // It may ask for pre-votes, and every other voter refuses while the leader is live: nothing changes.
            await().during(Duration.ofSeconds(40)).atMost(Duration.ofSeconds(100)).until(() ->
                    "LEADER".equals(statusOrNull(leaderEndpoint, "state"))
                            && endpoints.stream().allMatch(endpoint -> term.equals(statusOrNull(endpoint, "term"))));

            assertEquals(200, register(endpoints, "after-the-return"));
            await().atMost(Duration.ofSeconds(60)).until(() -> everyNodeHas(endpoints, "after-the-return"));
            assertEquals(term, String.valueOf(status(leaderEndpoint).path("term").asLong()),
                    "the cluster is still in the term it was in before the stop");
        } finally {
            cleanUp(cluster, List.of());
        }
    }
}

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.testing.fault.ExpectedDockerErrorsHelper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ComposeContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker test of the leader's view of the servers (observability list, Phase 4): on three containers the leader reports
 * every server healthy with a failure tolerance of one; once a follower's container is stopped, the leader reports that
 * follower unhealthy, the cluster unhealthy, and no failure tolerance left.
 *
 * <p>Requires Docker. Excluded from the default build; run with {@code mvn test -Dgroups=docker -Dtest.excludedGroups=}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
@Tag("docker")
// Stopping a follower's container makes the leader log it unreachable, and its name may stop resolving.
@ExpectedDockerErrorsHelper({
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE,
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_NAME_UNRESOLVED
})
class DockerAutopilotHealthTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theLeaderReportsAStoppedFollowerUnhealthy() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeCluster();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            List<String> servers = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            AtomicReference<String> leader = new AtomicReference<>();
            AtomicReference<JsonNode> health = new AtomicReference<>();
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions().until(() -> {
                for (String server : servers) {
                    HttpResponse<String> answer = get(http, server);
                    if (answer.statusCode() != 200) continue;
                    JsonNode body = JSON.readTree(answer.body());
                    if (!body.path("Healthy").asBoolean()) return false;
                    leader.set(server);
                    health.set(body);
                    return true;
                }
                return false;
            });
            assertEquals(1, health.get().path("FailureTolerance").asInt());

            String followerName = null;
            for (JsonNode server : health.get().path("Servers")) {
                if (!server.path("Leader").asBoolean()) {
                    followerName = server.path("Name").asText();
                    break;
                }
            }
            String stopped = followerName;
            SharedDockerClusterFixture.stopContainer(cluster, stopped);

            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions().until(() -> {
                JsonNode body = JSON.readTree(get(http, leader.get()).body());
                health.set(body);
                for (JsonNode server : body.path("Servers")) {
                    if (server.path("Name").asText().equals(stopped)) return !server.path("Healthy").asBoolean();
                }
                return false;
            });
            assertTrue(!health.get().path("Healthy").asBoolean(), "the cluster is not healthy with a server down");
            assertEquals(0, health.get().path("FailureTolerance").asInt(), "two voters of three can lose no more");
        } finally {
            SharedDockerClusterFixture.stopAndCapture(cluster);
        }
    }

    private static HttpResponse<String> get(HttpClient http, String server) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(server + "/v1/operator/autopilot/health"))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }
}

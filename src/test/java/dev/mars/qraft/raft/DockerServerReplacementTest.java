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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.DockerClusterApiHelper.configuration;
import static dev.mars.qraft.raft.DockerClusterApiHelper.configurationOrEmpty;
import static dev.mars.qraft.raft.DockerClusterApiHelper.everyNodeHas;
import static dev.mars.qraft.raft.DockerClusterApiHelper.exactlyOneLeader;
import static dev.mars.qraft.raft.DockerClusterApiHelper.ready;
import static dev.mars.qraft.raft.DockerClusterApiHelper.register;
import static dev.mars.qraft.raft.DockerClusterApiHelper.status;
import static dev.mars.qraft.raft.DockerClusterApiHelper.statusOrNull;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replacing a server that lost its storage, at the same name and address, in a real three-server cluster in
 * containers (membership Step 8), with the operator command run from the image as the storage runbook describes:
 * <ol>
 *   <li>a follower is stopped, its data directory wiped, and it is started empty, so it takes a new server ID;</li>
 *   <li>the cluster still lists the old server ID as a voter under that name, so the join is refused and the new
 *       ID stays out of the configuration, however long it asks;</li>
 *   <li>a removal with the wrong operator token is refused and changes nothing;</li>
 *   <li>a removal with the right token removes the old entry, after which the new server joins as a non-voter,
 *       catches up, and is promoted until all three are voters, the old ID among none of them;</li>
 *   <li>the replacement is ready, unfenced and holds the catalog, a new write reaches all three servers, and the
 *       replacement keeps its new identity and its vote across a restart.</li>
 * </ol>
 * The acceptance configurations carry a fixed, non-secret operator token, as the runbook requires of every server.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
@Tag("docker")
@ExpectedDockerErrorsHelper({
        // The replaced server is unreachable while it is stopped.
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE
})
// Each wait is bounded; their worst-case sum is about twelve minutes, so the method budget is twenty and a failure
// reports the condition that was not met rather than the module's default method timeout.
@Timeout(value = 20, unit = TimeUnit.MINUTES)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("shared-docker-clusters")
class DockerServerReplacementTest {
    private static final String OPERATOR_TOKEN = "acceptance-operator-token";

    @Test
    void aWipedServerReplacesItsOldEntryOnlyAfterAnAuthenticatedRemoval() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeCluster();
        Path tokenFile = Files.createTempFile("qraft-operator-token-", ".txt");
        try {
            List<String> endpoints = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
            register(endpoints, "before-replace");
            await().atMost(Duration.ofSeconds(30)).until(() -> everyNodeHas(endpoints, "before-replace"));
            DockerHealthApiHelper.awaitFirstSnapshotOnEveryServer(endpoints);

            // A follower is replaced, so the leader keeps the cluster running while it is wiped.
            int victimIndex = (DockerHealthApiHelper.leaderIndex(endpoints) + 1) % 3;
            String victim = "server" + (victimIndex + 1);
            String victimEndpoint = endpoints.get(victimIndex);
            String survivor = "server" + ((victimIndex + 1) % 3 + 1);
            String survivorEndpoint = endpoints.get((victimIndex + 1) % 3);
            String oldId = status(victimEndpoint).path("serverId").asText();
            Map<String, Boolean> before = configuration(survivorEndpoint);
            assertEquals(3, before.size(), before.toString());
            assertTrue(before.get(oldId), "the old server is a voter: " + before);

            SharedDockerClusterFixture.stopContainer(cluster, victim);
            SharedDockerClusterFixture.wipeDataDirectory(cluster, victim);
            SharedDockerClusterFixture.startContainer(cluster, victim);
            await().atMost(Duration.ofSeconds(60)).until(() -> statusOrNull(victimEndpoint, "serverId") != null);
            String newId = status(victimEndpoint).path("serverId").asText();
            assertNotEquals(oldId, newId, "an empty directory gives the server a new ID");

            // The old entry still holds the name, so the join is refused for as long as it is observed.
            await().during(Duration.ofSeconds(8)).atMost(Duration.ofSeconds(20)).until(() -> {
                Map<String, Boolean> now = configurationOrEmpty(survivorEndpoint);
                return Boolean.TRUE.equals(now.get(oldId)) && !now.containsKey(newId);
            });

            // A removal needs the operator token.
            SharedDockerClusterFixture.DockerCommandResult wrong = removePeer(cluster, victim, survivor,
                    tokenFile, "not-the-operator-token");
            assertTrue(wrong.exited(), wrong.output());
            assertEquals(1, wrong.exitCode(), wrong.output());
            Map<String, Boolean> unchanged = configuration(survivorEndpoint);
            assertTrue(unchanged.get(oldId) && !unchanged.containsKey(newId),
                    "the refused removal changed nothing: " + unchanged);

            SharedDockerClusterFixture.DockerCommandResult removed = removePeer(cluster, victim, survivor,
                    tokenFile, OPERATOR_TOKEN);
            assertTrue(removed.exited(), removed.output());
            assertEquals(0, removed.exitCode(), removed.output());
            await().atMost(Duration.ofSeconds(30)).until(() ->
                    !configurationOrEmpty(survivorEndpoint).containsKey(oldId));

            // The new server joins as a non-voter, catches up, and is promoted.
            await().atMost(Duration.ofSeconds(120)).until(() -> {
                Map<String, Boolean> now = configurationOrEmpty(survivorEndpoint);
                return now.size() == 3 && now.values().stream().allMatch(Boolean::booleanValue);
            });
            Map<String, Boolean> replaced = configuration(survivorEndpoint);
            assertTrue(replaced.containsKey(newId), "the replacement is a voter: " + replaced);
            assertFalse(replaced.containsKey(oldId), "the old server ID does not return: " + replaced);

            await().atMost(Duration.ofSeconds(60)).until(() -> ready(victimEndpoint));
            assertFalse(status(victimEndpoint).path("fenced").asBoolean(), "the replacement is not fenced");
            await().atMost(Duration.ofSeconds(60)).until(() -> everyNodeHas(endpoints, "before-replace"));
            register(endpoints, "after-replace");
            await().atMost(Duration.ofSeconds(30)).until(() -> everyNodeHas(endpoints, "after-replace"));

            // The replacement keeps its identity and its vote across a restart.
            SharedDockerClusterFixture.stopContainer(cluster, victim);
            SharedDockerClusterFixture.startContainer(cluster, victim);
            await().atMost(Duration.ofSeconds(60)).until(() -> ready(victimEndpoint));
            assertEquals(newId, status(victimEndpoint).path("serverId").asText(), "identity survives a restart");
            assertEquals(replaced, configuration(survivorEndpoint), "its vote survives a restart");
            await().atMost(Duration.ofSeconds(30)).until(() ->
                    everyNodeHas(endpoints, "before-replace") && everyNodeHas(endpoints, "after-replace"));
        } finally {
            Files.deleteIfExists(tokenFile);
            SharedDockerClusterFixture.stopAndCapture(cluster);
        }
    }

    /** Runs {@code operator raft remove-peer} for {@code name} against {@code via}, from the cluster's network. */
    private static SharedDockerClusterFixture.DockerCommandResult removePeer(
            ComposeContainer cluster, String name, String via, Path tokenFile, String token) throws Exception {
        Files.writeString(tokenFile, token);
        var permissions = Files.getFileAttributeView(tokenFile, java.nio.file.attribute.PosixFileAttributeView.class);
        if (permissions != null) {
            permissions.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        }
        return SharedDockerClusterFixture.runQraftCommand("replacement-remove-peer",
                List.of("--network", SharedDockerClusterFixture.networkOf(cluster),
                        "--mount", "type=bind,source=" + tokenFile.toAbsolutePath()
                                + ",target=/etc/qraft/operator-token,readonly"),
                "operator", "raft", "remove-peer", "--name", name, "--http-addr", via + ":8080",
                "--token-file", "/etc/qraft/operator-token");
    }
}

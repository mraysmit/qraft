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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.containers.ComposeContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lost-quorum recovery of a real three-server cluster in containers (membership Step 7 exit gate), with the
 * offline {@code qraft operator raft} commands run from the image:
 * <ul>
 *   <li>from current directories: the majority is lost, the surviving server's stopped directory is inspected and
 *       recovered, and the seed it publishes leads alone under a new server ID with every committed write;</li>
 *   <li>from a backup: a stopped copy of one server is taken, later writes are committed, the whole cluster is
 *       lost, and the seed recovered from the backup holds what preceded the copy and nothing after it.</li>
 * </ul>
 * In both, quarantine is then broken on purpose: the old majority restarts on its own directories, forms the old
 * cluster and commits its own write, while the recovered cluster keeps its term, configuration and data. Finally
 * the excluded servers are wiped and rejoin through the ordinary join path, under new server IDs, until all three
 * are voters again and a write reaches each.
 *
 * <p>The source volume is mounted inside a persistent recovery volume ({@code /recovery/source}), so the claim,
 * the staging directory and the seed ({@code /recovery/seed}) outlive the one-off containers, as the contract
 * requires. The seed runs as a detached container with the network alias {@code server1}, while the compose
 * {@code server1} container, the source of the recovery, stays stopped.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-23
 * @version 1.0
 */
@Tag("docker")
@ExpectedDockerErrorsHelper({
        // Stopped servers are unreachable, and their names do not resolve while their containers are stopped.
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE,
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_NAME_UNRESOLVED
})
// Each test waits only on bounded conditions; the method budget exceeds their sum, so a failure reports the
// condition that was not met rather than the module's default method timeout.
@Timeout(value = 10, unit = TimeUnit.MINUTES)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("shared-docker-clusters")
class DockerLostQuorumRecoveryTest {
    private static final Map<String, String> REGISTRATION_HEADERS = Map.of(
            "X-Qraft-Tenant", "platform", "X-Qraft-Namespace", "recovery", "X-Qraft-Node", "recovery-test");
    private static final Map<String, String> SCOPE_HEADERS = Map.of(
            "X-Qraft-Tenant", "platform", "X-Qraft-Namespace", "recovery");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String QRAFT_UID = "1001:1001";

    @Test
    void theSurvivorOfALostMajorityIsRecoveredAndTheExcludedServersRejoinEmpty() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeCluster();
        String recovery = SharedDockerClusterFixture.createVolume("recovery");
        SharedDockerClusterFixture.DetachedServer seed = null;
        try {
            List<String> endpoints = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
            register(endpoints, "before-loss");
            await().atMost(Duration.ofSeconds(30)).until(() -> everyNodeHas(endpoints, "before-loss"));
            DockerHealthApiHelper.awaitFirstSnapshotOnEveryServer(endpoints);
            String sourceId = status(endpoints.get(0)).path("serverId").asText();
            String source = SharedDockerClusterFixture.volumeName(cluster, "server1", "/app/data");

            // The majority is lost; the survivor is stopped for its offline recovery.
            SharedDockerClusterFixture.stopContainer(cluster, "server2");
            SharedDockerClusterFixture.stopContainer(cluster, "server3");
            SharedDockerClusterFixture.stopContainer(cluster, "server1");

            Map<String, String> inspected = inspect(recovery, source);
            assertEquals(sourceId, inspected.get("Server ID"));
            assertEquals("accepted", inspected.get("Ordinary startup"), inspected.toString());
            Map<String, String> recovered = recover(recovery, source, sourceId);
            String seedId = recovered.get("Server ID");
            assertNotEquals(sourceId, seedId, "the seed has a new server ID");

            seed = startSeed(cluster, recovery);
            assertRecoveredSeed(seed, seedId);
            assertTrue(everyNodeHas(List.of(seed.endpoint()), "before-loss"), "every committed write is kept");
            register(List.of(seed.endpoint()), "after-recovery");

            assertOldMajorityIsIsolated(cluster, endpoints, seed, seedId, "after-recovery");
            rejoinEmpty(cluster, endpoints, seed, seedId, sourceId, "after-recovery");
        } finally {
            if (seed != null) seed.close();
            SharedDockerClusterFixture.stopAndCapture(cluster);
            SharedDockerClusterFixture.removeVolume(recovery);
        }
    }

    @Test
    void aSeedRecoveredFromABackupHoldsWhatPrecededTheBackupAndNothingAfter() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeCluster();
        String recovery = SharedDockerClusterFixture.createVolume("recovery");
        String backup = SharedDockerClusterFixture.createVolume("backup");
        SharedDockerClusterFixture.DetachedServer seed = null;
        try {
            List<String> endpoints = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
            register(endpoints, "before-backup");
            await().atMost(Duration.ofSeconds(30)).until(() -> everyNodeHas(endpoints, "before-backup"));
            DockerHealthApiHelper.awaitFirstSnapshotOnEveryServer(endpoints);
            String sourceId = status(endpoints.get(0)).path("serverId").asText();

            // An offline backup: the complete directory of a stopped server, as the storage runbook requires.
            SharedDockerClusterFixture.stopContainer(cluster, "server1");
            SharedDockerClusterFixture.runAlpine("cp -a /app/data/. /backup/ && chown -R " + QRAFT_UID + " /backup",
                    "--volumes-from", containerId(cluster, "server1"), "-v", backup + ":/backup");
            SharedDockerClusterFixture.startContainer(cluster, "server1");
            await().atMost(Duration.ofSeconds(60)).until(() -> allReady(endpoints) && exactlyOneLeader(endpoints));
            register(endpoints, "after-backup");
            await().atMost(Duration.ofSeconds(30)).until(() -> everyNodeHas(endpoints, "after-backup"));

            // The whole cluster is lost; only the backup is used.
            for (String service : List.of("server1", "server2", "server3")) {
                SharedDockerClusterFixture.stopContainer(cluster, service);
            }
            Map<String, String> inspected = inspect(recovery, backup);
            assertEquals(sourceId, inspected.get("Server ID"));
            Map<String, String> recovered = recover(recovery, backup, sourceId);
            String seedId = recovered.get("Server ID");

            seed = startSeed(cluster, recovery);
            assertRecoveredSeed(seed, seedId);
            assertTrue(everyNodeHas(List.of(seed.endpoint()), "before-backup"), "what preceded the backup is kept");
            assertFalse(anyNodeHas(List.of(seed.endpoint()), "after-backup"), "what followed the backup is lost");
            register(List.of(seed.endpoint()), "after-restore");

            assertOldMajorityIsIsolated(cluster, endpoints, seed, seedId, "after-restore");
            rejoinEmpty(cluster, endpoints, seed, seedId, sourceId, "after-restore");
            List<String> recoveredCluster = List.of(seed.endpoint(), endpoints.get(1), endpoints.get(2));
            assertFalse(anyNodeHas(recoveredCluster, "after-backup"), "the rejoined servers took the seed's history");
        } finally {
            if (seed != null) seed.close();
            SharedDockerClusterFixture.stopAndCapture(cluster);
            SharedDockerClusterFixture.removeVolume(recovery);
            SharedDockerClusterFixture.removeVolume(backup);
        }
    }

    /** Runs {@code operator raft inspect} on the stopped {@code source} volume and returns its printed fields. */
    private static Map<String, String> inspect(String recovery, String source) {
        prepareRecoveryVolume(recovery);
        SharedDockerClusterFixture.DockerCommandResult inspected = SharedDockerClusterFixture.runQraftCommand(
                "recovery-inspect", List.of("-v", recovery + ":/recovery", "-v", source + ":/recovery/source"),
                "operator", "raft", "inspect", "--dir", "/recovery/source");
        assertTrue(inspected.exited(), inspected.output());
        assertEquals(0, inspected.exitCode(), inspected.output());
        return fields(inspected.output());
    }

    /** Runs {@code operator raft recover} with the source volume nested in the recovery volume. */
    private static Map<String, String> recover(String recovery, String source, String sourceId) throws Exception {
        Path input = Files.createTempFile("qraft-recovery-", ".json");
        Path server = Files.createTempFile("qraft-seed-", ".json");
        try {
            Files.writeString(input, """
                    {"version":1,"source":{"path":"/recovery/source","serverId":"%s"},
                     "serverConfig":"/etc/qraft/server.json"}
                    """.formatted(sourceId));
            Files.writeString(server, seedConfiguration());
            readableByTheImage(input);
            readableByTheImage(server);
            SharedDockerClusterFixture.DockerCommandResult recovered = SharedDockerClusterFixture.runQraftCommand(
                    "recovery-recover", List.of(
                            "-v", recovery + ":/recovery", "-v", source + ":/recovery/source",
                            "--mount", "type=bind,source=" + input.toAbsolutePath()
                                    + ",target=/etc/qraft/recovery.json,readonly",
                            "--mount", "type=bind,source=" + server.toAbsolutePath()
                                    + ",target=/etc/qraft/server.json,readonly"),
                    "operator", "raft", "recover", "--input", "/etc/qraft/recovery.json");
            assertTrue(recovered.exited(), recovered.output());
            assertEquals(0, recovered.exitCode(), recovered.output());
            Map<String, String> fields = fields(recovered.output());
            assertEquals("recovered", fields.get("Outcome"), recovered.output());
            assertEquals("/recovery/seed", fields.get("Output"));
            return fields;
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(server);
        }
    }

    /** The recovery volume belongs to the image's user, so the claim, staging copy and seed can be written. */
    private static void prepareRecoveryVolume(String recovery) {
        SharedDockerClusterFixture.runAlpine("chown " + QRAFT_UID + " /recovery", "-v", recovery + ":/recovery");
    }

    /** Server1's acceptance configuration, with its storage at the recovered seed. */
    private static String seedConfiguration() throws Exception {
        String acceptance = Files.readString(Path.of("docker/config/three-node-acceptance/server1.json"));
        String seed = acceptance.replace("\"path\":\"/app/data\"", "\"path\":\"/recovery/seed\"");
        assertNotEquals(acceptance, seed, "the acceptance configuration names /app/data as its storage path");
        return seed;
    }

    private static SharedDockerClusterFixture.DetachedServer startSeed(ComposeContainer cluster, String recovery)
            throws Exception {
        return SharedDockerClusterFixture.startDetachedServer(cluster, "server1", seedConfiguration(),
                "-v", recovery + ":/recovery");
    }

    /** The seed leads alone, under {@code seedId}, as the only server and voter in its configuration. */
    private static void assertRecoveredSeed(SharedDockerClusterFixture.DetachedServer seed, String seedId)
            throws Exception {
        await().atMost(Duration.ofSeconds(90)).until(() -> ready(seed.endpoint())
                && "LEADER".equals(statusOrNull(seed.endpoint(), "state")));
        assertEquals(seedId, status(seed.endpoint()).path("serverId").asText());
        Map<String, Boolean> voters = configuration(seed.endpoint());
        assertEquals(Map.of(seedId, true), voters, "the seed is the sole voter");
    }

    /**
     * Quarantine broken on purpose: the old majority restarts on its own directories and commits its own write as
     * the old cluster, while the seed keeps its term, configuration and data.
     */
    private static void assertOldMajorityIsIsolated(ComposeContainer cluster, List<String> endpoints,
                                                    SharedDockerClusterFixture.DetachedServer seed, String seedId,
                                                    String seedWrite) throws Exception {
        long seedTerm = status(seed.endpoint()).path("term").asLong();
        List<String> oldMajority = List.of(endpoints.get(1), endpoints.get(2));
        SharedDockerClusterFixture.startContainer(cluster, "server2");
        SharedDockerClusterFixture.startContainer(cluster, "server3");
        await().atMost(Duration.ofSeconds(90)).until(() -> exactlyOneLeader(oldMajority));
        register(oldMajority, "old-cluster");
        await().atMost(Duration.ofSeconds(30)).until(() -> everyNodeHas(oldMajority, "old-cluster"));
        // The old leader replicates to "server1", which now reaches the seed: its own log shows the seed's
        // answers under the new server ID, which the old configuration does not count.
        String ignored = "Ignoring an AppendEntries response from server1 with server ID " + seedId;
        await().atMost(Duration.ofSeconds(30)).until(() -> logsOf(cluster, "server2").contains(ignored)
                || logsOf(cluster, "server3").contains(ignored));

        JsonNode seedStatus = status(seed.endpoint());
        assertEquals("LEADER", seedStatus.path("state").asText(), "the seed ignores the old cluster's campaigns");
        assertEquals(seedTerm, seedStatus.path("term").asLong(), "appends meant for server1's old ID are refused");
        assertEquals(Map.of(seedId, true), configuration(seed.endpoint()));
        assertFalse(anyNodeHas(List.of(seed.endpoint()), "old-cluster"), "the old cluster's write never reaches it");
        assertTrue(everyNodeHas(List.of(seed.endpoint()), seedWrite));
    }

    /**
     * The excluded servers are stopped, wiped and started empty. They join the seed under new server IDs and are
     * promoted, until all three are voters and a write reaches each.
     */
    private static void rejoinEmpty(ComposeContainer cluster, List<String> endpoints,
                                    SharedDockerClusterFixture.DetachedServer seed, String seedId,
                                    String sourceId, String seedWrite) throws Exception {
        Set<String> oldIds = new HashSet<>(List.of(sourceId,
                status(endpoints.get(1)).path("serverId").asText(), status(endpoints.get(2)).path("serverId").asText()));
        for (String service : List.of("server2", "server3")) {
            SharedDockerClusterFixture.stopContainer(cluster, service);
            SharedDockerClusterFixture.wipeDataDirectory(cluster, service);
        }
        SharedDockerClusterFixture.startContainer(cluster, "server2");
        SharedDockerClusterFixture.startContainer(cluster, "server3");
        List<String> recovered = List.of(seed.endpoint(), endpoints.get(1), endpoints.get(2));
        await().atMost(Duration.ofSeconds(180)).until(() -> {
            Map<String, Boolean> voters = configurationOrEmpty(seed.endpoint());
            return voters.size() == 3 && voters.values().stream().allMatch(Boolean::booleanValue);
        });
        Map<String, Boolean> voters = configuration(seed.endpoint());
        assertTrue(voters.containsKey(seedId));
        for (String id : voters.keySet()) {
            assertFalse(oldIds.contains(id), "no old server ID returns: " + voters);
        }
        assertEquals("LEADER", status(seed.endpoint()).path("state").asText());
        await().atMost(Duration.ofSeconds(60)).until(() -> everyNodeHas(recovered, seedWrite));
        register(recovered, "after-rejoin");
        await().atMost(Duration.ofSeconds(30)).until(() -> everyNodeHas(recovered, "after-rejoin"));
        assertFalse(anyNodeHas(recovered, "old-cluster"), "the old cluster's write was wiped with its directories");
    }

    /**
     * Every printed {@code name  value} line, by name. Log lines in the same output begin with their timestamp,
     * and the command's labels with a capital letter.
     */
    private static Map<String, String> fields(String output) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : output.lines().toList()) {
            if (line.isEmpty() || !Character.isUpperCase(line.charAt(0))) continue;
            String[] parts = line.split("\\s{2,}", 2);
            if (parts.length == 2) fields.putIfAbsent(parts[0], parts[1].trim());
        }
        return fields;
    }

    private static void register(List<String> endpoints, String serviceName) throws Exception {
        String body = """
                {"serviceId":"%s-1","serviceName":"%s","address":"127.0.0.1","port":8100,
                 "tags":["recovery"],"metadata":{"scenario":"lost-quorum"},"health":"PASSING"}
                """.formatted(serviceName, serviceName);
        for (int attempt = 0; attempt < 30; attempt++) {
            for (String endpoint : endpoints) {
                try {
                    if (send(endpoint + "/v1/client/service/register", "PUT", body, REGISTRATION_HEADERS)
                            .statusCode() == 200) {
                        return;
                    }
                } catch (java.io.IOException unavailable) {
                    // A follower or a starting server answers later or not at all; the next endpoint is tried.
                }
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("no leader among " + endpoints + " accepted " + serviceName);
    }

    private static boolean everyNodeHas(List<String> endpoints, String serviceName) {
        return endpoints.stream().allMatch(endpoint -> catalogHas(endpoint, serviceName) == Boolean.TRUE);
    }

    private static boolean anyNodeHas(List<String> endpoints, String serviceName) {
        return endpoints.stream().anyMatch(endpoint -> catalogHas(endpoint, serviceName) != Boolean.FALSE);
    }

    /** Whether {@code endpoint}'s catalog holds an instance of {@code serviceName}, or null if it cannot say. */
    private static Boolean catalogHas(String endpoint, String serviceName) {
        try {
            HttpResponse<String> response = send(endpoint + "/v1/catalog/service/" + serviceName, "GET", null,
                    SCOPE_HEADERS);
            if (response.statusCode() != 200) return null;
            JsonNode instances = JSON.readTree(response.body());
            for (JsonNode instance : instances) {
                if ((serviceName + "-1").equals(instance.path("serviceId").asText())) return true;
            }
            return false;
        } catch (Exception unavailable) {
            return null;
        }
    }

    /** The voter flag of each server in {@code endpoint}'s configuration, by server ID. */
    private static Map<String, Boolean> configuration(String endpoint) throws Exception {
        HttpResponse<String> response = send(endpoint + "/v1/operator/raft/configuration", "GET", null, Map.of());
        assertEquals(200, response.statusCode(), response.body());
        Map<String, Boolean> voters = new LinkedHashMap<>();
        for (JsonNode server : JSON.readTree(response.body()).path("servers")) {
            voters.put(server.path("serverId").asText(), server.path("voter").asBoolean());
        }
        return voters;
    }

    private static Map<String, Boolean> configurationOrEmpty(String endpoint) {
        try {
            return configuration(endpoint);
        } catch (Exception | AssertionError unavailable) {
            return Map.of();
        }
    }

    private static String logsOf(ComposeContainer cluster, String service) {
        return cluster.getContainerByServiceName(service).orElseThrow().getLogs();
    }

    private static boolean exactlyOneLeader(List<String> endpoints) {
        int leaders = 0;
        for (String endpoint : endpoints) {
            String state = statusOrNull(endpoint, "state");
            if (state == null) return false;
            if ("LEADER".equals(state)) leaders++;
        }
        return leaders == 1;
    }

    private static boolean allReady(List<String> endpoints) {
        return endpoints.stream().allMatch(DockerLostQuorumRecoveryTest::ready);
    }

    private static boolean ready(String endpoint) {
        try {
            return send(endpoint + "/health/ready", "GET", null, Map.of()).statusCode() == 200;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static String statusOrNull(String endpoint, String field) {
        try {
            return status(endpoint).path(field).asText(null);
        } catch (Exception | AssertionError unavailable) {
            return null;
        }
    }

    private static JsonNode status(String endpoint) throws Exception {
        HttpResponse<String> response = send(endpoint + "/raft/status", "GET", null, Map.of());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static String containerId(ComposeContainer cluster, String service) {
        return cluster.getContainerByServiceName(service).orElseThrow().getContainerId();
    }

    private static void readableByTheImage(Path file) throws Exception {
        var permissions = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
        if (permissions != null) {
            permissions.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    private static HttpResponse<String> send(String uri, String method, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(uri)).timeout(Duration.ofSeconds(5));
        headers.forEach(request::header);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

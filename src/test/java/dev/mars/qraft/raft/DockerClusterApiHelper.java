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
import org.testcontainers.containers.ComposeContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The HTTP calls that the membership and recovery container scenarios make against running servers: registering a
 * service, asking each server's catalog whether it holds one, reading the Raft status and configuration, and
 * waiting for a single leader. Every answer that a stopped or starting server cannot give is reported as unknown,
 * not as absence, so a negative assertion never passes on a failed request.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
final class DockerClusterApiHelper {
    private static final Map<String, String> REGISTRATION_HEADERS = Map.of(
            "X-Qraft-Tenant", "platform", "X-Qraft-Namespace", "cluster-test", "X-Qraft-Node", "cluster-test");
    private static final Map<String, String> SCOPE_HEADERS = Map.of(
            "X-Qraft-Tenant", "platform", "X-Qraft-Namespace", "cluster-test");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private DockerClusterApiHelper() {
    }

    /** Registers {@code serviceName} on whichever endpoint leads, and returns the status that acknowledged it. */
    static int register(List<String> endpoints, String serviceName) throws Exception {
        String body = """
                {"serviceId":"%s-1","serviceName":"%s","address":"127.0.0.1","port":8100,
                 "tags":["cluster-test"],"metadata":{"scenario":"docker"},"health":"PASSING"}
                """.formatted(serviceName, serviceName);
        for (int attempt = 0; attempt < 30; attempt++) {
            for (String endpoint : endpoints) {
                try {
                    int status = send(endpoint + "/v1/client/service/register", "PUT", body, REGISTRATION_HEADERS)
                            .statusCode();
                    if (status == 200) {
                        return status;
                    }
                } catch (java.io.IOException unavailable) {
                    // A follower or a starting server answers later or not at all; the next endpoint is tried.
                }
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("no leader among " + endpoints + " accepted " + serviceName);
    }

    static boolean everyNodeHas(List<String> endpoints, String serviceName) {
        return endpoints.stream().allMatch(endpoint -> catalogHas(endpoint, serviceName) == Boolean.TRUE);
    }

    /**
     * Waits until every endpoint has answered that its catalog lacks {@code serviceName}. An endpoint that cannot
     * answer is not evidence of absence, so it is retried within the bound rather than counted either way.
     */
    static void assertNoNodeHas(List<String> endpoints, String serviceName, String message) {
        try {
            await().atMost(Duration.ofSeconds(30)).until(() -> endpoints.stream()
                    .allMatch(endpoint -> catalogHas(endpoint, serviceName) == Boolean.FALSE));
        } catch (org.awaitility.core.ConditionTimeoutException unanswered) {
            throw new AssertionError(message + ": " + serviceName + " is present or unanswered on " + endpoints,
                    unanswered);
        }
    }

    /** Whether {@code endpoint}'s catalog holds an instance of {@code serviceName}, or null if it cannot say. */
    static Boolean catalogHas(String endpoint, String serviceName) {
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
    static Map<String, Boolean> configuration(String endpoint) throws Exception {
        HttpResponse<String> response = send(endpoint + "/v1/operator/raft/configuration", "GET", null, Map.of());
        assertEquals(200, response.statusCode(), response.body());
        Map<String, Boolean> voters = new LinkedHashMap<>();
        for (JsonNode server : JSON.readTree(response.body()).path("servers")) {
            voters.put(server.path("serverId").asText(), server.path("voter").asBoolean());
        }
        return voters;
    }

    static Map<String, Boolean> configurationOrEmpty(String endpoint) {
        try {
            return configuration(endpoint);
        } catch (Exception | AssertionError unavailable) {
            return Map.of();
        }
    }

    static String logsOf(ComposeContainer cluster, String service) {
        return cluster.getContainerByServiceName(service).orElseThrow().getLogs();
    }

    static boolean exactlyOneLeader(List<String> endpoints) {
        int leaders = 0;
        for (String endpoint : endpoints) {
            String state = statusOrNull(endpoint, "state");
            if (state == null) return false;
            if ("LEADER".equals(state)) leaders++;
        }
        return leaders == 1;
    }

    static boolean allReady(List<String> endpoints) {
        return endpoints.stream().allMatch(DockerClusterApiHelper::ready);
    }

    static boolean ready(String endpoint) {
        try {
            return send(endpoint + "/health/ready", "GET", null, Map.of()).statusCode() == 200;
        } catch (Exception unavailable) {
            return false;
        }
    }

    static String statusOrNull(String endpoint, String field) {
        try {
            return status(endpoint).path(field).asText(null);
        } catch (Exception | AssertionError unavailable) {
            return null;
        }
    }

    static JsonNode status(String endpoint) throws Exception {
        HttpResponse<String> response = send(endpoint + "/raft/status", "GET", null, Map.of());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    /**
     * Closes the detached {@code servers}, captures and stops the cluster and removes the volumes. Every step runs
     * even if an earlier one fails, so no container or volume is left behind, and the first failure is then
     * reported with the others suppressed.
     */
    static void cleanUp(ComposeContainer cluster, List<SharedDockerClusterFixture.DetachedServer> servers,
                        String... volumes) throws Exception {
        Exception failure = null;
        for (SharedDockerClusterFixture.DetachedServer server : servers) {
            try {
                server.close();
            } catch (Exception error) {
                failure = failure == null ? error : suppress(failure, error);
            }
        }
        try {
            SharedDockerClusterFixture.stopAndCapture(cluster);
        } catch (Exception | AssertionError error) {
            Exception wrapped = error instanceof Exception e ? e : new IllegalStateException(error);
            failure = failure == null ? wrapped : suppress(failure, wrapped);
        }
        for (String volume : volumes) {
            try {
                SharedDockerClusterFixture.removeVolume(volume);
            } catch (Exception error) {
                failure = failure == null ? error : suppress(failure, error);
            }
        }
        if (failure != null) throw failure;
    }

    private static Exception suppress(Exception first, Exception later) {
        first.addSuppressed(later);
        return first;
    }

    /**
     * Runs {@code operator raft remove-peer --name <name>} against {@code via}, from the cluster's network, with
     * {@code token} written to {@code tokenFile} and mounted into the one-off container.
     */
    static SharedDockerClusterFixture.DockerCommandResult removePeer(
            ComposeContainer cluster, String name, String via, Path tokenFile, String token) throws Exception {
        Files.writeString(tokenFile, token);
        var permissions = Files.getFileAttributeView(tokenFile, PosixFileAttributeView.class);
        if (permissions != null) {
            permissions.setPermissions(PosixFilePermissions.fromString("rw-r--r--"));
        }
        return SharedDockerClusterFixture.runQraftCommand("remove-peer-" + name,
                List.of("--network", SharedDockerClusterFixture.networkOf(cluster),
                        "--mount", "type=bind,source=" + tokenFile.toAbsolutePath()
                                + ",target=/etc/qraft/operator-token,readonly"),
                "operator", "raft", "remove-peer", "--name", name, "--http-addr", via + ":8080",
                "--token-file", "/etc/qraft/operator-token");
    }

    /**
     * A server JSON for server {@code number}, derived from the acceptance configuration of server1 and listing
     * servers 1 to {@code number}, as a server that joins an existing three-server cluster is configured.
     */
    static String joinerConfiguration(int number) throws Exception {
        String threeServers =
                "{\"server1\":\"server1:9080\",\"server2\":\"server2:9080\",\"server3\":\"server3:9080\"}";
        String base = Files.readString(Path.of("docker/config/three-node-acceptance/server1.json"));
        StringBuilder listed = new StringBuilder("{");
        for (int i = 1; i <= number; i++) {
            listed.append(i > 1 ? "," : "").append("\"server").append(i).append("\":\"server").append(i)
                    .append(":9080\"");
        }
        String json = base.replace("\"id\":\"server1\"", "\"id\":\"server" + number + "\"")
                .replace(threeServers, listed.append('}').toString());
        if (json.equals(base) || !json.contains("\"id\":\"server" + number + "\"")
                || !json.contains("\"server" + number + "\":\"server" + number + ":9080\"")) {
            throw new AssertionError("the acceptance configuration for server1 has not the layout this rewrites");
        }
        return json;
    }

    /** The logs of the three compose servers together, as they stand now. */
    static String composeLogs(ComposeContainer cluster) {
        return logsOf(cluster, "server1") + logsOf(cluster, "server2") + logsOf(cluster, "server3");
    }

    /**
     * The logs of every server in the cluster, the compose servers and the detached ones together. Any voter can
     * win an election, so a line the leader logs may be in any of them.
     */
    static String clusterLogs(ComposeContainer cluster, List<SharedDockerClusterFixture.DetachedServer> detached) {
        StringBuilder logs = new StringBuilder(composeLogs(cluster));
        for (SharedDockerClusterFixture.DetachedServer server : detached) {
            try {
                logs.append(server.logs());
            } catch (Exception unreadable) {
                throw new IllegalStateException("Could not read the logs of " + server.name(), unreadable);
            }
        }
        return logs.toString();
    }

    /**
     * Starts server4 and server5, each on its own volume and listing the cluster, adds them to {@code added} as
     * they start so the caller's cleanup closes them, and waits until {@code observer} lists five voters.
     */
    static void growToFiveVoters(ComposeContainer cluster, String volume4, String volume5, String observer,
                                 List<SharedDockerClusterFixture.DetachedServer> added) throws Exception {
        added.add(SharedDockerClusterFixture.startDetachedServer(
                cluster, "server4", joinerConfiguration(4), "-v", volume4 + ":/app/data"));
        added.add(SharedDockerClusterFixture.startDetachedServer(
                cluster, "server5", joinerConfiguration(5), "-v", volume5 + ":/app/data"));
        await().atMost(Duration.ofSeconds(240)).until(() -> {
            Map<String, Boolean> now = configurationOrEmpty(observer);
            return now.size() == 5 && now.values().stream().allMatch(Boolean::booleanValue);
        });
    }

    /** How many times {@code needle} occurs in {@code text}, without overlapping. */
    static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int from = text.indexOf(needle); from >= 0; from = text.indexOf(needle, from + needle.length())) count++;
        return count;
    }

    static HttpResponse<String> send(String uri, String method, String body, Map<String, String> headers)
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

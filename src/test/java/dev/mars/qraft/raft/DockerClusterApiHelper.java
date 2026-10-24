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

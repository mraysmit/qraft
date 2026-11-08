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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ComposeContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Docker test of tenancy (tenancy list, Phase 4): on three servers in containers, a tenant and namespace created on the
 * leader are listed by every server; a write into a namespace that does not exist is refused, and into one that does is
 * accepted; and once the namespace is deleted every server reports it gone and its key with it.
 *
 * <p>Requires Docker. Excluded from the default build; run with {@code mvn test -Dgroups=docker -Dtest.excludedGroups=}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
@Tag("docker")
class DockerTenancyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aNamespaceCreatedOnTheLeaderIsEnforcedAndDeletedOnEveryServer() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeCluster();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            List<String> servers = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            AtomicReference<String> leader = new AtomicReference<>();
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions().until(() -> {
                for (String server : servers) {
                    if (send(http, "PUT", server, "/v1/tenant", "{\"Name\":\"acme\"}").statusCode() == 200) {
                        leader.set(server);
                        return true;
                    }
                }
                return false;
            });
            assertEquals(404, send(http, "PUT", leader.get(), "/v1/kv/config", "v", "X-Qraft-Tenant", "acme",
                    "X-Qraft-Namespace", "prod").statusCode(), "prod does not exist yet");
            assertEquals(200, send(http, "PUT", leader.get(), "/v1/namespace",
                    "{\"Name\":\"prod\",\"Tenant\":\"acme\"}").statusCode());
            assertEquals("true", send(http, "PUT", leader.get(), "/v1/kv/config", "v", "X-Qraft-Tenant", "acme",
                    "X-Qraft-Namespace", "prod").body());

            for (String server : servers) {
                await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
                        .until(() -> namespaces(http, server).equals(List.of("default", "prod")));
                await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> send(http, "GET", server,
                        "/v1/kv/config", null, "X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "prod").statusCode() == 200);
            }

            assertEquals("true", send(http, "DELETE", leader.get(), "/v1/namespace/prod", null,
                    "X-Qraft-Tenant", "acme").body());
            for (String server : servers) {
                await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
                        .until(() -> namespaces(http, server).equals(List.of("default")));
                assertEquals(404, send(http, "GET", server, "/v1/kv/config", null, "X-Qraft-Tenant", "acme",
                        "X-Qraft-Namespace", "prod").statusCode(), "the key went with its namespace on " + server);
            }
        } finally {
            SharedDockerClusterFixture.stopAndCapture(cluster);
        }
    }

    private static List<String> namespaces(HttpClient http, String server) throws Exception {
        HttpResponse<String> listed = send(http, "GET", server, "/v1/namespaces", null, "X-Qraft-Tenant", "acme");
        List<String> names = new ArrayList<>();
        if (listed.statusCode() != 200) return names;
        for (JsonNode namespace : JSON.readTree(listed.body())) names.add(namespace.path("Name").asText());
        return names;
    }

    private static HttpResponse<String> send(HttpClient http, String method, String server, String path, String body,
                                             String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server + path)).timeout(Duration.ofSeconds(10))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

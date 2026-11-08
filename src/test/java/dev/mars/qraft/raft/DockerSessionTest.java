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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Docker test of sessions and locks (sessions list, Phase 4): on three servers in containers, a session created and a
 * lock acquired on the leader survive the leader's container being stopped. The new leader holds the session and the
 * lock as they were, renews the session, refuses another session the lock, and frees it when its session releases it.
 *
 * <p>Requires Docker. Excluded from the default build; run with {@code mvn test -Dgroups=docker -Dtest.excludedGroups=}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
@Tag("docker")
class DockerSessionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOCK = "/v1/kv/locks/primary";

    @Test
    void aLockAndItsSessionSurviveALeaderChange() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeCluster();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            List<String> servers = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            String leader = awaitLeader(servers);
            HttpResponse<String> registered = send(http, "PUT", leader, "/v1/catalog/register",
                    "{\"node\":{\"address\":\"10.0.0.9\"}}", "X-Qraft-Node", "lock-host");
            assertEquals(200, registered.statusCode(), registered.body());

            String holder = id(send(http, "PUT", leader, "/v1/session/create",
                    "{\"Node\":\"lock-host\",\"Name\":\"primary\",\"TTL\":\"30s\",\"LockDelay\":\"0s\"}"));
            String other = id(send(http, "PUT", leader, "/v1/session/create", "{\"Node\":\"lock-host\"}"));
            assertEquals("true", send(http, "PUT", leader, LOCK + "?acquire=" + holder, "server-a").body());
            JsonNode before = entry(http, leader);

            String stopped = "server" + (servers.indexOf(leader) + 1);
            SharedDockerClusterFixture.stopContainer(cluster, stopped);
            List<String> remaining = servers.stream().filter(server -> !server.equals(leader)).toList();
            String newLeader = awaitLeader(remaining);

            await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
                    .until(() -> holder.equals(entry(http, newLeader).path("Session").asText()));
            JsonNode after = entry(http, newLeader);
            assertEquals(before.path("LockIndex").asLong(), after.path("LockIndex").asLong());
            assertEquals(holder, JSON.readTree(send(http, "PUT", newLeader, "/v1/session/renew/" + holder, null)
                    .body()).get(0).path("ID").asText(), "the new leader renews the session");
            assertEquals("false", send(http, "PUT", newLeader, LOCK + "?acquire=" + other, "server-b").body(),
                    "the lock still refuses another session");

            assertEquals("true", send(http, "PUT", newLeader, LOCK + "?release=" + holder, "server-a").body());
            assertFalse(entry(http, newLeader).has("Session"), "released");
            assertEquals("true", send(http, "PUT", newLeader, LOCK + "?acquire=" + other, "server-b").body(),
                    "with no lock delay, the other session acquires it at once");
        } finally {
            SharedDockerClusterFixture.stopAndCapture(cluster);
        }
    }

    /** The one server among {@code servers} that reports itself leader, once there is exactly one. */
    private static String awaitLeader(List<String> servers) {
        AtomicReference<String> leader = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions().until(() -> {
            List<String> leaders = servers.stream()
                    .filter(server -> "LEADER".equals(DockerClusterApiHelper.statusOrNull(server, "state"))).toList();
            if (leaders.size() != 1) return false;
            leader.set(leaders.getFirst());
            return true;
        });
        return leader.get();
    }

    private static String id(HttpResponse<String> created) throws Exception {
        assertEquals(200, created.statusCode(), created.body());
        return JSON.readTree(created.body()).path("ID").asText();
    }

    private static JsonNode entry(HttpClient http, String server) throws Exception {
        HttpResponse<String> read = send(http, "GET", server, LOCK, null);
        assertEquals(200, read.statusCode(), read.body());
        return JSON.readTree(read.body()).get(0);
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

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
import org.testcontainers.containers.ComposeContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker test of consistency (consistency list, Phase 5): on three servers in containers, a consistent read is
 * answered by the leader alone and refused by the followers; a stale read is answered by every server, which each
 * name a known leader; and a blocking key/value read on a follower wakes when the leader applies a write.
 *
 * <p>Requires Docker. Excluded from the default build; run with {@code mvn test -Dgroups=docker -Dtest.excludedGroups=}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
@Tag("docker")
class DockerConsistencyTest {
    @Test
    void theLeaderAloneAnswersAConsistentReadAndAFollowersBlockingReadWakesOnAWrite() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeCluster();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            List<String> servers = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                    .until(() -> servers.stream().filter(server -> status(http, server, "/v1/catalog/nodes?consistent")
                            == 200).count() == 1);
            String leader = servers.stream().filter(server -> status(http, server, "/v1/catalog/nodes?consistent") == 200)
                    .findFirst().orElseThrow();
            for (String server : servers) {
                HttpResponse<String> stale = get(http, server, "/v1/catalog/nodes?stale");
                assertEquals(200, stale.statusCode());
                assertEquals("true", stale.headers().firstValue("X-Qraft-Known-Leader").orElseThrow(), server);
                if (!server.equals(leader)) {
                    assertEquals(503, status(http, server, "/v1/kv/x?consistent"), "a follower refuses " + server);
                }
            }

            assertEquals(200, send(http, "PUT", leader, "/v1/kv/flag", "off").statusCode());
            String follower = servers.stream().filter(server -> !server.equals(leader)).findFirst().orElseThrow();
            await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> status(http, follower, "/v1/kv/flag") == 200);
            long index = Long.parseLong(get(http, follower, "/v1/kv/flag").headers().firstValue("X-Qraft-Index")
                    .orElseThrow());
            CompletableFuture<HttpResponse<String>> blocked = CompletableFuture.supplyAsync(() -> {
                try {
                    return get(http, follower, "/v1/kv/flag?index=" + index + "&wait=30s");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(500);
            assertFalse(blocked.isDone(), "nothing has changed");
            assertEquals(200, send(http, "PUT", leader, "/v1/kv/flag", "on").statusCode());

            HttpResponse<String> woken = blocked.get(20, TimeUnit.SECONDS);
            assertTrue(Long.parseLong(woken.headers().firstValue("X-Qraft-Index").orElseThrow()) > index);
        } finally {
            SharedDockerClusterFixture.stopAndCapture(cluster);
        }
    }

    private static int status(HttpClient http, String server, String path) {
        try {
            return get(http, server, path).statusCode();
        } catch (Exception unanswered) {
            return -1;
        }
    }

    private static HttpResponse<String> get(HttpClient http, String server, String path) throws Exception {
        return send(http, "GET", server, path, null);
    }

    private static HttpResponse<String> send(HttpClient http, String method, String server, String path, String body)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(server + path)).timeout(Duration.ofSeconds(40))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}

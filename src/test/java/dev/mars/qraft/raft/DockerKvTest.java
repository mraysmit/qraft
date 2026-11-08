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

/**
 * Docker test of key/value (key/value list, Phase 6): on three servers in containers, a compare-and-set write through
 * whichever server leads is read back through every server at the same indexes, and a
 * compare-and-set on a key that exists is refused. Recovery from a server's own data is tested by
 * {@code KvReplicationTest}, since a restarted container may come back on another host port.
 *
 * <p>Requires Docker. Excluded from the default build; run with {@code mvn test -Dgroups=docker -Dtest.excludedGroups=}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
@Tag("docker")
class DockerKvTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aCompareAndSetWriteIsReadThroughEveryServerAtTheSameIndexes() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeCluster();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            List<String> servers = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            AtomicReference<String> leader = new AtomicReference<>();
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions().until(() -> {
                for (String server : servers) {
                    HttpResponse<String> written = send(http, "PUT", server, "/v1/kv/config/db?cas=0", "postgres");
                    if (written.statusCode() == 200) {
                        assertEquals("true", written.body());
                        leader.set(server);
                        return true;
                    }
                }
                return false;
            });
            JsonNode written = entry(http, leader.get());
            for (String server : servers) {
                await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> written.equals(entry(http, server)));
            }
            assertEquals("false", send(http, "PUT", leader.get(), "/v1/kv/config/db?cas=0", "mysql").body(),
                    "a key that exists refuses cas 0");
        } finally {
            SharedDockerClusterFixture.stopAndCapture(cluster);
        }
    }

    private static JsonNode entry(HttpClient http, String server) throws Exception {
        HttpResponse<String> read = send(http, "GET", server, "/v1/kv/config/db", null);
        return read.statusCode() == 200 ? JSON.readTree(read.body()).get(0) : null;
    }

    private static HttpResponse<String> send(HttpClient http, String method, String server, String path, String body)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(server + path)).timeout(Duration.ofSeconds(5))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

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

package dev.mars.qraft.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.testing.fault.SubprocessOutputAuditHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of a host rebuilt under the same name (client list Phase 2, decision 8). A client in its own JVM
 * registers two services; a second client with the same node name and an empty data directory, so a new node ID,
 * is refused the name with {@code node_name_reserved} while the first holds it. Once the first is killed and its
 * node turns unreachable, the second takes the name over: the node carries the new ID, the first node's services
 * are gone, and only the services the second registers are in the catalog. Reaping is set far off, so the name
 * passes by takeover and not because the old node was reaped.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-30
 * @version 1.0
 */
@Tag("e2e")
class WipedClientDirectoryEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NODE_NAME = "rebuilt-host";

    @TempDir
    Path temporaryDirectory;

    private final List<RuntimeLifecycle> lifecycles = new ArrayList<>();
    private final List<Process> clients = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void closeResources() throws Exception {
        for (Process client : clients) {
            client.destroyForcibly();
            client.waitFor(10, TimeUnit.SECONDS);
        }
        for (RuntimeLifecycle lifecycle : lifecycles.reversed()) {
            try {
                lifecycle.closeAsync().get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Preserve the primary failure; each lifecycle is closed independently.
            }
        }
        http.close();
        // The clients are killed, so their console output is audited here.
        for (String output : List.of("original.out", "rebuilt.out")) {
            Path file = temporaryDirectory.resolve(output);
            if (Files.exists(file)) {
                SubprocessOutputAuditHelper.requireNoErrors(output,
                        new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void aRebuiltHostIsRefusedItsNameWhileTheOldNodeHoldsItAndTakesItOverWithoutTheOldServices() throws Exception {
        Path serverConfig = temporaryDirectory.resolve("server.json");
        writeServerConfig(serverConfig);
        RuntimeLifecycle server = QraftRuntimeApplication.launch(
                new String[]{"server", "--config", serverConfig.toString()});
        lifecycles.add(server);
        int httpPort = server.boundPorts().get("http");
        URI serverUri = URI.create("http://127.0.0.1:" + httpPort);
        await(Duration.ofSeconds(10), () -> status(serverUri.resolve("/health/ready")) == 200);

        Path originalData = temporaryDirectory.resolve("original-data");
        Process original = startClient("original", originalData, httpPort, List.of("web", "legacy"));
        await(Duration.ofSeconds(30), () -> "healthy".equals(nodeField(serverUri, "status"))
                && instances(serverUri, "web") == 1 && instances(serverUri, "legacy") == 1);
        String originalId = Files.readString(originalData.resolve("node-id")).strip();
        assertEquals(originalId, nodeField(serverUri, "id"));

        // The same host rebuilt: the same name, an empty data directory, and so a new node ID.
        Path rebuiltData = temporaryDirectory.resolve("rebuilt-data");
        startClient("rebuilt", rebuiltData, httpPort, List.of("web"));
        await(Duration.ofSeconds(30), () -> Files.exists(temporaryDirectory.resolve("rebuilt.out"))
                && Files.readString(temporaryDirectory.resolve("rebuilt.out")).contains("node_name_reserved"));
        assertEquals(originalId, nodeField(serverUri, "id"), "the name stays with the node that holds it");
        assertEquals(1, instances(serverUri, "legacy"));

        original.destroyForcibly();
        assertTrue(original.waitFor(10, TimeUnit.SECONDS), "the original client did not terminate");

        String rebuiltId = awaitFile(rebuiltData.resolve("node-id"));
        assertNotEquals(originalId, rebuiltId);
        await(Duration.ofSeconds(30), () -> rebuiltId.equals(nodeField(serverUri, "id"))
                && "healthy".equals(nodeField(serverUri, "status"))
                && instances(serverUri, "web") == 1 && instances(serverUri, "legacy") == 0);
        assertEquals(1, nodesNamed(serverUri), "one node holds the name");
    }

    private Process startClient(String label, Path dataDirectory, int serverPort, List<String> services)
            throws Exception {
        Path config = temporaryDirectory.resolve(label + ".json");
        writeClientConfig(config, dataDirectory, serverPort, services);
        Process client = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                QraftRuntimeApplication.class.getName(), "client", "--config", config.toString())
                .redirectErrorStream(true)
                .redirectOutput(temporaryDirectory.resolve(label + ".out").toFile())
                .start();
        clients.add(client);
        return client;
    }

    private String awaitFile(Path file) throws Exception {
        await(Duration.ofSeconds(30), () -> Files.exists(file) && !Files.readString(file).isBlank());
        return Files.readString(file).strip();
    }

    /** A field of the node named {@link #NODE_NAME}, or {@code null} while it is absent or unreadable. */
    private String nodeField(URI serverUri, String field) {
        JsonNode node = nodes(serverUri).stream().filter(entry -> NODE_NAME.equals(entry.path("name").asText()))
                .findFirst().orElse(null);
        return node == null || node.path(field).isNull() ? null : node.path(field).asText();
    }

    private long nodesNamed(URI serverUri) {
        return nodes(serverUri).stream().filter(entry -> NODE_NAME.equalsIgnoreCase(entry.path("name").asText()))
                .count();
    }

    private List<JsonNode> nodes(URI serverUri) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(serverUri.resolve("/v1/catalog/nodes"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return List.of();
            List<JsonNode> entries = new ArrayList<>();
            JSON.readTree(response.body()).forEach(entries::add);
            return entries;
        } catch (Exception unavailable) {
            return List.of();
        }
    }

    private int instances(URI serverUri, String service) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            serverUri.resolve("/v1/health/service/" + service)).timeout(Duration.ofSeconds(5)).GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? JSON.readTree(response.body()).size() : -1;
        } catch (Exception unavailable) {
            return -1;
        }
    }

    private int status(URI uri) {
        try {
            return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception unavailable) {
            return -1;
        }
    }

    private static void await(Duration timeout, CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) return;
            Thread.sleep(25);
        }
        assertTrue(condition.evaluate(), "condition was not met before the deadline");
    }

    private void writeServerConfig(Path target) throws Exception {
        Files.writeString(target, """
                {
                  "version": 1,
                  "server": {
                    "id": "identity-node",
                    "http": {"host": "127.0.0.1", "port": 0},
                    "apiGrpcPort": 0,
                    "raft": {
                      "port": 0,
                      "nodes": {"identity-node": "127.0.0.1:0"},
                      "electionTimeoutMs": 100,
                      "heartbeatIntervalMs": 25,
                      "storage": {"type": "raftlog", "path": %s, "fsync": true}
                    },
                    "health": {"expiryIntervalMs": 50, "nodeTtlMs": 600, "nodeReapAfterMs": 600000},
                    "telemetry": {"enabled": false},
                    "shutdown": {"drainTimeoutMs": 100, "timeoutMs": 5000}
                  },
                  "logging": {"directory": %s}
                }
                """.formatted(JSON.writeValueAsString(temporaryDirectory.resolve("raft").toString()),
                JSON.writeValueAsString(temporaryDirectory.resolve("logs").toString())));
    }

    private void writeClientConfig(Path target, Path dataDirectory, int serverPort, List<String> services)
            throws Exception {
        StringBuilder definitions = new StringBuilder();
        for (String service : services) {
            if (!definitions.isEmpty()) definitions.append(',');
            definitions.append("{\"id\": \"").append(service).append("\", \"name\": \"").append(service)
                    .append("\", \"address\": \"127.0.0.1\", \"port\": ").append(serverPort).append('}');
        }
        Files.writeString(target, """
                {
                  "version": 1,
                  "client": {"nodeName": "%s", "dataDirectory": %s, "address": "127.0.0.1", "httpPort": 0,
                            "heartbeatIntervalMs": 50, "shutdownTimeoutMs": 3000},
                  "servers": {"urls": ["http://127.0.0.1:%d"], "requestTimeoutMs": 5000},
                  "catalog": {
                    "registrationRetryMinMs": 25, "registrationRetryMaxMs": 100, "contactFreshnessMs": 1000,
                    "services": [%s]
                  },
                  "logging": {"directory": %s}
                }
                """.formatted(NODE_NAME, JSON.writeValueAsString(dataDirectory.toString()), serverPort, definitions,
                JSON.writeValueAsString(temporaryDirectory.resolve(dataDirectory.getFileName() + "-logs").toString())));
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }
}

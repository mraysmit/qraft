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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test in which server and client modes of the unified runtime converge, recover, and
 * shut down using generated configuration files.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
class UnifiedRuntimeEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    private final List<RuntimeLifecycle> lifecycles = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(300)).build();

    @AfterEach
    void closeRuntimeResources() {
        for (RuntimeLifecycle lifecycle : lifecycles.reversed()) {
            try {
                lifecycle.closeAsync().get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Preserve the test's primary failure; each lifecycle is independently closed.
            }
        }
        http.close();
    }

    @Test
    void serverAndClientConvergeRecoverAndShutDownThroughUnifiedRuntime() throws Exception {
        int httpPort = freePort();
        int raftPort = freePort();
        int apiGrpcPort = freePort();
        int agentPort = freePort();
        Path serverConfig = temporaryDirectory.resolve("server.json");
        Path clientConfig = temporaryDirectory.resolve("client.json");
        writeServerConfig(serverConfig, temporaryDirectory.resolve("raft-first"),
                httpPort, raftPort, apiGrpcPort);
        writeClientConfig(clientConfig, httpPort, agentPort);

        RuntimeLifecycle firstServer = launch("server", serverConfig);
        URI controller = URI.create("http://127.0.0.1:" + httpPort);
        await(() -> status(controller.resolve("/health/ready")) == 200);

        RuntimeLifecycle client = launch("client", clientConfig);
        URI agent = URI.create("http://127.0.0.1:" + agentPort);
        await(() -> status(agent.resolve("/health/ready")) == 200
                && serviceCount(controller, "web") == 1
                && serviceCount(controller, "api") == 1
                && agentPresent(controller, "runtime-agent"));

        firstServer.closeAsync().get(10, TimeUnit.SECONDS);
        assertTrue(firstServer.completion().isDone());
        await(() -> status(agent.resolve("/health/ready")) == 503);

        writeServerConfig(serverConfig, temporaryDirectory.resolve("raft-restarted"),
                httpPort, raftPort, apiGrpcPort);
        RuntimeLifecycle restartedServer = launch("server", serverConfig);
        await(() -> status(agent.resolve("/health/ready")) == 200
                && serviceCount(controller, "web") == 1
                && serviceCount(controller, "api") == 1
                && agentPresent(controller, "runtime-agent"));

        client.closeAsync().get(10, TimeUnit.SECONDS);
        assertTrue(client.completion().isDone());
        await(() -> serviceCount(controller, "web") == 0
                && serviceCount(controller, "api") == 0
                && !agentPresent(controller, "runtime-agent"));
        assertPortAvailable(agentPort);

        restartedServer.closeAsync().get(10, TimeUnit.SECONDS);
        assertTrue(restartedServer.completion().isDone());
        assertPortAvailable(httpPort);
        assertPortAvailable(raftPort);
        assertPortAvailable(apiGrpcPort);
    }

    private RuntimeLifecycle launch(String mode, Path config) {
        RuntimeLifecycle lifecycle = QraftRuntimeApplication.launch(
                new String[]{mode, "--config", config.toString()});
        lifecycles.add(lifecycle);
        return lifecycle;
    }

    private int status(URI uri) {
        try {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(500)).GET().build(), HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (Exception unavailable) {
            return -1;
        }
    }

    private int serviceCount(URI controller, String serviceName) {
        JsonNode response = getJson(controller.resolve("/v1/catalog/service/" + serviceName));
        return response == null || !response.isArray() ? -1 : response.size();
    }

    private boolean agentPresent(URI controller, String agentId) {
        JsonNode response = getJson(controller.resolve("/api/v1/agents"));
        if (response == null || !response.isArray()) return false;
        for (JsonNode agent : response) {
            if (agentId.equals(agent.path("agentId").asText())) return true;
        }
        return false;
    }

    private JsonNode getJson(URI uri) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(500)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? JSON.readTree(response.body()) : null;
        } catch (Exception unavailable) {
            return null;
        }
    }

    private static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) return;
            Thread.sleep(25);
        }
        assertTrue(condition.evaluate(), "condition was not met before the deadline");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void assertPortAvailable(int port) throws Exception {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            assertEquals(port, socket.getLocalPort());
        }
    }

    private void writeServerConfig(Path target, Path storage, int httpPort,
                                   int raftPort, int apiGrpcPort) throws Exception {
        String document = """
                {
                  "version": 1,
                  "server": {
                    "id": "runtime-node",
                    "http": {"host": "127.0.0.1", "port": %d},
                    "apiGrpcPort": %d,
                    "raft": {
                      "port": %d,
                      "nodes": {"runtime-node": "127.0.0.1:%d"},
                      "electionTimeoutMs": 100,
                      "heartbeatIntervalMs": 25,
                      "storage": {"type": "raftlog", "path": %s, "fsync": true},
                      "snapshot": {"enabled": true, "threshold": 10000, "checkIntervalMs": 60000},
                      "logHardLimit": 100000,
                      "io": {"poolSize": 2, "queueSize": 100}
                    },
                    "telemetry": {"enabled": false},
                    "shutdown": {"drainTimeoutMs": 100, "timeoutMs": 5000}
                  },
                  "logging": {"directory": %s}
                }
                """.formatted(httpPort, apiGrpcPort, raftPort, raftPort,
                JSON.writeValueAsString(storage.toString()),
                JSON.writeValueAsString(temporaryDirectory.resolve("logs").toString()));
        Files.writeString(target, document);
    }

    private void writeClientConfig(Path target, int controllerPort, int agentPort) throws Exception {
        String document = """
                {
                  "version": 1,
                  "agent": {
                    "id": "runtime-agent",
                    "hostname": "runtime-agent",
                    "address": "127.0.0.1",
                    "httpPort": %d,
                    "heartbeatIntervalMs": 40,
                    "shutdownTimeoutMs": 3000,
                    "datacenter": "test-dc",
                    "region": "test-region",
                    "version": "1.0.0"
                  },
                  "controllers": {
                    "urls": ["http://127.0.0.1:%d"],
                    "requestTimeoutMs": 500
                  },
                  "catalog": {
                    "tenant": "default",
                    "namespace": "default",
                    "registrationRetryMinMs": 25,
                    "registrationRetryMaxMs": 100,
                    "contactFreshnessMs": 200,
                    "services": [
                      {"id":"web","name":"web","address":"127.0.0.1","port":8080,
                       "tags":["http"],"metadata":{"owner":"runtime"},"enabled":true},
                      {"id":"api","name":"api","address":"127.0.0.1","port":8081,
                       "tags":["http"],"metadata":{"owner":"runtime"},"enabled":true}
                    ]
                  },
                  "logging": {"directory": %s}
                }
                """.formatted(agentPort, controllerPort,
                JSON.writeValueAsString(temporaryDirectory.resolve("logs").toString()));
        Files.writeString(target, document);
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }
}

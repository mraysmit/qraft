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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test in which a client runtime in a separate JVM publishes a check to a real server
 * runtime and is then killed without graceful shutdown: the server's leader expires the unrenewed
 * check and, after the check's deregistration delay, deregisters the crashed agent's service.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
class CrashedAgentExpiryEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    private final List<RuntimeLifecycle> lifecycles = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
    private Process client;

    @AfterEach
    void closeResources() throws Exception {
        if (client != null) {
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
    }

    @Test
    void theLeaderExpiresAndDeregistersTheServicesOfAKilledClient() throws Exception {
        int httpPort = freePort();
        Path serverConfig = temporaryDirectory.resolve("server.json");
        Path clientConfig = temporaryDirectory.resolve("client.json");
        writeServerConfig(serverConfig, httpPort, freePort(), freePort());
        writeClientConfig(clientConfig, httpPort, freePort());
        lifecycles.add(QraftRuntimeApplication.launch(new String[]{"server", "--config", serverConfig.toString()}));
        URI controller = URI.create("http://127.0.0.1:" + httpPort);
        await(Duration.ofSeconds(10), () -> status(controller.resolve("/health/ready")) == 200);

        client = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                QraftRuntimeApplication.class.getName(), "client", "--config", clientConfig.toString())
                .redirectErrorStream(true)
                .redirectOutput(temporaryDirectory.resolve("client.out").toFile())
                .start();
        await(Duration.ofSeconds(30), () -> "PASSING".equals(checkField(controller, "status"))
                && "PASSING".equals(serviceHealth(controller)));

        client.destroyForcibly();
        assertTrue(client.waitFor(10, TimeUnit.SECONDS), "the client process did not terminate");

        AtomicBoolean sawExpiry = new AtomicBoolean();
        await(Duration.ofSeconds(15), () -> {
            if ("true".equals(checkField(controller, "expired"))
                    && "CRITICAL".equals(serviceHealth(controller))) {
                sawExpiry.set(true);
            }
            return sawExpiry.get() && instanceCount(controller) == 0;
        });
        assertTrue(sawExpiry.get(), "the unrenewed check was expired before its service was deregistered");
    }

    @Test
    void theLeaderMarksAKilledClientUnreachableThenReapsItWithItsCheckFreeServices() throws Exception {
        int httpPort = freePort();
        Path serverConfig = temporaryDirectory.resolve("server.json");
        Path clientConfig = temporaryDirectory.resolve("client.json");
        writeServerConfig(serverConfig, httpPort, freePort(), freePort());
        writeCheckFreeClientConfig(clientConfig, httpPort, freePort());
        lifecycles.add(QraftRuntimeApplication.launch(new String[]{"server", "--config", serverConfig.toString()}));
        URI controller = URI.create("http://127.0.0.1:" + httpPort);
        await(Duration.ofSeconds(10), () -> status(controller.resolve("/health/ready")) == 200);

        client = startClient(clientConfig);
        await(Duration.ofSeconds(30), () -> "HEALTHY".equals(nodeStatus(controller)) && instanceCount(controller) == 1);

        client.destroyForcibly();
        assertTrue(client.waitFor(10, TimeUnit.SECONDS), "the client process did not terminate");

        AtomicBoolean sawUnreachable = new AtomicBoolean();
        await(Duration.ofSeconds(15), () -> {
            if ("UNREACHABLE".equals(nodeStatus(controller)) && instanceCount(controller) == 1) sawUnreachable.set(true);
            return sawUnreachable.get() && nodeStatus(controller) == null && instanceCount(controller) == 0;
        });
        assertTrue(sawUnreachable.get(), "the node was marked unreachable before it and its services were reaped");
    }

    private Process startClient(Path clientConfig) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                QraftRuntimeApplication.class.getName(), "client", "--config", clientConfig.toString())
                .redirectErrorStream(true)
                .redirectOutput(temporaryDirectory.resolve("client.out").toFile())
                .start();
    }

    /** Status of the crashing agent in the node registry, or {@code null} once it is gone. */
    private String nodeStatus(URI controller) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(controller.resolve("/api/v1/agents"))
                    .timeout(Duration.ofMillis(500)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return "unavailable";
            for (JsonNode agent : JSON.readTree(response.body())) {
                if ("crashing-agent".equals(agent.path("agentId").asText())) return agent.path("status").asText().toUpperCase(java.util.Locale.ROOT);
            }
            return null;
        } catch (Exception unavailable) {
            return "unavailable";
        }
    }

    private void writeCheckFreeClientConfig(Path target, int controllerPort, int agentPort) throws Exception {
        Files.writeString(target, """
                {
                  "version": 1,
                  "agent": {"id": "crashing-agent", "address": "127.0.0.1", "httpPort": %d,
                            "heartbeatIntervalMs": 50, "shutdownTimeoutMs": 3000},
                  "controllers": {"urls": ["http://127.0.0.1:%d"], "requestTimeoutMs": 5000},
                  "catalog": {
                    "registrationRetryMinMs": 25, "registrationRetryMaxMs": 100, "contactFreshnessMs": 1000,
                    "services": [{"id": "web", "name": "web", "address": "127.0.0.1", "port": %d}]
                  },
                  "logging": {"directory": %s}
                }
                """.formatted(agentPort, controllerPort, controllerPort,
                JSON.writeValueAsString(temporaryDirectory.resolve("client-logs").toString())));
    }

    private JsonNode healthEntries(URI controller) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            controller.resolve("/v1/health/service/web")).timeout(Duration.ofMillis(500)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? JSON.readTree(response.body()) : null;
        } catch (Exception unavailable) {
            return null;
        }
    }

    private String checkField(URI controller, String field) {
        JsonNode entries = healthEntries(controller);
        if (entries == null || entries.size() != 1 || entries.get(0).get("checks").isEmpty()) return null;
        return entries.get(0).get("checks").get(0).get(field).asText();
    }

    private String serviceHealth(URI controller) {
        JsonNode entries = healthEntries(controller);
        return entries == null || entries.size() != 1 ? null : entries.get(0).get("service").get("health").asText();
    }

    private int instanceCount(URI controller) {
        JsonNode entries = healthEntries(controller);
        return entries == null ? -1 : entries.size();
    }

    private int status(URI uri) {
        try {
            return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(500)).GET().build(),
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

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void writeServerConfig(Path target, int httpPort, int raftPort, int apiGrpcPort) throws Exception {
        Files.writeString(target, """
                {
                  "version": 1,
                  "server": {
                    "id": "expiry-node",
                    "http": {"host": "127.0.0.1", "port": %d},
                    "apiGrpcPort": %d,
                    "raft": {
                      "port": %d,
                      "nodes": {"expiry-node": "127.0.0.1:%d"},
                      "electionTimeoutMs": 100,
                      "heartbeatIntervalMs": 25,
                      "storage": {"type": "raftlog", "path": %s, "fsync": true}
                    },
                    "health": {"expiryIntervalMs": 50, "nodeTtlMs": 600, "nodeReapAfterMs": 800},
                    "telemetry": {"enabled": false},
                    "shutdown": {"drainTimeoutMs": 100, "timeoutMs": 5000}
                  },
                  "logging": {"directory": %s}
                }
                """.formatted(httpPort, apiGrpcPort, raftPort, raftPort,
                JSON.writeValueAsString(temporaryDirectory.resolve("raft").toString()),
                JSON.writeValueAsString(temporaryDirectory.resolve("logs").toString())));
    }

    private void writeClientConfig(Path target, int controllerPort, int agentPort) throws Exception {
        Files.writeString(target, """
                {
                  "version": 1,
                  "agent": {"id": "crashing-agent", "address": "127.0.0.1", "httpPort": %d,
                            "heartbeatIntervalMs": 50, "shutdownTimeoutMs": 3000},
                  "controllers": {"urls": ["http://127.0.0.1:%d"], "requestTimeoutMs": 5000},
                  "catalog": {
                    "registrationRetryMinMs": 25, "registrationRetryMaxMs": 100, "contactFreshnessMs": 1000,
                    "services": [
                      {"id": "web", "name": "web", "address": "127.0.0.1", "port": %d,
                       "checks": [{"id": "tcp", "type": "tcp", "intervalMs": 100, "ttlMs": 600,
                                   "deregisterAfterMs": 800}]}
                    ]
                  },
                  "logging": {"directory": %s}
                }
                """.formatted(agentPort, controllerPort, controllerPort,
                JSON.writeValueAsString(temporaryDirectory.resolve("client-logs").toString())));
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }
}

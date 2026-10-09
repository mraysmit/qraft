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

import java.net.ServerSocket;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test in which a client runtime in a separate JVM publishes a check to a real server
 * runtime and is then killed without graceful shutdown: the server's leader expires the unrenewed
 * check and, after the check's deregistration delay, deregisters the crashed client's service.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
@Tag("e2e")
class CrashedClientExpiryEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    private final List<RuntimeLifecycle> lifecycles = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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
        // The killed client never returns through an audit of its own, so its console output is audited here.
        Path clientOutput = temporaryDirectory.resolve("client.out");
        if (client != null && Files.exists(clientOutput)) {
            SubprocessOutputAuditHelper.requireNoErrors("client",
                    new String(Files.readAllBytes(clientOutput), StandardCharsets.UTF_8));
        }
    }

    @Test
    void theLeaderExpiresAndDeregistersTheServicesOfAKilledClient() throws Exception {
        Path serverConfig = temporaryDirectory.resolve("server.json");
        Path clientConfig = temporaryDirectory.resolve("client.json");
        writeServerConfig(serverConfig, 0, 0, 0);
        RuntimeLifecycle server = QraftRuntimeApplication.launch(new String[]{"server", "--config", serverConfig.toString()});
        lifecycles.add(server);
        int httpPort = server.boundPorts().get("http");
        writeClientConfig(clientConfig, httpPort, 0);
        URI serverUri = URI.create("http://127.0.0.1:" + httpPort);
        await(Duration.ofSeconds(10), () -> status(serverUri.resolve("/health/ready")) == 200);

        client = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                QraftRuntimeApplication.class.getName(), "client", "--config", clientConfig.toString())
                .redirectErrorStream(true)
                .redirectOutput(temporaryDirectory.resolve("client.out").toFile())
                .start();
        await(Duration.ofSeconds(30), () -> "PASSING".equals(checkField(serverUri, "status"))
                && "PASSING".equals(serviceHealth(serverUri)));

        client.destroyForcibly();
        assertTrue(client.waitFor(10, TimeUnit.SECONDS), "the client process did not terminate");

        // The check stays expired and critical for deregisterAfterMs (5 s) before deregistration, a window no
        // poll on a loaded machine can miss.
        AtomicBoolean sawExpiry = new AtomicBoolean();
        await(Duration.ofSeconds(30), () -> {
            if ("true".equals(checkField(serverUri, "expired"))
                    && "CRITICAL".equals(serviceHealth(serverUri))) {
                sawExpiry.set(true);
            }
            return sawExpiry.get() && instanceCount(serverUri) == 0;
        });
        assertTrue(sawExpiry.get(), "the unrenewed check was expired before its service was deregistered");
    }

    @Test
    void theLeaderMarksAKilledClientUnreachableThenReapsItWithItsCheckFreeServices() throws Exception {
        Path serverConfig = temporaryDirectory.resolve("server.json");
        Path clientConfig = temporaryDirectory.resolve("client.json");
        writeServerConfig(serverConfig, 0, 0, 0);
        RuntimeLifecycle server = QraftRuntimeApplication.launch(new String[]{"server", "--config", serverConfig.toString()});
        lifecycles.add(server);
        int httpPort = server.boundPorts().get("http");
        writeCheckFreeClientConfig(clientConfig, httpPort, 0);
        URI serverUri = URI.create("http://127.0.0.1:" + httpPort);
        await(Duration.ofSeconds(10), () -> status(serverUri.resolve("/health/ready")) == 200);

        client = startClient(clientConfig);
        await(Duration.ofSeconds(30), () -> "HEALTHY".equals(nodeStatus(serverUri)) && instanceCount(serverUri) == 1);

        client.destroyForcibly();
        assertTrue(client.waitFor(10, TimeUnit.SECONDS), "the client process did not terminate");

        // The node stays unreachable for nodeReapAfterMs (5 s) before it is reaped.
        AtomicBoolean sawUnreachable = new AtomicBoolean();
        await(Duration.ofSeconds(30), () -> {
            if ("UNREACHABLE".equals(nodeStatus(serverUri)) && instanceCount(serverUri) == 1) sawUnreachable.set(true);
            return sawUnreachable.get() && nodeStatus(serverUri) == null && instanceCount(serverUri) == 0;
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

    /** Status of the crashing client in the node registry, or {@code null} once it is gone. */
    private String nodeStatus(URI serverUri) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(serverUri.resolve("/v1/catalog/nodes"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return "unavailable";
            for (JsonNode client : JSON.readTree(response.body())) {
                if ("crashing-client".equals(client.path("name").asText())) return client.path("status").asText().toUpperCase(java.util.Locale.ROOT);
            }
            return null;
        } catch (Exception unavailable) {
            return "unavailable";
        }
    }

    private void writeCheckFreeClientConfig(Path target, int serverPort, int clientPort) throws Exception {
        Files.writeString(target, """
                {
                  "version": 1,
                  "client": {"id": "crashing-client", "address": "127.0.0.1", "httpPort": %d,
                            "heartbeatIntervalMs": 50, "shutdownTimeoutMs": 3000},
                  "servers": {"urls": ["http://127.0.0.1:%d"], "requestTimeoutMs": 5000},
                  "catalog": {
                    "registrationRetryMinMs": 25, "registrationRetryMaxMs": 100, "contactFreshnessMs": 1000,
                    "services": [{"id": "web", "name": "web", "address": "127.0.0.1", "port": %d}]
                  },
                  "logging": {"directory": %s}
                }
                """.formatted(clientPort, serverPort, serverPort,
                JSON.writeValueAsString(temporaryDirectory.resolve("client-logs").toString())));
    }

    private JsonNode healthEntries(URI serverUri) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            serverUri.resolve("/v1/health/service/web")).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? JSON.readTree(response.body()) : null;
        } catch (Exception unavailable) {
            return null;
        }
    }

    private String checkField(URI serverUri, String field) {
        JsonNode entries = healthEntries(serverUri);
        if (entries == null || entries.size() != 1 || entries.get(0).get("checks").isEmpty()) return null;
        return entries.get(0).get("checks").get(0).get(field).asText();
    }

    private String serviceHealth(URI serverUri) {
        JsonNode entries = healthEntries(serverUri);
        return entries == null || entries.size() != 1 ? null : entries.get(0).get("service").get("health").asText();
    }

    private int instanceCount(URI serverUri) {
        JsonNode entries = healthEntries(serverUri);
        return entries == null ? -1 : entries.size();
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
                    "health": {"expiryIntervalMs": 50, "nodeTtlMs": 600, "nodeReapAfterMs": 5000},
                    "telemetry": {"enabled": false},
                    "shutdown": {"drainTimeoutMs": 100, "timeoutMs": 5000}
                  },
                  "logging": {"directory": %s}
                }
                """.formatted(httpPort, apiGrpcPort, raftPort, raftPort,
                JSON.writeValueAsString(temporaryDirectory.resolve("raft").toString()),
                JSON.writeValueAsString(temporaryDirectory.resolve("logs").toString())));
    }

    private void writeClientConfig(Path target, int serverPort, int clientPort) throws Exception {
        Files.writeString(target, """
                {
                  "version": 1,
                  "client": {"id": "crashing-client", "address": "127.0.0.1", "httpPort": %d,
                            "heartbeatIntervalMs": 50, "shutdownTimeoutMs": 3000},
                  "servers": {"urls": ["http://127.0.0.1:%d"], "requestTimeoutMs": 5000},
                  "catalog": {
                    "registrationRetryMinMs": 25, "registrationRetryMaxMs": 100, "contactFreshnessMs": 1000,
                    "services": [
                      {"id": "web", "name": "web", "address": "127.0.0.1", "port": %d,
                       "checks": [{"id": "tcp", "type": "tcp", "intervalMs": 100, "ttlMs": 600,
                                   "deregisterAfterMs": 5000}]}
                    ]
                  },
                  "logging": {"directory": %s}
                }
                """.formatted(clientPort, serverPort, serverPort,
                JSON.writeValueAsString(temporaryDirectory.resolve("client-logs").toString())));
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }
}

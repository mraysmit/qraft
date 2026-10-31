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

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A client runtime in a JVM of its own is told to leave through its local API, and the process exits: the routes alone
 * cannot show this, because it depends on the runtime's lifecycle and on nothing keeping the JVM alive afterwards.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-31
 * @version 1.0
 */
@Tag("e2e")
class ClientLeaveEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Process client;

    @AfterEach
    void stopClient() throws Exception {
        if (client != null && client.isAlive()) {
            client.destroyForcibly();
            client.waitFor(30, TimeUnit.SECONDS);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void aClientToldToLeaveAnswersAndItsProcessExitsCleanly() throws Exception {
        int port = freePort();
        Path config = directory.resolve("client.json");
        Files.writeString(config, """
                {
                  "version": 1,
                  "client": {"nodeName": "leaving-client", "dataDirectory": %s, "address": "127.0.0.1", "httpPort": %d,
                            "heartbeatIntervalMs": 50, "shutdownTimeoutMs": 3000},
                  "servers": {"urls": ["http://127.0.0.1:%d"], "requestTimeoutMs": 1000},
                  "catalog": {"registrationRetryMinMs": 25, "registrationRetryMaxMs": 100, "services": []},
                  "logging": {"directory": %s}
                }
                """.formatted(JSON.writeValueAsString(directory.resolve("data").toString()), port, freePort(),
                JSON.writeValueAsString(directory.resolve("logs").toString())));
        Path output = directory.resolve("client.out");
        client = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                QraftRuntimeApplication.class.getName(), "client", "--config", config.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        URI api = URI.create("http://127.0.0.1:" + port);
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!isLive(api) && System.nanoTime() < deadline) {
            assertTrue(client.isAlive(), "the client stopped before it served anything");
            Thread.sleep(50);
        }
        assertTrue(isLive(api), "the client serves its local API");

        HttpResponse<String> left = http.send(HttpRequest.newBuilder(api.resolve("/v1/client/leave"))
                .timeout(Duration.ofSeconds(30)).PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, left.statusCode(), left.body());
        JsonNode body = JSON.readTree(left.body());
        assertTrue(body.has("deregistered"), left.body());
        assertTrue(client.waitFor(30, TimeUnit.SECONDS), "the process exits after leaving");
        assertEquals(0, client.exitValue(), "leaving is a clean exit");
        assertFalse(isLive(api), "nothing listens any more");
        SubprocessOutputAuditHelper.requireNoErrors("the leaving client", Files.readString(output));
    }

    private boolean isLive(URI api) {
        try {
            return http.send(HttpRequest.newBuilder(api.resolve("/health/live")).timeout(Duration.ofSeconds(2)).GET()
                    .build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException | InterruptedException notYet) {
            return false;
        }
    }
}

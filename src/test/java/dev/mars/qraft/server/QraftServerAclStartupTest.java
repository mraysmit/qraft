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

package dev.mars.qraft.server;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.server.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a real server started with ACLs enabled and a management file installs the file's token when it becomes
 * leader, through the bootstrap, so that its bootstrap endpoint then refuses with reset index 1 (ACL list, Phase 3),
 * and that no log line holds the file's secret.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
class QraftServerAclStartupTest {
    private static final String SECRET = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;

    @Test
    void aServerWithAManagementFileInstallsItsTokenWhenItLeads() throws Exception {
        Path secretFile = Files.writeString(directory.resolve("management-token"), SECRET);
        Path configuration = directory.resolve("server.json");
        JSON.writeValue(configuration.toFile(), Map.of("version", 1, "server", Map.of(
                "id", "acl-startup", "http", Map.of("port", 0), "apiGrpcPort", 0,
                "raft", Map.of("port", 0, "electionTimeoutMs", 300, "heartbeatIntervalMs", 100,
                        "storage", Map.of("path", directory.resolve("raft").toString())),
                "acl", Map.of("enabled", true, "defaultPolicy", "deny",
                        "initialManagementTokenFile", secretFile.toString()),
                "telemetry", Map.of("enabled", false))));
        AppConfig config = AppConfig.fromFile(configuration);
        config.validate();
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        RaftExecutorsFixture executors = RaftExecutorsFixture.create();
        QraftServerService service = new QraftServerService(executors.stateLoop(), executors.blockingTasks(), config);
        try {
            service.start().get(30, TimeUnit.SECONDS);
            URI bootstrap = URI.create("http://127.0.0.1:" + service.boundPorts().get("http") + "/v1/acl/bootstrap");

            HttpResponse<String> answer = refusedOnceInstalled(bootstrap);

            JsonNode error = JSON.readTree(answer.body());
            assertEquals("permission_denied", error.get("code").asText(), answer.body());
            assertEquals(1, error.get("resetIndex").asLong(), "the file's token used the bootstrap");
            assertTrue(captured.list.stream().anyMatch(event -> event.getFormattedMessage()
                    .startsWith("Installed the management token from server.acl.initialManagementTokenFile")));
        } finally {
            try {
                service.stop().get(30, TimeUnit.SECONDS);
            } finally {
                executors.close().get(30, TimeUnit.SECONDS);
                root.detachAppender(captured);
            }
        }
        assertFalse(captured.list.stream().anyMatch(event -> event.getFormattedMessage().contains(SECRET)),
                "no log line holds the management secret");
    }

    /** Asks for a bootstrap until the server, once leader, refuses it; a server not yet leading answers 503. */
    private static HttpResponse<String> refusedOnceInstalled(URI bootstrap) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            while (true) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(bootstrap)
                        .timeout(Duration.ofSeconds(10)).PUT(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 403 || System.nanoTime() > deadline) return response;
                assertEquals(503, response.statusCode(), "only a server that does not lead yet may refuse otherwise: "
                        + response.body());
                Thread.sleep(50);
            }
        }
    }
}

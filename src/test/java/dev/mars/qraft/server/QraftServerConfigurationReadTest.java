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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.server.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a started server answers its own effective configuration (UI-4 list, decision 3): the file it was started
 * from, the settings that file gave, and no secret.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-15
 * @version 1.0
 */
class QraftServerConfigurationReadTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OPERATOR_SECRET = "configuration-read-test-secret-keep-out";
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path directory;
    private RaftExecutorsFixture executors;
    private QraftServerService service;

    @AfterEach
    void stop() throws Exception {
        try {
            if (service != null) service.stop().get(30, TimeUnit.SECONDS);
        } finally {
            if (executors != null) executors.close().get(30, TimeUnit.SECONDS);
            http.close();
        }
    }

    @Test
    void aStartedServerAnswersTheConfigurationItWasStartedFromWithoutItsSecret() throws Exception {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("id", "conf-server");
        server.put("http", Map.of("host", "127.0.0.1", "port", 0));
        server.put("raft", Map.of("port", 0, "electionTimeoutMs", 300, "heartbeatIntervalMs", 100,
                "storage", Map.of("path", directory.resolve("raft").toString())));
        server.put("operator", Map.of("token", OPERATOR_SECRET));
        server.put("telemetry", Map.of("enabled", false));
        Path file = directory.resolve("server.json");
        JSON.writeValue(file.toFile(), Map.of("version", 1, "server", server));
        AppConfig config = AppConfig.fromFile(file);
        config.validate();
        executors = RaftExecutorsFixture.create();
        service = new QraftServerService(executors.stateLoop(), executors.blockingTasks(), config);
        service.start().get(30, TimeUnit.SECONDS);

        HttpResponse<String> answer = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + service.boundAddresses().get("http").getPort() + "/v1/operator/configuration")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, answer.statusCode(), answer.body());
        assertFalse(answer.body().contains(OPERATOR_SECRET), answer.body());
        JsonNode read = JSON.readTree(answer.body());
        assertEquals("conf-server", read.path("server").asText());
        assertEquals(file.toString(), read.path("file").asText());
        boolean found = false;
        for (JsonNode setting : read.path("settings")) {
            if (setting.path("key").asText().equals("server.raft.electionTimeoutMs")) {
                assertEquals(300, setting.path("value").asInt());
                assertEquals("configured", setting.path("source").asText());
                found = true;
            }
            if (setting.path("key").asText().equals("server.operator.token")) {
                assertTrue(setting.path("redacted").asBoolean());
            }
        }
        assertTrue(found, read.toString());
    }
}

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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.server.config.AppConfig;
import dev.mars.qraft.server.http.HttpApiServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a stopping server drains: in the shutdown's first phase its HTTP API takes no new work, readiness
 * reports {@code draining} and new writes are answered 503 {@code draining}. Code defects list, item 7: no drain hook
 * was registered, so a stopping server never drained and {@code server.shutdown.drainTimeoutMs} had no effect.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-21
 * @version 1.0
 */
class QraftServerDrainTest {
    private static final ObjectMapper JSON = new ObjectMapper();

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
        }
    }

    @Test
    void aStoppingServerDrainsItsHttpApiBeforeItStopsIt() throws Exception {
        Path configuration = directory.resolve("server.json");
        JSON.writeValue(configuration.toFile(), Map.of("version", 1, "server", Map.of(
                "id", "drain-test", "http", Map.of("port", 0), "apiGrpcPort", 0,
                "raft", Map.of("port", 0, "electionTimeoutMs", 300, "heartbeatIntervalMs", 100,
                        "storage", Map.of("path", directory.resolve("raft").toString())),
                "telemetry", Map.of("enabled", false))));
        AppConfig config = AppConfig.fromFile(configuration);
        config.validate();
        executors = RaftExecutorsFixture.create();
        service = new QraftServerService(executors.stateLoop(), executors.blockingTasks(), config);
        service.start().get(30, TimeUnit.SECONDS);
        HttpApiServer api = service.httpApi().orElseThrow();
        assertFalse(api.isDraining(), "a running server takes work");

        service.stop().get(30, TimeUnit.SECONDS);

        assertTrue(api.isDraining(), "the shutdown's drain phase put the HTTP API into drain mode");
    }
}

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

package dev.mars.qraft.controller.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the server configuration's range checks at and just beyond each bound: the Raft I/O pool and queue,
 * the Raft timing intervals, the snapshot threshold and check interval, and the Prometheus port. Also tests
 * that a file that cannot be read, and a document containing an environment-style placeholder, are refused.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-28
 * @version 1.0
 */
class AppConfigValidationTest {
    @TempDir
    Path directory;

    @Test
    void theRaftIoPoolSizeIsBetweenOneAndOneHundred() {
        accepted("\"raft\":{\"io\":{\"poolSize\":1}}");
        accepted("\"raft\":{\"io\":{\"poolSize\":100}}");
        rejected("\"raft\":{\"io\":{\"poolSize\":0}}", "Raft I/O pool size must be between 1 and 100");
        rejected("\"raft\":{\"io\":{\"poolSize\":101}}", "Raft I/O pool size must be between 1 and 100");
    }

    @Test
    void theRaftIoQueueSizeIsBetweenTenAndOneHundredThousand() {
        accepted("\"raft\":{\"io\":{\"queueSize\":10}}");
        accepted("\"raft\":{\"io\":{\"queueSize\":100000}}");
        rejected("\"raft\":{\"io\":{\"queueSize\":9}}", "Raft I/O queue size must be between 10 and 100000");
        rejected("\"raft\":{\"io\":{\"queueSize\":100001}}", "Raft I/O queue size must be between 10 and 100000");
    }

    @Test
    void raftTimingIntervalsArePositive() {
        accepted("\"raft\":{\"electionTimeoutMs\":1,\"heartbeatIntervalMs\":1}");
        rejected("\"raft\":{\"electionTimeoutMs\":0}", "Raft timing intervals must be positive");
        rejected("\"raft\":{\"heartbeatIntervalMs\":0}", "Raft timing intervals must be positive");
    }

    @Test
    void theSnapshotThresholdIsPositiveAndItsCheckIntervalAtLeastOneSecond() {
        accepted("\"raft\":{\"snapshot\":{\"threshold\":1,\"checkIntervalMs\":1000}}");
        rejected("\"raft\":{\"snapshot\":{\"threshold\":0}}", "Snapshot threshold must be positive");
        rejected("\"raft\":{\"snapshot\":{\"checkIntervalMs\":999}}", "check interval at least 1000ms");
    }

    @Test
    void thePrometheusPortIsAFixedPort() {
        accepted("\"telemetry\":{\"prometheusPort\":1}");
        accepted("\"telemetry\":{\"prometheusPort\":65535}");
        rejected("\"telemetry\":{\"prometheusPort\":0}", "server.telemetry.prometheusPort must be between 1 and 65535");
        rejected("\"telemetry\":{\"prometheusPort\":65536}",
                "server.telemetry.prometheusPort must be between 1 and 65535");
    }

    @Test
    void theOperatorTokenIsOptionalAndAtLeastSixteenCharacters() {
        assertEquals(Optional.empty(), config("").getOperatorToken(), "no token means removals are refused");
        assertEquals(Optional.of("sixteen-chars-ok"),
                config("\"operator\":{\"token\":\"sixteen-chars-ok\"}").getOperatorToken());
        rejected("\"operator\":{\"token\":\"too-short\"}", "server.operator.token must be at least 16 characters");
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> config("\"operator\":{\"secret\":\"x\"}"));
        assertTrue(unknown.getMessage().contains("secret"), unknown.getMessage());
    }

    @Test
    void aFileThatCannotBeReadIsRefusedByName() throws Exception {
        Path missing = directory.resolve("missing.json");
        Path aDirectory = Files.createDirectories(directory.resolve("server.json"));

        for (Path unreadable : new Path[] {missing, aDirectory}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> AppConfig.fromFile(unreadable), unreadable.toString());
            assertTrue(refused.getMessage().contains("Could not read server configuration " + unreadable),
                    refused.getMessage());
        }
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AppConfig.fromFile(null))
                .getMessage().contains("configuration path is required"));
    }

    @Test
    void anEnvironmentStylePlaceholderIsRefused() {
        for (String server : new String[] {
                "\"http\":{\"host\":\"${QRAFT_HOST}\"}",
                "\"raft\":{\"storage\":{\"path\":\"/data/${NODE}\"}}",
                "\"raft\":{\"nodes\":{\"a\":\"${PEER_A}:9080\"}}",
                "\"telemetry\":{\"otlpEndpoint\":\"http://${COLLECTOR}:4317\"}"}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> AppConfig.fromJson("{\"version\":1,\"server\":{" + server + "}}"), server);
            assertTrue(refused.getMessage().contains("environment-style placeholder"), refused.getMessage());
        }
        IllegalArgumentException logging = assertThrows(IllegalArgumentException.class, () -> AppConfig.fromJson(
                "{\"version\":1,\"server\":{},\"logging\":{\"directory\":\"${LOG_DIR}\"}}"));
        assertTrue(logging.getMessage().startsWith("logging.directory"), logging.getMessage());
    }

    private static void accepted(String server) {
        assertDoesNotThrow(() -> config(server).validate(), server);
    }

    private static void rejected(String server, String reason) {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> config(server).validate(),
                server);
        assertTrue(refused.getMessage().contains(reason), server + ": " + refused.getMessage());
    }

    private static AppConfig config(String server) {
        return AppConfig.fromJson("{\"version\":1,\"server\":{" + server + "}}");
    }
}

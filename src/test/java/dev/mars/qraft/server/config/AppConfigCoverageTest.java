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

package dev.mars.qraft.server.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link AppConfig} parsing of versioned JSON documents, rejection of malformed or unknown
 * settings, node ID rules, and durable WAL storage requirements.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
class AppConfigCoverageTest {
    @Test
    void parsesACompleteVersionedServerDocument() {
        AppConfig config = AppConfig.fromJson("""
                {
                  "version": 1,
                  "server": {
                    "id": "server-a",
                    "http": {"host": "127.0.0.1", "port": 8180},
                    "apiGrpcPort": 10180,
                    "raft": {
                      "port": 9180,
                      "nodes": {"server-a":"server-a:9180","server-b":"server-b:9180"},
                      "electionTimeoutMs": 3200, "heartbeatIntervalMs": 450,
                      "storage": {"type":"raftlog","path":"/data/a","fsync":true},
                      "snapshot": {"enabled":true,"threshold":200,"checkIntervalMs":1500},
                      "logHardLimit": 9000, "io":{"poolSize":4,"queueSize":200}
                    },
                    "telemetry": {"enabled":false,"prometheusPort":9470},
                    "shutdown": {"drainTimeoutMs":1500,"timeoutMs":5000}
                  },
                  "logging": {"directory":"/var/log/qraft"}
                }
                """);

        assertEquals("server-a", config.getNodeId());
        assertEquals(8180, config.getHttpPort());
        assertEquals(9180, config.getRaftPort());
        assertEquals(10180, config.getApiGrpcPort());
        assertEquals("server-a=server-a:9180,server-b=server-b:9180", config.getClusterNodes());
        assertEquals(List.of(Map.entry("server-a", "server-a:9180"), Map.entry("server-b", "server-b:9180")),
                List.copyOf(config.getClusterMembers().entrySet()), "the members keep the document's order");
        assertEquals(1500, config.getShutdownDrainTimeoutMs());
        assertEquals(5000, config.getShutdownTimeoutMs());
        assertEquals(3200, config.getElectionTimeoutMs());
        assertEquals(450, config.getHeartbeatIntervalMs());
        assertEquals("/data/a", config.getRaftStoragePath());
        assertEquals("/var/log/qraft", config.getLoggingDirectory());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void parsesAPositiveLeaderExpiryEvaluationInterval() {
        assertEquals(1_000, AppConfig.fromJson("{\"version\":1,\"server\":{}}").getHealthExpiryIntervalMs());
        assertEquals(250, AppConfig.fromJson(
                "{\"version\":1,\"server\":{\"health\":{\"expiryIntervalMs\":250}}}").getHealthExpiryIntervalMs());
        for (String invalid : List.of("{\"expiryIntervalMs\":0}", "{\"expiryIntervalMs\":-5}",
                "{\"expiryIntervalMs\":\"fast\"}", "{\"expiryIntervalMs\":1.5}", "{\"unexpected\":1}", "[]")) {
            assertThrows(IllegalArgumentException.class, () -> AppConfig.fromJson(
                    "{\"version\":1,\"server\":{\"health\":" + invalid + "}}"), invalid);
        }
    }

    @Test
    void parsesTheServerWideNodeMembershipPolicy() {
        AppConfig defaults = AppConfig.fromJson("{\"version\":1,\"server\":{}}");
        assertEquals(90_000, defaults.getNodeTtlMs());
        assertEquals(Duration.ofHours(72).toMillis(), defaults.getNodeReapAfterMs(),
                "a node left unreachable for 72 hours is reaped with its services unless configured otherwise");

        AppConfig neverReap = AppConfig.fromJson(
                "{\"version\":1,\"server\":{\"health\":{\"nodeReapAfterMs\":0}}}");
        assertEquals(0, neverReap.getNodeReapAfterMs(), "zero explicitly turns reaping off");

        AppConfig configured = AppConfig.fromJson(
                "{\"version\":1,\"server\":{\"health\":{\"nodeTtlMs\":600,\"nodeReapAfterMs\":800}}}");
        assertEquals(600, configured.getNodeTtlMs());
        assertEquals(800, configured.getNodeReapAfterMs());
        for (String invalid : List.of("{\"nodeTtlMs\":0}", "{\"nodeTtlMs\":-1}", "{\"nodeReapAfterMs\":-1}",
                "{\"nodeTtlMs\":\"90s\"}")) {
            assertThrows(IllegalArgumentException.class, () -> AppConfig.fromJson(
                    "{\"version\":1,\"server\":{\"health\":" + invalid + "}}"), invalid);
        }
    }

    @Test
    void rejectsMalformedTypesInsteadOfFallingBack() {
        assertThrows(IllegalArgumentException.class,
                () -> AppConfig.fromJson("{\"version\":1,\"server\":{\"http\":{\"port\":\"bad\"}}}"));
        assertThrows(IllegalArgumentException.class,
                () -> AppConfig.fromJson("{\"version\":2,\"server\":{}}"));
        AppConfig invalidPort = AppConfig.fromJson(
                "{\"version\":1,\"server\":{\"http\":{\"port\":70000}}}");
        assertThrows(IllegalStateException.class, invalidPort::validate);
    }

    @Test
    void rejectsUnknownSettingsAndDuplicateJsonKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> AppConfig.fromJson("{\"version\":1,\"server\":{\"unexpected\":true}}"));
        assertThrows(IllegalArgumentException.class,
                () -> AppConfig.fromJson("{\"version\":1,\"version\":1,\"server\":{}}"));
    }

    @Test
    void requiresAnExplicitNodeIdForMultipleNodes() {
        AppConfig config = AppConfig.fromJson("""
                {"version":1,"server":{"raft":{"nodes":{"a":"a:9080","b":"b:9080"}}}}
                """);
        assertThrows(IllegalStateException.class, config::validate);
    }

    @Test
    void aDocumentWithNoSettingsIsValidAndDescribesAClusterOfThisServerAlone() {
        AppConfig config = AppConfig.fromJson("{\"version\":1,\"server\":{}}");

        assertDoesNotThrow(config::validate);
        assertEquals("./data/raft/" + config.getNodeId(), config.getRaftStoragePath());
        assertEquals(Map.of(config.getNodeId(), "localhost:9080"), config.getClusterMembers());
        assertEquals(config.getNodeId() + "=localhost:9080", config.getClusterNodes());
        assertEquals(5_000, config.getShutdownDrainTimeoutMs());
        assertEquals(30_000, config.getShutdownTimeoutMs());
    }

    @Test
    void memberNamesAndAddressesAreTrimmed() {
        AppConfig config = AppConfig.fromJson("""
                {"version":1,"server":{"id":"a","raft":{"nodes":{" a ":" host-a:9080 ","b":"host-b:9080"}}}}
                """);

        assertEquals(List.of("a", "b"), List.copyOf(config.getClusterMembers().keySet()));
        assertEquals("a=host-a:9080,b=host-b:9080", config.getClusterNodes());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void refusesNonWalOrNonDurableStorage() {
        AppConfig wrongType = AppConfig.fromJson("""
                {"version":1,"server":{"raft":{"storage":{"type":"file","fsync":true}}}}
                """);
        assertThrows(IllegalStateException.class, wrongType::validate);
        AppConfig noFsync = AppConfig.fromJson("""
                {"version":1,"server":{"raft":{"storage":{"type":"raftlog","fsync":false}}}}
                """);
        assertThrows(IllegalStateException.class, noFsync::validate);
    }
}

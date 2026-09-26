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

package dev.mars.qraft.agent.config;

import dev.mars.qraft.agent.health.HttpCheck;
import dev.mars.qraft.agent.health.TcpCheck;
import dev.mars.qraft.agent.health.TtlCheck;
import dev.mars.qraft.catalog.ServiceDefinition;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link AgentConfiguration} building, JSON parsing, defaults, controller seed normalization,
 * and validation of services and health checks.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
class AgentConfigurationTest {
    @Test
    void buildsDiscoveryConfiguration() {
        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-1").hostname("host").address("127.0.0.1")
                .agentPort(8081).region("eu").datacenter("dc1")
                .controllerUrl("http://localhost:9000").heartbeatInterval(1000)
                .httpConnectionTimeout(2500).version("2.0").build();

        assertEquals("agent-1", config.getAgentId());
        assertEquals("host", config.getHostname());
        assertEquals(8081, config.getAgentPort());
        assertEquals("eu", config.getRegion());
        assertEquals("dc1", config.getDatacenter());
        assertEquals(1000, config.getHeartbeatInterval());
        assertEquals(2500, config.getHttpConnectionTimeout());
        assertEquals(2500, config.getHttpIdleTimeout());
        assertEquals("2.0", config.getVersion());
    }

    @Test
    void rejectsInvalidRequiredValues() {
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.builder()
                .controllerUrl("http://localhost").build());
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.builder()
                .agentId("agent").build());
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.builder()
                .agentId("agent").controllerUrl("http://localhost").agentPort(0).build());
    }

    @Test
    void parsesACompleteVersionedJsonDocument() {
        AgentConfiguration config = AgentConfiguration.fromJson("""
                {
                  "version": 1,
                  "agent": {
                    "id": "node-a", "hostname": "host-a", "address": "10.0.0.4",
                    "httpPort": 8181, "heartbeatIntervalMs": 4000,
                    "shutdownTimeoutMs": 12000,
                    "datacenter": "dc1", "region": "eu-west", "version": "2.1"
                  },
                  "controllers": {
                    "urls": ["http://one:8080", "http://two:8080/", "http://one:8080"],
                    "requestTimeoutMs": 2500
                  },
                  "catalog": {
                    "tenant": "tenant-a", "namespace": "payments",
                    "registrationRetryMinMs": 100, "registrationRetryMaxMs": 10000,
                    "contactFreshnessMs": 15000,
                    "services": [{
                      "id": "web", "name": "web-api", "address": "10.0.0.4", "port": 9000,
                      "tags": ["primary"], "metadata": {"zone": "a"}, "enabled": false
                    }]
                  },
                  "logging": {"directory": "/var/log/qraft"}
                }
                """);

        assertEquals("node-a", config.getAgentId());
        assertEquals(List.of(URI.create("http://one:8080"), URI.create("http://two:8080")),
                config.getControllerUrls());
        assertEquals("tenant-a", config.getTenant());
        assertEquals("payments", config.getNamespace());
        assertEquals(100, config.getRegistrationRetryMinMs());
        assertEquals(10_000, config.getRegistrationRetryMaxMs());
        assertEquals(15_000, config.getContactFreshnessMs());
        assertEquals(12_000, config.getShutdownTimeoutMs());
        assertEquals(2_500, config.getRequestTimeoutMs());
        assertEquals("/var/log/qraft", config.getLoggingDirectory());
        assertEquals(new ServiceDefinition("web", "web-api", "10.0.0.4", 9000,
                List.of("primary"), java.util.Map.of("zone", "a"), false), config.getServices().getFirst());
    }

    @Test
    void defaultsCatalogScopeAndAcceptsNoServices() {
        AgentConfiguration config = AgentConfiguration.fromJson(minimalJson(
                "[\"http://localhost:8080\"]", "\"services\": []"));

        assertEquals("default", config.getTenant());
        assertEquals("default", config.getNamespace());
        assertEquals(List.of(), config.getServices());
    }

    @Test
    void rejectsInvalidControllerSeeds() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfiguration.fromJson(minimalJson("[]", "")));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfiguration.fromJson(minimalJson("[\"not a uri\"]", "")));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfiguration.fromJson(minimalJson("[\"ftp://host/path\"]", "")));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfiguration.fromJson(minimalJson("[\"http://host/qraft\"]", "")));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfiguration.fromJson(minimalJson("[\"http://host?zone=a\"]", "")));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfiguration.fromJson(minimalJson("[\"http://host#seed\"]", "")));
    }

    @Test
    void normalizesRootTrailingSlashesBeforeDeduplicatingControllerSeeds() {
        AgentConfiguration config = AgentConfiguration.fromJson(
                minimalJson("[\"http://one:8080/\",\"http://one:8080\"]", ""));

        assertEquals(List.of(URI.create("http://one:8080")), config.getControllerUrls());
    }

    @Test
    void rejectsBlankScopeAndBadNumericValues() {
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.fromJson(
                minimalJson("[\"http://localhost:8080\"]", "\"tenant\": \" \"")));
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.fromJson("""
                {"version":1,"agent":{"id":"a","httpPort":"invalid"},
                 "controllers":{"urls":["http://localhost:8080"]},"catalog":{}}
                """));
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.fromJson("""
                {"version":1,"agent":{"id":"a","httpPort":70000},
                 "controllers":{"urls":["http://localhost:8080"]},"catalog":{}}
                """));
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.fromJson("""
                {"version":1,"agent":{"id":"a"},
                 "controllers":{"urls":["http://localhost:8080"],"requestTimeoutMs":0},
                 "catalog":{}}
                """));
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.fromJson("""
                {"version":1,"agent":{"id":"a"},
                 "controllers":{"urls":["http://localhost:8080"]},
                 "catalog":{"registrationRetryMinMs":1000,"registrationRetryMaxMs":100}}
                """));
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.fromJson("""
                {"version":1,"agent":{"id":"a"},
                 "controllers":{"urls":["http://localhost:8080"]},
                 "catalog":{"contactFreshnessMs":0}}
                """));
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.fromJson("""
                {"version":1,"agent":{"id":"a","shutdownTimeoutMs":0},
                 "controllers":{"urls":["http://localhost:8080"]},"catalog":{}}
                """));
    }

    @Test
    void rejectsUnknownSettingsAndDuplicateJsonKeys() {
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.fromJson("""
                {"version":1,"agent":{"id":"a","unexpected":true},
                 "controllers":{"urls":["http://localhost:8080"]}}
                """));
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.fromJson("""
                {"version":1,"version":1,"agent":{"id":"a"},
                 "controllers":{"urls":["http://localhost:8080"]}}
                """));
    }

    @Test
    void rejectsDuplicateAndMalformedServiceDefinitions() {
        String duplicate = """
                "services":[
                  {"id":"web","name":"web","address":"localhost","port":8080},
                  {"id":"web","name":"other","address":"localhost","port":8081}
                ]
                """;
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfiguration.fromJson(minimalJson("[\"http://localhost:8080\"]", duplicate)));
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.fromJson(
                minimalJson("[\"http://localhost:8080\"]",
                        "\"services\":[{\"id\":\"web\",\"name\":\"web\",\"port\":0}]")));
    }

    @Test
    void producedCollectionsCannotBeMutated() {
        AgentConfiguration config = AgentConfiguration.fromJson(
                minimalJson("[\"http://localhost:8080\"]", ""));
        assertThrows(UnsupportedOperationException.class,
                () -> config.getControllerUrls().add(URI.create("http://other")));
        assertThrows(UnsupportedOperationException.class,
                () -> config.getServices().add(null));
        assertFalse(config.getControllerUrls().isEmpty());
    }

    @Test
    void parsesHealthChecksNestedUnderServiceDefinitions() {
        AgentConfiguration config = AgentConfiguration.fromJson(minimalJson("[\"http://localhost:8080\"]", """
                "services": [
                  {"id":"web","name":"web","address":"10.0.0.4","port":9000,"checks":[
                    {"id":"http","type":"http","url":"http://127.0.0.1:9000/health",
                     "intervalMs":10000,"timeoutMs":2000,"ttlMs":30000},
                    {"id":"tcp","type":"tcp","intervalMs":5000,"required":false},
                    {"id":"app","type":"ttl","ttlMs":15000}
                  ]},
                  {"id":"db","name":"db","address":"10.0.0.5","port":5432,"checks":[
                    {"id":"tcp","type":"tcp","address":"db.local","port":6432,"intervalMs":1000}
                  ]},
                  {"id":"cache","name":"cache","address":"10.0.0.6","port":6379}
                ]
                """));

        assertEquals(List.of(
                new HttpCheck("web", "http", URI.create("http://127.0.0.1:9000/health"),
                        Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(30), true),
                new TcpCheck("web", "tcp", "10.0.0.4", 9000, Duration.ofSeconds(5),
                        Duration.ofSeconds(2), Duration.ofSeconds(15), false),
                new TtlCheck("web", "app", Duration.ofSeconds(15), true),
                new TcpCheck("db", "tcp", "db.local", 6432, Duration.ofSeconds(1),
                        Duration.ofSeconds(1), Duration.ofSeconds(3), true)),
                config.getHealthChecks());
        assertEquals(3, config.getServices().size());
        assertThrows(UnsupportedOperationException.class, () -> config.getHealthChecks().add(null));
    }

    @Test
    void appliesDefaultCheckTimingWhenOnlyTheTypeIsGiven() {
        AgentConfiguration config = AgentConfiguration.fromJson(minimalJson("[\"http://localhost:8080\"]", """
                "services": [{"id":"web","name":"web","address":"localhost","port":8080,"checks":[
                  {"id":"tcp","type":"tcp"},
                  {"id":"http","type":"http","url":"https://localhost:8443/ready"}
                ]}]
                """));

        assertEquals(List.of(
                new TcpCheck("web", "tcp", "localhost", 8080, Duration.ofSeconds(10),
                        Duration.ofSeconds(2), Duration.ofSeconds(30), true),
                new HttpCheck("web", "http", URI.create("https://localhost:8443/ready"),
                        Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(30), true)),
                config.getHealthChecks());
    }

    @Test
    void rejectsInvalidHealthCheckDefinitions() {
        List<String> invalidChecks = List.of(
                "{}",
                "[\"tcp\"]",
                "[{\"type\":\"tcp\"}]",
                "[{\"id\":\" \",\"type\":\"tcp\"}]",
                "[{\"id\":\"c\"}]",
                "[{\"id\":\"c\",\"type\":\"script\"}]",
                "[{\"id\":\"c\",\"type\":\"tcp\"},{\"id\":\"c\",\"type\":\"ttl\",\"ttlMs\":1000}]",
                "[{\"id\":\"c\",\"type\":\"tcp\",\"url\":\"http://localhost\"}]",
                "[{\"id\":\"c\",\"type\":\"tcp\",\"port\":0}]",
                "[{\"id\":\"c\",\"type\":\"tcp\",\"address\":\"\"}]",
                "[{\"id\":\"c\",\"type\":\"http\"}]",
                "[{\"id\":\"c\",\"type\":\"http\",\"url\":\"ftp://localhost/health\"}]",
                "[{\"id\":\"c\",\"type\":\"http\",\"url\":\"http:///health\"}]",
                "[{\"id\":\"c\",\"type\":\"http\",\"url\":\"http://user@localhost/health\"}]",
                "[{\"id\":\"c\",\"type\":\"http\",\"url\":\"http://localhost/health\",\"address\":\"x\"}]",
                "[{\"id\":\"c\",\"type\":\"ttl\"}]",
                "[{\"id\":\"c\",\"type\":\"ttl\",\"ttlMs\":1000,\"intervalMs\":500}]",
                "[{\"id\":\"c\",\"type\":\"ttl\",\"ttlMs\":0}]",
                "[{\"id\":\"c\",\"type\":\"tcp\",\"intervalMs\":0}]",
                "[{\"id\":\"c\",\"type\":\"tcp\",\"intervalMs\":1000,\"timeoutMs\":0}]",
                "[{\"id\":\"c\",\"type\":\"tcp\",\"intervalMs\":1000,\"timeoutMs\":1001}]",
                "[{\"id\":\"c\",\"type\":\"tcp\",\"intervalMs\":1000,\"ttlMs\":1000}]",
                "[{\"id\":\"c\",\"type\":\"tcp\",\"intervalMs\":\"1000\"}]",
                "[{\"id\":\"c\",\"type\":\"tcp\",\"required\":\"yes\"}]");

        for (String checks : invalidChecks) {
            String services = """
                    "services":[{"id":"web","name":"web","address":"localhost","port":8080,"checks":%s}]
                    """.formatted(checks);
            assertThrows(IllegalArgumentException.class,
                    () -> AgentConfiguration.fromJson(minimalJson("[\"http://localhost:8080\"]", services)),
                    checks);
        }
    }

    @Test
    void builderRejectsChecksForUnknownServicesAndDuplicateCheckIdentities() {
        ServiceDefinition web = new ServiceDefinition("web", "web", "localhost", 8080, List.of(), Map.of(), true);
        TtlCheck check = new TtlCheck("web", "app", Duration.ofSeconds(10), true);

        assertEquals(List.of(check), AgentConfiguration.builder().agentId("agent")
                .controllerUrl("http://localhost").services(List.of(web))
                .healthChecks(List.of(check)).build().getHealthChecks());
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.builder().agentId("agent")
                .controllerUrl("http://localhost").services(List.of(web))
                .healthChecks(List.of(new TtlCheck("missing", "app", Duration.ofSeconds(10), true))).build());
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.builder().agentId("agent")
                .controllerUrl("http://localhost").services(List.of(web))
                .healthChecks(List.of(check, new TtlCheck("web", "app", Duration.ofSeconds(20), true))).build());
    }

    private static String minimalJson(String urls, String catalogFields) {
        String separator = catalogFields.isBlank() ? "" : catalogFields;
        return """
                {"version":1,"agent":{"id":"agent-a"},
                 "controllers":{"urls":%s},"catalog":{%s}}
                """.formatted(urls, separator);
    }
}

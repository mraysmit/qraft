package dev.mars.qraft.agent.config;

import dev.mars.qraft.catalog.ServiceDefinition;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static String minimalJson(String urls, String catalogFields) {
        String separator = catalogFields.isBlank() ? "" : catalogFields;
        return """
                {"version":1,"agent":{"id":"agent-a"},
                 "controllers":{"urls":%s},"catalog":{%s}}
                """.formatted(urls, separator);
    }
}

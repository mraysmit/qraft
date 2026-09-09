package dev.mars.qraft.agent.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}

package dev.mars.qraft.agent.service;

import dev.mars.qraft.agent.config.AgentConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthServiceTest {
    @Test
    void tracksLocalAgentHealth() {
        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-1").controllerUrl("http://localhost").build();
        HealthService health = new HealthService(config);

        assertFalse(health.isHealthy());
        assertTrue(health.agentId().equals("agent-1"));
        health.start();
        assertTrue(health.isHealthy());
        health.shutdown();
        assertFalse(health.isHealthy());
    }
}

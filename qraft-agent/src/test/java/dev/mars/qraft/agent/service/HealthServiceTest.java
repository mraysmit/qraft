package dev.mars.qraft.agent.service;

import dev.mars.qraft.agent.config.AgentConfiguration;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthServiceTest {
    @Test
    void tracksLocalAgentHealth() {
        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-1").controllerUrl("http://localhost").agentPort(freePort()).build();
        HealthService health = new HealthService(config, () -> false);

        assertFalse(health.isHealthy());
        assertTrue(health.agentId().equals("agent-1"));
        health.start();
        assertTrue(health.isHealthy());
        health.shutdown();
        assertFalse(health.isHealthy());
    }

    @Test
    void servesLivenessAndReadinessEndpoints() throws Exception {
        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-health").controllerUrl("http://localhost").agentPort(freePort()).build();
        AtomicBoolean ready = new AtomicBoolean();
        HealthService health = new HealthService(config, ready::get);
        HttpClient client = HttpClient.newHttpClient();

        try {
            health.start();

            assertEquals(200, get(client, config.getAgentPort(), "/health/live"));
            assertEquals(503, get(client, config.getAgentPort(), "/health/ready"));
            assertEquals(503, get(client, config.getAgentPort(), "/health"));

            ready.set(true);

            assertEquals(200, get(client, config.getAgentPort(), "/health/ready"));
            assertEquals(200, get(client, config.getAgentPort(), "/health"));
        } finally {
            health.shutdown();
        }
    }

    private static int get(HttpClient client, int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find a free test port", e);
        }
    }
}

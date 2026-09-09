package dev.mars.qraft.agent.service;

import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.agent.AgentInfo;
import dev.mars.qraft.agent.config.AgentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void publishesHeartbeatAfterRegistration() throws Exception {
        AtomicInteger heartbeats = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/agents/register", exchange -> {
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.createContext("/agents/heartbeat", exchange -> {
            heartbeats.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-1").controllerUrl("http://localhost:" + server.getAddress().getPort())
                .httpConnectionTimeout(1000).build();
        AgentRegistrationClient registration = new AgentRegistrationClient(
                HttpClient.newHttpClient(), new com.fasterxml.jackson.databind.ObjectMapper(),
                URI.create(config.getControllerUrl()), Duration.ofSeconds(1));
        AgentInfo agent = new AgentInfo("agent-1", "host", "127.0.0.1", 8080);
        assertTrue(registration.register(agent).join());

        HeartbeatService heartbeat = new HeartbeatService(config, registration);
        assertTrue(heartbeat.sendHeartbeat().join());
        assertTrue(heartbeats.get() == 1);
    }

    @Test
    void doesNotPublishBeforeRegistration() {
        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-1").controllerUrl("http://localhost").build();
        AgentRegistrationClient registration = new AgentRegistrationClient(
                HttpClient.newHttpClient(), new com.fasterxml.jackson.databind.ObjectMapper(),
                URI.create("http://localhost"), Duration.ofMillis(100));
        assertFalse(new HeartbeatService(config, registration).sendHeartbeat().join());
    }
}

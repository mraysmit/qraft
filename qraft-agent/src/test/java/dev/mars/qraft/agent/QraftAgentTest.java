package dev.mars.qraft.agent;

import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.agent.config.AgentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QraftAgentTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void startsAndShutsDownAgainstController() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/agents/register", exchange -> {
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.createContext("/api/v1/agents/agent-1", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-1")
                .hostname("host")
                .address("127.0.0.1")
                .agentPort(freePort())
                .controllerUrl("http://localhost:" + server.getAddress().getPort() + "/api/v1")
                .heartbeatInterval(60_000)
                .build();
        QraftAgent agent = new QraftAgent(config);

        assertTrue(agent.start().join());
        assertTrue(agent.isRunning());
        assertTrue(agent.healthService().isHealthy());
        assertTrue(agent.shutdown().join());
        assertFalse(agent.isRunning());
        assertFalse(agent.healthService().isHealthy());
    }

    @Test
    void remainsLiveButUnreadyWhenInitialRegistrationFails() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/agents/register", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-1")
                .hostname("host")
                .address("127.0.0.1")
                .agentPort(freePort())
                .controllerUrl("http://localhost:" + server.getAddress().getPort() + "/api/v1")
                .build();
        QraftAgent agent = new QraftAgent(config);

        try {
            assertFalse(agent.start().join());
            assertTrue(agent.isRunning());
            assertTrue(agent.healthService().isHealthy());
            assertFalse(agent.healthService().isReady());
        } finally {
            agent.shutdown().join();
        }
    }

    @Test
    void retriesRegistrationAndBecomesReadyWhenControllerRecovers() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger heartbeats = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/agents/register", exchange -> {
            int status = registrations.incrementAndGet() == 1 ? 503 : 201;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.createContext("/api/v1/agents/heartbeat", exchange -> {
            heartbeats.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/api/v1/agents/agent-1", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-1")
                .hostname("host")
                .address("127.0.0.1")
                .agentPort(freePort())
                .controllerUrl("http://localhost:" + server.getAddress().getPort() + "/api/v1")
                .heartbeatInterval(25)
                .build();
        QraftAgent agent = new QraftAgent(config);

        try {
            assertFalse(agent.start().join(), "the initial registration should expose the outage");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while ((!agent.healthService().isReady() || heartbeats.get() == 0)
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            assertAll(
                    () -> assertTrue(registrations.get() >= 2, "registration must be retried"),
                    () -> assertTrue(agent.healthService().isReady(), "successful retry must make the agent ready"),
                    () -> assertTrue(heartbeats.get() > 0, "heartbeats must begin after recovery"));
        } finally {
            agent.shutdown().join();
        }
    }

    @Test
    void registersAgainWhenControllerForgetsAgent() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger heartbeats = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/agents/register", exchange -> {
            registrations.incrementAndGet();
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.createContext("/api/v1/agents/heartbeat", exchange -> {
            int status = heartbeats.incrementAndGet() == 1 ? 404 : 204;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.createContext("/api/v1/agents/agent-1", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-1")
                .hostname("host")
                .address("127.0.0.1")
                .agentPort(freePort())
                .controllerUrl("http://localhost:" + server.getAddress().getPort() + "/api/v1")
                .heartbeatInterval(25)
                .httpConnectionTimeout(1_000)
                .build();
        QraftAgent agent = new QraftAgent(config);

        try {
            assertTrue(agent.start().get(1, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((registrations.get() < 2 || heartbeats.get() < 2)
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            assertAll(
                    () -> assertTrue(registrations.get() >= 2,
                            "a heartbeat 404 must trigger registration again"),
                    () -> assertTrue(heartbeats.get() >= 2,
                            "heartbeats must resume after registration"),
                    () -> assertTrue(agent.healthService().isReady()));
        } finally {
            agent.shutdown().join();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

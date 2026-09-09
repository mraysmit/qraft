package dev.mars.qraft.agent;

import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.agent.config.AgentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

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
        server.createContext("/agents/register", exchange -> {
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.createContext("/agents/agent-1", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-1")
                .hostname("host")
                .address("127.0.0.1")
                .agentPort(8080)
                .controllerUrl("http://localhost:" + server.getAddress().getPort())
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
}

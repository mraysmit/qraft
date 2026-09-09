package dev.mars.qraft.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.agent.AgentInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AgentRegistrationClientTest {
    private HttpServer server;
    private String requestPath;
    private String requestBody;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1", exchange -> {
            requestPath = exchange.getRequestURI().getPath();
            requestBody = new String(exchange.getRequestBody().readAllBytes());
            int status = exchange.getRequestMethod().equals("DELETE") ? 204 : 201;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    void registersAgentUsingJdkHttpClient() throws Exception {
        AgentRegistrationClient client = client();
        AgentInfo agent = new AgentInfo("agent-1", "host", "127.0.0.1", 8080);

        assertTrue(client.register(agent).join());
        assertEquals("/api/v1/agents/register", requestPath);
        assertTrue(requestBody.contains("agent-1"));
    }

    @Test
    void deregistersAgentUsingJdkHttpClient() {
        assertTrue(client().deregister("agent-1").join());
        assertEquals("/api/v1/agents/agent-1", requestPath);
    }

    private AgentRegistrationClient client() {
        return new AgentRegistrationClient(HttpClient.newHttpClient(), new ObjectMapper(),
                java.net.URI.create("http://localhost:" + server.getAddress().getPort() + "/api/v1"),
                Duration.ofSeconds(2));
    }
}

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

package dev.mars.qraft.agent.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.agent.AgentInfo;
import dev.mars.qraft.agent.catalog.HttpCatalogClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link AgentRegistrationClient} node registration and deregistration, seed selection,
 * rejection handling, and shutdown ordering against a local HTTP server.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
class AgentRegistrationClientTest {
    private HttpServer server;
    private String requestPath;
    private String requestBody;
    private HttpCatalogClient controllerClient;

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
    void tearDown() {
        if (controllerClient != null) controllerClient.close();
        server.stop(0);
    }

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

    @Test
    void nodeRegistrationUsesTheSharedControllerSeedSelector() throws Exception {
        URI refused;
        try (ServerSocket socket = new ServerSocket(0)) {
            refused = URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
        URI available = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        controllerClient = new HttpCatalogClient(HttpClient.newHttpClient(), new ObjectMapper(),
                List.of(refused, available), "agent-1", "default", "default",
                "default", "default", Duration.ofSeconds(10));
        AgentRegistrationClient client = new AgentRegistrationClient(controllerClient);

        assertTrue(client.register(new AgentInfo("agent-1", "host", "127.0.0.1", 8080)).join());
        assertEquals("/api/v1/agents/register", requestPath);
    }

    @Test
    void rejectedRegistrationIsLoggedAndIsNotRetryable() throws Exception {
        server.removeContext("/api/v1");
        AtomicInteger registrations = new AtomicInteger();
        server.createContext("/api/v1", exchange -> {
            registrations.incrementAndGet();
            byte[] body = ("{\"code\":\"invalid_agent\",\"message\":\"bad address\","
                    + "\"retryable\":false}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        AgentRegistrationClient client = client();
        Logger logger = (Logger) LoggerFactory.getLogger(AgentRegistrationClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertFalse(client.register(new AgentInfo("agent-1", "host", "127.0.0.1", 8080)).join());

            assertFalse(client.shouldRetryRegistration());
            assertEquals(1, registrations.get());
            assertEquals(1, appender.list.stream().filter(event ->
                    event.getFormattedMessage().contains("agentId=agent-1")
                            && event.getFormattedMessage().contains("invalid_agent")
                            && event.getFormattedMessage().contains("bad address")).count());
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void shutdownDeregistrationWaitsForAnActiveRegistration() throws Exception {
        server.removeContext("/api/v1");
        CountDownLatch registrationStarted = new CountDownLatch(1);
        CountDownLatch releaseRegistration = new CountDownLatch(1);
        AtomicInteger deregistrations = new AtomicInteger();
        server.createContext("/api/v1", exchange -> {
            if (exchange.getRequestMethod().equals("DELETE")) {
                deregistrations.incrementAndGet();
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            registrationStarted.countDown();
            try {
                releaseRegistration.await(2, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(201, -1);
                exchange.close();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        AgentRegistrationClient client = client();
        CompletableFuture<Boolean> registration = client.register(
                new AgentInfo("agent-1", "host", "127.0.0.1", 8080));
        assertTrue(registrationStarted.await(1, TimeUnit.SECONDS));

        CompletableFuture<Boolean> shutdown = client.beginShutdownAndDeregister("agent-1");

        assertFalse(shutdown.isDone());
        releaseRegistration.countDown();
        assertTrue(registration.get(10, TimeUnit.SECONDS));
        assertTrue(shutdown.get(10, TimeUnit.SECONDS));
        assertEquals(1, deregistrations.get());
        assertFalse(client.isRegistered());
    }

    @Test
    void aSlowHeartbeatFromAnEarlierRegistrationCannotUnregisterANewerOne() throws Exception {
        java.util.concurrent.CountDownLatch heartbeatArrived = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseHeartbeat = new java.util.concurrent.CountDownLatch(1);
        startConcurrentServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (exchange.getRequestURI().getPath().endsWith("/heartbeat")) {
                heartbeatArrived.countDown();
                await(releaseHeartbeat);
                respond(exchange, 404, "{\"code\":\"agent_not_found\",\"message\":\"gone\",\"retryable\":false}");
            } else {
                exchange.sendResponseHeaders(201, -1);
                exchange.close();
            }
        });
        AgentRegistrationClient client = client();
        AgentInfo agent = new AgentInfo("agent-1", "host", "127.0.0.1", 8080);
        assertTrue(client.register(agent).get(10, TimeUnit.SECONDS));

        CompletableFuture<Boolean> staleHeartbeat = client.heartbeat("agent-1", java.time.Instant.now(), 1, "passing");
        assertTrue(heartbeatArrived.await(10, TimeUnit.SECONDS));
        assertTrue(client.register(agent).get(10, TimeUnit.SECONDS), "a newer registration succeeds");
        String current = client.registrationId();
        releaseHeartbeat.countDown();
        staleHeartbeat.get(10, TimeUnit.SECONDS);

        assertTrue(client.isRegistered(), "the rejection belonged to the earlier registration");
        assertEquals(current, client.registrationId());
    }

    @Test
    void aSlowFailedRegistrationCannotOverwriteANewerSuccessfulOne() throws Exception {
        java.util.concurrent.CountDownLatch firstArrived = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseFirst = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger registrations = new java.util.concurrent.atomic.AtomicInteger();
        startConcurrentServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (registrations.incrementAndGet() == 1) {
                firstArrived.countDown();
                await(releaseFirst);
                respond(exchange, 503, "{\"code\":\"leader_unavailable\",\"message\":\"later\",\"retryable\":true}");
            } else {
                exchange.sendResponseHeaders(201, -1);
                exchange.close();
            }
        });
        controllerClient = new HttpCatalogClient(HttpClient.newHttpClient(), new ObjectMapper(),
                List.of(java.net.URI.create("http://localhost:" + server.getAddress().getPort())),
                "agent-1", "default", "default", "default", "default", Duration.ofSeconds(30));
        AgentRegistrationClient client = new AgentRegistrationClient(controllerClient);
        AgentInfo agent = new AgentInfo("agent-1", "host", "127.0.0.1", 8080);

        CompletableFuture<Boolean> slowFailure = client.register(agent);
        assertTrue(firstArrived.await(10, TimeUnit.SECONDS));
        assertTrue(client.register(agent).get(10, TimeUnit.SECONDS));
        releaseFirst.countDown();
        assertEquals(false, slowFailure.get(10, TimeUnit.SECONDS));

        assertTrue(client.isRegistered(), "an earlier attempt's late failure must not undo the newer registration");
    }

    /** Replaces the fixture server with one that serves requests concurrently, so a held response blocks nothing else. */
    private void startConcurrentServer(com.sun.net.httpserver.HttpHandler handler) throws java.io.IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/v1", handler);
        server.start();
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private AgentRegistrationClient client() {
        controllerClient = new HttpCatalogClient(HttpClient.newHttpClient(), new ObjectMapper(),
                List.of(java.net.URI.create("http://localhost:" + server.getAddress().getPort())),
                "agent-1", "default", "default", "default", "default", Duration.ofSeconds(10));
        return new AgentRegistrationClient(controllerClient);
    }
}

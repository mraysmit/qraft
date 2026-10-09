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

package dev.mars.qraft.client.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.common.Node;
import dev.mars.qraft.client.NodeAnswerHelper;
import dev.mars.qraft.client.catalog.HttpCatalogClient;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link RegistrationClient} node registration and deregistration, seed selection,
 * rejection handling, and shutdown ordering against a local HTTP server.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
class RegistrationClientTest {
    private HttpServer server;
    private String requestPath;
    private String requestBody;
    private String requestIdentity;
    private HttpCatalogClient serverClient;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/catalog", exchange -> {
            requestPath = exchange.getRequestURI().getPath();
            requestBody = new String(exchange.getRequestBody().readAllBytes());
            requestIdentity = exchange.getRequestMethod() + " as "
                    + exchange.getRequestHeaders().getFirst("X-Qraft-Node");
            NodeAnswerHelper.accept(exchange);
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (serverClient != null) serverClient.close();
        server.stop(0);
    }

    @Test
    void registersTheNodeThatTheIdentityHeaderNames() throws Exception {
        RegistrationClient client = client();
        Node info = Node.of("client-1", "127.0.0.1", "dc-1", null, java.util.Map.of("rack", "r7"));

        assertTrue(client.register(info).join());

        assertEquals(NodeAnswerHelper.REGISTER, requestPath);
        assertEquals("PUT as client-1", requestIdentity);
        ObjectMapper json = new ObjectMapper();
        assertEquals(json.readTree("""
                {"node":{"address":"127.0.0.1","datacenter":"dc-1","region":null,
                         "metadata":{"qraft.registrationId":"%s","rack":"r7"}}}
                """.formatted(client.registrationId())), json.readTree(requestBody),
                "the body describes the node and carries the identifier of this registration");
    }

    @Test
    void aNodeOfAnotherNameIsNeverSent() {
        RegistrationClient client = client();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> client.register(Node.of("another-node", "127.0.0.1", null, null, null)));

        assertEquals("This client speaks for node client-1, not another-node", refused.getMessage());
        assertNull(requestPath, "nothing was sent");
    }

    @Test
    void deregistersTheNodeThatTheIdentityHeaderNames() {
        assertTrue(client().deregister().join());

        assertEquals(NodeAnswerHelper.DEREGISTER, requestPath);
        assertEquals("PUT as client-1", requestIdentity);
        assertEquals("{}", requestBody, "a body that names no service removes the node itself");
    }

    @Test
    void aSuccessThatDoesNotNameTheNodeIsNotASuccess() {
        server.removeContext("/v1/catalog");
        server.createContext("/v1/catalog", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "{}");
        });
        RegistrationClient client = client();

        assertFalse(client.register(Node.of("client-1", "127.0.0.1", null, null, null)).join(),
                "an answer that some other HTTP service could give does not register the node");
        assertTrue(client.shouldRetryRegistration());
        assertFalse(client.isRegistered());
    }

    @Test
    void nodeRegistrationUsesTheSharedServerSeedSelector() throws Exception {
        URI refused;
        try (ServerSocket socket = new ServerSocket(0)) {
            refused = URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
        URI available = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        serverClient = new HttpCatalogClient(HttpClient.newHttpClient(), new ObjectMapper(),
                List.of(refused, available), "client-1", "default", "default",
                "default", "default", Duration.ofSeconds(10));
        RegistrationClient client = new RegistrationClient(serverClient);

        assertTrue(client.register(Node.of("client-1", "127.0.0.1", null, null, null)).join());
        assertEquals(NodeAnswerHelper.REGISTER, requestPath);
    }

    @Test
    void rejectedRegistrationIsLoggedAndIsNotRetryable() throws Exception {
        server.removeContext("/v1/catalog");
        AtomicInteger registrations = new AtomicInteger();
        server.createContext("/v1/catalog", exchange -> {
            registrations.incrementAndGet();
            byte[] body = ("{\"code\":\"invalid_registration\",\"message\":\"bad address\","
                    + "\"retryable\":false}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        RegistrationClient client = client();
        Logger logger = (Logger) LoggerFactory.getLogger(RegistrationClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertFalse(client.register(Node.of("client-1", "127.0.0.1", null, null, null)).join());

            assertFalse(client.shouldRetryRegistration());
            assertEquals(1, registrations.get());
            assertEquals(1, appender.list.stream().filter(event ->
                    event.getFormattedMessage().contains("clientId=client-1")
                            && event.getFormattedMessage().contains("invalid_registration")
                            && event.getFormattedMessage().contains("bad address")).count());
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void shutdownDeregistrationWaitsForAnActiveRegistration() throws Exception {
        server.removeContext("/v1/catalog");
        CountDownLatch registrationStarted = new CountDownLatch(1);
        CountDownLatch releaseRegistration = new CountDownLatch(1);
        AtomicInteger deregistrations = new AtomicInteger();
        server.createContext("/v1/catalog", exchange -> {
            if (exchange.getRequestURI().getPath().equals(NodeAnswerHelper.DEREGISTER)) {
                deregistrations.incrementAndGet();
                NodeAnswerHelper.accept(exchange);
                return;
            }
            registrationStarted.countDown();
            try {
                // Held until the test has asserted that shutdown waits; the bound only frees a failed test.
                releaseRegistration.await(30, TimeUnit.SECONDS);
                NodeAnswerHelper.answer(exchange, 200, "registered", true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        RegistrationClient client = client();
        CompletableFuture<Boolean> registration = client.register(
                Node.of("client-1", "127.0.0.1", null, null, null));
        assertTrue(registrationStarted.await(10, TimeUnit.SECONDS));

        CompletableFuture<Boolean> shutdown = client.beginShutdownAndDeregister();

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
                respond(exchange, 404, "{\"code\":\"node_not_found\",\"message\":\"gone\",\"retryable\":false}");
            } else {
                NodeAnswerHelper.answer(exchange, 200, "registered", true);
            }
        });
        RegistrationClient client = client();
        Node info = Node.of("client-1", "127.0.0.1", null, null, null);
        assertTrue(client.register(info).get(10, TimeUnit.SECONDS));

        CompletableFuture<Boolean> staleHeartbeat = client.heartbeat(1);
        assertTrue(heartbeatArrived.await(10, TimeUnit.SECONDS));
        assertTrue(client.register(info).get(10, TimeUnit.SECONDS), "a newer registration succeeds");
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
                NodeAnswerHelper.answer(exchange, 200, "registered", true);
            }
        });
        serverClient = new HttpCatalogClient(HttpClient.newHttpClient(), new ObjectMapper(),
                List.of(java.net.URI.create("http://localhost:" + server.getAddress().getPort())),
                "client-1", "default", "default", "default", "default", Duration.ofSeconds(30));
        RegistrationClient client = new RegistrationClient(serverClient);
        Node info = Node.of("client-1", "127.0.0.1", null, null, null);

        CompletableFuture<Boolean> slowFailure = client.register(info);
        assertTrue(firstArrived.await(10, TimeUnit.SECONDS));
        assertTrue(client.register(info).get(10, TimeUnit.SECONDS));
        releaseFirst.countDown();
        assertEquals(false, slowFailure.get(10, TimeUnit.SECONDS));

        assertTrue(client.isRegistered(), "an earlier attempt's late failure must not undo the newer registration");
    }

    /** Replaces the fixture server with one that serves requests concurrently, so a held response blocks nothing else. */
    private void startConcurrentServer(com.sun.net.httpserver.HttpHandler handler) throws java.io.IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/catalog", handler);
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

    private RegistrationClient client() {
        serverClient = new HttpCatalogClient(HttpClient.newHttpClient(), new ObjectMapper(),
                List.of(java.net.URI.create("http://localhost:" + server.getAddress().getPort())),
                "client-1", "default", "default", "default", "default", Duration.ofSeconds(10));
        return new RegistrationClient(serverClient);
    }
}

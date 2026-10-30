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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mars.qraft.client.config.ClientConfiguration;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link LocalApiServer} local health tracking across start and shutdown, and its liveness and
 * readiness HTTP endpoints.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
class LocalApiServerTest {
    @Test
    void tracksLocalClientHealth() {
        ClientConfiguration config = ClientConfiguration.builder()
                .nodeName("client-1").dataDirectory(Path.of("data"))
                .serverUrl("http://localhost").clientPort(0).build();
        LocalApiServer health = new LocalApiServer(config, () -> false);

        assertFalse(health.isHealthy());
        assertEquals("client-1", health.nodeName());
        health.start();
        assertTrue(health.isHealthy());
        health.shutdown();
        assertFalse(health.isHealthy());
    }

    @Test
    void servesLivenessAndReadinessEndpoints() throws Exception {
        ClientConfiguration config = ClientConfiguration.builder()
                .nodeName("client-health").dataDirectory(Path.of("data"))
                .serverUrl("http://localhost").clientPort(0).build();
        AtomicBoolean ready = new AtomicBoolean();
        LocalApiServer health = new LocalApiServer(config, ready::get);
        HttpClient client = HttpClient.newHttpClient();

        try {
            health.start();
            int port = health.port();

            assertEquals(200, get(client, port, "/health/live"));
            assertEquals(503, get(client, port, "/health/ready"));
            assertEquals(404, get(client, port, "/health"), "the bare path is removed");

            ready.set(true);

            assertEquals(200, get(client, port, "/health/ready"));
            assertEquals(404, get(client, port, "/health"));
        } finally {
            health.shutdown();
        }
    }

    @Test
    void reportsThePortItBoundOnlyWhileRunning() throws Exception {
        ClientConfiguration config = ClientConfiguration.builder()
                .nodeName("client-port").dataDirectory(Path.of("data"))
                .serverUrl("http://localhost").clientPort(0).build();
        LocalApiServer health = new LocalApiServer(config, () -> true);

        assertThrows(IllegalStateException.class, health::port, "no port is bound before start");
        health.start();
        int port;
        try {
            port = health.port();
            assertTrue(port > 0, "port 0 is replaced by the port the system chose");
            assertEquals(200, get(HttpClient.newHttpClient(), port, "/health/live"));
        } finally {
            health.shutdown();
        }
        assertThrows(IllegalStateException.class, health::port, "no port is bound after shutdown");
    }

    @Test
    void listensOnTheLoopbackAddressByDefaultAndAnnouncesWhereItListens() throws Exception {
        ClientConfiguration config = ClientConfiguration.builder()
                .nodeName("client-bind").dataDirectory(Path.of("data"))
                .serverUrl("http://localhost").clientPort(0).build();
        LocalApiServer health = new LocalApiServer(config, () -> true);
        Logger logger = (Logger) LoggerFactory.getLogger(LocalApiServer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        health.start();
        try {
            InetSocketAddress bound = health.address();
            assertEquals(InetAddress.getLoopbackAddress(), bound.getAddress(),
                    "as Consul's client_addr, the local API is reached only from the host itself");
            assertEquals(health.port(), bound.getPort());
            assertEquals(1, appender.list.stream().filter(event -> event.getLevel() == Level.INFO
                    && event.getFormattedMessage().equals("Local API started: protocol=http, address="
                            + bound.getAddress().getHostAddress() + ":" + bound.getPort())).count(),
                    "the startup log says where the local API listens: " + appender.list);
        } finally {
            health.shutdown();
            logger.detachAppender(appender);
        }
        assertThrows(IllegalStateException.class, health::address, "no address is bound after shutdown");
    }

    @Test
    void answersAsTheServersApiDoesWithTheRequestIdAndTheErrorEnvelope() throws Exception {
        ClientConfiguration config = ClientConfiguration.builder()
                .nodeName("client-envelope").dataDirectory(Path.of("data"))
                .serverUrl("http://localhost").clientPort(0).build();
        LocalApiServer api = new LocalApiServer(config, () -> true);
        api.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> live = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + api.port() + "/health/live")).header("X-Request-Id", "req-1")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("req-1", live.headers().firstValue("X-Request-Id").orElseThrow());

            HttpResponse<String> wrongMethod = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + api.port() + "/health/live"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, wrongMethod.statusCode());
            com.fasterxml.jackson.databind.JsonNode body =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(wrongMethod.body());
            assertEquals("method_not_allowed", body.path("code").asText(), wrongMethod.body());
            assertEquals("method_not_allowed", body.path("error").asText());
            assertFalse(body.path("requestId").asText().isBlank(), wrongMethod.body());
        } finally {
            api.shutdown();
        }
    }

    @Test
    void onceShutdownBeginsWritesAreRefusedAndReadsAreServedUntilTheListenerStops() throws Exception {
        ClientConfiguration config = ClientConfiguration.builder()
                .nodeName("client-shutdown").dataDirectory(Path.of("data"))
                .serverUrl("http://localhost").clientPort(0).build();
        LocalApiServer api = new LocalApiServer(config, () -> true);
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        api.route("/v1/client/test", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) writes.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        api.start();
        HttpClient client = HttpClient.newHttpClient();
        try {
            assertTrue(api.acceptsWrites());
            assertEquals(200, send(client, api, "PUT", "/v1/client/test"));
            assertEquals(1, writes.get());

            api.beginShutdown();

            assertFalse(api.acceptsWrites());
            HttpResponse<String> refused = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + api.port() + "/v1/client/test"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, refused.statusCode(), refused.body());
            com.fasterxml.jackson.databind.JsonNode body =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(refused.body());
            assertEquals("shutting_down", body.path("code").asText());
            assertTrue(body.path("retryable").asBoolean(), "the restarted client takes the write");
            assertEquals(1, writes.get(), "a refused write never reaches its route");

            assertEquals(200, send(client, api, "GET", "/v1/client/test"), "reads are served while shutting down");
            assertEquals(200, send(client, api, "GET", "/health/live"));
        } finally {
            api.shutdown();
        }
    }

    private static int send(HttpClient client, LocalApiServer api, String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path))
                .method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString("{}")).build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void listensOnTheConfiguredAddress() throws Exception {
        ClientConfiguration config = ClientConfiguration.builder()
                .nodeName("client-bind").dataDirectory(Path.of("data"))
                .serverUrl("http://localhost").clientPort(0).bindAddress(InetAddress.ofLiteral("0.0.0.0")).build();
        LocalApiServer health = new LocalApiServer(config, () -> true);
        health.start();
        try {
            assertTrue(health.address().getAddress().isAnyLocalAddress(), health.address().toString());
            assertEquals(200, get(HttpClient.newHttpClient(), health.port(), "/health/live"));
        } finally {
            health.shutdown();
        }
    }

    private static int get(HttpClient client, int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

}

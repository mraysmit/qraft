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

package dev.mars.qraft.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.client.catalog.ServerRetryPolicy;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.client.service.RegistrationClient;
import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.ServiceDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link QraftClient} registration, retry, readiness, service reconciliation, and ordered
 * shutdown against a stub server. Retry delays and periodic heartbeats run on a
 * {@link ManualScheduledExecutorHelper}, so a test decides when they fire and proves exactly that none is
 * scheduled.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
class QraftClientTest {
    private HttpServer server;
    private final ManualScheduledExecutorHelper scheduler = new ManualScheduledExecutorHelper();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aClientRegistersItsNodeWithItsVersionInTheMetadata() throws Exception {
        AtomicReference<String> registration = new AtomicReference<>();
        AtomicReference<String> identity = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(NodeAnswerHelper.REGISTER, exchange -> {
            registration.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            identity.set(exchange.getRequestMethod() + " as " + exchange.getRequestHeaders().getFirst("X-Qraft-Node"));
            NodeAnswerHelper.answer(exchange, 200, "registered", true);
        });
        server.createContext(NodeAnswerHelper.DEREGISTER, NodeAnswerHelper::accept);
        server.start();
        ClientConfiguration config = ClientConfiguration.builder()
                .clientId("client-1").address("127.0.0.1").clientPort(0)
                .datacenter("dc-1").region("eu-west").version("1.2.3")
                .serverUrl("http://localhost:" + server.getAddress().getPort())
                .heartbeatInterval(60_000)
                .build();
        QraftClient client = manuallyTimedClient(config);

        try {
            assertTrue(client.start().get(10, TimeUnit.SECONDS));
            assertTrue(client.healthService().port() > 0, "port zero binds a port the client is reached on");

            assertEquals("PUT as client-1", identity.get(), "the identity header names the node");
            JsonNode body = new ObjectMapper().readTree(registration.get());
            assertEquals(List.of("node"), fieldNames(body));
            JsonNode node = body.path("node");
            assertEquals(List.of("address", "datacenter", "region", "metadata"), fieldNames(node),
                    "a client sends no name, host name, port, status, or time for its node");
            assertEquals("127.0.0.1", node.path("address").asText());
            assertEquals("dc-1", node.path("datacenter").asText());
            assertEquals("eu-west", node.path("region").asText());
            assertEquals(Set.of(Node.VERSION_METADATA_KEY, Node.REGISTRATION_ID_METADATA_KEY),
                    Set.copyOf(fieldNames(node.path("metadata"))));
            assertEquals("1.2.3", node.path("metadata").path(Node.VERSION_METADATA_KEY).asText(),
                    "the client's version travels in the node's metadata");
        } finally {
            client.shutdown().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void startsAndShutsDownAgainstServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(NodeAnswerHelper.REGISTER, exchange -> {
            NodeAnswerHelper.answer(exchange, 200, "registered", true);
        });
        server.createContext(NodeAnswerHelper.DEREGISTER, exchange -> {
            NodeAnswerHelper.answer(exchange, 200, "deregistered", true);
        });
        server.start();

        ClientConfiguration config = ClientConfiguration.builder()
                .clientId("client-1")
                .address("127.0.0.1")
                .clientPort(0)
                .serverUrl("http://localhost:" + server.getAddress().getPort())
                .heartbeatInterval(60_000)
                .build();
        QraftClient client = new QraftClient(config);

        assertTrue(client.start().join());
        assertTrue(client.isRunning());
        assertTrue(client.healthService().isHealthy());
        assertTrue(client.shutdown().join());
        assertFalse(client.isRunning());
        assertFalse(client.healthService().isHealthy());
    }

    @Test
    void remainsLiveButUnreadyWhenInitialRegistrationFails() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(NodeAnswerHelper.REGISTER, exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        ClientConfiguration config = ClientConfiguration.builder()
                .clientId("client-1")
                .address("127.0.0.1")
                .clientPort(0)
                .serverUrl("http://localhost:" + server.getAddress().getPort())
                .build();
        QraftClient client = new QraftClient(config);

        try {
            assertFalse(client.start().join());
            assertTrue(client.isRunning());
            assertTrue(client.healthService().isHealthy());
            assertFalse(client.healthService().isReady());
        } finally {
            client.shutdown().join();
        }
    }

    @Test
    void rejectedNodeRegistrationIsLoggedAndNeverRetried() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(NodeAnswerHelper.REGISTER, exchange -> {
            registrations.incrementAndGet();
            byte[] body = ("{\"code\":\"invalid_registration\",\"message\":\"bad address\","
                    + "\"retryable\":false}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext(NodeAnswerHelper.DEREGISTER, exchange -> {
            NodeAnswerHelper.answer(exchange, 200, "deregistered", false);
        });
        server.start();
        ClientConfiguration config = ClientConfiguration.builder()
                .clientId("client-1").address("127.0.0.1")
                .clientPort(0).serverUrl("http://localhost:" + server.getAddress().getPort())
                .build();
        QraftClient client = manuallyTimedClient(config);
        Logger logger = (Logger) LoggerFactory.getLogger(RegistrationClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertFalse(client.start().get(10, TimeUnit.SECONDS));

            assertEquals(0, scheduler.pendingCount(), "no registration retry may be scheduled");
            assertEquals(1, registrations.get());
            assertEquals(1, appender.list.stream().filter(event ->
                    event.getFormattedMessage().contains("clientId=client-1")
                            && event.getFormattedMessage().contains("invalid_registration")
                            && event.getFormattedMessage().contains("bad address")).count());
        } finally {
            logger.detachAppender(appender);
            assertTrue(client.shutdown().get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void retriesRegistrationAndBecomesReadyWhenServerRecovers() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger heartbeats = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(NodeAnswerHelper.REGISTER, exchange -> {
            NodeAnswerHelper.answer(exchange, registrations.incrementAndGet() == 1 ? 503 : 200, "registered", true);
        });
        server.createContext(NodeAnswerHelper.HEARTBEAT, exchange -> {
            heartbeats.incrementAndGet();
            NodeAnswerHelper.answer(exchange, 200, "accepted", true);
        });
        server.createContext(NodeAnswerHelper.DEREGISTER, exchange -> {
            NodeAnswerHelper.answer(exchange, 200, "deregistered", true);
        });
        server.start();
        ClientConfiguration config = ClientConfiguration.builder()
                .clientId("client-1")
                .address("127.0.0.1")
                .clientPort(0)
                .serverUrl("http://localhost:" + server.getAddress().getPort())
                .heartbeatInterval(60_000)
                .build();
        QraftClient client = manuallyTimedClient(config);

        try {
            assertFalse(client.start().get(10, TimeUnit.SECONDS),
                    "the initial registration should expose the outage");
            assertEquals(1, scheduler.pendingCount(), "exactly one registration retry is scheduled");
            assertEquals(0, heartbeats.get());

            scheduler.advance(RETRY_WINDOW);
            awaitTrue(() -> scheduler.pendingCount() == 2, "reconciliation and heartbeat are scheduled");
            assertEquals(2, registrations.get(), "registration is retried once");
            assertTrue(client.healthService().isReady(), "successful retry must make the client ready");

            scheduler.advance(Duration.ofMillis(config.getHeartbeatInterval()));
            awaitTrue(() -> heartbeats.get() == 1, "heartbeats begin after recovery");
        } finally {
            client.shutdown().join();
        }
    }

    @Test
    void checksOfADisabledServiceAreNeverRun() throws Exception {
        ClientConfiguration config = ClientConfiguration.builder()
                .clientId("client-1").address("127.0.0.1").clientPort(0)
                .serverUrl("http://localhost:1")
                .services(List.of(
                        new ServiceDefinition("web", "web", "127.0.0.1", 8080, List.of(), Map.of(), true),
                        new ServiceDefinition("legacy", "legacy", "127.0.0.1", 8081, List.of(), Map.of(), false)))
                .healthChecks(List.of(
                        new dev.mars.qraft.client.health.TtlCheck("web", "app", Duration.ofSeconds(30), true),
                        new dev.mars.qraft.client.health.TtlCheck("legacy", "app", Duration.ofSeconds(30), true)))
                .build();
        QraftClient client = new QraftClient(config);
        try {
            assertTrue(client.statusReporter("web", "app").isPresent(), "an enabled service's check runs");
            assertTrue(client.statusReporter("legacy", "app").isEmpty(),
                    "a disabled service is not registered, so its checks are never run or reported");
        } finally {
            client.shutdown().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void registrationBackoffGrowsWhileTheServerIsDownAndRestartsAfterSuccess() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger heartbeats = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(NodeAnswerHelper.REGISTER, exchange -> {
            NodeAnswerHelper.answer(exchange, registrations.incrementAndGet() <= 3 ? 503 : 200, "registered", true);
        });
        server.createContext(NodeAnswerHelper.HEARTBEAT, exchange -> {
            NodeAnswerHelper.answer(exchange, heartbeats.incrementAndGet() == 1 ? 404 : 200, "accepted", true);
        });
        server.createContext(NodeAnswerHelper.DEREGISTER, exchange -> {
            NodeAnswerHelper.answer(exchange, 200, "deregistered", true);
        });
        server.start();
        ClientConfiguration config = ClientConfiguration.builder()
                .clientId("client-1").address("127.0.0.1")
                .clientPort(0).serverUrl("http://localhost:" + server.getAddress().getPort())
                .heartbeatInterval(60_000)
                .build();
        QraftClient client = manuallyTimedClient(config);

        try {
            assertFalse(client.start().get(10, TimeUnit.SECONDS));
            // With a zero jitter sample, retry n waits half of min(10 ms * 2^n, 100 ms).
            assertEquals(Duration.ofMillis(5), scheduler.nextDelay());
            advanceToRegistration(2, registrations);
            assertEquals(Duration.ofMillis(10), scheduler.nextDelay(), "the second retry waits longer");
            advanceToRegistration(3, registrations);
            assertEquals(Duration.ofMillis(20), scheduler.nextDelay(), "the third retry waits longer again");

            scheduler.advance(scheduler.nextDelay());
            awaitTrue(() -> registrations.get() == 4 && scheduler.pendingCount() == 2,
                    "the fourth attempt registers and schedules heartbeats");
            assertTrue(client.healthService().isReady());

            scheduler.advance(Duration.ofMillis(config.getHeartbeatInterval()));
            awaitTrue(() -> scheduler.pendingCount() == 3, "the rejected heartbeat schedules a retry");
            assertEquals(Duration.ofMillis(5), scheduler.nextDelay(),
                    "a successful registration restarts the backoff");
        } finally {
            client.shutdown().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void shutdownCancelsAPendingRegistrationRetry() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(NodeAnswerHelper.REGISTER, exchange -> {
            registrations.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        ClientConfiguration config = ClientConfiguration.builder()
                .clientId("client-1").address("127.0.0.1")
                .clientPort(0).serverUrl("http://localhost:" + server.getAddress().getPort())
                .build();
        QraftClient client = manuallyTimedClient(config);

        assertFalse(client.start().get(10, TimeUnit.SECONDS));
        assertEquals(1, scheduler.pendingCount(), "a registration retry is pending");

        client.shutdown().get(10, TimeUnit.SECONDS);

        assertEquals(0, scheduler.pendingCount(), "shutdown leaves no retry scheduled");
        scheduler.advance(Duration.ofHours(1));
        assertEquals(1, registrations.get(), "no registration is attempted after shutdown");
        assertTrue(client.isTerminated());
    }

    private void advanceToRegistration(int attempt, AtomicInteger registrations) throws InterruptedException {
        scheduler.advance(scheduler.nextDelay());
        awaitTrue(() -> registrations.get() == attempt && scheduler.pendingCount() == 1,
                "attempt " + attempt + " fails and schedules the next retry");
    }

    @Test
    void registersAgainWhenServerForgetsClient() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger heartbeats = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(NodeAnswerHelper.REGISTER, exchange -> {
            registrations.incrementAndGet();
            NodeAnswerHelper.answer(exchange, 200, "registered", true);
        });
        server.createContext(NodeAnswerHelper.HEARTBEAT, exchange -> {
            NodeAnswerHelper.answer(exchange, heartbeats.incrementAndGet() == 1 ? 404 : 200, "accepted", true);
        });
        server.createContext(NodeAnswerHelper.DEREGISTER, exchange -> {
            NodeAnswerHelper.answer(exchange, 200, "deregistered", true);
        });
        server.start();
        ClientConfiguration config = ClientConfiguration.builder()
                .clientId("client-1")
                .address("127.0.0.1")
                .clientPort(0)
                .serverUrl("http://localhost:" + server.getAddress().getPort())
                .heartbeatInterval(60_000)
                .build();
        QraftClient client = manuallyTimedClient(config);
        Duration heartbeatInterval = Duration.ofMillis(config.getHeartbeatInterval());

        try {
            assertTrue(client.start().get(10, TimeUnit.SECONDS));
            assertEquals(2, scheduler.pendingCount(), "reconciliation and heartbeat are scheduled");

            scheduler.advance(heartbeatInterval);
            awaitTrue(() -> scheduler.pendingCount() == 3,
                    "the rejected heartbeat schedules a registration retry");
            assertEquals(1, heartbeats.get());
            assertFalse(client.healthService().isReady(), "a forgotten client is not ready");

            scheduler.advance(RETRY_WINDOW);
            awaitTrue(() -> registrations.get() == 2 && client.healthService().isReady(),
                    "the retry registers the client again");
            scheduler.advance(heartbeatInterval);
            awaitTrue(() -> heartbeats.get() == 2, "heartbeats resume after registration");
            assertEquals(2, registrations.get());
        } finally {
            client.shutdown().join();
        }
    }

    @Test
    void startsServiceReconciliationAfterNodeRegistration() throws Exception {
        AtomicInteger serviceRegistrations = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(NodeAnswerHelper.REGISTER, exchange -> {
            NodeAnswerHelper.answer(exchange, 200, "registered", true);
        });
        server.createContext("/v1/client/service/register", exchange -> {
            serviceRegistrations.incrementAndGet();
            byte[] body = "{\"serviceId\":\"web\",\"registered\":true}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext(NodeAnswerHelper.DEREGISTER, exchange -> {
            NodeAnswerHelper.answer(exchange, 200, "deregistered", true);
        });
        server.start();
        ClientConfiguration config = ClientConfiguration.builder()
                .clientId("client-1")
                .address("127.0.0.1")
                .clientPort(0)
                .serverUrl("http://localhost:" + server.getAddress().getPort())
                .heartbeatInterval(60_000)
                .services(List.of(new ServiceDefinition("web", "web", "127.0.0.1", 8080,
                        List.of(), Map.of(), true)))
                .build();
        QraftClient client = new QraftClient(config);

        try {
            assertTrue(client.start().get(10, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (client.serviceReconciler().registeredCount() == 0
                    && System.nanoTime() < deadline) Thread.onSpinWait();

            assertEquals(1, serviceRegistrations.get());
            assertEquals(1, client.serviceReconciler().registeredCount());
            assertTrue(client.healthService().isReady());
        } finally {
            client.shutdown().join();
        }
    }

    @Test
    void remainsLiveButUnreadyWhenAnEnabledServiceIsRejected() throws Exception {
        AtomicInteger serviceRegistrations = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(NodeAnswerHelper.REGISTER, exchange -> {
            NodeAnswerHelper.answer(exchange, 200, "registered", true);
        });
        server.createContext("/v1/client/service/register", exchange -> {
            serviceRegistrations.incrementAndGet();
            byte[] body = ("{\"code\":\"invalid_registration\",\"message\":\"bad service\","
                    + "\"retryable\":false}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext(NodeAnswerHelper.DEREGISTER, exchange -> {
            NodeAnswerHelper.answer(exchange, 200, "deregistered", true);
        });
        server.start();
        ClientConfiguration config = ClientConfiguration.builder()
                .clientId("client-1").address("127.0.0.1")
                .clientPort(0).serverUrl("http://localhost:" + server.getAddress().getPort())
                .heartbeatInterval(60_000)
                .services(List.of(new ServiceDefinition("web", "web", "127.0.0.1", 8080,
                        List.of(), Map.of(), true)))
                .build();
        QraftClient client = new QraftClient(config);

        try {
            assertTrue(client.start().get(10, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while ((serviceRegistrations.get() == 0 || client.serviceReconciler().isReconciling())
                    && System.nanoTime() < deadline) Thread.onSpinWait();

            assertEquals(1, serviceRegistrations.get());
            assertTrue(client.healthService().isHealthy());
            assertFalse(client.healthService().isReady());
        } finally {
            client.shutdown().join();
        }
    }

    @Test
    void remainsLiveWhenServerContactBecomesStale() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(NodeAnswerHelper.REGISTER, exchange -> {
            NodeAnswerHelper.answer(exchange, 200, "registered", true);
        });
        server.createContext(NodeAnswerHelper.DEREGISTER, exchange -> {
            NodeAnswerHelper.answer(exchange, 200, "deregistered", true);
        });
        server.start();
        ClientConfiguration config = ClientConfiguration.builder()
                .clientId("client-1").address("127.0.0.1")
                .clientPort(0).serverUrl("http://localhost:" + server.getAddress().getPort())
                .heartbeatInterval(60_000).contactFreshnessMs(1_000)
                .build();
        MutableClockHelper clock = new MutableClockHelper(Instant.parse("2026-09-24T10:00:00Z"));
        QraftClient client = new QraftClient(config,
                new ServerRetryPolicy(10, 100, () -> 0.0), clock);

        try {
            assertTrue(client.start().get(10, TimeUnit.SECONDS));
            assertTrue(client.healthService().isReady());

            clock.advance(Duration.ofMillis(1_001));

            assertTrue(client.healthService().isHealthy());
            assertFalse(client.healthService().isReady());
        } finally {
            client.shutdown().join();
        }
    }

    @Test
    void shutdownWithdrawsReadinessThenDeregistersServicesBeforeNodeAndStopsHealth() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch servicesStarted = new CountDownLatch(1);
        CountDownLatch releaseServices = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(NodeAnswerHelper.REGISTER, exchange -> {
            NodeAnswerHelper.answer(exchange, 200, "registered", true);
        });
        server.createContext("/v1/client/service/register", exchange -> {
            String id = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    .contains("\"web\"") ? "web" : "api";
            byte[] body = ("{\"serviceId\":\"" + id + "\",\"registered\":true}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/v1/client/service/deregister", exchange -> {
            String serviceId = exchange.getRequestURI().getPath()
                    .substring(exchange.getRequestURI().getPath().lastIndexOf('/') + 1);
            events.add("service:" + serviceId);
            servicesStarted.countDown();
            try {
                releaseServices.await(30, TimeUnit.SECONDS);
                byte[] body = ("{\"serviceId\":\"" + serviceId + "\",\"deregistered\":true}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.createContext(NodeAnswerHelper.DEREGISTER, exchange -> {
            events.add("node");
            NodeAnswerHelper.answer(exchange, 200, "deregistered", true);
        });
        server.start();
        // The test releases deregistration itself, so the deadline must never be what ends shutdown.
        ClientConfiguration config = clientConfigWithServices(60_000);
        QraftClient client = new QraftClient(config);

        assertTrue(client.start().get(10, TimeUnit.SECONDS));
        awaitRegisteredServices(client, 2);
        CompletableFuture<Boolean> shutdown = client.shutdown();
        CompletableFuture<Boolean> repeated = client.shutdown();

        assertSame(shutdown, repeated);
        assertFalse(client.healthService().isReady(), "readiness must be withdrawn synchronously");
        assertTrue(client.healthService().isHealthy(), "local health must remain live during deregistration");
        assertTrue(servicesStarted.await(10, TimeUnit.SECONDS));
        assertFalse(events.contains("node"), "node deregistration must wait for every service");
        releaseServices.countDown();

        assertTrue(shutdown.get(10, TimeUnit.SECONDS));
        assertEquals(Set.of("service:web", "service:api"), Set.copyOf(events.subList(0, 2)));
        assertEquals("node", events.get(2));
        assertFalse(client.healthService().isHealthy());
        assertTrue(client.isTerminated());
    }

    @Test
    void unreachableServerCannotExtendShutdownPastDeadlineAndLogsOnce() throws Exception {
        CountDownLatch releaseDeregistration = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(NodeAnswerHelper.REGISTER, exchange -> {
            NodeAnswerHelper.answer(exchange, 200, "registered", true);
        });
        server.createContext("/v1/client/service/register", exchange -> {
            byte[] body = "{\"serviceId\":\"web\",\"registered\":true}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/v1/client/service/deregister", exchange -> {
            try {
                // Held until the test has asserted, so a shutdown that completes cannot have waited for it.
                releaseDeregistration.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        ClientConfiguration config = clientConfigWithServices(100);
        QraftClient client = new QraftClient(config);
        Logger logger = (Logger) LoggerFactory.getLogger(QraftClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertTrue(client.start().get(10, TimeUnit.SECONDS));
            awaitRegisteredServices(client, 2);

            assertFalse(client.shutdown().get(10, TimeUnit.SECONDS),
                    "shutdown completes at its deadline while every deregistration is still held open");
            assertSame(client.shutdown(), client.shutdown());
            assertEquals(1, appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("shutdown incomplete"))
                    .count());
            assertFalse(client.healthService().isHealthy());
            assertTrue(client.isTerminated());
        } finally {
            releaseDeregistration.countDown();
            logger.detachAppender(appender);
            client.shutdown().join();
        }
    }

    private ClientConfiguration clientConfigWithServices(long shutdownTimeoutMs) throws Exception {
        return ClientConfiguration.builder()
                .clientId("client-1").address("127.0.0.1")
                .clientPort(0).serverUrl("http://localhost:" + server.getAddress().getPort())
                .heartbeatInterval(60_000).requestTimeoutMs(10_000)
                .shutdownTimeoutMs(shutdownTimeoutMs)
                .services(List.of(
                        new ServiceDefinition("web", "web", "127.0.0.1", 8080, List.of(), Map.of(), true),
                        new ServiceDefinition("api", "api", "127.0.0.1", 8081, List.of(), Map.of(), true)))
                .build();
    }

    private static void awaitRegisteredServices(QraftClient client, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (client.serviceReconciler().registeredCount() < count && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(count, client.serviceReconciler().registeredCount());
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new java.util.ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** A retry window longer than any delay the retry policy of {@link #manuallyTimedClient} produces. */
    private static final Duration RETRY_WINDOW = Duration.ofMillis(100);

    private QraftClient manuallyTimedClient(ClientConfiguration config) {
        return new QraftClient(config, new ServerRetryPolicy(10, 100, () -> 0.0), Clock.systemUTC(),
                scheduler, Executors.newSingleThreadScheduledExecutor());
    }

    /** Bounds a wait for an HTTP exchange the manual scheduler started; the bound only diagnoses a hang. */
    private static void awaitTrue(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), description);
    }

    /** Test clock helper that lets the enclosing tests advance time explicitly. */
    private static final class MutableClockHelper extends Clock {
        private Instant instant;

        private MutableClockHelper(Instant instant) { this.instant = instant; }
        private void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}

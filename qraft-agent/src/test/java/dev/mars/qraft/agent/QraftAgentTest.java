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

package dev.mars.qraft.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.agent.catalog.ControllerRetryPolicy;
import dev.mars.qraft.agent.config.AgentConfiguration;
import dev.mars.qraft.agent.service.AgentRegistrationClient;
import dev.mars.qraft.catalog.ServiceDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link QraftAgent} registration, retry, readiness, service reconciliation, and ordered
 * shutdown against a stub controller. Retry delays and periodic heartbeats run on a
 * {@link ManualScheduledExecutor}, so a test decides when they fire and proves exactly that none is
 * scheduled.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
class QraftAgentTest {
    private HttpServer server;
    private final ManualScheduledExecutor scheduler = new ManualScheduledExecutor();

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
                .controllerUrl("http://localhost:" + server.getAddress().getPort())
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
    void rejectedNodeRegistrationIsLoggedAndNeverRetried() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/agents/register", exchange -> {
            registrations.incrementAndGet();
            byte[] body = ("{\"code\":\"invalid_agent\",\"message\":\"bad address\","
                    + "\"retryable\":false}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/api/v1/agents/agent-1", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-1").hostname("host").address("127.0.0.1")
                .agentPort(freePort()).controllerUrl("http://localhost:" + server.getAddress().getPort())
                .build();
        QraftAgent agent = manuallyTimedAgent(config);
        Logger logger = (Logger) LoggerFactory.getLogger(AgentRegistrationClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertFalse(agent.start().get(10, TimeUnit.SECONDS));

            assertEquals(0, scheduler.pendingCount(), "no registration retry may be scheduled");
            assertEquals(1, registrations.get());
            assertEquals(1, appender.list.stream().filter(event ->
                    event.getFormattedMessage().contains("agentId=agent-1")
                            && event.getFormattedMessage().contains("invalid_agent")
                            && event.getFormattedMessage().contains("bad address")).count());
        } finally {
            logger.detachAppender(appender);
            assertTrue(agent.shutdown().get(10, TimeUnit.SECONDS));
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
                .controllerUrl("http://localhost:" + server.getAddress().getPort())
                .heartbeatInterval(60_000)
                .build();
        QraftAgent agent = manuallyTimedAgent(config);

        try {
            assertFalse(agent.start().get(10, TimeUnit.SECONDS),
                    "the initial registration should expose the outage");
            assertEquals(1, scheduler.pendingCount(), "exactly one registration retry is scheduled");
            assertEquals(0, heartbeats.get());

            scheduler.advance(RETRY_WINDOW);
            awaitTrue(() -> scheduler.pendingCount() == 2, "reconciliation and heartbeat are scheduled");
            assertEquals(2, registrations.get(), "registration is retried once");
            assertTrue(agent.healthService().isReady(), "successful retry must make the agent ready");

            scheduler.advance(Duration.ofMillis(config.getHeartbeatInterval()));
            awaitTrue(() -> heartbeats.get() == 1, "heartbeats begin after recovery");
        } finally {
            agent.shutdown().join();
        }
    }

    @Test
    void registrationBackoffGrowsWhileTheControllerIsDownAndRestartsAfterSuccess() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger heartbeats = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/agents/register", exchange -> {
            int status = registrations.incrementAndGet() <= 3 ? 503 : 201;
            exchange.sendResponseHeaders(status, -1);
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
                .agentId("agent-1").hostname("host").address("127.0.0.1")
                .agentPort(freePort()).controllerUrl("http://localhost:" + server.getAddress().getPort())
                .heartbeatInterval(60_000)
                .build();
        QraftAgent agent = manuallyTimedAgent(config);

        try {
            assertFalse(agent.start().get(10, TimeUnit.SECONDS));
            // With a zero jitter sample, retry n waits half of min(10 ms * 2^n, 100 ms).
            assertEquals(Duration.ofMillis(5), scheduler.nextDelay());
            advanceToRegistration(2, registrations);
            assertEquals(Duration.ofMillis(10), scheduler.nextDelay(), "the second retry waits longer");
            advanceToRegistration(3, registrations);
            assertEquals(Duration.ofMillis(20), scheduler.nextDelay(), "the third retry waits longer again");

            scheduler.advance(scheduler.nextDelay());
            awaitTrue(() -> registrations.get() == 4 && scheduler.pendingCount() == 2,
                    "the fourth attempt registers and schedules heartbeats");
            assertTrue(agent.healthService().isReady());

            scheduler.advance(Duration.ofMillis(config.getHeartbeatInterval()));
            awaitTrue(() -> scheduler.pendingCount() == 3, "the rejected heartbeat schedules a retry");
            assertEquals(Duration.ofMillis(5), scheduler.nextDelay(),
                    "a successful registration restarts the backoff");
        } finally {
            agent.shutdown().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void shutdownCancelsAPendingRegistrationRetry() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/agents/register", exchange -> {
            registrations.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-1").hostname("host").address("127.0.0.1")
                .agentPort(freePort()).controllerUrl("http://localhost:" + server.getAddress().getPort())
                .build();
        QraftAgent agent = manuallyTimedAgent(config);

        assertFalse(agent.start().get(10, TimeUnit.SECONDS));
        assertEquals(1, scheduler.pendingCount(), "a registration retry is pending");

        agent.shutdown().get(10, TimeUnit.SECONDS);

        assertEquals(0, scheduler.pendingCount(), "shutdown leaves no retry scheduled");
        scheduler.advance(Duration.ofHours(1));
        assertEquals(1, registrations.get(), "no registration is attempted after shutdown");
        assertTrue(agent.isTerminated());
    }

    private void advanceToRegistration(int attempt, AtomicInteger registrations) throws InterruptedException {
        scheduler.advance(scheduler.nextDelay());
        awaitTrue(() -> registrations.get() == attempt && scheduler.pendingCount() == 1,
                "attempt " + attempt + " fails and schedules the next retry");
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
                .controllerUrl("http://localhost:" + server.getAddress().getPort())
                .heartbeatInterval(60_000)
                .build();
        QraftAgent agent = manuallyTimedAgent(config);
        Duration heartbeatInterval = Duration.ofMillis(config.getHeartbeatInterval());

        try {
            assertTrue(agent.start().get(10, TimeUnit.SECONDS));
            assertEquals(2, scheduler.pendingCount(), "reconciliation and heartbeat are scheduled");

            scheduler.advance(heartbeatInterval);
            awaitTrue(() -> scheduler.pendingCount() == 3,
                    "the rejected heartbeat schedules a registration retry");
            assertEquals(1, heartbeats.get());
            assertFalse(agent.healthService().isReady(), "a forgotten agent is not ready");

            scheduler.advance(RETRY_WINDOW);
            awaitTrue(() -> registrations.get() == 2 && agent.healthService().isReady(),
                    "the retry registers the agent again");
            scheduler.advance(heartbeatInterval);
            awaitTrue(() -> heartbeats.get() == 2, "heartbeats resume after registration");
            assertEquals(2, registrations.get());
        } finally {
            agent.shutdown().join();
        }
    }

    @Test
    void startsServiceReconciliationAfterNodeRegistration() throws Exception {
        AtomicInteger serviceRegistrations = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/agents/register", exchange -> {
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.createContext("/v1/agent/service/register", exchange -> {
            serviceRegistrations.incrementAndGet();
            byte[] body = "{\"serviceId\":\"web\",\"registered\":true}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
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
                .controllerUrl("http://localhost:" + server.getAddress().getPort())
                .heartbeatInterval(60_000)
                .services(List.of(new ServiceDefinition("web", "web", "127.0.0.1", 8080,
                        List.of(), Map.of(), true)))
                .build();
        QraftAgent agent = new QraftAgent(config);

        try {
            assertTrue(agent.start().get(10, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (agent.serviceReconciler().registeredCount() == 0
                    && System.nanoTime() < deadline) Thread.onSpinWait();

            assertEquals(1, serviceRegistrations.get());
            assertEquals(1, agent.serviceReconciler().registeredCount());
            assertTrue(agent.healthService().isReady());
        } finally {
            agent.shutdown().join();
        }
    }

    @Test
    void remainsLiveButUnreadyWhenAnEnabledServiceIsRejected() throws Exception {
        AtomicInteger serviceRegistrations = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/agents/register", exchange -> {
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.createContext("/v1/agent/service/register", exchange -> {
            serviceRegistrations.incrementAndGet();
            byte[] body = ("{\"code\":\"invalid_registration\",\"message\":\"bad service\","
                    + "\"retryable\":false}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/api/v1/agents/agent-1", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-1").hostname("host").address("127.0.0.1")
                .agentPort(freePort()).controllerUrl("http://localhost:" + server.getAddress().getPort())
                .heartbeatInterval(60_000)
                .services(List.of(new ServiceDefinition("web", "web", "127.0.0.1", 8080,
                        List.of(), Map.of(), true)))
                .build();
        QraftAgent agent = new QraftAgent(config);

        try {
            assertTrue(agent.start().get(10, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while ((serviceRegistrations.get() == 0 || agent.serviceReconciler().isReconciling())
                    && System.nanoTime() < deadline) Thread.onSpinWait();

            assertEquals(1, serviceRegistrations.get());
            assertTrue(agent.healthService().isHealthy());
            assertFalse(agent.healthService().isReady());
        } finally {
            agent.shutdown().join();
        }
    }

    @Test
    void remainsLiveWhenControllerContactBecomesStale() throws Exception {
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
                .agentId("agent-1").hostname("host").address("127.0.0.1")
                .agentPort(freePort()).controllerUrl("http://localhost:" + server.getAddress().getPort())
                .heartbeatInterval(60_000).contactFreshnessMs(1_000)
                .build();
        MutableClock clock = new MutableClock(Instant.parse("2026-09-24T10:00:00Z"));
        QraftAgent agent = new QraftAgent(config,
                new ControllerRetryPolicy(10, 100, () -> 0.0), clock);

        try {
            assertTrue(agent.start().get(10, TimeUnit.SECONDS));
            assertTrue(agent.healthService().isReady());

            clock.advance(Duration.ofMillis(1_001));

            assertTrue(agent.healthService().isHealthy());
            assertFalse(agent.healthService().isReady());
        } finally {
            agent.shutdown().join();
        }
    }

    @Test
    void shutdownWithdrawsReadinessThenDeregistersServicesBeforeNodeAndStopsHealth() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch servicesStarted = new CountDownLatch(1);
        CountDownLatch releaseServices = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/agents/register", exchange -> {
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.createContext("/v1/agent/service/register", exchange -> {
            String id = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    .contains("\"web\"") ? "web" : "api";
            byte[] body = ("{\"serviceId\":\"" + id + "\",\"registered\":true}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/v1/agent/service/deregister", exchange -> {
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
        server.createContext("/api/v1/agents/agent-1", exchange -> {
            events.add("node");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        // The test releases deregistration itself, so the deadline must never be what ends shutdown.
        AgentConfiguration config = agentConfigWithServices(60_000);
        QraftAgent agent = new QraftAgent(config);

        assertTrue(agent.start().get(10, TimeUnit.SECONDS));
        awaitRegisteredServices(agent, 2);
        CompletableFuture<Boolean> shutdown = agent.shutdown();
        CompletableFuture<Boolean> repeated = agent.shutdown();

        assertSame(shutdown, repeated);
        assertFalse(agent.healthService().isReady(), "readiness must be withdrawn synchronously");
        assertTrue(agent.healthService().isHealthy(), "local health must remain live during deregistration");
        assertTrue(servicesStarted.await(10, TimeUnit.SECONDS));
        assertFalse(events.contains("node"), "node deregistration must wait for every service");
        releaseServices.countDown();

        assertTrue(shutdown.get(10, TimeUnit.SECONDS));
        assertEquals(Set.of("service:web", "service:api"), Set.copyOf(events.subList(0, 2)));
        assertEquals("node", events.get(2));
        assertFalse(agent.healthService().isHealthy());
        assertTrue(agent.isTerminated());
    }

    @Test
    void unreachableControllerCannotExtendShutdownPastDeadlineAndLogsOnce() throws Exception {
        CountDownLatch releaseDeregistration = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/agents/register", exchange -> {
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.createContext("/v1/agent/service/register", exchange -> {
            byte[] body = "{\"serviceId\":\"web\",\"registered\":true}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/v1/agent/service/deregister", exchange -> {
            try {
                // Held until the test has asserted, so a shutdown that completes cannot have waited for it.
                releaseDeregistration.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        AgentConfiguration config = agentConfigWithServices(100);
        QraftAgent agent = new QraftAgent(config);
        Logger logger = (Logger) LoggerFactory.getLogger(QraftAgent.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertTrue(agent.start().get(10, TimeUnit.SECONDS));
            awaitRegisteredServices(agent, 2);

            assertFalse(agent.shutdown().get(10, TimeUnit.SECONDS),
                    "shutdown completes at its deadline while every deregistration is still held open");
            assertSame(agent.shutdown(), agent.shutdown());
            assertEquals(1, appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("shutdown incomplete"))
                    .count());
            assertFalse(agent.healthService().isHealthy());
            assertTrue(agent.isTerminated());
        } finally {
            releaseDeregistration.countDown();
            logger.detachAppender(appender);
            agent.shutdown().join();
        }
    }

    private AgentConfiguration agentConfigWithServices(long shutdownTimeoutMs) throws Exception {
        return AgentConfiguration.builder()
                .agentId("agent-1").hostname("host").address("127.0.0.1")
                .agentPort(freePort()).controllerUrl("http://localhost:" + server.getAddress().getPort())
                .heartbeatInterval(60_000).requestTimeoutMs(10_000)
                .shutdownTimeoutMs(shutdownTimeoutMs)
                .services(List.of(
                        new ServiceDefinition("web", "web", "127.0.0.1", 8080, List.of(), Map.of(), true),
                        new ServiceDefinition("api", "api", "127.0.0.1", 8081, List.of(), Map.of(), true)))
                .build();
    }

    private static void awaitRegisteredServices(QraftAgent agent, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (agent.serviceReconciler().registeredCount() < count && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(count, agent.serviceReconciler().registeredCount());
    }

    /** A retry window longer than any delay the retry policy of {@link #manuallyTimedAgent} produces. */
    private static final Duration RETRY_WINDOW = Duration.ofMillis(100);

    private QraftAgent manuallyTimedAgent(AgentConfiguration config) {
        return new QraftAgent(config, new ControllerRetryPolicy(10, 100, () -> 0.0), Clock.systemUTC(),
                scheduler, Executors.newSingleThreadScheduledExecutor());
    }

    /** Bounds a wait for an HTTP exchange the manual scheduler started; the bound only diagnoses a hang. */
    private static void awaitTrue(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), description);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }
        private void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}

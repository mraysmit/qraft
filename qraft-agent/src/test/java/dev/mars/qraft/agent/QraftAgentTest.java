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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link QraftAgent} registration, retry, readiness, service reconciliation, and ordered
 * shutdown against a stub controller.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
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
        CountDownLatch repeatedRegistration = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/agents/register", exchange -> {
            if (registrations.incrementAndGet() > 1) repeatedRegistration.countDown();
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
                .registrationRetryMinMs(10).registrationRetryMaxMs(20).build();
        QraftAgent agent = new QraftAgent(config);
        Logger logger = (Logger) LoggerFactory.getLogger(AgentRegistrationClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertFalse(agent.start().get(1, TimeUnit.SECONDS));

            assertFalse(repeatedRegistration.await(100, TimeUnit.MILLISECONDS));
            assertEquals(1, registrations.get());
            assertEquals(1, appender.list.stream().filter(event ->
                    event.getFormattedMessage().contains("agentId=agent-1")
                            && event.getFormattedMessage().contains("invalid_agent")
                            && event.getFormattedMessage().contains("bad address")).count());
        } finally {
            logger.detachAppender(appender);
            assertTrue(agent.shutdown().get(1, TimeUnit.SECONDS));
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
                .controllerUrl("http://localhost:" + server.getAddress().getPort())
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
            assertTrue(agent.start().get(1, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
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
            assertTrue(agent.start().get(1, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
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
            assertTrue(agent.start().get(1, TimeUnit.SECONDS));
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
                releaseServices.await(2, TimeUnit.SECONDS);
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
        AgentConfiguration config = agentConfigWithServices(2_000);
        QraftAgent agent = new QraftAgent(config);

        assertTrue(agent.start().get(1, TimeUnit.SECONDS));
        awaitRegisteredServices(agent, 2);
        CompletableFuture<Boolean> shutdown = agent.shutdown();
        CompletableFuture<Boolean> repeated = agent.shutdown();

        assertSame(shutdown, repeated);
        assertFalse(agent.healthService().isReady(), "readiness must be withdrawn synchronously");
        assertTrue(agent.healthService().isHealthy(), "local health must remain live during deregistration");
        assertTrue(servicesStarted.await(1, TimeUnit.SECONDS));
        assertFalse(events.contains("node"), "node deregistration must wait for every service");
        releaseServices.countDown();

        assertTrue(shutdown.get(1, TimeUnit.SECONDS));
        assertEquals(Set.of("service:web", "service:api"), Set.copyOf(events.subList(0, 2)));
        assertEquals("node", events.get(2));
        assertFalse(agent.healthService().isHealthy());
        assertTrue(agent.resourcesTerminated());
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
                releaseDeregistration.await(2, TimeUnit.SECONDS);
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
            assertTrue(agent.start().get(1, TimeUnit.SECONDS));
            awaitRegisteredServices(agent, 2);
            long started = System.nanoTime();

            assertFalse(agent.shutdown().get(1, TimeUnit.SECONDS));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0);
            assertSame(agent.shutdown(), agent.shutdown());
            assertEquals(1, appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("shutdown incomplete"))
                    .count());
            assertFalse(agent.healthService().isHealthy());
            assertTrue(agent.resourcesTerminated());
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (agent.serviceReconciler().registeredCount() < count && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(count, agent.serviceReconciler().registeredCount());
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

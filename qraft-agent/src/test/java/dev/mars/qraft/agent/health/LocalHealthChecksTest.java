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

package dev.mars.qraft.agent.health;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LocalHealthChecks}: independent execution, the TTL status input boundary,
 * and terminal stop.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
class LocalHealthChecksTest {
    private static final Instant START = Instant.parse("2026-09-26T12:00:00Z");

    private final ManualTime time = new ManualTime(START);
    private final RecordingListener results = new RecordingListener();
    private final HttpClient client = HttpClient.newHttpClient();
    private final CountDownLatch releaseHttp = new CountDownLatch(1);
    private final CountDownLatch httpEntered = new CountDownLatch(1);
    private final AtomicInteger httpRequests = new AtomicInteger();
    private HttpServer server;
    private LocalHealthChecks checks;

    @AfterEach
    void cleanUp() {
        if (checks != null) checks.stop();
        releaseHttp.countDown();
        if (server != null) server.stop(0);
        client.close();
    }

    @Test
    void aHungCheckDoesNotBlockChecksForUnrelatedServices() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            httpRequests.incrementAndGet();
            httpEntered.countDown();
            try {
                releaseHttp.await();
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/health");
        HttpCheck hung = new HttpCheck("web", "http", url, Duration.ofMinutes(1), Duration.ofMinutes(1),
                Duration.ofMinutes(3), true);
        TcpCheck tcp = new TcpCheck("db", "tcp", "127.0.0.1", 5432, Duration.ofSeconds(5),
                Duration.ofSeconds(1), Duration.ofSeconds(15), true);
        checks = new LocalHealthChecks(List.of(hung, tcp), client,
                (host, port, timeout) -> CompletableFuture.completedFuture(null), time, time, results);

        checks.start();
        time.runDue();
        assertTrue(httpEntered.await(5, TimeUnit.SECONDS));
        RecordingListener.Delivery first = results.nextDelivery();
        assertEquals(tcp, first.check());
        for (int interval = 1; interval <= 3; interval++) {
            time.advance(Duration.ofSeconds(5));
            RecordingListener.Delivery next = results.nextDelivery();
            assertEquals(tcp, next.check());
            assertEquals(START.plusSeconds(5L * interval), next.result().observedAt());
        }
        assertEquals(1, httpRequests.get());
    }

    @Test
    void processLocalStatusIsAnInputOnlyForTtlChecks() throws Exception {
        TtlCheck app = new TtlCheck("web", "app", Duration.ofSeconds(30), false);
        TcpCheck tcp = new TcpCheck("web", "tcp", "127.0.0.1", 8080, Duration.ofSeconds(5),
                Duration.ofSeconds(1), Duration.ofSeconds(15), true);
        checks = new LocalHealthChecks(List.of(app, tcp), client,
                (host, port, timeout) -> new CompletableFuture<>(), time, time, results);

        assertTrue(checks.reporter("web", "app").isPresent());
        assertTrue(checks.reporter("web", "tcp").isEmpty());
        assertTrue(checks.reporter("other", "app").isEmpty());
        LocalStatusReporter reporter = checks.reporter("web", "app").orElseThrow();
        assertFalse(reporter.report(CheckStatus.PASSING, "before start"));

        checks.start();
        assertTrue(reporter.report(CheckStatus.WARNING, "degraded"));
        RecordingListener.Delivery delivery = results.nextDelivery();
        assertEquals(app, delivery.check());
        assertEquals(CheckStatus.WARNING, delivery.result().status());
        assertEquals("degraded", delivery.result().output());
    }

    @Test
    void stopIsTerminalForEveryCheck() throws Exception {
        TtlCheck app = new TtlCheck("web", "app", Duration.ofSeconds(30), true);
        TcpCheck tcp = new TcpCheck("db", "tcp", "127.0.0.1", 5432, Duration.ofSeconds(5),
                Duration.ofSeconds(1), Duration.ofSeconds(15), true);
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        checks = new LocalHealthChecks(List.of(app, tcp), client,
                (host, port, timeout) -> attempt, time, time, results);
        checks.start();
        checks.start();
        time.runDue();
        assertEquals(2, time.pendingTasks(), "one TTL expiry and one TCP timeout");

        checks.stop();
        checks.stop();

        assertEquals(0, time.pendingTasks());
        assertTrue(attempt.isCancelled());
        assertFalse(checks.reporter("web", "app").orElseThrow().report(CheckStatus.PASSING, "late"));
        checks.start();
        time.advance(Duration.ofMinutes(5));
        results.assertNoResult();
    }
}

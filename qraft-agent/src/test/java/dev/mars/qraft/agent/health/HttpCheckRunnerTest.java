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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-HTTP tests for {@link ProbeCheckRunner} running an {@link HttpCheck}, with all timing
 * driven by {@link ManualTime}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
class HttpCheckRunnerTest {
    private static final Instant START = Instant.parse("2026-09-26T12:00:00Z");
    private static final Duration INTERVAL = Duration.ofSeconds(10);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final ManualTime time = new ManualTime(START);
    private final RecordingListener results = new RecordingListener();
    private final HttpClient client = HttpClient.newHttpClient();
    private final ConcurrentLinkedQueue<Integer> statuses = new ConcurrentLinkedQueue<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final CountDownLatch requestEntered = new CountDownLatch(1);
    private final CountDownLatch releaseResponse = new CountDownLatch(1);
    private final CountDownLatch responseFinished = new CountDownLatch(1);
    private volatile boolean blockResponses;
    private HttpServer server;
    private HealthCheckRunner runner;

    @AfterEach
    void cleanUp() {
        if (runner != null) runner.stop();
        releaseResponse.countDown();
        if (server != null) server.stop(0);
        client.close();
    }

    @Test
    void successfulThrottledAndFailedResponsesMapToPassingWarningAndCritical() throws Exception {
        statuses.addAll(List.of(200, 429, 503, 204));
        runner = startRunner();

        time.runDue();
        assertResult(results.next(), CheckStatus.PASSING, "HTTP 200", START);
        time.advance(INTERVAL);
        assertResult(results.next(), CheckStatus.WARNING, "HTTP 429", START.plus(INTERVAL));
        time.advance(INTERVAL);
        assertResult(results.next(), CheckStatus.CRITICAL, "HTTP 503", START.plus(INTERVAL.multipliedBy(2)));
        time.advance(INTERVAL);
        assertResult(results.next(), CheckStatus.PASSING, "HTTP 204", START.plus(INTERVAL.multipliedBy(3)));
        assertEquals(4, requests.get());
    }

    @Test
    void connectionFailureIsCriticalAndAHealthyResponseRecovers() throws Exception {
        int closedPort;
        try (ServerSocket released = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = released.getLocalPort();
        }
        HttpCheck refusedCheck = check(URI.create("http://127.0.0.1:" + closedPort + "/health"));
        runner = new ProbeCheckRunner(refusedCheck, new HttpProbe(refusedCheck, client), time, time, results);
        runner.start();
        time.runDue();
        CheckResult refused = results.next();
        assertEquals(CheckStatus.CRITICAL, refused.status());
        assertTrue(refused.output().startsWith("HTTP GET failed: "), refused.output());
        runner.stop();

        statuses.addAll(List.of(500, 200));
        runner = startRunner();
        time.runDue();
        assertEquals(CheckStatus.CRITICAL, results.next().status());
        time.advance(INTERVAL);
        assertResult(results.next(), CheckStatus.PASSING, "HTTP 200", START.plus(INTERVAL));
    }

    @Test
    void timeoutCancelsTheRequestAndTheCheckNeverOverlapsItself() throws Exception {
        blockResponses = true;
        statuses.add(200);
        runner = startRunner();

        time.runDue();
        assertTrue(requestEntered.await(5, TimeUnit.SECONDS));
        assertEquals(1, time.pendingTasks(), "only the timeout is pending while the probe runs");
        time.advance(TIMEOUT.minusMillis(1));
        results.assertNoResult();
        assertEquals(1, requests.get());
        assertEquals(1, time.pendingTasks());

        time.advance(Duration.ofMillis(1));
        assertResult(results.next(), CheckStatus.CRITICAL, "Check timed out after 2000 ms", START.plus(TIMEOUT));
        assertEquals(1, time.pendingTasks(), "only the next interval is pending after the timeout");
        assertEquals(1, requests.get());
    }

    @Test
    void stoppingCancelsScheduledAndInFlightWorkWithoutDeliveringLateResults() throws Exception {
        blockResponses = true;
        statuses.add(200);
        runner = startRunner();
        time.runDue();
        assertTrue(requestEntered.await(5, TimeUnit.SECONDS));

        runner.stop();
        assertEquals(0, time.pendingTasks());
        releaseResponse.countDown();
        responseFinished.await(5, TimeUnit.SECONDS);
        results.assertNoResult();
        time.advance(Duration.ofMinutes(5));
        results.assertNoResult();
        assertEquals(1, requests.get());

        runner.start();
        assertEquals(0, time.pendingTasks(), "a stopped runner cannot be restarted");
    }

    private HealthCheckRunner startRunner() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            requests.incrementAndGet();
            requestEntered.countDown();
            try {
                if (blockResponses) releaseResponse.await();
                Integer status = statuses.poll();
                exchange.sendResponseHeaders(status == null ? 500 : status, -1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
                responseFinished.countDown();
            }
        });
        server.start();
        HttpCheck check = check(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/health"));
        HealthCheckRunner started = new ProbeCheckRunner(check, new HttpProbe(check, client), time, time, results);
        started.start();
        return started;
    }

    private static HttpCheck check(URI url) {
        return new HttpCheck("web", "http", url, INTERVAL, TIMEOUT, Duration.ofSeconds(30), true);
    }

    private static void assertResult(CheckResult result, CheckStatus status, String output, Instant observedAt) {
        assertEquals(status, result.status(), result.output());
        assertEquals(output, result.output());
        assertEquals(observedAt, result.observedAt());
    }
}

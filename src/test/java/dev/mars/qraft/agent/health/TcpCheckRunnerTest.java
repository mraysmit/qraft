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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProbeCheckRunner} running a {@link TcpCheck} against real sockets and a
 * scripted connector at the transport boundary, including the slow-connection warning threshold.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
class TcpCheckRunnerTest {
    private static final Instant START = Instant.parse("2026-09-26T12:00:00Z");
    private static final Duration INTERVAL = Duration.ofSeconds(5);
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final ManualTime time = new ManualTime(START);
    private final RecordingListener results = new RecordingListener();
    private HealthCheckRunner runner;

    @AfterEach
    void stopRunner() {
        if (runner != null) runner.stop();
    }

    @Test
    void realConnectorPassesForAListeningPortAndFailsForAClosedPort() throws Exception {
        int port;
        try (ServerSocket listening = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            port = listening.getLocalPort();
            runner = start(check(port), new SocketTcpConnector());
            time.runDue();
            CheckResult passing = results.next();
            assertEquals(CheckStatus.PASSING, passing.status());
            assertEquals("TCP connect to 127.0.0.1:" + port + " succeeded", passing.output());
            assertEquals(START, passing.observedAt());
        }

        time.advance(INTERVAL);
        CheckResult refused = results.next();
        assertEquals(CheckStatus.CRITICAL, refused.status());
        assertTrue(refused.output().startsWith("TCP connect to 127.0.0.1:" + port + " failed: "), refused.output());
        assertEquals(START.plus(INTERVAL), refused.observedAt());
    }

    @Test
    void failureRecoversOnTheNextSuccessfulConnection() throws Exception {
        ScriptedConnector connector = new ScriptedConnector();
        runner = start(check(7000), connector);

        time.runDue();
        connector.attempt(0).completeExceptionally(new ConnectException("Connection refused"));
        CheckResult failed = results.next();
        assertEquals(CheckStatus.CRITICAL, failed.status());
        assertEquals("TCP connect to 127.0.0.1:7000 failed: Connection refused", failed.output());

        time.advance(INTERVAL);
        connector.attempt(1).complete(null);
        assertEquals(CheckStatus.PASSING, results.next().status());
        assertEquals(2, connector.attempts.size());
        assertEquals(TIMEOUT, connector.timeouts.getFirst());
    }

    @Test
    void timeoutCancelsTheConnectionAttemptAndTheCheckNeverOverlapsItself() throws Exception {
        ScriptedConnector connector = new ScriptedConnector();
        runner = start(check(7000), connector);

        time.runDue();
        time.advance(TIMEOUT.minusMillis(1));
        assertEquals(1, connector.attempts.size());
        assertEquals(1, time.pendingTasks());

        time.advance(Duration.ofMillis(1));
        CheckResult timedOut = results.next();
        assertEquals(CheckStatus.CRITICAL, timedOut.status());
        assertEquals("Check timed out after 1000 ms", timedOut.output());
        assertEquals(START.plus(TIMEOUT), timedOut.observedAt());
        assertTrue(connector.attempt(0).isCancelled());

        connector.attempt(0).complete(null);
        results.assertNoResult();
        time.advance(INTERVAL);
        assertEquals(2, connector.attempts.size());
    }

    @Test
    void stoppingCancelsTheAttemptAndPendingScheduleWithoutLateResults() throws Exception {
        ScriptedConnector connector = new ScriptedConnector();
        runner = start(check(7000), connector);
        time.runDue();

        runner.stop();

        assertTrue(connector.attempt(0).isCancelled());
        assertEquals(0, time.pendingTasks());
        connector.attempt(0).complete(null);
        time.advance(Duration.ofMinutes(1));
        results.assertNoResult();
        assertEquals(1, connector.attempts.size());
    }

    @Test
    void aConnectionThatTakesAtLeastTheWarningThresholdIsAWarning() throws Exception {
        ScriptedConnector connector = new ScriptedConnector();
        runner = start(check(7000, Duration.ofMillis(300)), connector);

        time.runDue();
        time.advance(Duration.ofMillis(299));
        connector.attempt(0).complete(null);
        CheckResult justUnder = results.next();
        assertEquals(CheckStatus.PASSING, justUnder.status());
        assertEquals("TCP connect to 127.0.0.1:7000 succeeded", justUnder.output());

        time.advance(INTERVAL);
        time.advance(Duration.ofMillis(300));
        connector.attempt(1).complete(null);
        CheckResult atThreshold = results.next();
        assertEquals(CheckStatus.WARNING, atThreshold.status());
        assertEquals("TCP connect to 127.0.0.1:7000 took 300 ms, at or above the warning threshold of 300 ms",
                atThreshold.output());

        time.advance(INTERVAL);
        connector.attempt(2).complete(null);
        assertEquals(CheckStatus.PASSING, results.next().status(), "a fast connection recovers from the warning");
    }

    @Test
    void withoutAWarningThresholdASlowConnectionStillPasses() throws Exception {
        ScriptedConnector connector = new ScriptedConnector();
        runner = start(check(7000), connector);

        time.runDue();
        time.advance(TIMEOUT.minusMillis(1));
        connector.attempt(0).complete(null);

        assertEquals(CheckStatus.PASSING, results.next().status());
    }

    @Test
    void theWarningThresholdMustBeShorterThanTheTimeout() {
        assertThrows(IllegalArgumentException.class, () -> check(7000, TIMEOUT),
                "a connection that takes the whole timeout has already failed");
        assertThrows(IllegalArgumentException.class, () -> check(7000, Duration.ofMillis(-1)));
        assertEquals(Duration.ZERO, check(7000).warnAfter(), "no threshold unless one is configured");
    }

    @Test
    void aConnectorThatThrowsIsReportedAsCritical() throws Exception {
        runner = start(check(7000), (host, port, timeout) -> {
            throw new IllegalStateException("resolver unavailable");
        });
        time.runDue();

        CheckResult result = results.next();
        assertEquals(CheckStatus.CRITICAL, result.status());
        assertEquals("TCP connect to 127.0.0.1:7000 failed: resolver unavailable", result.output());
        assertEquals(1, time.pendingTasks());
    }

    private HealthCheckRunner start(TcpCheck check, TcpConnector connector) {
        HealthCheckRunner started = new ProbeCheckRunner(check, new TcpProbe(check, connector, time), time, time,
                results);
        started.start();
        return started;
    }

    private static TcpCheck check(int port) {
        return new TcpCheck("db", "tcp", "127.0.0.1", port, INTERVAL, TIMEOUT, Duration.ofSeconds(15), true);
    }

    private static TcpCheck check(int port, Duration warnAfter) {
        return new TcpCheck("db", "tcp", "127.0.0.1", port, INTERVAL, TIMEOUT, Duration.ofSeconds(15), true,
                Duration.ZERO, warnAfter);
    }

    private static final class ScriptedConnector implements TcpConnector {
        final List<CompletableFuture<Void>> attempts = new CopyOnWriteArrayList<>();
        final List<Duration> timeouts = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<Void> connect(String host, int port, Duration timeout) throws IOException {
            CompletableFuture<Void> attempt = new CompletableFuture<>();
            attempts.add(attempt);
            timeouts.add(timeout);
            return attempt;
        }

        CompletableFuture<Void> attempt(int index) {
            return attempts.get(index);
        }
    }
}

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

package dev.mars.qraft.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.async.BlockingTaskExecutor;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.common.async.RaftStateLoop;
import dev.mars.qraft.raft.storage.RaftStorageFactory;
import dev.mars.qraft.server.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that server launch validates configuration before opening resources, closes acquired
 * resources when startup fails, and releases the runtime and telemetry off the thread that finished
 * stopping the server.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
class QraftServerLifecycleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OPERATOR_TOKEN = "membership-wiring-test-secret-keep-out-of-logs";
    @TempDir
    Path temporaryDirectory;

    /** {@code launch} sets the process-wide log directory; it is restored so later tests do not log to a deleted directory. */
    private final String previousLogDirectory = System.getProperty("qraft.log.dir");
    private final String previousLogMode = System.getProperty("qraft.log.mode");

    @Test
    void blockingWorkCompletesOnTheStateLoopBeforeTheOwnerStopsThatLoop() throws Exception {
        RaftStateLoop loop = RaftStateLoop.create();
        BlockingTaskExecutor workers = new BlockingTaskExecutor("server-release-test", 1, 1, loop);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Boolean> completedOnLoop = new CompletableFuture<>();
        try {
            CompletableFuture<Void> operation = workers.execute(() -> {
                started.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
                return null;
            });
            operation.whenComplete((value, failure) -> completedOnLoop.complete(
                    failure == null && RaftStateLoop.currentContext() == loop));
            assertTrue(started.await(10, TimeUnit.SECONDS));
            CompletableFuture<Void> stopped = QraftServerApplication.shutdownExecutors(workers, loop);
            org.junit.jupiter.api.Assertions.assertFalse(stopped.isDone());
            CompletableFuture<Void> stillRunning = new CompletableFuture<>();
            loop.execute(() -> stillRunning.complete(null));
            stillRunning.get(10, TimeUnit.SECONDS);
            release.countDown();
            assertTrue(completedOnLoop.get(10, TimeUnit.SECONDS));
            stopped.get(10, TimeUnit.SECONDS);
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> loop.execute(() -> {}));
        } finally {
            release.countDown();
            QraftServerApplication.shutdownExecutors(workers, loop).get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void aFailedBlockingShutdownStillClosesTheStateLoopAndTelemetry() throws Exception {
        RaftStateLoop loop = RaftStateLoop.create();
        BlockingTaskExecutor workers = new BlockingTaskExecutor("failed-release-test", 1, 1, loop);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Thread> workerThread = new CompletableFuture<>();
        AtomicBoolean telemetryClosed = new AtomicBoolean();
        CompletableFuture<Void> operation = workers.execute(() -> {
            workerThread.complete(Thread.currentThread());
            if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
            return null;
        });
        try {
            workerThread.get(10, TimeUnit.SECONDS);
            // Interrupt the waiting owner to drive the real forced-shutdown path without a clock delay.
            Thread.currentThread().interrupt();
            CompletionException interrupted = assertThrows(CompletionException.class, workers::close);
            assertTrue(Thread.interrupted(), "close must restore the owner's interrupt flag");
            org.junit.jupiter.api.Assertions.assertInstanceOf(InterruptedException.class, interrupted.getCause());
            CompletableFuture<Void> released = QraftServerApplication.releaseAfter(
                    CompletableFuture.completedFuture(null),
                    () -> QraftServerApplication.shutdownExecutors(workers, loop),
                    () -> telemetryClosed.set(true));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> released.get(10, TimeUnit.SECONDS));
            assertSame(interrupted.getCause(), failure.getCause());
            assertTrue(operation.isCompletedExceptionally());
            assertTrue(telemetryClosed.get());
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> loop.execute(() -> {}));
        } finally {
            Thread.interrupted();
            release.countDown();
            QraftServerApplication.shutdownExecutors(workers, loop)
                    .handle((value, failure) -> null).get(10, TimeUnit.SECONDS);
            if (workerThread.isDone()) {
                Thread worker = workerThread.getNow(null);
                worker.join(10_000);
                org.junit.jupiter.api.Assertions.assertFalse(worker.isAlive());
            }
        }
    }

    @AfterEach
    void restoreProcessWideState() {
        if (previousLogDirectory == null) System.clearProperty("qraft.log.dir");
        else System.setProperty("qraft.log.dir", previousLogDirectory);
        if (previousLogMode == null) System.clearProperty("qraft.log.mode");
        else System.setProperty("qraft.log.mode", previousLogMode);
    }

    @Test
    void validatesConfigurationBeforeOpeningServerResources() throws Exception {
        Path configuration = temporaryDirectory.resolve("invalid-server.json");
        Files.writeString(configuration, """
                {"version":1,"server":{"id":"node-a","http":{"port":-1}}}
                """);
        AtomicInteger opened = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> QraftServerApplication.launch(configuration,
                ignored -> {
                    opened.incrementAndGet();
                    return new TestServerResourceFixture();
                }, TestServerResourceFixture::start, TestServerResourceFixture::shutdown));

        assertEquals(0, opened.get());
    }

    @Test
    void failedStartupClosesAcquiredServerResources() throws Exception {
        Path configuration = validConfiguration();
        RuntimeException expected = new RuntimeException("raft failed");
        TestServerResourceFixture resource = new TestServerResourceFixture();
        resource.startup = CompletableFuture.failedFuture(expected);

        CompletionException actual = assertThrows(CompletionException.class,
                () -> QraftServerApplication.launch(configuration, ignored -> resource,
                        TestServerResourceFixture::start, TestServerResourceFixture::shutdown));

        assertSame(expected, actual.getCause());
        assertEquals(1, resource.shutdowns.get());
    }

    @Test
    void theRuntimeAndTelemetryAreReleasedOffTheThreadThatFinishedStoppingTheServer() throws Exception {
        CompletableFuture<Void> serverStopped = new CompletableFuture<>();
        CountDownLatch stoppingThreadReturned = new CountDownLatch(1);
        CompletableFuture<Boolean> runtimeSawTheStoppingThreadReturn = new CompletableFuture<>();
        AtomicBoolean telemetryClosed = new AtomicBoolean();

        // A resource release must not wait on the same thread that is completing server stop.
        CompletableFuture<Void> released = QraftServerApplication.releaseAfter(serverStopped, () -> {
            runtimeSawTheStoppingThreadReturn.complete(awaitQuietly(stoppingThreadReturned));
            return CompletableFuture.completedFuture(null);
        }, () -> telemetryClosed.set(true));
        Thread stoppingThread = Thread.ofPlatform().start(() -> {
            serverStopped.complete(null);
            stoppingThreadReturned.countDown();
        });

        assertTrue(runtimeSawTheStoppingThreadReturn.get(30, TimeUnit.SECONDS),
                "the runtime shutdown ran on the thread it was waiting for");
        released.get(10, TimeUnit.SECONDS);
        assertTrue(telemetryClosed.get());
        stoppingThread.join(10_000);
    }

    @Test
    void everyReleaseStepRunsAndTheFirstFailureCarriesTheOthers() {
        IllegalStateException serverFailure = new IllegalStateException("server");
        IllegalStateException runtimeFailure = new IllegalStateException("runtime");
        IllegalStateException telemetryFailure = new IllegalStateException("telemetry");

        ExecutionException error = assertThrows(ExecutionException.class, () ->
                QraftServerApplication.releaseAfter(CompletableFuture.failedFuture(serverFailure),
                        () -> CompletableFuture.failedFuture(runtimeFailure),
                        () -> { throw telemetryFailure; }).get(10, TimeUnit.SECONDS));

        assertSame(serverFailure, error.getCause());
        assertArrayEquals(new Throwable[]{runtimeFailure, telemetryFailure}, serverFailure.getSuppressed());
    }

    @Test
    void aFailedStartupReleasesTheRuntimeBeforeTelemetry() {
        List<String> released = new java.util.concurrent.CopyOnWriteArrayList<>();
        IllegalStateException startupFailure = new IllegalStateException("startup");

        QraftServerApplication.closePartiallyOpened(() -> {
            released.add("runtime");
            return CompletableFuture.completedFuture(null);
        }, () -> released.add("telemetry"), startupFailure);

        assertEquals(List.of("runtime", "telemetry"), released);
        assertArrayEquals(new Throwable[0], startupFailure.getSuppressed(), "a clean release adds nothing");
    }

    @Test
    void aStartupThatFailedBeforeCreatingTheRuntimeReleasesOnlyTelemetry() {
        List<String> released = new java.util.concurrent.CopyOnWriteArrayList<>();
        IllegalStateException startupFailure = new IllegalStateException("startup");

        QraftServerApplication.closePartiallyOpened(null, () -> released.add("telemetry"), startupFailure);

        assertEquals(List.of("telemetry"), released);
        assertArrayEquals(new Throwable[0], startupFailure.getSuppressed(), "an absent runtime is not a failure");
    }

    @Test
    void everyStartupReleaseStepRunsAndItsFailuresAreCarriedByTheStartupFailure() {
        IllegalStateException startupFailure = new IllegalStateException("startup");
        IllegalStateException runtimeFailure = new IllegalStateException("runtime");
        IllegalStateException telemetryFailure = new IllegalStateException("telemetry");

        QraftServerApplication.closePartiallyOpened(() -> CompletableFuture.failedFuture(runtimeFailure),
                () -> { throw telemetryFailure; }, startupFailure);

        assertArrayEquals(new Throwable[]{runtimeFailure, telemetryFailure}, startupFailure.getSuppressed(),
                "the runtime's own failure, not a wrapper, and then telemetry's");
    }

    @Test
    void aRuntimeShutdownThatThrowsStillLetsTelemetryClose() {
        AtomicBoolean telemetryClosed = new AtomicBoolean();
        IllegalStateException startupFailure = new IllegalStateException("startup");
        IllegalStateException runtimeFailure = new IllegalStateException("runtime");

        QraftServerApplication.closePartiallyOpened(() -> { throw runtimeFailure; },
                () -> telemetryClosed.set(true), startupFailure);

        assertTrue(telemetryClosed.get());
        assertArrayEquals(new Throwable[]{runtimeFailure}, startupFailure.getSuppressed());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("membershipPolicies")
    void aRealServerReportsItsEffectiveMembershipPolicyAndReleasesItsWal(
            String description, long electionMs, long heartbeatMs, String membershipJson,
            String expectedPolicy) throws Exception {
        Path storage = temporaryDirectory.resolve("raft");
        Map<String, Object> raft = new HashMap<>();
        raft.put("port", 0);
        raft.put("electionTimeoutMs", electionMs);
        raft.put("heartbeatIntervalMs", heartbeatMs);
        raft.put("storage", Map.of("path", storage.toString()));
        if (membershipJson != null) raft.put("membership", JSON.readTree(membershipJson));
        Path configuration = temporaryDirectory.resolve("membership-server.json");
        JSON.writeValue(configuration.toFile(), Map.of("version", 1, "server", Map.of(
                "id", "membership-wiring", "http", Map.of("port", 0), "apiGrpcPort", 0,
                "raft", raft, "operator", Map.of("token", OPERATOR_TOKEN),
                "telemetry", Map.of("enabled", false))));
        AppConfig config = AppConfig.fromFile(configuration);
        config.validate();

        Logger logger = (Logger) LoggerFactory.getLogger(QraftServerService.class);
        MembershipLogCaptureHelper capture = new MembershipLogCaptureHelper();
        capture.start();
        logger.addAppender(capture);
        RaftExecutorsFixture executors = RaftExecutorsFixture.create();
        QraftServerService service = new QraftServerService(
                executors.stateLoop(), executors.blockingTasks(), config);
        try {
            service.start().get(30, TimeUnit.SECONDS);
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + service.boundPorts().get("http") + "/health/live"))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
            }
            List<ILoggingEvent> policies = capture.events.stream()
                    .filter(event -> event.getFormattedMessage().startsWith("Raft membership policy:"))
                    .toList();
            assertEquals(List.of(expectedPolicy), policies.stream().map(ILoggingEvent::getFormattedMessage).toList(),
                    "the running node must report its policy once, including rounded durations");
            assertEquals(Level.INFO, policies.getFirst().getLevel());
            assertFalse(capture.events.stream().anyMatch(event -> event.getFormattedMessage().contains(OPERATOR_TOKEN)),
                    "effective membership settings must not disclose the operator token");
        } finally {
            try {
                try {
                    service.stop().get(30, TimeUnit.SECONDS);
                } finally {
                    executors.close().get(30, TimeUnit.SECONDS);
                }
            } finally {
                logger.detachAppender(capture);
                capture.stop();
            }
        }

        // A successful reopen proves the real service released the WAL directory's exclusive lock.
        RaftStorageFactory.DurableStorage reopened = RaftStorageFactory.createDurable(storage, true)
                .get(30, TimeUnit.SECONDS);
        try {
            reopened.snapshots().closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } finally {
            reopened.wal().closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    private static Stream<Arguments> membershipPolicies() {
        return Stream.of(
                Arguments.of("absent defaults", 5000L, 1000L, null,
                        policy(10_000, 250, true, 60_000, 10_000, 3, 10, 60, 10)),
                Arguments.of("non-default disabled cleanup", 5000L, 1000L, """
                        {"promotionStabilizationMs":12501,"promotionMaxTrailingEntries":17,
                         "cleanup":{"enabled":false,"failureTimeoutMs":75001,"intervalMs":12501,"minVoters":5}}
                        """, policy(12_501, 17, false, 75_001, 12_501, 5, 13, 76, 13)),
                Arguments.of("non-default enabled cleanup", 5000L, 1000L, """
                        {"promotionStabilizationMs":2001,"promotionMaxTrailingEntries":3,
                         "cleanup":{"enabled":true,"failureTimeoutMs":6001,"intervalMs":1001,"minVoters":7}}
                        """, policy(2001, 3, true, 6001, 1001, 7, 3, 7, 2)),
                Arguments.of("defaults follow configured timing", 90_000L, 20_000L, null,
                        policy(10_000, 250, true, 90_000, 20_000, 3, 1, 5, 1)),
                Arguments.of("zero promotion and exact cleanup boundaries", 5000L, 1000L, """
                        {"promotionStabilizationMs":0,"promotionMaxTrailingEntries":0,
                         "cleanup":{"enabled":false,"failureTimeoutMs":5000,"intervalMs":1000,"minVoters":1}}
                        """, policy(0, 0, false, 5000, 1000, 1, 0, 5, 1)),
                Arguments.of("partial membership retains defaults", 5000L, 1000L,
                        "{\"promotionMaxTrailingEntries\":7}",
                        policy(10_000, 7, true, 60_000, 10_000, 3, 10, 60, 10)),
                Arguments.of("largest representable policy", 5000L, 1000L, """
                        {"promotionStabilizationMs":9223372036854775807,
                         "promotionMaxTrailingEntries":9223372036854775807,
                         "cleanup":{"failureTimeoutMs":9223372036854775807,
                                    "intervalMs":9223372036854775807,"minVoters":2147483647}}
                        """, policy(Long.MAX_VALUE, Long.MAX_VALUE, true, Long.MAX_VALUE, Long.MAX_VALUE,
                        Integer.MAX_VALUE, 9_223_372_036_854_776L, 9_223_372_036_854_776L, 9_223_372_036_854_776L)));
    }

    private static String policy(long stabilization, long lag, boolean enabled, long failure, long interval,
                                 int minimum, long stabilizationRounds, long failureRounds, long intervalRounds) {
        return "Raft membership policy: promotionStabilizationMs=" + stabilization
                + ", promotionMaxTrailingEntries=" + lag + ", cleanup.enabled=" + enabled
                + ", cleanup.failureTimeoutMs=" + failure + ", cleanup.intervalMs=" + interval
                + ", cleanup.minVoters=" + minimum + ", promotionStabilizationRounds=" + stabilizationRounds
                + ", cleanup.failureTimeoutRounds=" + failureRounds + ", cleanup.intervalRounds=" + intervalRounds
                + ", cleanup.active=false";
    }

    /** Test log capture helper; copies service events without filtering or changing existing appenders. */
    private static final class MembershipLogCaptureHelper extends AppenderBase<ILoggingEvent> {
        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            events.add(event);
        }
    }

    private static boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Path validConfiguration() throws Exception {
        Path configuration = temporaryDirectory.resolve("server.json");
        Files.writeString(configuration, """
                {"version":1,"server":{"id":"node-a","telemetry":{"enabled":false}}}
                """);
        return configuration;
    }

    /** Test fixture that supplies controllable server lifecycle operations for startup and shutdown assertions. */
    private static final class TestServerResourceFixture {
        private CompletableFuture<Void> startup = CompletableFuture.completedFuture(null);
        private final AtomicInteger shutdowns = new AtomicInteger();

        CompletableFuture<Void> start() {
            return startup;
        }

        CompletableFuture<Void> shutdown() {
            shutdowns.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }
}

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

import dev.mars.qraft.common.async.BlockingTaskExecutor;
import dev.mars.qraft.common.async.RaftStateLoop;
import dev.mars.qraft.raft.RaftMembershipPolicy;
import dev.mars.qraft.server.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @TempDir
    Path temporaryDirectory;

    /** {@code launch} sets the process-wide log directory; it is restored so later tests do not log to a deleted directory. */
    private final String previousLogDirectory = System.getProperty("qraft.log.dir");
    private final String previousLogMode = System.getProperty("qraft.log.mode");

    @Test
    void startupWiresNonDefaultMembershipSettingsIntoTheActualRaftNode() throws Exception {
        assertStartedPolicy("""
                "membership":{"promotionStabilizationMs":12501,"promotionMaxTrailingEntries":17,
                  "cleanup":{"enabled":false,"failureTimeoutMs":75001,"intervalMs":12501,"minVoters":5}}
                """, new RaftMembershipPolicy(12501, 17, false, 75001, 12501, 5));
    }

    @Test
    void startupPreservesTimingAwareMembershipDefaultsForAnAbsentObject() throws Exception {
        assertStartedPolicy("\"electionTimeoutMs\":90000,\"heartbeatIntervalMs\":20000",
                new RaftMembershipPolicy(10000, 250, true, 90000, 20000, 3));
    }

    @Test
    void startupAcceptsTheLargestRepresentableMembershipDurations() throws Exception {
        assertStartedPolicy("""
                "membership":{"promotionStabilizationMs":9223372036854775807,
                  "promotionMaxTrailingEntries":9223372036854775807,
                  "cleanup":{"failureTimeoutMs":9223372036854775807,"intervalMs":9223372036854775807,
                             "minVoters":2147483647}}
                """, new RaftMembershipPolicy(Long.MAX_VALUE, Long.MAX_VALUE, true,
                Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE));
    }

    private void assertStartedPolicy(String raftSettings, RaftMembershipPolicy expected) throws Exception {
        AppConfig configuration = AppConfig.fromJson("""
                {"version":1,"server":{"id":"policy-server","http":{"port":0},"apiGrpcPort":0,
                  "telemetry":{"enabled":false},"raft":{"port":0,"snapshot":{"enabled":false},
                  "storage":{"path":"%s"},%s}}}
                """.formatted(temporaryDirectory.resolve("raft").toString().replace('\\', '/'), raftSettings));
        configuration.validate();
        RaftStateLoop loop = RaftStateLoop.create();
        BlockingTaskExecutor workers = new BlockingTaskExecutor("policy-startup-test", 1, 8, loop);
        QraftServerService service = new QraftServerService(loop, workers, configuration);
        try {
            service.start().get(10, TimeUnit.SECONDS);
            assertEquals(expected, service.membershipPolicy(), "the policy is read from the constructed node");
        } finally {
            try {
                service.stop().get(10, TimeUnit.SECONDS);
            } finally {
                QraftServerApplication.shutdownExecutors(workers, loop).get(10, TimeUnit.SECONDS);
            }
        }
    }

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

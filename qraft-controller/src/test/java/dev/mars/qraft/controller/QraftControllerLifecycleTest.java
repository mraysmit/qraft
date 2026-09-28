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

package dev.mars.qraft.controller;

import dev.mars.qraft.controller.config.AppConfig;
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
 * Tests that controller launch validates configuration before opening resources, closes acquired
 * resources when startup fails, and releases the runtime and telemetry off the thread that finished
 * stopping the controller.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
class QraftControllerLifecycleTest {
    @TempDir
    Path temporaryDirectory;

    /** {@code launch} sets the process-wide log directory; it is restored so later tests do not log to a deleted directory. */
    private final String previousLogDirectory = System.getProperty("qraft.log.dir");

    /** {@code launch} installs its configuration process-wide; later tests must see the one found here. */
    private final AppConfig previousConfiguration = AppConfig.get();

    @AfterEach
    void restoreProcessWideState() {
        if (previousLogDirectory == null) System.clearProperty("qraft.log.dir");
        else System.setProperty("qraft.log.dir", previousLogDirectory);
        AppConfig.install(previousConfiguration);
    }

    @Test
    void validatesConfigurationBeforeOpeningControllerResources() throws Exception {
        Path configuration = temporaryDirectory.resolve("invalid-server.json");
        Files.writeString(configuration, """
                {"version":1,"server":{"id":"node-a","http":{"port":-1}}}
                """);
        AtomicInteger opened = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> QraftControllerApplication.launch(configuration,
                ignored -> {
                    opened.incrementAndGet();
                    return new TestControllerResource();
                }, TestControllerResource::start, TestControllerResource::shutdown));

        assertEquals(0, opened.get());
    }

    @Test
    void failedStartupClosesAcquiredControllerResources() throws Exception {
        Path configuration = validConfiguration();
        RuntimeException expected = new RuntimeException("raft failed");
        TestControllerResource resource = new TestControllerResource();
        resource.startup = CompletableFuture.failedFuture(expected);

        CompletionException actual = assertThrows(CompletionException.class,
                () -> QraftControllerApplication.launch(configuration, ignored -> resource,
                        TestControllerResource::start, TestControllerResource::shutdown));

        assertSame(expected, actual.getCause());
        assertEquals(1, resource.shutdowns.get());
    }

    @Test
    void theRuntimeAndTelemetryAreReleasedOffTheThreadThatFinishedStoppingTheController() throws Exception {
        CompletableFuture<Void> controllerStopped = new CompletableFuture<>();
        CountDownLatch stoppingThreadReturned = new CountDownLatch(1);
        CompletableFuture<Boolean> runtimeSawTheStoppingThreadReturn = new CompletableFuture<>();
        AtomicBoolean telemetryClosed = new AtomicBoolean();

        // Like JavaRuntime.shutdown when the stop finished on one of its workers: it waits for that thread.
        CompletableFuture<Void> released = QraftControllerApplication.releaseAfter(controllerStopped, () -> {
            runtimeSawTheStoppingThreadReturn.complete(awaitQuietly(stoppingThreadReturned));
            return CompletableFuture.completedFuture(null);
        }, () -> telemetryClosed.set(true));
        Thread stoppingThread = Thread.ofPlatform().start(() -> {
            controllerStopped.complete(null);
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
        IllegalStateException controllerFailure = new IllegalStateException("controller");
        IllegalStateException runtimeFailure = new IllegalStateException("runtime");
        IllegalStateException telemetryFailure = new IllegalStateException("telemetry");

        ExecutionException error = assertThrows(ExecutionException.class, () ->
                QraftControllerApplication.releaseAfter(CompletableFuture.failedFuture(controllerFailure),
                        () -> CompletableFuture.failedFuture(runtimeFailure),
                        () -> { throw telemetryFailure; }).get(10, TimeUnit.SECONDS));

        assertSame(controllerFailure, error.getCause());
        assertArrayEquals(new Throwable[]{runtimeFailure, telemetryFailure}, controllerFailure.getSuppressed());
    }

    @Test
    void aFailedStartupReleasesTheRuntimeBeforeTelemetry() {
        List<String> released = new java.util.concurrent.CopyOnWriteArrayList<>();
        IllegalStateException startupFailure = new IllegalStateException("startup");

        QraftControllerApplication.closePartiallyOpened(() -> {
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

        QraftControllerApplication.closePartiallyOpened(null, () -> released.add("telemetry"), startupFailure);

        assertEquals(List.of("telemetry"), released);
        assertArrayEquals(new Throwable[0], startupFailure.getSuppressed(), "an absent runtime is not a failure");
    }

    @Test
    void everyStartupReleaseStepRunsAndItsFailuresAreCarriedByTheStartupFailure() {
        IllegalStateException startupFailure = new IllegalStateException("startup");
        IllegalStateException runtimeFailure = new IllegalStateException("runtime");
        IllegalStateException telemetryFailure = new IllegalStateException("telemetry");

        QraftControllerApplication.closePartiallyOpened(() -> CompletableFuture.failedFuture(runtimeFailure),
                () -> { throw telemetryFailure; }, startupFailure);

        assertArrayEquals(new Throwable[]{runtimeFailure, telemetryFailure}, startupFailure.getSuppressed(),
                "the runtime's own failure, not a wrapper, and then telemetry's");
    }

    @Test
    void aRuntimeShutdownThatThrowsStillLetsTelemetryClose() {
        AtomicBoolean telemetryClosed = new AtomicBoolean();
        IllegalStateException startupFailure = new IllegalStateException("startup");
        IllegalStateException runtimeFailure = new IllegalStateException("runtime");

        QraftControllerApplication.closePartiallyOpened(() -> { throw runtimeFailure; },
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

    private static final class TestControllerResource {
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

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

package dev.mars.qraft.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import dev.mars.qraft.config.ConfigFileResolver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link QraftRuntimeApplication} mode and config selection, launcher dispatch, failure
 * propagation, and single-shot close.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-10
 * @version 1.0
 */
class QraftRuntimeApplicationTest {
    @Test
    void acceptsExplicitServerAndClientModes() {
        assertEquals(new QraftRuntimeApplication.Startup("server", java.nio.file.Path.of("server.json")),
                QraftRuntimeApplication.parseArguments(
                        new String[]{"server", "--config", "server.json"}));
        assertEquals(new QraftRuntimeApplication.Startup("client", java.nio.file.Path.of("client.json")),
                QraftRuntimeApplication.parseArguments(
                        new String[]{"client", "--config", "client.json"}));
    }

    @Test
    @ResourceLock("systemProperties")
    void acceptsJvmPropertyWhenConfigArgumentIsOmitted() {
        String previous = System.getProperty(ConfigFileResolver.CONFIG_FILE_PROPERTY);
        try {
            System.setProperty(ConfigFileResolver.CONFIG_FILE_PROPERTY, "property-server.json");
            assertEquals(new QraftRuntimeApplication.Startup(
                            "server", java.nio.file.Path.of("property-server.json")),
                    QraftRuntimeApplication.parseArguments(new String[]{"server"}));
        } finally {
            if (previous == null) System.clearProperty(ConfigFileResolver.CONFIG_FILE_PROPERTY);
            else System.setProperty(ConfigFileResolver.CONFIG_FILE_PROPERTY, previous);
        }
    }

    @Test
    void rejectsMissingInvalidAndAmbiguousModes() {
        assertThrows(IllegalArgumentException.class,
                () -> QraftRuntimeApplication.parseArguments(new String[0]));
        assertThrows(IllegalArgumentException.class,
                () -> QraftRuntimeApplication.parseArguments(new String[]{"worker", "--config", "a.json"}));
        assertThrows(IllegalArgumentException.class,
                () -> QraftRuntimeApplication.parseArguments(new String[]{"server"}));
        assertThrows(IllegalArgumentException.class,
                () -> QraftRuntimeApplication.parseArguments(
                        new String[]{"server", "--config", "a.json", "extra"}));
    }

    @Test
    void invokesOnlySuppliedServerLauncherWithResolvedPath() {
        RecordingLauncher server = new RecordingLauncher();
        RecordingLauncher client = new RecordingLauncher();

        RuntimeLifecycle lifecycle = QraftRuntimeApplication.run(new String[]{"server", "--config", "server.json"},
                server, client);

        assertEquals(List.of(Path.of("server.json")), server.paths);
        assertEquals(List.of(), client.paths);
        assertSame(server.lifecycle, lifecycle);
    }

    @Test
    void invokesOnlySuppliedClientLauncherWithResolvedPath() {
        RecordingLauncher server = new RecordingLauncher();
        RecordingLauncher client = new RecordingLauncher();

        RuntimeLifecycle lifecycle = QraftRuntimeApplication.run(new String[]{"client", "--config", "client.json"},
                server, client);

        assertEquals(List.of(), server.paths);
        assertEquals(List.of(Path.of("client.json")), client.paths);
        assertSame(client.lifecycle, lifecycle);
    }

    @Test
    void invalidModeFailsBeforeEitherLauncherIsInvoked() {
        RecordingLauncher server = new RecordingLauncher();
        RecordingLauncher client = new RecordingLauncher();

        assertThrows(IllegalArgumentException.class,
                () -> QraftRuntimeApplication.run(new String[]{"worker"}, server, client));
        assertThrows(IllegalArgumentException.class,
                () -> QraftRuntimeApplication.run(new String[0], server, client));

        assertEquals(List.of(), server.paths);
        assertEquals(List.of(), client.paths);
    }

    @Test
    void launcherFailureIsPropagatedWithoutStartingOtherMode() {
        RuntimeException expected = new RuntimeException("server did not start");
        QraftRuntimeApplication.ModeLauncher failingServer = path -> { throw expected; };
        RecordingLauncher client = new RecordingLauncher();

        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> QraftRuntimeApplication.run(
                        new String[]{"server", "--config", "server.json"}, failingServer, client));

        assertSame(expected, actual);
        assertEquals(List.of(), client.paths);
    }

    @Test
    void launchedModeExposesOneCompletionAndCloseBoundary() {
        CompletableFuture<Void> shutdown = new CompletableFuture<>();
        RuntimeLifecycle lifecycle = new ManagedRuntimeLifecycle(() -> shutdown);

        assertFalse(lifecycle.completion().isDone());
        CompletableFuture<Void> close = lifecycle.closeAsync();
        assertFalse(close.isDone());

        shutdown.complete(null);

        assertSame(close, lifecycle.completion());
        assertTrue(lifecycle.completion().isDone());
    }

    @Test
    void concurrentCloseStartsShutdownOnlyOnce() throws Exception {
        AtomicInteger closeCalls = new AtomicInteger();
        CountDownLatch enteredClose = new CountDownLatch(1);
        CompletableFuture<Void> shutdown = new CompletableFuture<>();
        RuntimeLifecycle lifecycle = new ManagedRuntimeLifecycle(() -> {
            closeCalls.incrementAndGet();
            enteredClose.countDown();
            return shutdown;
        });

        try (ExecutorService callers = Executors.newFixedThreadPool(8)) {
            List<CompletableFuture<Void>> closes = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                closes.add(CompletableFuture.supplyAsync(lifecycle::closeAsync, callers).thenCompose(value -> value));
            }
            assertTrue(enteredClose.await(2, TimeUnit.SECONDS));
            shutdown.complete(null);
            CompletableFuture.allOf(closes.toArray(CompletableFuture[]::new)).join();
        }

        assertEquals(1, closeCalls.get());
        assertSame(lifecycle.closeAsync(), lifecycle.completion());
    }

    private static final class RecordingLauncher implements QraftRuntimeApplication.ModeLauncher {
        private final List<Path> paths = new ArrayList<>();
        private final RuntimeLifecycle lifecycle = new ManagedRuntimeLifecycle(
                () -> CompletableFuture.completedFuture(null));

        @Override
        public RuntimeLifecycle launch(Path configurationPath) {
            paths.add(configurationPath);
            return lifecycle;
        }
    }
}

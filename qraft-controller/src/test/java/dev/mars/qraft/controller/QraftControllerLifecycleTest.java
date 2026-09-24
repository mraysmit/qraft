package dev.mars.qraft.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QraftControllerLifecycleTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesConfigurationBeforeOpeningControllerResources() throws Exception {
        Path configuration = temporaryDirectory.resolve("invalid-server.json");
        Files.writeString(configuration, """
                {"version":1,"server":{"id":"node-a","http":{"port":0}}}
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

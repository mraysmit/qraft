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

import dev.mars.qraft.agent.config.AgentConfiguration;
import org.junit.jupiter.api.AfterEach;
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

/**
 * Tests that the {@link QraftAgent} launch path validates {@link AgentConfiguration} before opening
 * resources and closes acquired resources when startup fails.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
class QraftAgentApplicationTest {
    @TempDir
    Path temporaryDirectory;

    /** {@code launch} sets the process-wide log directory; it is restored so later tests do not log to a deleted directory. */
    private final String previousLogDirectory = System.getProperty("qraft.log.dir");

    @AfterEach
    void restoreLogDirectory() {
        if (previousLogDirectory == null) System.clearProperty("qraft.log.dir");
        else System.setProperty("qraft.log.dir", previousLogDirectory);
    }

    @Test
    void validatesConfigurationBeforeOpeningAgentResources() throws Exception {
        Path configuration = temporaryDirectory.resolve("invalid-client.json");
        Files.writeString(configuration, """
                {"version":1,"agent":{"id":"agent-a","httpPort":-1},
                 "controllers":{"urls":["http://127.0.0.1:8080"]}}
                """);
        AtomicInteger opened = new AtomicInteger();

        assertThrows(IllegalArgumentException.class, () -> QraftAgent.launch(configuration,
                ignored -> {
                    opened.incrementAndGet();
                    return new TestAgentResourceFixture();
                }, TestAgentResourceFixture::start, TestAgentResourceFixture::shutdown));

        assertEquals(0, opened.get());
    }

    @Test
    void failedStartupClosesAcquiredAgentResources() throws Exception {
        Path configuration = validConfiguration();
        RuntimeException expected = new RuntimeException("registration failed");
        TestAgentResourceFixture resource = new TestAgentResourceFixture();
        resource.startup = CompletableFuture.failedFuture(expected);

        CompletionException actual = assertThrows(CompletionException.class,
                () -> QraftAgent.launch(configuration, ignored -> resource,
                        TestAgentResourceFixture::start, TestAgentResourceFixture::shutdown));

        assertSame(expected, actual.getCause());
        assertEquals(1, resource.shutdowns.get());
    }

    private Path validConfiguration() throws Exception {
        Path configuration = temporaryDirectory.resolve("client.json");
        Files.writeString(configuration, """
                {"version":1,"agent":{"id":"agent-a","httpPort":8500},
                 "controllers":{"urls":["http://127.0.0.1:8080"]}}
                """);
        return configuration;
    }

    /** Test fixture that supplies controllable agent startup and records shutdown calls for lifecycle assertions. */
    private static final class TestAgentResourceFixture {
        private CompletableFuture<Boolean> startup = CompletableFuture.completedFuture(true);
        private final AtomicInteger shutdowns = new AtomicInteger();

        CompletableFuture<Boolean> start() {
            return startup;
        }

        CompletableFuture<Boolean> shutdown() {
            shutdowns.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        }
    }
}

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

package dev.mars.qraft.controller.raft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SharedDockerCluster} runtime JAR freshness checks against sources and POMs, and
 * lifecycle commands that do not remove volumes.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-21
 * @version 1.0
 */
class SharedDockerClusterFreshnessTest {

    @TempDir
    Path repositoryRoot;

    @Test
    void rejectsMissingRuntimeJar() throws Exception {
        Path source = repositoryRoot.resolve("qraft-runtime/src/main/java/Runtime.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Runtime {}");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> SharedDockerCluster.assertRuntimeJarIsCurrent(
                        repositoryRoot, repositoryRoot.resolve("qraft-runtime/target/qraft-runtime.jar")));

        assertTrue(error.getMessage().contains("Host-built runtime JAR not found"));
    }

    @Test
    void rejectsJarOlderThanProductionSource() throws Exception {
        Path source = repositoryRoot.resolve("qraft-controller/src/main/java/Controller.java");
        Path jar = repositoryRoot.resolve("qraft-runtime/target/qraft-runtime.jar");
        Files.createDirectories(source.getParent());
        Files.createDirectories(jar.getParent());
        Files.writeString(source, "class Controller {}");
        Files.writeString(jar, "old jar");
        Files.setLastModifiedTime(jar, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(source, FileTime.from(Instant.parse("2026-01-02T00:00:00Z")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> SharedDockerCluster.assertRuntimeJarIsCurrent(repositoryRoot, jar));

        assertTrue(error.getMessage().contains("older than build input"));
        assertTrue(error.getMessage().contains("Controller.java"));
    }

    @Test
    void acceptsJarNewerThanSourcesAndPoms() throws Exception {
        Path source = repositoryRoot.resolve("qraft-agent/src/main/resources/agent.properties");
        Path pom = repositoryRoot.resolve("pom.xml");
        Path jar = repositoryRoot.resolve("qraft-runtime/target/qraft-runtime.jar");
        Files.createDirectories(source.getParent());
        Files.createDirectories(jar.getParent());
        Files.writeString(source, "key=value");
        Files.writeString(pom, "<project/>");
        Files.writeString(jar, "current jar");
        FileTime inputTime = FileTime.from(Instant.parse("2026-01-01T00:00:00Z"));
        Files.setLastModifiedTime(source, inputTime);
        Files.setLastModifiedTime(pom, inputTime);
        Files.setLastModifiedTime(jar, FileTime.from(Instant.parse("2026-01-02T00:00:00Z")));

        assertDoesNotThrow(() -> SharedDockerCluster.assertRuntimeJarIsCurrent(repositoryRoot, jar));
    }

    @Test
    void lifecycleCommandsUseDockerWithoutRemovingVolumes() {
        List<List<String>> commands = new ArrayList<>();

        SharedDockerCluster.runDockerLifecycleCommand(
                "stop", "container-id", command -> commands.add(List.copyOf(command)));
        SharedDockerCluster.runDockerLifecycleCommand(
                "kill", "container-id", command -> commands.add(List.copyOf(command)));
        SharedDockerCluster.runDockerLifecycleCommand(
                "start", "container-id", command -> commands.add(List.copyOf(command)));

        assertEquals(List.of(
                List.of("docker", "stop", "container-id"),
                List.of("docker", "kill", "container-id"),
                List.of("docker", "start", "container-id")), commands);
    }
}

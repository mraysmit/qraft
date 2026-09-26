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

import dev.mars.qraft.agent.config.AgentConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that Docker, Compose, and launcher artifacts build the unified runtime on the host, use
 * file-based configuration, and separate HTTP and Raft ports.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-21
 * @version 1.0
 */
class DockerDeploymentContractTest {
    private static final List<String> BUILD_COMPOSE_FILES = List.of(
            "docker/compose/docker-compose.yml",
            "docker/compose/docker-compose-5node.yml",
            "docker/compose/docker-compose-cluster.yml",
            "docker/compose/docker-compose-controller-first.yml",
            "docker/compose/docker-compose-network-test.yml",
            "docker/compose/docker-compose-observability-cluster.yml",
            "docker/compose/docker-compose-single-controller.yml",
            "qraft-controller/src/test/resources/docker-compose-test.yml",
            "qraft-controller/src/test/resources/docker-compose-network-test.yml",
            "qraft-controller/src/test/resources/docker-compose-build-image.yml",
            "qraft-controller/src/test/resources/docker-compose-5node-test.yml");
    private static final List<String> PREBUILT_COMPOSE_FILES = List.of(
            "qraft-controller/src/test/resources/docker-compose-3node-prebuilt.yml",
            "qraft-controller/src/test/resources/docker-compose-5node-prebuilt.yml");

    @Test
    void controllerDeploymentsBuildTheUnifiedRuntimeInServerMode() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        assertFalse(Files.exists(root.resolve("qraft-controller/Dockerfile")),
                "the obsolete controller-only image must not return");
        assertFalse(Files.exists(root.resolve("qraft-controller/docker-entrypoint.sh")),
                "the obsolete controller-only entrypoint must not return");

        for (String relativePath : BUILD_COMPOSE_FILES) {
            String compose = Files.readString(root.resolve(relativePath));
            assertTrue(compose.contains("dockerfile: qraft-runtime/Dockerfile"), relativePath);
            if (!relativePath.endsWith("docker-compose-build-image.yml")) {
                assertTrue(compose.contains("command: [\"server\", \"--config\", \"/etc/qraft/server.json\"]"),
                        relativePath);
                assertTrue(compose.contains(":/etc/qraft/server.json:ro"), relativePath);
            }
            assertFalse(compose.contains("QRAFT_"), relativePath);
            assertFalse(compose.contains("additional_contexts:"), relativePath);
            assertFalse(compose.contains("qraft-controller/Dockerfile"), relativePath);
            assertFalse(compose.contains("BUILDER_IMAGE"), relativePath);
            assertFalse(compose.contains("maven:"), relativePath);
            assertFalse(compose.matches("(?s).*driver: local\\R\\s+driver: local.*"), relativePath);
        }

        String buildCompose = Files.readString(root.resolve(
                "qraft-controller/src/test/resources/docker-compose-build-image.yml"));
        assertTrue(buildCompose.contains("image: qraft-runtime:test"));

        for (String relativePath : PREBUILT_COMPOSE_FILES) {
            String compose = Files.readString(root.resolve(relativePath));
            assertTrue(compose.contains("image: qraft-runtime:test"), relativePath);
            assertTrue(compose.contains("command: [\"server\", \"--config\", \"/etc/qraft/server.json\"]"),
                    relativePath);
            assertTrue(compose.contains(":/etc/qraft/server.json:ro"), relativePath);
            assertFalse(compose.contains("QRAFT_"), relativePath);
        }
    }

    @Test
    void unifiedRuntimeComposeCoversExplicitAndConventionalConfigurationSelection() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String compose = Files.readString(root.resolve(
                "docker/compose/docker-compose-single-controller.yml"));

        assertTrue(compose.contains("command: [\"server\", \"--config\", \"/etc/qraft/server.json\"]"));
        assertTrue(compose.contains("command: [\"client\"]"));
        assertTrue(compose.contains("../config/client.json:/etc/qraft/client.json:ro"));
        assertEquals(2, occurrences(compose, "dockerfile: qraft-runtime/Dockerfile"));
    }

    @Test
    void runtimeDeploymentsKeepHttpAndRaftOnSeparatePorts() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        for (String relativePath : BUILD_COMPOSE_FILES) {
            if (relativePath.endsWith("docker-compose-build-image.yml")) {
                continue;
            }
            String compose = Files.readString(root.resolve(relativePath));
            assertTrue(compose.contains("/etc/qraft/server.json"), relativePath);
        }
    }

    @Test
    void runtimeImageOnlyPackagesTheHostBuiltArtifact() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String dockerfile = Files.readString(root.resolve("qraft-runtime/Dockerfile"));
        assertTrue(dockerfile.startsWith("FROM sapmachine:27-jre-alpine"));
        assertTrue(dockerfile.contains(
                "COPY qraft-runtime/target/qraft-runtime.jar /app/qraft.jar"));
        assertFalse(dockerfile.contains("*.jar"));
        assertFalse(dockerfile.contains("FROM maven:"));
        assertFalse(dockerfile.contains("RUN mvn"));
        assertFalse(dockerfile.contains("COPY --from="));

        String agentDockerfile = Files.readString(root.resolve("qraft-agent/Dockerfile"));
        assertTrue(agentDockerfile.startsWith("FROM sapmachine:27-jre-alpine"));
        assertTrue(agentDockerfile.contains(
                "COPY qraft-runtime/target/qraft-runtime.jar app.jar"));
        assertFalse(agentDockerfile.contains("*.jar"));
        assertFalse(agentDockerfile.contains("FROM maven:"));
        assertFalse(agentDockerfile.contains("RUN mvn"));
        assertFalse(agentDockerfile.contains("COPY --from="));

        String dockerignore = Files.readString(root.resolve(".dockerignore"));
        List<String> dockerignoreLines = dockerignore.lines().toList();
        int includeDirectory = dockerignoreLines.indexOf("!qraft-runtime/target/");
        int excludeDirectoryContents = dockerignoreLines.indexOf("qraft-runtime/target/*");
        int includeRuntimeJar = dockerignoreLines.indexOf(
                "!qraft-runtime/target/qraft-runtime.jar");
        assertTrue(includeDirectory >= 0);
        assertTrue(excludeDirectoryContents > includeDirectory);
        assertTrue(includeRuntimeJar > excludeDirectoryContents);
    }

    @Test
    void clusterLaunchersBuildTheRuntimeJarOnTheHostFirst() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String powershellBuild = Files.readString(root.resolve("docker/build-runtime.ps1"));
        assertTrue(powershellBuild.contains("mvn"));
        assertTrue(powershellBuild.contains("package"));
        assertTrue(powershellBuild.contains("-pl qraft-runtime -am"));
        assertTrue(powershellBuild.contains("-DskipTests"));
        assertFalse(powershellBuild.contains("-Dmaven.test.skip=true"));
        assertFalse(powershellBuild.contains("docker"));

        String shellBuild = Files.readString(root.resolve("docker/build-runtime.sh"));
        assertTrue(shellBuild.contains("mvn"));
        assertTrue(shellBuild.contains("package -pl qraft-runtime -am"));
        assertTrue(shellBuild.contains("-DskipTests"));
        assertFalse(shellBuild.contains("-Dmaven.test.skip=true"));
        assertFalse(shellBuild.contains("docker"));

        assertTrue(Files.readString(root.resolve("docker/start.ps1"))
                .contains("build-runtime.ps1"));
        assertTrue(Files.readString(root.resolve("docker/start.sh"))
                .contains("build-runtime.sh"));
        assertTrue(Files.readString(root.resolve("docker/start-quick.ps1"))
                .contains("build-runtime.ps1"));
        assertTrue(Files.readString(root.resolve("docker/start-quick.sh"))
                .contains("build-runtime.sh"));
    }

    @Test
    void dockerIntegrationTestsRequireTheHostBuiltRuntimeJar() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String sharedCluster = Files.readString(root.resolve(
                "qraft-controller/src/test/java/dev/mars/qraft/controller/raft/SharedDockerCluster.java"));
        assertTrue(sharedCluster.contains("qraft-runtime/target/qraft-runtime.jar"));
        assertTrue(sharedCluster.contains("assertRuntimeJarIsCurrent"));
        assertFalse(sharedCluster.contains("isImageCached"));
    }

    @Test
    void prebuiltDockerClustersPersistRaftStateAndExerciseSnapshots() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        for (String relativePath : PREBUILT_COMPOSE_FILES) {
            String compose = Files.readString(root.resolve(relativePath));
            assertTrue(compose.contains("-acceptance/"), relativePath);
            assertTrue(compose.contains(":/app/data"), relativePath);
            assertTrue(compose.contains("volumes:"), relativePath);
        }
        for (String profile : List.of("three-node-acceptance", "five-node-acceptance")) {
            try (var paths = Files.list(root.resolve("docker/config/" + profile))) {
                for (Path config : paths.toList()) {
                    String json = Files.readString(config);
                    assertTrue(json.contains("\"path\":\"/app/data\""), config.toString());
                    assertTrue(json.contains("\"threshold\":5"), config.toString());
                    assertTrue(json.contains("\"checkIntervalMs\":1000"), config.toString());
                }
            }
        }
    }

    @Test
    void agentContainerUsesTheConventionalMountedConfiguration() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String entrypoint = Files.readString(root.resolve("qraft-agent/docker-entrypoint.sh"));
        assertTrue(entrypoint.contains("\"$@\""));
        assertFalse(entrypoint.contains("QRAFT_"));
        assertFalse(entrypoint.contains("AGENT_"));

        String dockerfile = Files.readString(root.resolve("qraft-agent/Dockerfile"));
        assertTrue(dockerfile.contains("CMD [\"client\"]"));
        assertFalse(dockerfile.contains("ENV "));
        assertTrue(dockerfile.contains("sed -i 's/\\r$//' /docker-entrypoint.sh"));
        assertTrue(Files.readString(root.resolve(".gitattributes"))
                .contains("*.sh text eol=lf"));
    }

    @Test
    void documentedClientConfigurationIsAValidCompleteExample() {
        Path root = Path.of("..").toAbsolutePath().normalize();
        AgentConfiguration configuration = AgentConfiguration.fromFile(
                root.resolve("docker/config/client.json"));

        assertTrue(configuration.getAgentId().equals("agent-example"));
        assertTrue(configuration.getControllerUrls().size() == 1);
        assertTrue(configuration.getServices().size() == 1);
        assertTrue(configuration.getServices().getFirst().id().equals("web"));
    }

    @Test
    void qraftDeploymentArtifactsDoNotConfigureThroughEnvironmentVariables() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        List<Path> roots = List.of(root.resolve("qraft-agent/src/main"),
                root.resolve("qraft-controller/src/main"), root.resolve("qraft-runtime/src/main"),
                root.resolve("docker/compose"), root.resolve("qraft-agent/Dockerfile"),
                root.resolve("qraft-runtime/Dockerfile"), root.resolve("qraft-agent/docker-entrypoint.sh"),
                root.resolve("qraft-runtime/docker-entrypoint.sh"));
        for (Path candidate : roots) {
            if (Files.isDirectory(candidate)) {
                try (var paths = Files.walk(candidate)) {
                    for (Path file : paths.filter(Files::isRegularFile).toList()) {
                        String content = Files.readString(file);
                        assertFalse(content.contains("System.getenv("), file.toString());
                        assertFalse(content.matches("(?s).*QRAFT_[A-Z0-9_]+.*"), file.toString());
                    }
                }
            } else {
                String content = Files.readString(candidate);
                assertFalse(content.contains("System.getenv("), candidate.toString());
                assertFalse(content.matches("(?s).*QRAFT_[A-Z0-9_]+.*"), candidate.toString());
            }
        }
    }

    @Test
    void corePublishesTheTestFixturesRequiredByController() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String corePom = Files.readString(root.resolve("qraft-core/pom.xml"));
        assertTrue(corePom.contains("<goal>test-jar</goal>"));
    }

    @Test
    void noDockerfileBuildsProjectArtifacts() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        try (var paths = Files.walk(root)) {
            for (Path dockerfile : paths
                    .filter(path -> path.getFileName().toString().equals("Dockerfile"))
                    .toList()) {
                String content = Files.readString(dockerfile);
                assertFalse(content.contains("FROM maven:"), dockerfile.toString());
                assertFalse(content.matches("(?s).*RUN\\s+.*\\bmvn\\b.*"), dockerfile.toString());
                assertFalse(content.contains("COPY --from="), dockerfile.toString());
            }
        }
    }

    private static int occurrences(String value, String target) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(target, offset)) >= 0) {
            count++;
            offset += target.length();
        }
        return count;
    }
}

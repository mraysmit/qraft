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

import dev.mars.qraft.client.config.AgentConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
            "docker/compose/docker-compose-5node.yml",
            "docker/compose/docker-compose-cluster.yml",
            "docker/compose/docker-compose-server-first.yml",
            "docker/compose/docker-compose-network-test.yml",
            "docker/compose/docker-compose-observability-cluster.yml",
            "docker/compose/docker-compose-single-server.yml",
            "src/test/resources/docker-compose-build-image.yml");
    private static final List<String> PREBUILT_COMPOSE_FILES = List.of(
            "src/test/resources/docker-compose-3node-prebuilt.yml",
            "src/test/resources/docker-compose-3node-agent-prebuilt.yml",
            "src/test/resources/docker-compose-3node-agent-restart-prebuilt.yml");

    @Test
    void controllerDeploymentsBuildTheUnifiedRuntimeInServerMode() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        assertFalse(Files.exists(root.resolve("qraft-controller/Dockerfile")),
                "the obsolete controller-only image must not return");
        assertFalse(Files.exists(root.resolve("qraft-controller/docker-entrypoint.sh")),
                "the obsolete controller-only entrypoint must not return");
        for (String obsolete : List.of("qraft-agent/Dockerfile", "qraft-runtime/Dockerfile")) {
            assertFalse(Files.exists(root.resolve(obsolete)), obsolete + ": docker/Dockerfile is the one image");
        }

        for (String relativePath : BUILD_COMPOSE_FILES) {
            String compose = Files.readString(root.resolve(relativePath));
            assertTrue(compose.contains("dockerfile: docker/Dockerfile"), relativePath);
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
                "src/test/resources/docker-compose-build-image.yml"));
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
        Path root = Path.of("").toAbsolutePath();
        String compose = Files.readString(root.resolve(
                "docker/compose/docker-compose-single-server.yml"));

        assertTrue(compose.contains("command: [\"server\", \"--config\", \"/etc/qraft/server.json\"]"));
        assertTrue(compose.contains("command: [\"client\"]"));
        assertTrue(compose.contains("../config/client.json:/etc/qraft/client.json:ro"));
        assertEquals(2, occurrences(compose, "dockerfile: docker/Dockerfile"));
    }

    @Test
    void runtimeDeploymentsKeepHttpAndRaftOnSeparatePorts() throws IOException {
        Path root = Path.of("").toAbsolutePath();
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
        Path root = Path.of("").toAbsolutePath();
        String dockerfile = Files.readString(root.resolve("docker/Dockerfile"));
        assertTrue(dockerfile.startsWith("FROM sapmachine:27-jre-alpine"));
        assertTrue(dockerfile.contains("COPY target/qraft.jar /app/qraft.jar"));
        assertTrue(dockerfile.contains("COPY docker/docker-entrypoint.sh /docker-entrypoint.sh"));
        assertFalse(dockerfile.contains("*.jar"));
        assertFalse(dockerfile.contains("FROM maven:"));
        assertFalse(dockerfile.contains("RUN mvn"));
        assertFalse(dockerfile.contains("COPY --from="));

        String dockerignore = Files.readString(root.resolve(".dockerignore"));
        List<String> dockerignoreLines = dockerignore.lines().toList();
        int includeDirectory = dockerignoreLines.indexOf("!target/");
        int excludeDirectoryContents = dockerignoreLines.indexOf("target/*");
        int includeRuntimeJar = dockerignoreLines.indexOf("!target/qraft.jar");
        assertTrue(includeDirectory >= 0);
        assertTrue(excludeDirectoryContents > includeDirectory);
        assertTrue(includeRuntimeJar > excludeDirectoryContents);
        int excludeDocker = dockerignoreLines.indexOf("docker/");
        assertTrue(dockerignoreLines.indexOf("!docker/docker-entrypoint.sh") > excludeDocker,
                "the image copies its entrypoint from the otherwise excluded docker/ directory");
    }

    @Test
    void clusterLaunchersBuildTheRuntimeJarOnTheHostFirst() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        String powershellBuild = Files.readString(root.resolve("docker/build-runtime.ps1"));
        assertTrue(powershellBuild.contains("mvn"));
        assertTrue(powershellBuild.contains("package"));
        assertFalse(powershellBuild.contains("-pl"));
        assertTrue(powershellBuild.contains("-DskipTests"));
        assertFalse(powershellBuild.contains("-Dmaven.test.skip=true"));
        assertFalse(powershellBuild.contains("docker"));

        String shellBuild = Files.readString(root.resolve("docker/build-runtime.sh"));
        assertTrue(shellBuild.contains("mvn"));
        assertTrue(shellBuild.contains("package -DskipTests"));
        assertFalse(shellBuild.contains("-pl"));
        assertTrue(shellBuild.contains("-DskipTests"));
        assertFalse(shellBuild.contains("-Dmaven.test.skip=true"));
        assertFalse(shellBuild.contains("docker"));

        assertTrue(Files.readString(root.resolve("docker/start.ps1"))
                .contains("build-runtime.ps1"));
        assertTrue(Files.readString(root.resolve("docker/start.ps1")).contains("\"servers\" {"),
                "the PowerShell dispatcher must accept the servers command its help documents");
        assertTrue(Files.readString(root.resolve("docker/start.sh"))
                .contains("build-runtime.sh"));
        assertTrue(Files.readString(root.resolve("docker/start-quick.ps1"))
                .contains("build-runtime.ps1"));
        assertTrue(Files.readString(root.resolve("docker/start-quick.sh"))
                .contains("build-runtime.sh"));
    }

    @Test
    void dockerIntegrationTestsRequireTheHostBuiltRuntimeJar() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        String sharedCluster = Files.readString(root.resolve(
                "src/test/java/dev/mars/qraft/raft/SharedDockerClusterFixture.java"));
        assertTrue(sharedCluster.contains("target/qraft.jar"));
        assertTrue(sharedCluster.contains("assertRuntimeJarIsCurrent"));
        assertFalse(sharedCluster.contains("isImageCached"));
    }

    @Test
    void prebuiltDockerClustersPersistRaftStateAndExerciseSnapshots() throws IOException {
        Path root = Path.of("").toAbsolutePath();
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
    void runtimeImagePassesTheModeAndConfigurationThroughItsEntrypoint() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        String entrypoint = Files.readString(root.resolve("docker/docker-entrypoint.sh"));
        assertTrue(entrypoint.contains("\"$@\""));
        assertFalse(entrypoint.contains("QRAFT_"));
        assertFalse(entrypoint.contains("AGENT_"));

        String dockerfile = Files.readString(root.resolve("docker/Dockerfile"));
        assertFalse(dockerfile.contains("ENV "));
        assertTrue(Files.readString(root.resolve(".gitattributes"))
                .contains("*.sh text eol=lf"));
    }

    @Test
    void documentedClientConfigurationIsAValidCompleteExample() {
        Path root = Path.of("").toAbsolutePath();
        AgentConfiguration configuration = AgentConfiguration.fromFile(
                root.resolve("docker/config/client.json"));

        assertEquals("agent-example", configuration.getAgentId());
        assertTrue(configuration.getControllerUrls().size() == 1);
        assertTrue(configuration.getServices().size() == 1);
        assertEquals("web", configuration.getServices().getFirst().id());
    }

    @Test
    void agentAcceptanceProfilesRunTheirHealthChecksAgainstEveryServer() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        Map<String, String> composeByProfile = Map.of(
                "agent.json", "docker-compose-3node-agent-prebuilt.yml",
                "agent-restart.json", "docker-compose-3node-agent-restart-prebuilt.yml");
        for (Map.Entry<String, String> profile : composeByProfile.entrySet()) {
            AgentConfiguration configuration = AgentConfiguration.fromFile(
                    root.resolve("docker/config/agent-acceptance/" + profile.getKey()));
            assertTrue(configuration.getControllerUrls().size() == 3, profile.getKey());
            assertTrue(configuration.getHealthChecks().size() == 2, profile.getKey());
            String compose = Files.readString(root.resolve(
                    "src/test/resources/" + profile.getValue()));
            assertTrue(compose.contains("command: [\"client\", \"--config\", \"/etc/qraft/client.json\"]"));
            assertTrue(compose.contains("agent-acceptance/" + profile.getKey() + ":/etc/qraft/client.json:ro"),
                    profile.getValue());
        }
    }

    @Test
    void theRestartProfileLeavesMarginsOfSeveralSecondsForContainerAndJvmStart() {
        Path root = Path.of("").toAbsolutePath();
        AgentConfiguration restart = AgentConfiguration.fromFile(
                root.resolve("docker/config/agent-acceptance/agent-restart.json"));

        for (var check : restart.getHealthChecks()) {
            assertTrue(check.ttl().compareTo(java.time.Duration.ofSeconds(15)) >= 0, check.checkId());
        }
        assertTrue(restart.getHealthChecks().stream().anyMatch(check ->
                check.deregisterAfter().compareTo(java.time.Duration.ofSeconds(30)) >= 0));
        assertTrue(restart.getContactFreshnessMs() <= 5_000,
                "an outage becomes visible as unreadiness well before the checks' TTL");
    }

    @Test
    void qraftDeploymentArtifactsDoNotConfigureThroughEnvironmentVariables() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        List<Path> roots = List.of(root.resolve("src/main"), root.resolve("docker/compose"),
                root.resolve("docker/Dockerfile"), root.resolve("docker/docker-entrypoint.sh"));
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
    void noDockerfileBuildsProjectArtifacts() throws IOException {
        Path root = Path.of("").toAbsolutePath();
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

    @Test
    void deploymentServiceDnsConfigurationsAndTelemetryUseServerNames() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        assertTrue(Files.exists(root.resolve("docker/compose/docker-compose-single-server.yml")));
        assertTrue(Files.exists(root.resolve("docker/compose/docker-compose-server-first.yml")));
        for (String profile : List.of("three-node", "five-node", "three-node-acceptance",
                "five-node-acceptance", "three-node-observability")) {
            Path configuration = root.resolve("docker/config/" + profile + "/server1.json");
            assertTrue(Files.exists(configuration), configuration.toString());
            String json = Files.readString(configuration);
            assertTrue(json.contains("server1"), configuration.toString());
            assertFalse(json.contains("controller"), configuration.toString());
        }
        for (String artifact : List.of("src/test/resources/docker-compose-3node-prebuilt.yml",
                "docker/compose/prometheus-cluster.yml", "docker/compose/otel-collector-cluster-config.yaml")) {
            String content = Files.readString(root.resolve(artifact));
            assertTrue(content.contains("server1"), artifact);
            assertFalse(content.contains("controller"), artifact);
        }
        String dashboard = Files.readString(root.resolve(
                "docker/compose/grafana/provisioning/dashboards/json/qraft-server.json"));
        assertTrue(dashboard.contains("qraft-servers-compose"));
        assertFalse(dashboard.contains("controller"));
        AgentConfiguration client = AgentConfiguration.fromFile(root.resolve("docker/config/client.json"));
        assertTrue(client.getControllerUrls().stream().allMatch(url -> url.getHost().startsWith("server")));
    }

    @Test
    void dockerFixturesConstructTheRenamedServiceDns() throws IOException {
        Path directory = Path.of("src/test/java/dev/mars/qraft/raft");
        try (var paths = Files.list(directory)) {
            for (Path source : paths.filter(path -> path.getFileName().toString().startsWith("Docker")
                    || path.getFileName().toString().equals("SharedDockerClusterFixture.java")).toList()) {
                String content = Files.readString(source);
                assertFalse(content.contains("\"controller\" +")
                        || content.contains("\"http://controller\" +"), source.toString());
            }
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("docker")
    void everyDocumentedComposeModelIsValid() throws Exception {
        try (var paths = Files.list(Path.of("docker/compose"))) {
            for (Path compose : paths.filter(path -> path.getFileName().toString().startsWith("docker-compose-")
                    && path.toString().endsWith(".yml")).sorted().toList()) {
                Process process = new ProcessBuilder("docker", "compose", "-f", compose.toString(),
                        "config", "--quiet").redirectErrorStream(true).start();
                try {
                    org.junit.jupiter.api.Assertions.assertTrue(process.waitFor(30,
                            java.util.concurrent.TimeUnit.SECONDS), compose + ": compose validation timed out");
                    String output = new String(process.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    System.out.print(output);
                    assertEquals(0, process.exitValue(), compose + ": " + output);
                } finally {
                    if (process.isAlive()) {
                        process.destroyForcibly();
                        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                    }
                }
            }
        }
    }
}

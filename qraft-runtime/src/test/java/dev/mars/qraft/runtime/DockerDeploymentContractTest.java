package dev.mars.qraft.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                assertTrue(compose.contains("QRAFT_MODE=server"), relativePath);
            }
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
            assertTrue(compose.contains("QRAFT_MODE=server"), relativePath);
        }
    }

    @Test
    void runtimeDeploymentsKeepHttpAndRaftOnSeparatePorts() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        for (String relativePath : BUILD_COMPOSE_FILES) {
            if (relativePath.endsWith("docker-compose-build-image.yml")) {
                continue;
            }
            String compose = Files.readString(root.resolve(relativePath));
            assertFalse(compose.contains("QRAFT_RAFT_PORT=8080"), relativePath);
        }
    }

    @Test
    void runtimeImageOnlyPackagesTheHostBuiltArtifact() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String dockerfile = Files.readString(root.resolve("qraft-runtime/Dockerfile"));
        assertTrue(dockerfile.startsWith("FROM eclipse-temurin:25-jre-alpine"));
        assertTrue(dockerfile.contains(
                "COPY qraft-runtime/target/qraft-runtime.jar /app/qraft.jar"));
        assertFalse(dockerfile.contains("*.jar"));
        assertFalse(dockerfile.contains("FROM maven:"));
        assertFalse(dockerfile.contains("RUN mvn"));
        assertFalse(dockerfile.contains("COPY --from="));

        String agentDockerfile = Files.readString(root.resolve("qraft-agent/Dockerfile"));
        assertTrue(agentDockerfile.startsWith("FROM eclipse-temurin:25-jre-alpine"));
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
    void agentContainerChecksTheControllerRootHealthEndpoint() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String entrypoint = Files.readString(root.resolve("qraft-agent/docker-entrypoint.sh"));
        assertTrue(entrypoint.contains("CONTROLLER_HEALTH_URL"));
        assertTrue(entrypoint.contains("${controller_base%/api/v1}/health"));
        assertFalse(entrypoint.contains("curl -f \"$CONTROLLER_URL/health\""));

        String dockerfile = Files.readString(root.resolve("qraft-agent/Dockerfile"));
        assertTrue(dockerfile.contains("sed -i 's/\\r$//' /docker-entrypoint.sh"));
        assertTrue(Files.readString(root.resolve(".gitattributes"))
                .contains("*.sh text eol=lf"));
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
}

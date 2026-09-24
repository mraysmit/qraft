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

import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.DockerClientFactory;
import com.github.dockerjava.api.model.ContainerNetwork;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Shared Docker cluster containers for integration tests.
 *
 * <p>Uses the singleton pattern to build the Docker image once and share
 * running 3-node and 5-node clusters across all test classes. This avoids
 * redundant Docker image builds and container startups.</p>
 *
 * <h3>Performance impact:</h3>
 * <ul>
 *   <li>The runtime JAR is built by Maven on the host before these tests run</li>
 *   <li>Image packaged ONCE via {@code docker compose build} (not per test class)</li>
 *   <li>Clusters started ONCE and reused across DockerRaftClusterTest,
 *       ConfigurableRaftClusterTest, AdvancedNetworkTest, NetworkPartitionTest</li>
 *   <li>Pre-built compose files use {@code image:} -- no build context transfer on start</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 1.0
 * @since 2025-08-20
 */
public final class SharedDockerCluster {

    private static final Set<String> LIFECYCLE_ACTIONS = Set.of("stop", "kill", "start");

    private static final Logger logger = Logger.getLogger(SharedDockerCluster.class.getName());

    private static volatile boolean imageBuilt = false;
    private static ComposeContainer threeNodeCluster;
    private static ComposeContainer fiveNodeCluster;
    private static final Map<String, Map<String, ContainerNetwork>> DISCONNECTED_NETWORKS =
            new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(
                new Thread(SharedDockerCluster::shutdown, "SharedDockerCluster-shutdown"));
    }

    private SharedDockerCluster() {}

    /**
     * Returns the shared 3-node cluster, building the image and starting containers on first call.
     */
    public static synchronized ComposeContainer getThreeNodeCluster() {
        if (threeNodeCluster == null) {
            ensureImageBuilt();

            logger.info("Starting shared 3-node cluster...");
            threeNodeCluster = new ComposeContainer(
                    new File("src/test/resources/docker-compose-3node-prebuilt.yml"))
                    .withExposedService("controller1", 8080, Wait.forHttp("/health").forStatusCode(200))
                    .withExposedService("controller2", 8080, Wait.forHttp("/health").forStatusCode(200))
                    .withExposedService("controller3", 8080, Wait.forHttp("/health").forStatusCode(200))
                    .withStartupTimeout(Duration.ofSeconds(90));

            threeNodeCluster.start();
            logger.info("Shared 3-node cluster started successfully");
        }
        return threeNodeCluster;
    }

    /** Starts a disposable three-node cluster for tests that intentionally poison a volume. */
    public static ComposeContainer startIsolatedThreeNodeCluster() {
        ensureImageBuilt();
        ComposeContainer cluster = new ComposeContainer(
                new File("src/test/resources/docker-compose-3node-prebuilt.yml"))
                .withExposedService("controller1", 8080, Wait.forHttp("/health").forStatusCode(200))
                .withExposedService("controller2", 8080, Wait.forHttp("/health").forStatusCode(200))
                .withExposedService("controller3", 8080, Wait.forHttp("/health").forStatusCode(200))
                .withStartupTimeout(Duration.ofSeconds(90));
        cluster.start();
        return cluster;
    }

    /**
     * Returns the shared 5-node cluster, building the image and starting containers on first call.
     */
    public static synchronized ComposeContainer getFiveNodeCluster() {
        if (fiveNodeCluster == null) {
            ensureImageBuilt();

            logger.info("Starting shared 5-node cluster...");
            fiveNodeCluster = new ComposeContainer(
                    new File("src/test/resources/docker-compose-5node-prebuilt.yml"))
                    .withExposedService("controller1", 8080, Wait.forHttp("/health").forStatusCode(200))
                    .withExposedService("controller2", 8080, Wait.forHttp("/health").forStatusCode(200))
                    .withExposedService("controller3", 8080, Wait.forHttp("/health").forStatusCode(200))
                    .withExposedService("controller4", 8080, Wait.forHttp("/health").forStatusCode(200))
                    .withExposedService("controller5", 8080, Wait.forHttp("/health").forStatusCode(200))
                    .withStartupTimeout(Duration.ofSeconds(90));

            fiveNodeCluster.start();
            logger.info("Shared 5-node cluster started successfully");
        }
        return fiveNodeCluster;
    }

    /**
     * Returns HTTP endpoints for nodes in the given cluster.
     */
    public static List<String> getNodeEndpoints(ComposeContainer cluster, int nodeCount) {
        List<String> endpoints = new ArrayList<>();
        for (int i = 1; i <= nodeCount; i++) {
            Integer port = cluster.getServicePort("controller" + i, 8080);
            endpoints.add("http://localhost:" + port);
        }
        return endpoints;
    }

    /** Stops one compose service without removing its container or volume. */
    public static synchronized void stopContainer(ComposeContainer cluster, String serviceName) {
        runDockerLifecycleCommand("stop", containerId(cluster, serviceName),
                SharedDockerCluster::runCommand);
    }

    /** Abruptly kills one compose service without removing its container or volume. */
    public static synchronized void killContainer(ComposeContainer cluster, String serviceName) {
        runDockerLifecycleCommand("kill", containerId(cluster, serviceName),
                SharedDockerCluster::runCommand);
    }

    /** Starts a previously stopped or killed compose service. */
    public static synchronized void startContainer(ComposeContainer cluster, String serviceName) {
        runDockerLifecycleCommand("start", containerId(cluster, serviceName),
                SharedDockerCluster::runCommand);
    }

    /** Stops every node and starts the same containers again, retaining named volumes. */
    public static synchronized void restartCluster(ComposeContainer cluster, int nodeCount) {
        List<String> containerIds = new ArrayList<>();
        for (int i = 1; i <= nodeCount; i++) {
            containerIds.add(containerId(cluster, "controller" + i));
        }
        for (String containerId : containerIds) {
            runDockerLifecycleCommand("stop", containerId, SharedDockerCluster::runCommand);
        }
        for (String containerId : containerIds) {
            runDockerLifecycleCommand("start", containerId, SharedDockerCluster::runCommand);
        }
    }

    /** Overwrites one byte in a stopped container's durable volume without recreating it. */
    public static synchronized void overwriteVolumeFileByte(
            ComposeContainer cluster, String serviceName, String file, long offset) {
        if (file == null || !file.startsWith("/app/data/") || file.contains("..")) {
            throw new IllegalArgumentException("file must be inside /app/data");
        }
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
        String script = "printf '\\000' | dd of='" + file + "' bs=1 seek=" + offset
                + " count=1 conv=notrunc";
        try {
            runCommand(List.of("docker", "run", "--rm", "--volumes-from",
                    containerId(cluster, serviceName), "alpine:3.20", "sh", "-c", script));
        } catch (Exception error) {
            throw new IllegalStateException("Could not overwrite " + file + " in " + serviceName, error);
        }
    }

    /** Runs a second controller against an active controller's volume and captures its exit. */
    public static DockerCommandResult runStorageLockContender(
            ComposeContainer cluster, String ownerService) {
        Path config = null;
        try {
            config = Files.createTempFile("qraft-lock-contender-", ".json");
            Files.writeString(config, """
                    {"version":1,"server":{"id":"lock-contender","http":{"port":8080},
                    "apiGrpcPort":10080,"raft":{"port":9080,
                    "nodes":{"lock-contender":"localhost:9080"},
                    "storage":{"type":"raftlog","path":"/app/data","fsync":true}},
                    "telemetry":{"enabled":false}}}
                    """);
            List<String> command = List.of(
                    "docker", "run", "--rm", "--volumes-from", containerId(cluster, ownerService),
                    "--mount", "type=bind,source=" + config.toAbsolutePath()
                            + ",target=/etc/qraft/server.json,readonly",
                    "qraft-runtime:test", "server", "--config", "/etc/qraft/server.json");
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            return new DockerCommandResult(process.waitFor(), output);
        } catch (Exception error) {
            throw new IllegalStateException("Could not run storage-lock contender", error);
        } finally {
            if (config != null) {
                try {
                    Files.deleteIfExists(config);
                } catch (IOException ignored) {
                    // The temporary file is best-effort cleanup after Docker releases the mount.
                }
            }
        }
    }

    public record DockerCommandResult(int exitCode, String output) { }

    /**
     * Disconnects a running service from all of its Docker networks. The container
     * is restarted while disconnected because Docker can otherwise leave an
     * already-established TCP connection usable after a network disconnect.
     */
    public static synchronized void isolateContainerNetwork(
            ComposeContainer cluster, String serviceName) {
        var container = cluster.getContainerByServiceName(serviceName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown compose service: " + serviceName));
        String containerId = container.getContainerId();
        Map<String, ContainerNetwork> networks = Map.copyOf(
                container.getContainerInfo().getNetworkSettings().getNetworks());
        if (networks.isEmpty()) throw new IllegalStateException(serviceName + " has no Docker networks");
        for (String network : networks.keySet()) {
            DockerClientFactory.instance().client().disconnectFromNetworkCmd()
                    .withContainerId(containerId).withNetworkId(network).exec();
        }
        DISCONNECTED_NETWORKS.put(containerId, networks);
        DockerClientFactory.instance().client().restartContainerCmd(containerId).exec();
    }

    /** Reconnects a service to the exact Docker networks and aliases it previously used. */
    public static synchronized void restoreContainerNetwork(
            ComposeContainer cluster, String serviceName) {
        var container = cluster.getContainerByServiceName(serviceName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown compose service: " + serviceName));
        String containerId = container.getContainerId();
        Map<String, ContainerNetwork> networks = DISCONNECTED_NETWORKS.remove(containerId);
        if (networks != null) {
            networks.forEach((network, settings) ->
                    DockerClientFactory.instance().client().connectToNetworkCmd()
                            .withContainerId(containerId).withNetworkId(network)
                            .withContainerNetwork(settings).exec());
        }
    }

    @FunctionalInterface
    interface DockerCommandRunner {
        void run(List<String> command) throws Exception;
    }

    static void runDockerLifecycleCommand(
            String action, String containerId, DockerCommandRunner commandRunner) {
        if (!LIFECYCLE_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("Unsupported Docker lifecycle action: " + action);
        }
        if (containerId == null || containerId.isBlank()) {
            throw new IllegalArgumentException("containerId is required");
        }
        try {
            commandRunner.run(List.of("docker", action, containerId));
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Docker " + action + " failed for container " + containerId, error);
        }
    }

    private static String containerId(ComposeContainer cluster, String serviceName) {
        return cluster.getContainerByServiceName(serviceName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown compose service: " + serviceName))
                .getContainerId();
    }

    private static void runCommand(List<String> command) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(String.join(" ", command)
                    + " exited with " + exitCode + ": " + output.toString().trim());
        }
    }

    /**
     * Packages the host-built runtime JAR as the qraft-runtime:test Docker image.
     */
    private static synchronized void ensureImageBuilt() {
        if (imageBuilt) return;

        Path repositoryRoot = Path.of("..").toAbsolutePath().normalize();
        Path runtimeJar = repositoryRoot.resolve("qraft-runtime/target/qraft-runtime.jar");
        assertRuntimeJarIsCurrent(repositoryRoot, runtimeJar);

        File buildComposeFile = new File("src/test/resources/docker-compose-build-image.yml");
        if (!buildComposeFile.exists()) {
            throw new RuntimeException(
                    "Build compose file not found: " + buildComposeFile.getAbsolutePath()
                    + " -- ensure working directory is the qraft-controller module root");
        }

        logger.info("Building Docker image via: " + buildComposeFile.getAbsolutePath());
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "compose",
                    "-f", buildComposeFile.getAbsolutePath(),
                    "build");
            pb.environment().put("DOCKER_BUILDKIT", "1");
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Drain output to prevent blocking
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.fine("[Docker Build] " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException(
                        "Docker image build failed with exit code: " + exitCode);
            }

            imageBuilt = true;
            logger.info("Docker image built successfully: qraft-runtime:test");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Docker image", e);
        }
    }

    static void assertRuntimeJarIsCurrent(Path repositoryRoot, Path runtimeJar) {
        if (!Files.isRegularFile(runtimeJar)) {
            throw new IllegalStateException(
                    "Host-built runtime JAR not found: " + runtimeJar.toAbsolutePath()
                    + " -- run docker/build-runtime.ps1 or docker/build-runtime.sh "
                    + "from the repository before Docker integration tests");
        }

        try {
            FileTime jarTime = Files.getLastModifiedTime(runtimeJar);
            Path newestInput = newestProductionBuildInput(repositoryRoot);
            if (newestInput != null
                    && Files.getLastModifiedTime(newestInput).compareTo(jarTime) > 0) {
                throw new IllegalStateException(
                        "Host-built runtime JAR is older than build input "
                        + repositoryRoot.relativize(newestInput)
                        + " -- rebuild it with docker/build-runtime.ps1 or docker/build-runtime.sh "
                        + "before Docker integration tests");
            }
        } catch (IOException error) {
            throw new IllegalStateException("Could not verify runtime JAR freshness", error);
        }
    }

    private static Path newestProductionBuildInput(Path repositoryRoot) throws IOException {
        List<Path> candidates = new ArrayList<>();
        Path rootPom = repositoryRoot.resolve("pom.xml");
        if (Files.isRegularFile(rootPom)) candidates.add(rootPom);

        try (Stream<Path> children = Files.list(repositoryRoot)) {
            for (Path module : children.filter(Files::isDirectory).toList()) {
                Path modulePom = module.resolve("pom.xml");
                if (Files.isRegularFile(modulePom)) candidates.add(modulePom);

                Path productionSources = module.resolve("src/main");
                if (!Files.isDirectory(productionSources)) continue;
                try (Stream<Path> sources = Files.walk(productionSources)) {
                    sources.filter(Files::isRegularFile).forEach(candidates::add);
                }
            }
        }

        return candidates.stream()
                .max(Comparator.comparing(path -> lastModifiedTime(path).toInstant()))
                .orElse(null);
    }

    private static FileTime lastModifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException error) {
            throw new IllegalStateException("Could not read build-input timestamp: " + path, error);
        }
    }

    private static void shutdown() {
        logger.info("Shutting down shared Docker clusters...");
        if (threeNodeCluster != null) {
            try { threeNodeCluster.stop(); } catch (Exception e) { /* ignore */ }
        }
        if (fiveNodeCluster != null) {
            try { fiveNodeCluster.stop(); } catch (Exception e) { /* ignore */ }
        }
    }
}

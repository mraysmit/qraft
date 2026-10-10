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

package dev.mars.qraft.raft;

import dev.mars.qraft.testing.fault.DockerLogCaptureHelper;
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
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Test fixture that manages shared Docker cluster containers for integration tests.
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
public final class SharedDockerClusterFixture {

    private static final Set<String> LIFECYCLE_ACTIONS = Set.of("stop", "kill", "start", "pause", "unpause");

    private static final Logger logger = Logger.getLogger(SharedDockerClusterFixture.class.getName());

    private static volatile boolean imageBuilt = false;
    private static ComposeContainer threeNodeCluster;
    private static final Map<ComposeContainer, List<String>> ACTIVE_CLUSTERS = new IdentityHashMap<>();
    private static final Map<String, Map<String, ContainerNetwork>> DISCONNECTED_NETWORKS =
            new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(
                new Thread(SharedDockerClusterFixture::shutdown, "SharedDockerClusterFixture-shutdown"));
    }

    private SharedDockerClusterFixture() {}

    /**
     * Returns the shared 3-node cluster, building the image and starting containers on first call.
     */
    public static synchronized ComposeContainer getThreeNodeCluster() {
        if (threeNodeCluster == null) {
            ensureImageBuilt();

            logger.info("Starting shared 3-node cluster...");
            threeNodeCluster = new ComposeContainer(
                    new File("src/test/resources/docker-compose-3node-prebuilt.yml"))
                    .withExposedService("server1", 8080, Wait.forHttp("/health/live").forStatusCode(200))
                    .withExposedService("server2", 8080, Wait.forHttp("/health/live").forStatusCode(200))
                    .withExposedService("server3", 8080, Wait.forHttp("/health/live").forStatusCode(200))
                    .withStartupTimeout(Duration.ofSeconds(90));

            threeNodeCluster.start();
            registerCluster(threeNodeCluster, List.of("server1", "server2", "server3"));
            logger.info("Shared 3-node cluster started successfully");
        }
        return threeNodeCluster;
    }

    /** Starts a disposable three-node cluster for tests that intentionally poison a volume. */
    public static ComposeContainer startIsolatedThreeNodeCluster() {
        ensureImageBuilt();
        ComposeContainer cluster = new ComposeContainer(
                new File("src/test/resources/docker-compose-3node-prebuilt.yml"))
                .withExposedService("server1", 8080, Wait.forHttp("/health/live").forStatusCode(200))
                .withExposedService("server2", 8080, Wait.forHttp("/health/live").forStatusCode(200))
                .withExposedService("server3", 8080, Wait.forHttp("/health/live").forStatusCode(200))
                .withStartupTimeout(Duration.ofSeconds(90));
        cluster.start();
        registerCluster(cluster, List.of("server1", "server2", "server3"));
        return cluster;
    }

    /**
     * Starts a disposable three-node cluster with one client-mode {@code client} container using the expiry
     * profile: HTTP and TCP checks with a 5-second TTL, so expiry and deregistration are quick to observe.
     */
    public static ComposeContainer startIsolatedThreeNodeClusterWithClient() {
        return startIsolatedThreeNodeClusterWithClient("docker-compose-3node-client-prebuilt.yml");
    }

    /**
     * Starts a disposable three-node cluster with one client-mode {@code client} container using the restart
     * profile: a 15-second check TTL, a 30-second deregistration delay, and 5-second server contact
     * freshness, which leave margins of several seconds for container and JVM start.
     */
    public static ComposeContainer startIsolatedThreeNodeClusterWithRestartClient() {
        return startIsolatedThreeNodeClusterWithClient("docker-compose-3node-client-restart-prebuilt.yml");
    }

    /**
     * Starts a disposable three-node cluster with one client-mode {@code client} container using the long-TTL
     * profile: a 10-minute check TTL and no deregistration delay, so nothing a frozen client registered
     * expires or is removed before the test's own time limit.
     */
    public static ComposeContainer startIsolatedThreeNodeClusterWithLongTtlClient() {
        return startIsolatedThreeNodeClusterWithClient("docker-compose-3node-client-long-ttl-prebuilt.yml");
    }

    private static ComposeContainer startIsolatedThreeNodeClusterWithClient(String composeFile) {
        ensureImageBuilt();
        ComposeContainer cluster = new ComposeContainer(new File("src/test/resources/" + composeFile))
                .withExposedService("server1", 8080, Wait.forHttp("/health/live").forStatusCode(200))
                .withExposedService("server2", 8080, Wait.forHttp("/health/live").forStatusCode(200))
                .withExposedService("server3", 8080, Wait.forHttp("/health/live").forStatusCode(200))
                .withExposedService("client", 8080, Wait.forHttp("/health/live").forStatusCode(200))
                .withStartupTimeout(Duration.ofSeconds(90));
        cluster.start();
        registerCluster(cluster, List.of("server1", "server2", "server3", "client"));
        return cluster;
    }

    /**
     * Returns HTTP endpoints for nodes in the given cluster.
     */
    public static List<String> getNodeEndpoints(ComposeContainer cluster, int nodeCount) {
        List<String> endpoints = new ArrayList<>();
        for (int i = 1; i <= nodeCount; i++) {
            Integer port = cluster.getServicePort("server" + i, 8080);
            endpoints.add("http://localhost:" + port);
        }
        return endpoints;
    }

    /** HTTP endpoint of one compose service's port 8080. */
    public static String getServiceEndpoint(ComposeContainer cluster, String serviceName) {
        return "http://localhost:" + cluster.getServicePort(serviceName, 8080);
    }

    /** Freezes every process in one compose service without stopping, restarting, or removing it. */
    public static synchronized void pauseContainer(ComposeContainer cluster, String serviceName) {
        runDockerLifecycleCommand("pause", containerId(cluster, serviceName), SharedDockerClusterFixture::runCommand);
    }

    /** Resumes a service frozen by {@link #pauseContainer}. */
    public static synchronized void unpauseContainer(ComposeContainer cluster, String serviceName) {
        runDockerLifecycleCommand("unpause", containerId(cluster, serviceName), SharedDockerClusterFixture::runCommand);
    }

    /** Stops one compose service without removing its container or volume. */
    public static synchronized void stopContainer(ComposeContainer cluster, String serviceName) {
        runDockerLifecycleCommand("stop", containerId(cluster, serviceName),
                SharedDockerClusterFixture::runCommand);
    }

    /** Abruptly kills one compose service without removing its container or volume. */
    public static synchronized void killContainer(ComposeContainer cluster, String serviceName) {
        runDockerLifecycleCommand("kill", containerId(cluster, serviceName),
                SharedDockerClusterFixture::runCommand);
    }

    /** Starts a previously stopped or killed compose service. */
    public static synchronized void startContainer(ComposeContainer cluster, String serviceName) {
        runDockerLifecycleCommand("start", containerId(cluster, serviceName),
                SharedDockerClusterFixture::runCommand);
    }

    /** Stops every node and starts the same containers again, retaining named volumes. */
    public static synchronized void restartCluster(ComposeContainer cluster, int nodeCount) {
        List<String> containerIds = new ArrayList<>();
        for (int i = 1; i <= nodeCount; i++) {
            containerIds.add(containerId(cluster, "server" + i));
        }
        for (String containerId : containerIds) {
            runDockerLifecycleCommand("stop", containerId, SharedDockerClusterFixture::runCommand);
        }
        for (String containerId : containerIds) {
            runDockerLifecycleCommand("start", containerId, SharedDockerClusterFixture::runCommand);
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

    /** Empties a stopped container's durable data directory without recreating its volume, as a lost disk would. */
    public static synchronized void wipeDataDirectory(ComposeContainer cluster, String serviceName) {
        try {
            runCommand(List.of("docker", "run", "--rm", "--volumes-from",
                    containerId(cluster, serviceName), "alpine:3.20", "sh", "-c",
                    "rm -rf /app/data/* /app/data/.[!.]*"));
        } catch (Exception error) {
            throw new IllegalStateException("Could not wipe the data directory of " + serviceName, error);
        }
    }

    /** Runs a second server against an active server's volume and captures its exit. */
    public static DockerCommandResult runStorageLockContender(
            ComposeContainer cluster, String ownerService) {
        Path config = null;
        Path output = null;
        try {
            config = Files.createTempFile("qraft-lock-contender-", ".json");
            Files.writeString(config, """
                    {"version":1,"server":{"id":"lock-contender","http":{"port":8080},
                    "apiGrpcPort":10080,"raft":{"port":9080,
                    "nodes":{"lock-contender":"localhost:9080"},
                    "storage":{"type":"raftlog","path":"/app/data","fsync":true}},
                    "telemetry":{"enabled":false}}}
                    """);
            makeContainerConfigReadable(config);
            String name = "qraft-lock-contender-" + java.util.UUID.randomUUID();
            List<String> command = List.of(
                    "docker", "run", "--rm", "--name", name, "--volumes-from", containerId(cluster, ownerService),
                    "--mount", "type=bind,source=" + config.toAbsolutePath()
                            + ",target=/etc/qraft/server.json,readonly",
                    "qraft-runtime:test", "server", "--config", "/etc/qraft/server.json");
            output = Files.createTempFile("qraft-lock-contender-", ".log");
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(output.toFile()).start();
            // A contender that wrongly acquired the lock would serve forever: it is bounded, then removed.
            boolean exited = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                new ProcessBuilder("docker", "rm", "-f", name).redirectErrorStream(true).start()
                        .waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            }
            String text = Files.readString(output, java.nio.charset.StandardCharsets.UTF_8);
            DockerLogCaptureHelper.capture("lock-contender-" + name, "lock-contender", text);
            return new DockerCommandResult(exited, exited ? process.exitValue() : -1, text);
        } catch (Exception error) {
            throw new IllegalStateException("Could not run storage-lock contender", error);
        } finally {
            for (Path temporary : new Path[]{config, output}) {
                if (temporary == null) continue;
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Temporary files are best-effort cleanup after Docker releases the mount.
                }
            }
        }
    }

    /** A command's outcome; {@code exited} is false when it was still running at its bound and was removed. */
    public record DockerCommandResult(boolean exited, int exitCode, String output) { }

    /** A client container started beside a compose cluster; {@link #close()} removes it and its configuration. */
    public record DetachedClient(String name, Path config) implements AutoCloseable {
        /** Stops the client gracefully, so it deregisters its services, and waits for it to exit. */
        public void stopGracefully() throws Exception {
            runCommand(List.of("docker", "stop", "--time", "20", name));
        }

        @Override
        public void close() throws Exception {
            try {
                captureDetachedContainer(name);
                new ProcessBuilder("docker", "rm", "-f", name).redirectErrorStream(true).start()
                        .waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                Files.deleteIfExists(config);
            }
        }
    }

    /**
     * Starts a client-mode client on {@code cluster}'s network with a configuration chosen at run time, such as
     * seeds ordered by which server is the leader. It is reachable on that network as {@code alias}.
     */
    public static DetachedClient startDetachedClient(ComposeContainer cluster, String alias, String clientJson)
            throws Exception {
        String network = cluster.getContainerByServiceName("server1")
                .orElseThrow(() -> new IllegalStateException("the cluster has no server1"))
                .getContainerInfo().getNetworkSettings().getNetworks().keySet().iterator().next();
        Path config = Files.createTempFile("qraft-client-", ".json");
        String name = "qraft-client-" + alias + "-" + java.util.UUID.randomUUID();
        try {
            Files.writeString(config, clientJson);
            makeContainerConfigReadable(config);
            runCommand(List.of("docker", "run", "-d", "--name", name, "--network", network,
                    "--network-alias", alias, "--label", "org.testcontainers=true",
                    "--mount", "type=bind,source=" + config.toAbsolutePath() + ",target=/etc/qraft/client.json,readonly",
                    "qraft-runtime:test", "client", "--config", "/etc/qraft/client.json"));
        } catch (Exception failed) {
            Files.deleteIfExists(config);
            throw failed;
        }
        return new DetachedClient(name, config);
    }

    /** Makes a non-secret test configuration readable by the image's non-root user on POSIX hosts. */
    private static void makeContainerConfigReadable(Path config) throws IOException {
        PosixFileAttributeView permissions = Files.getFileAttributeView(config, PosixFileAttributeView.class);
        if (permissions != null) {
            permissions.setPermissions(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
        }
    }

    /**
     * Disconnects a running service from all of its Docker networks. The container
     * is restarted while disconnected because Docker can otherwise leave an
     * already-established TCP connection usable after a network disconnect.
     */
    public static synchronized void isolateContainerNetwork(
            ComposeContainer cluster, String serviceName) {
        String containerId = partitionContainer(cluster, serviceName);
        DockerClientFactory.instance().client().restartContainerCmd(containerId).exec();
    }

    /**
     * Disconnects a running service from every Docker network it uses, without restarting it, and returns
     * its container ID. Its processes keep running but can reach no other service, and published ports
     * stop reaching it; read its state with {@link #execInService}. {@link #restoreContainerNetwork} heals
     * the partition.
     */
    public static synchronized String partitionContainer(ComposeContainer cluster, String serviceName) {
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
        return containerId;
    }

    /** Runs a command inside a running service's container and returns its standard output. */
    public static String execInService(ComposeContainer cluster, String serviceName, String... command)
            throws Exception {
        return cluster.getContainerByServiceName(serviceName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown compose service: " + serviceName))
                .execInContainer(command).getStdout();
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

    /** Captures every registered container's output without stopping it. */
    public static synchronized void captureRunningLogs() {
        ACTIVE_CLUSTERS.forEach(SharedDockerClusterFixture::captureClusterLogs);
    }

    /** Captures a disposable cluster before Testcontainers removes its containers, then stops it. */
    public static synchronized void stopAndCapture(ComposeContainer cluster) {
        captureClusterLogs(cluster, ACTIVE_CLUSTERS.getOrDefault(cluster, List.of()));
        ACTIVE_CLUSTERS.remove(cluster);
        cluster.stop();
    }

    private static void registerCluster(ComposeContainer cluster, List<String> services) {
        ACTIVE_CLUSTERS.put(cluster, List.copyOf(services));
    }

    private static void captureClusterLogs(ComposeContainer cluster, List<String> services) {
        for (String service : services) {
            cluster.getContainerByServiceName(service).ifPresent(container -> DockerLogCaptureHelper.capture(
                    container.getContainerId(), service + "-" + container.getContainerId().substring(0, 12),
                    container.getLogs()));
        }
    }

    private static void captureDetachedContainer(String name) {
        try {
            Process process = new ProcessBuilder("docker", "logs", name).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (process.waitFor() == 0) DockerLogCaptureHelper.capture("detached-" + name, name, output);
        } catch (Exception error) {
            throw new IllegalStateException("Could not capture Docker logs for " + name, error);
        }
    }

    /**
     * Packages the host-built runtime JAR as the qraft-runtime:test Docker image.
     */
    private static synchronized void ensureImageBuilt() {
        if (imageBuilt) return;

        Path repositoryRoot = Path.of("").toAbsolutePath();
        Path runtimeJar = repositoryRoot.resolve("target/qraft.jar");
        assertRuntimeJarIsCurrent(repositoryRoot, runtimeJar);

        File buildComposeFile = new File("src/test/resources/docker-compose-build-image.yml");
        if (!buildComposeFile.exists()) {
            throw new RuntimeException(
                    "Build compose file not found: " + buildComposeFile.getAbsolutePath()
                    + " -- ensure working directory is the repository root");
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

            // Drain output to prevent blocking, keeping the last lines to explain a failure.
            java.util.ArrayDeque<String> lastLines = new java.util.ArrayDeque<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.fine("[Docker Build] " + line);
                    if (lastLines.size() == 40) lastLines.removeFirst();
                    lastLines.addLast(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Docker image build failed with exit code: " + exitCode
                        + "; its last output was:\n" + String.join("\n", lastLines));
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
                    + " -- run mvn install from the repository before the Docker tests (docs/TESTING.md)");
        }

        try {
            FileTime jarTime = Files.getLastModifiedTime(runtimeJar);
            Path newestInput = newestProductionBuildInput(repositoryRoot);
            if (newestInput != null
                    && Files.getLastModifiedTime(newestInput).compareTo(jarTime) > 0) {
                throw new IllegalStateException(
                        "Host-built runtime JAR is older than build input "
                        + repositoryRoot.relativize(newestInput)
                        + " -- run mvn install before the Docker tests (docs/TESTING.md)");
            }
        } catch (IOException error) {
            throw new IllegalStateException("Could not verify runtime JAR freshness", error);
        }
    }

    private static Path newestProductionBuildInput(Path repositoryRoot) throws IOException {
        List<Path> candidates = new ArrayList<>();
        Path rootPom = repositoryRoot.resolve("pom.xml");
        if (Files.isRegularFile(rootPom)) candidates.add(rootPom);

        Path productionSources = repositoryRoot.resolve("src/main");
        if (Files.isDirectory(productionSources)) {
            try (Stream<Path> sources = Files.walk(productionSources)) {
                sources.filter(Files::isRegularFile).forEach(candidates::add);
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
            try { stopAndCapture(threeNodeCluster); } catch (Exception e) { /* ignore */ }
        }
    }
}

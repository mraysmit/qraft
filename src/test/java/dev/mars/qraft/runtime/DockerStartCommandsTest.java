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

import dev.mars.qraft.testing.fault.DockerLogCaptureHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that every start command of the {@code docker/} directory starts what it names, that the servers
 * it starts become ready, and that its stop and clean actions remove exactly what they say.
 *
 * <p>Runs the shell scripts, and on Windows the PowerShell scripts. The commands run in a Compose project
 * of their own and publish their ports on free host ports, so nothing a developer started by hand, and no
 * volume of another project, is touched. A script builds the runtime JAR only when it is out of date, so a
 * run after {@code mvn install} starts no second Maven build.</p>
 *
 * <p>Requires Docker. Excluded from the default build; run with
 * {@code mvn test -Dgroups=docker -Dtest.excludedGroups=}.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-10
 * @version 1.0
 */
@Tag("docker")
@Timeout(value = 20, unit = TimeUnit.MINUTES)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class DockerStartCommandsTest {
    private static final Logger logger = LoggerFactory.getLogger(DockerStartCommandsTest.class);
    private static final String PROJECT = "qraft-start-commands";
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private static final File DOCKER_DIRECTORY = Path.of("docker").toAbsolutePath().toFile();
    private static final String OBSERVABILITY_CLUSTER = "compose/docker-compose-observability-cluster.yml";
    private static final List<String> OBSERVABILITY_CONTAINERS = List.of("qraft-otel-collector", "qraft-tempo",
            "qraft-prometheus", "qraft-loki", "qraft-grafana");
    private static final List<String> OBSERVABILITY_VOLUMES = List.of(PROJECT + "_grafana-data",
            PROJECT + "_loki-data", PROJECT + "_prometheus-data", PROJECT + "_tempo-data");
    /** An error line of Grafana, Loki, Tempo, or Prometheus, or of the collector. */
    private static final Pattern THIRD_PARTY_ERROR = Pattern.compile("\\blevel=error\\b|\\terror\\t");
    /**
     * Tempo 2.3.1 starts the watcher of its metrics generator's write-ahead log with the first span it
     * receives, a fraction of a millisecond after it creates that log and before the log has a segment. The
     * watcher reports this once, tries again, and reports "Done replaying WAL". Only this line is accepted,
     * and only when that report follows.
     */
    private static final Pattern TEMPO_WAL_NOT_YET_WRITTEN = Pattern.compile(
            "component=remote level=error .* msg=\"error tailing WAL\" err=\"failed to find segment for index\"$");
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterAll
    static void removeWhatTheCommandsStarted() throws Exception {
        captureProjectLogs("left-running");
        script("start", "stop");
        script("start-quick", "clean");
        observability("clean");
        run(Duration.ofMinutes(2), "docker", "compose", "-f", OBSERVABILITY_CLUSTER, "down", "-v");
    }

    @Test
    @Order(1)
    void startClusterRunsOneReadyServerWithAReadyClientAndStopRemovesBoth() throws Exception {
        script("start", "cluster");
        awaitReady("qraft-server-dev");
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                .until(() -> exitCode("docker", "exec", "qraft-client-dev", "curl", "-fsS",
                        "http://localhost:8080/health/ready") == 0);

        String status = script("start", "status");
        assertTrue(status.contains("qraft-server-dev") && status.contains("qraft-client-dev"), status);

        captureProjectLogs("start-cluster");
        script("start", "stop");
        assertEquals(List.of(), projectContainers());
    }

    @Test
    @Order(2)
    void startMultinodeRunsThreeReadyServersAndStopKeepsTheirData() throws Exception {
        script("start", "multinode");
        awaitServersReady(3);

        String status = script("start", "status");
        assertTrue(status.contains("qraft-server1") && status.contains("qraft-server3"), status);

        captureProjectLogs("start-multinode");
        script("start", "stop");
        assertEquals(List.of(), projectContainers());
        assertEquals(serverVolumes(3), projectVolumes(), "stop keeps the data volumes");
    }

    @Test
    @Order(3)
    void startQuickRunsThreeServersOnTheDataOfTheClusterBeforeAndCleanRemovesIt() throws Exception {
        script("start-quick", "cluster", "3node");
        awaitServersReady(3);

        String status = script("start-quick", "status");
        assertTrue(status.contains("qraft-server1") && status.contains("qraft-server3"), status);

        captureProjectLogs("start-quick-3node");
        script("start-quick", "stop");
        assertEquals(List.of(), projectContainers());
        assertEquals(serverVolumes(3), projectVolumes(), "stop keeps the data volumes");

        script("start-quick", "clean");
        assertEquals(List.of(), projectVolumes(), "clean removes the data volumes");
    }

    @Test
    @Order(4)
    void startQuickRunsFiveReadyServers() throws Exception {
        script("start-quick", "cluster", "5node");
        awaitServersReady(5);

        captureProjectLogs("start-quick-5node");
        script("start-quick", "clean");
        assertEquals(List.of(), projectContainers());
        assertEquals(List.of(), projectVolumes());
    }

    @Test
    @Order(5)
    void startQuickRunsTheFiveServersOfTheNetworkTestCluster() throws Exception {
        script("start-quick", "cluster", "network-test");
        awaitServersReady(5);

        captureProjectLogs("start-quick-network-test");
        script("start-quick", "clean");
        assertEquals(List.of(), projectContainers());
        assertEquals(List.of(), projectVolumes());
    }

    @Test
    @Order(6)
    void startObservabilityRunsTheStackWithoutAnErrorAndDownKeepsItsDataUntilClean() throws Exception {
        observability("up");
        awaitHealthy("qraft-grafana");
        awaitOk("qraft-prometheus", 9090, "/-/ready");

        String status = observability("status");
        for (String container : OBSERVABILITY_CONTAINERS) {
            assertTrue(status.contains(container), container + " is not in the status:\n" + status);
        }
        assertNoThirdPartyError();

        captureProjectLogs("start-observability");
        observability("down");
        assertEquals(List.of(), projectContainers());
        assertEquals(OBSERVABILITY_VOLUMES, projectVolumes(), "down keeps the data volumes");

        observability("clean");
        assertEquals(List.of(), projectVolumes(), "clean removes the data volumes");
    }

    @Test
    @Order(7)
    void theObservabilityClusterStartsItsThreeServersAndPrometheusScrapesEachOfThem() throws Exception {
        run(Duration.ofMinutes(10), "docker", "compose", "-f", OBSERVABILITY_CLUSTER, "up", "-d");
        awaitServersReady(3);
        awaitHealthy("qraft-grafana");

        // The selector of the dashboard's panel "Raft Cluster Network": every server, by its service label.
        String query = "/api/v1/query?query=count(up%7Bservice%3D%22qraft-server%22%7D%20%3D%3D%201)";
        int prometheus = publishedPort("qraft-prometheus", 9090);
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(2)).ignoreExceptions()
                .until(() -> get("http://localhost:" + prometheus + query).body().contains(",\"3\"]"));
        assertNoThirdPartyError();

        captureProjectLogs("observability-cluster");
        run(Duration.ofMinutes(2), "docker", "compose", "-f", OBSERVABILITY_CLUSTER, "down", "-v");
        assertEquals(List.of(), projectContainers());
        assertEquals(List.of(), projectVolumes());
    }

    private static void awaitServersReady(int count) throws Exception {
        for (int server = 1; server <= count; server++) {
            awaitReady("qraft-server" + server);
        }
    }

    /** A server is ready once its cluster has a leader. */
    private static void awaitReady(String container) throws Exception {
        awaitOk(container, 8080, "/health/ready");
    }

    private static void awaitOk(String container, int port, String path) throws Exception {
        String url = "http://localhost:" + publishedPort(container, port) + path;
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                .until(() -> get(url).statusCode() == 200);
    }

    private static void awaitHealthy(String container) {
        await().atMost(Duration.ofSeconds(180)).pollInterval(Duration.ofSeconds(2)).ignoreExceptions()
                .until(() -> run(Duration.ofSeconds(30), "docker", "inspect", "--format",
                        "{{.State.Health.Status}}", container).strip().equals("healthy"));
    }

    private static void assertNoThirdPartyError() throws Exception {
        for (String container : OBSERVABILITY_CONTAINERS) {
            List<String> errors = new ArrayList<>(run(Duration.ofSeconds(30), "docker", "logs", container).lines()
                    .filter(line -> THIRD_PARTY_ERROR.matcher(line).find()).toList());
            if (errors.removeIf(line -> TEMPO_WAL_NOT_YET_WRITTEN.matcher(line).find())) {
                await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(2)).ignoreExceptions()
                        .until(() -> run(Duration.ofSeconds(30), "docker", "logs", container)
                                .contains("msg=\"Done replaying WAL\""));
            }
            assertEquals(List.of(), errors, container + " logged an error");
        }
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The host port that Docker chose for a container's port. */
    private static int publishedPort(String container, int port) throws Exception {
        String published = run(Duration.ofSeconds(30), "docker", "port", container, port + "/tcp")
                .lines().findFirst().orElseThrow(() -> new AssertionError(container + " publishes no port " + port));
        return Integer.parseInt(published.substring(published.lastIndexOf(':') + 1).strip());
    }

    private static List<String> projectContainers() throws Exception {
        return run(Duration.ofSeconds(30), "docker", "ps", "-a", "--filter",
                "label=com.docker.compose.project=" + PROJECT, "--format", "{{.Names}}").lines().sorted().toList();
    }

    private static List<String> projectVolumes() throws Exception {
        return run(Duration.ofSeconds(30), "docker", "volume", "ls", "--filter",
                "label=com.docker.compose.project=" + PROJECT, "--format", "{{.Name}}").lines().sorted().toList();
    }

    private static List<String> serverVolumes(int count) {
        List<String> volumes = new ArrayList<>();
        for (int server = 1; server <= count; server++) {
            volumes.add(PROJECT + "_server" + server + "-data");
        }
        return volumes;
    }

    /** Hands every container of the project to the Docker log audit before a command removes it. */
    private static void captureProjectLogs(String command) throws Exception {
        for (String container : projectContainers()) {
            String id = run(Duration.ofSeconds(30), "docker", "inspect", "--format", "{{.Id}}", container).strip();
            DockerLogCaptureHelper.capture(id, command + "-" + container,
                    run(Duration.ofSeconds(30), "docker", "logs", container));
        }
    }

    /** Runs {@code start} or {@code start-quick} with the arguments both script families take. */
    private static String script(String name, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(WINDOWS
                ? List.of("pwsh", "-NoProfile", "-NonInteractive", "-File", name + ".ps1")
                : List.of("sh", "./" + name + ".sh"));
        command.addAll(List.of(arguments));
        return run(Duration.ofMinutes(10), command.toArray(String[]::new));
    }

    /** Runs the observability script; its two families name their actions differently. */
    private static String observability(String action) throws Exception {
        List<String> command = new ArrayList<>(WINDOWS
                ? List.of("pwsh", "-NoProfile", "-NonInteractive", "-File", "start-observability.ps1")
                : List.of("sh", "./start-observability.sh"));
        if (!action.equals("up")) {
            command.add(WINDOWS ? "-" + action.substring(0, 1).toUpperCase(Locale.ROOT) + action.substring(1)
                    : action);
        }
        return run(Duration.ofMinutes(10), command.toArray(String[]::new));
    }

    private static int exitCode(String... command) throws Exception {
        Process process = start(command);
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), String.join(" ", command) + " did not end");
        return process.exitValue();
    }

    /** Runs a command from {@code docker/} in the test's own Compose project and returns its output. */
    private static String run(Duration limit, String... command) throws Exception {
        Path output = Files.createTempFile("qraft-start-command-", ".log");
        try {
            Process process = start(command, output.toFile());
            if (!process.waitFor(limit.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError(String.join(" ", command) + " did not end within " + limit + ":\n"
                        + Files.readString(output, StandardCharsets.UTF_8));
            }
            String text = Files.readString(output, StandardCharsets.UTF_8);
            // The PowerShell scripts do not pass a failed docker command on, so their effect is asserted instead.
            assertEquals(0, process.exitValue(), String.join(" ", command) + ":\n" + text);
            if (!command[0].equals("docker")) {
                logger.info("{}:\n{}", String.join(" ", command), text.strip());
            }
            return text;
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static Process start(String... command) throws Exception {
        return start(command, null);
    }

    private static Process start(String[] command, File output) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(DOCKER_DIRECTORY).redirectErrorStream(true);
        if (output != null) builder.redirectOutput(output);
        builder.environment().put("COMPOSE_PROJECT_NAME", PROJECT);
        // Host port 0 lets Docker choose a free port, so a stack started by hand or another job is no obstacle.
        builder.environment().put("PUBLISHED_PORT", "0");
        return builder.start();
    }
}

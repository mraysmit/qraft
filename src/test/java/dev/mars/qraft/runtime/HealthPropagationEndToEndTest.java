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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.agent.QraftAgent;
import dev.mars.qraft.agent.health.CheckStatus;
import dev.mars.qraft.agent.health.LocalStatusReporter;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of health propagation through servers launched from configuration files. An agent
 * started from its configuration file, as client mode starts it, runs HTTP, TCP, and TTL checks. The tests
 * observe results only through each server's public HTTP API: health transitions, {@code passing}
 * discovery filtering, local TTL lapse, and deregistration on shutdown.
 *
 * <p>In a three-server cluster, proxies in front of each server hold the agent's observations so the
 * renewal/expiry boundary is crossed on purpose. The leader is replaced while the held observation falls
 * due. A renewal released inside the new leader's grace means the check never expires. A renewal held
 * beyond the grace means every survivor expires the check, and the renewal then restores it.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
@Tag("e2e")
class HealthPropagationEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CONVERGENCE = Duration.ofSeconds(30);
    /** Observation lifetime in the cluster tests; the agent renews at half of it. */
    private static final Duration CLUSTER_TTL = Duration.ofSeconds(8);
    /** How long before the held observation's deadline the leader is shut down. */
    private static final Duration LEADER_LOSS_LEAD = Duration.ofMillis(2_500);
    private static final String HELD = "{\"code\":\"held_by_test\",\"message\":\"observation held\","
            + "\"retryable\":true}";
    private static final String UNREACHABLE = "{\"code\":\"unreachable\",\"message\":\"server unreachable\","
            + "\"retryable\":true}";

    @TempDir
    Path temporaryDirectory;

    private final List<RuntimeLifecycle> lifecycles = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicInteger workloadStatus = new AtomicInteger(200);
    private final AtomicReference<CheckStatus> reportedStatus = new AtomicReference<>(CheckStatus.PASSING);
    private HttpServer workload;
    private ServerSocket tcpListener;
    private ScheduledExecutorService reporter;
    private QraftAgent agent;
    private final AtomicBoolean holdObservations = new AtomicBoolean();
    private final AtomicInteger observationsInFlight = new AtomicInteger();
    private final List<HttpServer> proxies = new ArrayList<>();
    private final List<ExecutorService> proxyExecutors = new ArrayList<>();

    @AfterEach
    void closeResources() throws Exception {
        CleanupHelper cleanup = new CleanupHelper()
                .run(this::stopReporting)
                .run(() -> { if (agent != null) agent.shutdown().get(10, TimeUnit.SECONDS); })
                .run(() -> holdObservations.set(false));
        for (RuntimeLifecycle lifecycle : lifecycles.reversed()) {
            cleanup.run(() -> lifecycle.closeAsync().get(10, TimeUnit.SECONDS));
        }
        proxies.forEach(proxy -> cleanup.run(() -> proxy.stop(0)));
        proxyExecutors.forEach(executor -> cleanup.run(executor::close));
        cleanup.run(() -> { if (workload != null) workload.stop(0); })
                .run(() -> { if (tcpListener != null) tcpListener.close(); })
                .run(http::close)
                .rethrow();
    }

    @Test
    void configuredChecksDriveHealthTransitionsAndPassingDiscoveryThroughThePublicApi() throws Exception {
        Path serverConfig = temporaryDirectory.resolve("server.json");
        writeServerConfig(serverConfig, "health-node", Map.of("health-node", 0));
        int httpPort = launch("server", serverConfig).boundPorts().get("http");
        URI controller = URI.create("http://127.0.0.1:" + httpPort);
        await(() -> status(controller.resolve("/health/ready")) == 200);

        URI workloadUrl = startWorkload();
        startTcpListener();
        Path clientConfig = temporaryDirectory.resolve("client.json");
        writeChecksClientConfig(clientConfig, controller, workloadUrl, tcpListener.getLocalPort());
        agent = QraftAgent.launch(clientConfig);
        startReporting(agent.statusReporter("web-b", "app").orElseThrow());

        await(() -> passing(controller).equals(Set.of("web-a", "web-b")));

        reportedStatus.set(CheckStatus.WARNING);
        await(() -> passing(controller).equals(Set.of("web-a"))
                && "WARNING".equals(checkField(controller, "web-b", "app", "status"))
                && "WARNING".equals(serviceHealth(controller, "web-b")));

        reportedStatus.set(CheckStatus.PASSING);
        workloadStatus.set(503);
        await(() -> passing(controller).equals(Set.of("web-b"))
                && "CRITICAL".equals(checkField(controller, "web-a", "http", "status"))
                && "CRITICAL".equals(serviceHealth(controller, "web-a")));

        workloadStatus.set(200);
        await(() -> passing(controller).equals(Set.of("web-a", "web-b")));

        tcpListener.close();
        await(() -> passing(controller).equals(Set.of("web-a"))
                && "CRITICAL".equals(checkField(controller, "web-b", "tcp", "status")));
        assertEquals(Set.of("web-a", "web-b"), instances(controller, false),
                "a critical instance stays registered and is only hidden by the passing filter");

        stopReporting();
        await(() -> "CRITICAL".equals(checkField(controller, "web-b", "app", "status"))
                && checkField(controller, "web-b", "app", "output").contains("expired"));

        assertTrue(agent.shutdown().get(10, TimeUnit.SECONDS));
        agent = null;
        await(() -> instances(controller, false).isEmpty());
    }

    @Test
    void aRenewalHeldPastItsDeadlineAcrossALeaderChangeLandsInsideTheNewLeadersGraceSoTheCheckNeverExpires()
            throws Exception {
        Cluster cluster = startClusterWithClient();
        holdObservationsUntilQuiet();
        JsonNode held = awaitAgreedCheck(cluster.controllers().values());
        long heldSequence = held.path("sequenceNumber").asLong();
        Instant deadline = Instant.parse(held.path("deadline").asText());

        // The former leader stops evaluating expiry as its shutdown begins, well before the deadline.
        awaitInstant(deadline.minus(LEADER_LOSS_LEAD));
        Map<String, URI> survivors = replaceLeader(cluster);

        AtomicBoolean sawExpiry = new AtomicBoolean();
        await(() -> {
            recordExpiry(survivors.values(), sawExpiry);
            return leader(survivors.values()) != null && Instant.now().isAfter(deadline.plusSeconds(1));
        });
        assertFalse(sawExpiry.get(), "the held observation's deadline passed, but the new leader granted a full TTL");

        holdObservations.set(false);
        await(() -> {
            recordExpiry(survivors.values(), sawExpiry);
            return convergedOnRenewalAfter(survivors.values(), heldSequence);
        });
        assertFalse(sawExpiry.get(), "a renewal that lands inside the new leader's grace means the check never expires");
    }

    @Test
    void aRenewalHeldBeyondTheNewLeadersGraceExpiresTheCheckOnEverySurvivorAndTheRenewalThenRestoresIt()
            throws Exception {
        Cluster cluster = startClusterWithClient();
        holdObservationsUntilQuiet();
        long heldSequence = awaitAgreedCheck(cluster.controllers().values()).path("sequenceNumber").asLong();
        Map<String, URI> survivors = replaceLeader(cluster);

        await(() -> {
            if (leader(survivors.values()) == null) return false;
            List<JsonNode> checks = survivors.values().stream()
                    .map(controller -> check(controller, "web", "http")).toList();
            return checks.stream().allMatch(check -> check != null && check.path("expired").asBoolean()
                    && check.path("sequenceNumber").asLong() == heldSequence)
                    && checks.stream().distinct().count() == 1
                    && survivors.values().stream().allMatch(controller ->
                            "CRITICAL".equals(serviceHealth(controller, "web")));
        });
        for (URI survivor : survivors.values()) {
            assertEquals(Set.of("web"), instances(survivor, false), "expiry marks the check but keeps the service");
        }

        holdObservations.set(false);
        await(() -> convergedOnRenewalAfter(survivors.values(), heldSequence));
    }

    private record Cluster(Map<String, URI> controllers, Map<String, RuntimeLifecycle> servers) { }

    /** Three servers, and a client-mode runtime that reaches each of them through a holding proxy. */
    private Cluster startClusterWithClient() throws Exception {
        Map<String, RuntimeLifecycle> servers = launchThreeServers();
        Map<String, URI> controllers = new LinkedHashMap<>();
        List<URI> proxied = new ArrayList<>();
        for (Map.Entry<String, RuntimeLifecycle> server : servers.entrySet()) {
            URI controller = URI.create("http://127.0.0.1:" + server.getValue().boundPorts().get("http"));
            controllers.put(server.getKey(), controller);
            proxied.add(startProxy(controller));
        }
        await(() -> leader(controllers.values()) != null);

        URI workloadUrl = startWorkload();
        Path clientConfig = temporaryDirectory.resolve("client.json");
        writeClusterClientConfig(clientConfig, proxied, workloadUrl);
        launch("client", clientConfig);
        await(() -> controllers.values().stream().allMatch(controller ->
                "PASSING".equals(checkField(controller, "web", "http", "status"))));
        return new Cluster(controllers, servers);
    }

    /**
     * Launches the three servers. Every listener binds port 0 except Raft: each server dials its peers at
     * the Raft addresses in its configuration, so those ports must be known before any server starts. They
     * are reserved together, so they are distinct, and released just before launch. Should another process
     * take one in that window, the bind fails with a {@link java.net.BindException}; the partial cluster is
     * closed and the launch repeats with fresh ports. Any other failure is the test's.
     */
    private Map<String, RuntimeLifecycle> launchThreeServers() throws Exception {
        List<String> nodeIds = List.of("node-a", "node-b", "node-c");
        for (int attempt = 1; ; attempt++) {
            Map<String, Integer> raftPorts = new LinkedHashMap<>();
            List<Integer> reserved = reserveDistinctPorts(nodeIds.size());
            for (int index = 0; index < nodeIds.size(); index++) raftPorts.put(nodeIds.get(index), reserved.get(index));
            Map<String, RuntimeLifecycle> servers = new LinkedHashMap<>();
            try {
                for (String nodeId : nodeIds) {
                    Path config = temporaryDirectory.resolve(nodeId + ".json");
                    writeServerConfig(config, nodeId, raftPorts);
                    servers.put(nodeId, launch("server", config));
                }
                return servers;
            } catch (RuntimeException failure) {
                if (!causedByBindFailure(failure) || attempt == 3) throw failure;
                for (RuntimeLifecycle server : servers.values()) server.closeAsync().get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static List<Integer> reserveDistinctPorts(int count) throws Exception {
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) sockets.add(new ServerSocket(0));
            return sockets.stream().map(ServerSocket::getLocalPort).toList();
        } finally {
            for (ServerSocket socket : sockets) socket.close();
        }
    }

    private static boolean causedByBindFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.BindException) return true;
        }
        return false;
    }

    /** Deliberately stops the leader, declares only that peer's RPC failures, and returns the survivors. */
    private Map<String, URI> replaceLeader(Cluster cluster) throws Exception {
        String formerLeader = leader(cluster.controllers().values());
        assertNotNull(formerLeader);
        IntentionalErrorsHelper.expect(RAFT_PEER_UNREACHABLE, formerLeader);
        cluster.servers().get(formerLeader).closeAsync().get(10, TimeUnit.SECONDS);
        Map<String, URI> survivors = new LinkedHashMap<>(cluster.controllers());
        survivors.remove(formerLeader);
        return survivors;
    }

    /** Stops observations reaching any server, and waits until none that passed the proxy is still open. */
    private void holdObservationsUntilQuiet() throws Exception {
        holdObservations.set(true);
        await(() -> observationsInFlight.get() == 0);
    }

    /** The check state every server holds once all of them agree. */
    private JsonNode awaitAgreedCheck(Collection<URI> controllers) throws Exception {
        AtomicReference<JsonNode> agreed = new AtomicReference<>();
        await(() -> {
            List<JsonNode> checks = controllers.stream().map(controller -> check(controller, "web", "http")).toList();
            if (checks.stream().anyMatch(Objects::isNull) || checks.stream().distinct().count() != 1) return false;
            agreed.set(checks.getFirst());
            return true;
        });
        return agreed.get();
    }

    private void recordExpiry(Collection<URI> controllers, AtomicBoolean sawExpiry) {
        for (URI controller : controllers) {
            if ("true".equals(checkField(controller, "web", "http", "expired"))) sawExpiry.set(true);
        }
    }

    /** One leader, and every server holding the same passing, unexpired renewal newer than {@code sequence}. */
    private boolean convergedOnRenewalAfter(Collection<URI> controllers, long sequence) {
        if (leader(controllers) == null) return false;
        List<JsonNode> checks = controllers.stream().map(controller -> check(controller, "web", "http")).toList();
        return checks.stream().allMatch(check -> check != null
                && check.path("sequenceNumber").asLong() > sequence
                && "PASSING".equals(check.path("status").asText())
                && !check.path("expired").asBoolean())
                && checks.stream().distinct().count() == 1
                && controllers.stream().allMatch(controller -> "PASSING".equals(serviceHealth(controller, "web")));
    }

    /**
     * Forwards every request to {@code target}, except that observations are answered with a retryable
     * 503 while they are held. A server that cannot be reached also yields a retryable 503.
     */
    private URI startProxy(URI target) throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        proxyExecutors.add(executor);
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.setExecutor(executor);
        proxy.createContext("/", exchange -> {
            boolean observation = "/v1/agent/check/observe".equals(exchange.getRequestURI().getPath());
            if (observation) observationsInFlight.incrementAndGet();
            try (exchange) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (observation && holdObservations.get()) {
                    reply(exchange, 503, HELD.getBytes(StandardCharsets.UTF_8));
                    return;
                }
                HttpRequest.Builder request = HttpRequest.newBuilder(target.resolve(exchange.getRequestURI().toString()))
                        .timeout(Duration.ofSeconds(5))
                        .method(exchange.getRequestMethod(), body.length == 0
                                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
                for (String header : List.of("X-Qraft-Node", "X-Qraft-Tenant", "X-Qraft-Namespace", "X-Request-Id",
                        "Content-Type", "Accept")) {
                    String value = exchange.getRequestHeaders().getFirst(header);
                    if (value != null) request.header(header, value);
                }
                HttpResponse<byte[]> response;
                try {
                    response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                } catch (Exception unreachable) {
                    reply(exchange, 503, UNREACHABLE.getBytes(StandardCharsets.UTF_8));
                    return;
                }
                reply(exchange, response.statusCode(), response.body());
            } finally {
                if (observation) observationsInFlight.decrementAndGet();
            }
        });
        proxy.start();
        proxies.add(proxy);
        return URI.create("http://127.0.0.1:" + proxy.getAddress().getPort());
    }

    private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) exchange.getResponseBody().write(body);
    }

    /** Waits until the shared wall clock, which the servers also read, reaches {@code instant}. */
    private static void awaitInstant(Instant instant) throws Exception {
        long deadline = System.nanoTime() + CONVERGENCE.toNanos();
        while (Instant.now().isBefore(instant) && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(Instant.now().isBefore(instant), "the wall clock did not reach " + instant);
    }

    private RuntimeLifecycle launch(String mode, Path config) {
        RuntimeLifecycle lifecycle = QraftRuntimeApplication.launch(new String[]{mode, "--config", config.toString()});
        lifecycles.add(lifecycle);
        return lifecycle;
    }

    private URI startWorkload() throws Exception {
        workload = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workload.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(workloadStatus.get(), -1);
            exchange.close();
        });
        workload.start();
        return URI.create("http://127.0.0.1:" + workload.getAddress().getPort());
    }

    /** Accepts and closes every connection until closed, so probes never fill the listen backlog. */
    private void startTcpListener() throws Exception {
        ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        tcpListener = listener;
        Thread.ofVirtual().name("tcp-check-target").start(() -> {
            while (!listener.isClosed()) {
                try {
                    listener.accept().close();
                } catch (java.io.IOException closed) {
                    // The listener was closed; the check now fails to connect.
                }
            }
        });
    }

    /** Reports the current TTL status every 200 ms, as a live application would, until stopped. */
    private void startReporting(LocalStatusReporter statusReporter) {
        reporter = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
        reporter.scheduleAtFixedRate(() -> statusReporter.report(reportedStatus.get(), "reported by the test"),
                0, 200, TimeUnit.MILLISECONDS);
    }

    private void stopReporting() throws InterruptedException {
        if (reporter == null) return;
        reporter.shutdownNow();
        assertTrue(reporter.awaitTermination(10, TimeUnit.SECONDS));
        reporter = null;
    }

    /** The node ID of the only server reporting itself leader, or {@code null} while there is not exactly one. */
    private String leader(Collection<URI> controllers) {
        List<String> leaders = new ArrayList<>();
        for (URI controller : controllers) {
            JsonNode raft = getJson(controller.resolve("/raft/status"));
            if (raft != null && "LEADER".equals(raft.path("state").asText())) leaders.add(raft.path("nodeId").asText());
        }
        return leaders.size() == 1 ? leaders.getFirst() : null;
    }

    private Set<String> passing(URI controller) {
        return instances(controller, true);
    }

    /** Service IDs of the {@code web} instances the server returns, or an impossible marker if it cannot answer. */
    private Set<String> instances(URI controller, boolean passingOnly) {
        JsonNode entries = healthEntries(controller, passingOnly);
        if (entries == null) return Set.of("<unavailable>");
        Set<String> serviceIds = new TreeSet<>();
        entries.forEach(entry -> serviceIds.add(entry.path("service").path("serviceId").asText()));
        return serviceIds;
    }

    private String serviceHealth(URI controller, String serviceId) {
        JsonNode entry = entry(controller, serviceId);
        return entry == null ? null : entry.path("service").path("health").asText();
    }

    private String checkField(URI controller, String serviceId, String checkId, String field) {
        JsonNode check = check(controller, serviceId, checkId);
        return check == null ? "" : check.path(field).asText();
    }

    private JsonNode check(URI controller, String serviceId, String checkId) {
        JsonNode entry = entry(controller, serviceId);
        if (entry == null) return null;
        for (JsonNode check : entry.path("checks")) {
            if (checkId.equals(check.path("checkId").asText())) return check;
        }
        return null;
    }

    private JsonNode entry(URI controller, String serviceId) {
        JsonNode entries = healthEntries(controller, false);
        if (entries == null) return null;
        for (JsonNode entry : entries) {
            if (serviceId.equals(entry.path("service").path("serviceId").asText())) return entry;
        }
        return null;
    }

    private JsonNode healthEntries(URI controller, boolean passingOnly) {
        return getJson(controller.resolve("/v1/health/service/web" + (passingOnly ? "?passing" : "")));
    }

    private JsonNode getJson(URI uri) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? JSON.readTree(response.body()) : null;
        } catch (Exception unavailable) {
            return null;
        }
    }

    private int status(URI uri) {
        try {
            return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception unavailable) {
            return -1;
        }
    }

    private static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + CONVERGENCE.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) return;
            Thread.sleep(25);
        }
        assertTrue(condition.evaluate(), "condition was not met before the deadline");
    }

    /** Writes a server configuration whose HTTP and API gRPC listeners bind any free port. */
    private void writeServerConfig(Path target, String nodeId, Map<String, Integer> raftPorts)
            throws Exception {
        Map<String, String> members = new LinkedHashMap<>();
        raftPorts.forEach((member, port) -> members.put(member, "127.0.0.1:" + port));
        Files.writeString(target, """
                {
                  "version": 1,
                  "server": {
                    "id": %s,
                    "http": {"host": "127.0.0.1", "port": %d},
                    "apiGrpcPort": %d,
                    "raft": {
                      "port": %d,
                      "nodes": %s,
                      "electionTimeoutMs": 500,
                      "heartbeatIntervalMs": 50,
                      "storage": {"type": "raftlog", "path": %s, "fsync": true}
                    },
                    "health": {"expiryIntervalMs": 50},
                    "telemetry": {"enabled": false},
                    "shutdown": {"drainTimeoutMs": 100, "timeoutMs": 5000}
                  },
                  "logging": {"directory": %s}
                }
                """.formatted(JSON.writeValueAsString(nodeId), 0, 0, raftPorts.get(nodeId),
                JSON.writeValueAsString(members),
                JSON.writeValueAsString(temporaryDirectory.resolve("raft-" + nodeId).toString()),
                JSON.writeValueAsString(temporaryDirectory.resolve("logs").toString())));
    }

    /**
     * Two instances of {@code web}: {@code web-a} is probed over HTTP, and {@code web-b} is probed over TCP
     * and also carries a TTL check that the test reports through the agent's process-local input.
     */
    private void writeChecksClientConfig(Path target, URI controller, URI workloadUrl, int tcpPort) throws Exception {
        Files.writeString(target, """
                {
                  "version": 1,
                  "agent": {"id": "checks-agent", "address": "127.0.0.1", "httpPort": %d,
                            "heartbeatIntervalMs": 50, "shutdownTimeoutMs": 5000},
                  "controllers": {"urls": [%s], "requestTimeoutMs": 5000},
                  "catalog": {
                    "registrationRetryMinMs": 25, "registrationRetryMaxMs": 100, "contactFreshnessMs": 5000,
                    "services": [
                      {"id": "web-a", "name": "web", "address": "127.0.0.1", "port": %d,
                       "checks": [{"id": "http", "type": "http", "url": %s,
                                   "intervalMs": 250, "timeoutMs": 250, "ttlMs": 5000}]},
                      {"id": "web-b", "name": "web", "address": "127.0.0.1", "port": %d,
                       "checks": [{"id": "tcp", "type": "tcp", "intervalMs": 250, "timeoutMs": 250, "ttlMs": 5000},
                                  {"id": "app", "type": "ttl", "ttlMs": 3000}]}
                    ]
                  },
                  "logging": {"directory": %s}
                }
                """.formatted(0, JSON.writeValueAsString(controller.toString()), workloadUrl.getPort(),
                JSON.writeValueAsString(workloadUrl.resolve("/health").toString()), tcpPort,
                JSON.writeValueAsString(temporaryDirectory.resolve("client-logs").toString())));
    }

    /** One {@code web} instance whose HTTP observations live for {@link #CLUSTER_TTL} and renew at half of it. */
    private void writeClusterClientConfig(Path target, Collection<URI> controllers, URI workloadUrl) throws Exception {
        List<String> urls = controllers.stream().map(URI::toString).toList();
        Files.writeString(target, """
                {
                  "version": 1,
                  "agent": {"id": "cluster-agent", "address": "127.0.0.1", "httpPort": %d,
                            "heartbeatIntervalMs": 50, "shutdownTimeoutMs": 5000},
                  "controllers": {"urls": %s, "requestTimeoutMs": 5000},
                  "catalog": {
                    "registrationRetryMinMs": 25, "registrationRetryMaxMs": 100, "contactFreshnessMs": 5000,
                    "services": [
                      {"id": "web", "name": "web", "address": "127.0.0.1", "port": %d,
                       "checks": [{"id": "http", "type": "http", "url": %s,
                                   "intervalMs": 250, "timeoutMs": 250, "ttlMs": %d,
                                   "deregisterAfterMs": 60000}]}
                    ]
                  },
                  "logging": {"directory": %s}
                }
                """.formatted(0, JSON.writeValueAsString(urls), workloadUrl.getPort(),
                JSON.writeValueAsString(workloadUrl.resolve("/health").toString()), CLUSTER_TTL.toMillis(),
                JSON.writeValueAsString(temporaryDirectory.resolve("client-logs").toString())));
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }
}

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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.agent.QraftAgent;
import dev.mars.qraft.agent.config.AgentConfiguration;
import dev.mars.qraft.agent.health.CheckStatus;
import dev.mars.qraft.agent.health.HealthCheckDefinition;
import dev.mars.qraft.agent.health.HttpCheck;
import dev.mars.qraft.agent.health.TtlCheck;
import dev.mars.qraft.catalog.HealthCheckState;
import dev.mars.qraft.catalog.ServiceCheckId;
import dev.mars.qraft.catalog.ServiceDefinition;
import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstanceId;
import dev.mars.qraft.controller.http.HttpApiServer;
import dev.mars.qraft.controller.raft.RaftMessage;
import dev.mars.qraft.controller.raft.RaftNode;
import dev.mars.qraft.controller.raft.RaftNodeMode;
import dev.mars.qraft.controller.raft.RaftTransport;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests in which a real {@link QraftAgent} runs local checks and publishes their observations
 * to a real controller: publication through a failed seed, renewal, failure and recovery with
 * readiness, process-local TTL status, controller restart, and publication stopping before
 * deregistration.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
class AgentHealthPublicationTest {
    private static final String AGENT_ID = "health-agent";

    private final AtomicInteger workloadStatus = new AtomicInteger(200);
    private final List<String> proxiedRequests = new CopyOnWriteArrayList<>();
    private Controller controller;
    private HttpServer workload;
    private HttpServer proxy;
    private QraftAgent agent;

    @AfterEach
    void closeResources() throws Exception {
        if (agent != null) agent.shutdown().get(5, TimeUnit.SECONDS);
        if (proxy != null) proxy.stop(0);
        if (workload != null) workload.stop(0);
        if (controller != null) controller.close();
    }

    @Test
    void agentPublishesRenewsAndRecoversRequiredChecksThroughAFailedSeed() throws Exception {
        int controllerPort = freePort();
        controller = Controller.start(controllerPort);
        URI workloadUrl = startWorkload();
        agent = agent(List.of(refusedEndpoint(), controller.endpoint()), workloadUrl);

        assertTrue(agent.start().get(5, TimeUnit.SECONDS));
        waitUntil(() -> status("http").filter(ServiceHealth.PASSING::equals).isPresent()
                && agent.healthService().isReady());
        assertEquals(ServiceHealth.PASSING, serviceHealth());

        long firstSequence = check("http").orElseThrow().observation().sequenceNumber();
        waitUntil(() -> check("http").map(state -> state.observation().sequenceNumber() > firstSequence
                && state.observation().status() == ServiceHealth.PASSING).orElse(false));

        workloadStatus.set(503);
        waitUntil(() -> status("http").filter(ServiceHealth.CRITICAL::equals).isPresent()
                && !agent.healthService().isReady());
        assertEquals(ServiceHealth.CRITICAL, serviceHealth());
        assertTrue(agent.healthService().isHealthy(), "a failing required check never affects liveness");

        workloadStatus.set(200);
        waitUntil(() -> status("http").filter(ServiceHealth.PASSING::equals).isPresent()
                && agent.healthService().isReady());

        assertTrue(agent.statusReporter("web", "app").orElseThrow().report(CheckStatus.WARNING, "cache cold"));
        waitUntil(() -> status("app").filter(ServiceHealth.WARNING::equals).isPresent());
        assertEquals("cache cold", check("app").orElseThrow().observation().output());
        assertEquals(ServiceHealth.WARNING, serviceHealth());
        assertTrue(agent.healthService().isReady(), "an optional warning check keeps the agent ready");
        assertTrue(agent.statusReporter("web", "http").isEmpty());

        controller.close();
        waitUntil(() -> !agent.healthService().isReady());
        controller = Controller.start(controllerPort);
        waitUntil(() -> status("http").filter(ServiceHealth.PASSING::equals).isPresent()
                && agent.healthService().isReady());
    }

    @Test
    void shutdownStopsChecksAndPublicationsBeforeDeregistration() throws Exception {
        controller = Controller.start(0);
        URI workloadUrl = startWorkload();
        agent = agent(List.of(startRecordingProxy(controller.endpoint())), workloadUrl);
        assertTrue(agent.start().get(5, TimeUnit.SECONDS));
        waitUntil(() -> status("http").filter(ServiceHealth.PASSING::equals).isPresent());
        waitUntil(() -> proxiedRequests.stream().filter(this::isObservation).count() >= 2);

        assertTrue(agent.shutdown().get(5, TimeUnit.SECONDS));

        int deregistration = proxiedRequests.indexOf("PUT /v1/agent/service/deregister/web");
        int lastObservation = -1;
        for (int index = 0; index < proxiedRequests.size(); index++) {
            if (isObservation(proxiedRequests.get(index))) lastObservation = index;
        }
        assertTrue(deregistration > 0, proxiedRequests.toString());
        assertTrue(lastObservation < deregistration,
                "every observation precedes deregistration: " + proxiedRequests);
        assertTrue(controller.store().getServiceCatalog().instances("web").isEmpty());
        assertTrue(controller.store().healthChecks().isEmpty());
        int requestsAfterShutdown = proxiedRequests.size();
        Thread.sleep(300);
        assertEquals(requestsAfterShutdown, proxiedRequests.size(), "no request follows shutdown");
    }

    private QraftAgent agent(List<URI> controllers, URI workloadUrl) throws Exception {
        ServiceDefinition web = new ServiceDefinition("web", "web", "127.0.0.1", workloadUrl.getPort(),
                List.of("health"), Map.of(), true);
        List<HealthCheckDefinition> checks = List.of(
                new HttpCheck("web", "http", workloadUrl.resolve("/health"), Duration.ofMillis(100),
                        Duration.ofMillis(100), Duration.ofMillis(600), true),
                new TtlCheck("web", "app", Duration.ofSeconds(30), false));
        return new QraftAgent(AgentConfiguration.builder()
                .agentId(AGENT_ID).hostname(AGENT_ID + "-host").address("127.0.0.1")
                .agentPort(freePort()).controllerUrls(controllers)
                .heartbeatInterval(40).requestTimeoutMs(500)
                .registrationRetryMinMs(20).registrationRetryMaxMs(100)
                .contactFreshnessMs(300).shutdownTimeoutMs(3_000)
                .services(List.of(web)).healthChecks(checks).build());
    }

    private URI startWorkload() throws IOException {
        workload = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workload.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(workloadStatus.get(), -1);
            exchange.close();
        });
        workload.start();
        return URI.create("http://127.0.0.1:" + workload.getAddress().getPort());
    }

    /** Forwards every request to the controller and records its method and path in arrival order. */
    private URI startRecordingProxy(URI target) throws IOException {
        HttpClient forwarder = HttpClient.newHttpClient();
        proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            proxiedRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            forward(forwarder, target, exchange);
        });
        proxy.start();
        return URI.create("http://127.0.0.1:" + proxy.getAddress().getPort());
    }

    private static void forward(HttpClient forwarder, URI target, HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            HttpRequest.Builder request = HttpRequest.newBuilder(target.resolve(exchange.getRequestURI().toString()))
                    .method(exchange.getRequestMethod(), body.length == 0
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            for (String header : List.of("X-Qraft-Node", "X-Qraft-Tenant", "X-Qraft-Namespace", "Content-Type")) {
                String value = exchange.getRequestHeaders().getFirst(header);
                if (value != null) request.header(header, value);
            }
            HttpResponse<byte[]> response = forwarder.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] reply = response.body();
            exchange.sendResponseHeaders(response.statusCode(), reply.length == 0 ? -1 : reply.length);
            if (reply.length > 0) exchange.getResponseBody().write(reply);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            exchange.sendResponseHeaders(503, -1);
        }
    }

    private boolean isObservation(String request) {
        return request.equals("PUT /v1/agent/check/observe");
    }

    private Optional<HealthCheckState> check(String checkId) {
        Controller current = controller;
        if (current == null) return Optional.empty();
        return current.store().findHealthCheck(new ServiceCheckId(
                new ServiceInstanceId("default", "default", AGENT_ID, "web"), checkId));
    }

    private Optional<ServiceHealth> status(String checkId) {
        return check(checkId).map(state -> state.observation().status());
    }

    private ServiceHealth serviceHealth() {
        return controller.store().getServiceCatalog().instances("web").getFirst().health();
    }

    private static URI refusedEndpoint() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "condition was not met before the deadline");
    }

    /** One single-node controller with its own runtime, so it can be stopped and restarted on a port. */
    private record Controller(JavaRuntime runtime, RaftNode node, QraftStateStore store, HttpApiServer server)
            implements AutoCloseable {

        static Controller start(int port) throws Exception {
            JavaRuntime runtime = JavaRuntime.create();
            QraftStateStore store = new QraftStateStore();
            RaftNode node = RaftNode.builder().runtime(runtime).nodeId("health-controller")
                    .clusterNodes(Set.of("health-controller")).transport(new SingleNodeTransport())
                    .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec())
                    .mode(RaftNodeMode.volatileMode()).electionTimeout(25).heartbeatInterval(10_000).build();
            node.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            waitUntil(node::isLeader);
            HttpApiServer server = new HttpApiServer(port, node, store);
            server.start().get(5, TimeUnit.SECONDS);
            return new Controller(runtime, node, store, server);
        }

        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.port());
        }

        @Override
        public void close() throws Exception {
            server.close();
            node.stop().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            runtime.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static final class SingleNodeTransport implements RaftTransport {
        @Override public void start(Consumer<RaftMessage> messageHandler) { }
        @Override public void stop() { }
        @Override public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return Future.failedFuture("single-node transport has no peers");
        }
        @Override public Future<AppendEntriesResponse> sendAppendEntries(
                String targetId, AppendEntriesRequest request) {
            return Future.failedFuture("single-node transport has no peers");
        }
        @Override public Future<InstallSnapshotResponse> sendInstallSnapshot(
                String targetId, InstallSnapshotRequest request) {
            return Future.failedFuture("single-node transport has no peers");
        }
    }
}

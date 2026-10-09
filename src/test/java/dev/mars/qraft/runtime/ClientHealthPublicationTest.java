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
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.state.catalog.ServiceKey;
import dev.mars.qraft.client.QraftClient;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.client.health.CheckStatus;
import dev.mars.qraft.client.health.HealthCheckDefinition;
import dev.mars.qraft.client.health.HttpCheck;
import dev.mars.qraft.client.health.TtlCheck;
import dev.mars.qraft.state.catalog.HealthCheckState;
import dev.mars.qraft.state.catalog.ServiceCheckId;
import dev.mars.qraft.common.ServiceDefinition;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstanceId;
import dev.mars.qraft.server.http.HttpApiServer;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.common.async.JavaRuntime;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests in which a real {@link QraftClient} runs local checks and publishes their observations
 * to a real server: publication through a failed seed, renewal, failure and recovery with
 * readiness, process-local TTL status, server restart, and publication stopping before
 * deregistration.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
@Tag("e2e")
class ClientHealthPublicationTest {
    private static final String CLIENT_ID = "health-client";

    private final AtomicInteger workloadStatus = new AtomicInteger(200);
    private final List<String> proxiedRequests = new CopyOnWriteArrayList<>();
    private Server server;
    private HttpServer workload;
    private HttpServer proxy;
    private QraftClient client;

    @AfterEach
    void closeResources() throws Exception {
        new CleanupHelper()
                .run(() -> { if (client != null) client.shutdown().get(10, TimeUnit.SECONDS); })
                .run(() -> { if (proxy != null) proxy.stop(0); })
                .run(() -> { if (workload != null) workload.stop(0); })
                .run(() -> { if (server != null) server.close(); })
                .rethrow();
    }

    @Test
    void clientPublishesRenewsAndRecoversRequiredChecksThroughAFailedSeed() throws Exception {
        server = Server.start(0);
        int serverPort = server.server().port();
        URI workloadUrl = startWorkload();
        client = client(List.of(refusedEndpoint(), server.endpoint()), workloadUrl);

        assertTrue(client.start().get(10, TimeUnit.SECONDS));
        waitUntil(() -> status("http").filter(ServiceHealth.PASSING::equals).isPresent()
                && client.healthService().isReady());
        assertEquals(ServiceHealth.PASSING, serviceHealth());

        long firstSequence = check("http").orElseThrow().observation().sequenceNumber();
        waitUntil(() -> check("http").map(state -> state.observation().sequenceNumber() > firstSequence
                && state.observation().status() == ServiceHealth.PASSING).orElse(false));

        workloadStatus.set(503);
        waitUntil(() -> status("http").filter(ServiceHealth.CRITICAL::equals).isPresent()
                && !client.healthService().isReady());
        assertEquals(ServiceHealth.CRITICAL, serviceHealth());
        assertTrue(client.healthService().isHealthy(), "a failing required check never affects liveness");

        workloadStatus.set(200);
        waitUntil(() -> status("http").filter(ServiceHealth.PASSING::equals).isPresent()
                && client.healthService().isReady());

        assertTrue(client.statusReporter("web", "app").orElseThrow().report(CheckStatus.WARNING, "cache cold"));
        waitUntil(() -> status("app").filter(ServiceHealth.WARNING::equals).isPresent());
        assertEquals("cache cold", check("app").orElseThrow().observation().output());
        assertEquals(ServiceHealth.WARNING, serviceHealth());
        waitUntil(() -> client.healthService().isReady()); // an optional warning check keeps the client ready
        assertTrue(client.statusReporter("web", "http").isEmpty());

        server.close();
        waitUntil(() -> !client.healthService().isReady());
        // The client knows the server by URL, so the restarted server must bind the same port.
        server = Server.start(serverPort);
        waitUntil(() -> status("http").filter(ServiceHealth.PASSING::equals).isPresent()
                && client.healthService().isReady());
    }

    @Test
    void shutdownStopsChecksAndPublicationsBeforeDeregistration() throws Exception {
        server = Server.start(0);
        URI workloadUrl = startWorkload();
        client = client(List.of(startRecordingProxy(server.endpoint())), workloadUrl);
        assertTrue(client.start().get(10, TimeUnit.SECONDS));
        waitUntil(() -> status("http").filter(ServiceHealth.PASSING::equals).isPresent());
        waitUntil(() -> proxiedRequests.stream().filter(this::isObservation).count() >= 2);

        assertTrue(client.shutdown().get(10, TimeUnit.SECONDS));

        int deregistration = proxiedRequests.indexOf("PUT /v1/client/service/deregister/web");
        int lastObservation = -1;
        for (int index = 0; index < proxiedRequests.size(); index++) {
            if (isObservation(proxiedRequests.get(index))) lastObservation = index;
        }
        assertTrue(deregistration > 0, proxiedRequests.toString());
        assertTrue(lastObservation < deregistration,
                "every observation precedes deregistration: " + proxiedRequests);
        assertTrue(server.store().getServiceCatalog().instances(ServiceKey.inDefaultScope("web")).isEmpty());
        assertTrue(server.store().healthChecks().isEmpty());
        assertTrue(client.isTerminated(),
                "no client thread or client remains after shutdown, so no request can follow it");
    }

    private QraftClient client(List<URI> servers, URI workloadUrl) throws Exception {
        ServiceDefinition web = new ServiceDefinition("web", "web", "127.0.0.1", workloadUrl.getPort(),
                List.of("health"), Map.of(), true);
        List<HealthCheckDefinition> checks = List.of(
                // A one-second interval and timeout: a slow local response under load cannot flip the check.
                new HttpCheck("web", "http", workloadUrl.resolve("/health"), Duration.ofSeconds(1),
                        Duration.ofSeconds(1), Duration.ofSeconds(5), true),
                new TtlCheck("web", "app", Duration.ofSeconds(30), false));
        return new QraftClient(ClientConfiguration.builder()
                .clientId(CLIENT_ID).hostname(CLIENT_ID + "-host").address("127.0.0.1")
                .clientPort(0).serverUrls(servers)
                .heartbeatInterval(40).requestTimeoutMs(5_000)
                .registrationRetryMinMs(20).registrationRetryMaxMs(100)
                .contactFreshnessMs(1_000).shutdownTimeoutMs(3_000)
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

    /** Forwards every request to the server and records its method and path in arrival order. */
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
        return request.equals("PUT /v1/client/check/observe");
    }

    private Optional<HealthCheckState> check(String checkId) {
        Server current = server;
        if (current == null) return Optional.empty();
        return current.store().findHealthCheck(new ServiceCheckId(
                new ServiceInstanceId("default", "default", CLIENT_ID, "web"), checkId));
    }

    private Optional<ServiceHealth> status(String checkId) {
        return check(checkId).map(state -> state.observation().status());
    }

    private ServiceHealth serviceHealth() {
        return server.store().getServiceCatalog().instances(ServiceKey.inDefaultScope("web")).getFirst().health();
    }

    private static URI refusedEndpoint() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
    }


    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "condition was not met before the deadline");
    }

    /** One single-node server with its own runtime, so it can be stopped and restarted on a port. */
    private record Server(JavaRuntime runtime, RaftNode node, QraftStateStore store, HttpApiServer server)
            implements AutoCloseable {

        static Server start(int port) throws Exception {
            JavaRuntime runtime = JavaRuntime.create();
            QraftStateStore store = new QraftStateStore();
            RaftNode node = RaftNode.builder().runtime(runtime).nodeId("health-server")
                    .clusterNodes(Set.of("health-server")).transport(new PeerlessTransportFixture())
                    .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec())
                    .mode(RaftNodeMode.volatileMode()).electionTimeout(25).heartbeatInterval(10_000).build();
            node.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            waitUntil(node::isLeader);
            HttpApiServer server = new HttpApiServer(port, node, store);
            server.start().get(10, TimeUnit.SECONDS);
            return new Server(runtime, node, store, server);
        }

        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.port());
        }

        @Override
        public void close() throws Exception {
            server.close();
            node.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}

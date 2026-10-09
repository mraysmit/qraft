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
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.common.NodeStatus;
import dev.mars.qraft.client.QraftClient;
import dev.mars.qraft.client.catalog.CatalogOutcome;
import dev.mars.qraft.client.catalog.HttpCatalogClient;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.common.ServiceDefinition;
import dev.mars.qraft.server.http.HttpApiServer;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.common.async.JavaRuntime;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link HttpCatalogClient} and {@link QraftClient} registration, heartbeat, and
 * deregistration against a real single-node server.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-21
 * @version 1.0
 */
class ClientServerContractTest {
    private JavaRuntime runtime;
    private RaftNode node;
    private HttpApiServer server;
    private QraftClient client;
    private HttpCatalogClient catalogClient;
    private HttpServer retryableServer;

    @AfterEach
    void closeResources() throws Exception {
        new CleanupHelper()
                .run(() -> { if (client != null) client.shutdown().get(10, TimeUnit.SECONDS); })
                .run(() -> { if (catalogClient != null) catalogClient.close(); })
                .run(() -> { if (retryableServer != null) retryableServer.stop(0); })
                .run(() -> { if (server != null) server.close(); })
                .run(() -> {
                    if (node != null) node.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                })
                .run(() -> {
                    if (runtime != null) runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                })
                .rethrow();
    }

    @Test
    void httpCatalogClientRegistersAndDeregistersAgainstRealServer() throws Exception {
        startServer();
        URI endpoint = URI.create("http://127.0.0.1:" + server.port());
        URI refused;
        try (ServerSocket socket = new ServerSocket(0)) {
            refused = URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
        AtomicInteger retryableAttempts = new AtomicInteger();
        retryableServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        retryableServer.createContext("/", exchange -> {
            retryableAttempts.incrementAndGet();
            byte[] body = "{\"code\":\"leader_unavailable\",\"message\":\"not leader\","
                    .concat("\"retryable\":true}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        retryableServer.start();
        URI retryable = URI.create("http://127.0.0.1:" + retryableServer.getAddress().getPort());
        catalogClient = new HttpCatalogClient(HttpClient.newHttpClient(), new ObjectMapper(),
                List.of(refused, retryable, endpoint),
                "catalog-client", "default", "default", "dc-1", "eu-west", Duration.ofSeconds(2));
        ServiceDefinition service = new ServiceDefinition("payments-1", "payments", "127.0.0.1", 9090,
                List.of("blue"), Map.of("team", "platform"), true);

        CatalogOutcome.Success registered = assertInstanceOf(CatalogOutcome.Success.class,
                catalogClient.register(service).get(10, TimeUnit.SECONDS));
        assertTrue(registered.changed());
        assertEquals(1, retryableAttempts.get());
        JsonNode afterRegistration = readCatalog(endpoint, "payments");
        assertEquals(1, afterRegistration.size());
        assertEquals("payments-1", afterRegistration.get(0).path("serviceId").textValue());
        assertEquals("catalog-client", afterRegistration.get(0).path("nodeId").textValue());

        CatalogOutcome.Success deregistered = assertInstanceOf(CatalogOutcome.Success.class,
                catalogClient.deregister("payments-1").get(10, TimeUnit.SECONDS));
        assertTrue(deregistered.changed());
        assertEquals(1, retryableAttempts.get(), "the successful server must be preferred next");
        assertTrue(readCatalog(endpoint, "payments").isEmpty());
    }

    @Test
    void realClientCompletesRegistrationHeartbeatAndDeregistrationAgainstServer() throws Exception {
        QraftStateStore store = startServer();
        ClientConfiguration configuration = ClientConfiguration.builder()
                .clientId("contract-client")
                .address("127.0.0.1")
                .clientPort(0)
                .serverUrl("http://localhost:" + server.port())
                .heartbeatInterval(25)
                .requestTimeoutMs(1_000)
                .build();
        client = new QraftClient(configuration);

        assertTrue(client.start().get(10, TimeUnit.SECONDS),
                () -> "the real client rejected the server response; replicated state="
                        + store.findNode("contract-client"));
        assertTrue(client.healthService().isReady());
        waitUntil(() -> store.findNode("contract-client")
                .filter(info -> info.status() == NodeStatus.HEALTHY && info.lastHeartbeat() != null)
                .isPresent());

        assertTrue(client.shutdown().get(10, TimeUnit.SECONDS));
        waitUntil(() -> store.findNode("contract-client").isEmpty());
        assertFalse(client.isRunning());
        assertEquals(0, store.getNodes().size());
    }

    private QraftStateStore startServer() throws Exception {
        runtime = JavaRuntime.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("contract-node")
                .clusterNodes(Set.of("contract-node"))
                .transport(new PeerlessTransportFixture())
                .stateMachine(store)
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode())
                .electionTimeout(25)
                .heartbeatInterval(10_000)
                .build();
        node.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        waitUntil(node::isLeader);
        server = new HttpApiServer(0, node, store);
        server.start().get(10, TimeUnit.SECONDS);
        return store;
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "condition was not met before the deadline");
    }


    private static JsonNode readCatalog(URI endpoint, String serviceName) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        endpoint.resolve("/v1/catalog/service/" + serviceName))
                .GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return new ObjectMapper().readTree(response.body());
        }
    }
}

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
import dev.mars.qraft.client.QraftClient;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.common.ServiceDefinition;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.server.QraftServerService;
import dev.mars.qraft.server.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of tenancy (tenancy list, Phase 4): three servers in one JVM, and two clients in two tenants. Each
 * client registers its service and writes its keys in its own tenant and namespace, and through its local API sees
 * only its own; a client cannot register into a tenant that does not exist until it is created; and deleting a
 * namespace removes its keys for both clients, and refuses writes there afterwards.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
@Tag("e2e")
class TenancyEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    private final List<RaftExecutorsFixture> executors = new ArrayList<>();
    private final List<QraftServerService> servers = new ArrayList<>();
    private final List<QraftClient> clients = new ArrayList<>();
    private final List<Integer> httpPorts = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() throws Exception {
        CleanupHelper cleanup = new CleanupHelper();
        for (QraftClient client : clients.reversed()) cleanup.run(() -> client.shutdown().get(10, TimeUnit.SECONDS));
        for (QraftServerService server : servers.reversed()) cleanup.run(() -> server.stop().get(30, TimeUnit.SECONDS));
        for (RaftExecutorsFixture executor : executors) cleanup.run(() -> executor.close().get(30, TimeUnit.SECONDS));
        cleanup.run(http::close);
        cleanup.rethrow();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void twoClientsInTwoTenantsSeeOnlyTheirOwnAndADeletedNamespaceIsGoneForBoth() throws Exception {
        startCluster();
        waitUntil(() -> created("/v1/tenant", "{\"Name\":\"acme\"}"), "acme created on the leader");
        waitUntil(() -> created("/v1/namespace", "{\"Name\":\"prod\",\"Tenant\":\"acme\"}"), "acme/prod created");
        QraftClient acme = startClient("host-a", "acme", "prod", List.of(0, 1, 2));
        QraftClient beta = startClient("host-b", "beta", "default", List.of(2, 1, 0));
        waitUntil(() -> acme.localApi().isReady(), "the acme client registered");
        assertTrue(!beta.localApi().isReady(), "the beta client cannot register while its tenant does not exist");

        waitUntil(() -> created("/v1/tenant", "{\"Name\":\"beta\"}"), "beta created");
        waitUntil(() -> beta.localApi().isReady(), "the beta client registers once its tenant exists");

        waitUntil(() -> services(acme).equals(List.of("billing")), "acme sees its own service alone");
        waitUntil(() -> services(beta).equals(List.of("billing")), "beta sees its own service alone");
        assertEquals("true", text(local(acme, "PUT", "/v1/kv/config", "acme-value")));
        assertEquals("true", text(local(beta, "PUT", "/v1/kv/config", "beta-value")));
        waitUntil(() -> "acme-value".equals(raw(acme, "/v1/kv/config?raw")), "acme reads its own key");
        waitUntil(() -> "beta-value".equals(raw(beta, "/v1/kv/config?raw")), "beta reads its own key");
        waitUntil(() -> "acme-value".equals(raw(beta, "/v1/kv/config?raw", "X-Qraft-Tenant", "acme",
                "X-Qraft-Namespace", "prod")), "beta reads acme's key only by naming its scope");

        // A namespace that holds a key, and no service a client keeps registered.
        assertEquals(200, local(acme, "PUT", "/v1/namespace", "{\"Name\":\"scratch\"}").statusCode());
        assertEquals("true", text(local(acme, "PUT", "/v1/kv/temp", "t", "X-Qraft-Namespace", "scratch")));
        assertEquals("true", text(local(acme, "DELETE", "/v1/namespace/scratch", null)));
        waitUntil(() -> local(beta, "GET", "/v1/kv/temp", null, "X-Qraft-Tenant", "acme",
                "X-Qraft-Namespace", "scratch").statusCode() == 404, "the deleted namespace's key is gone for beta");
        assertEquals(404, local(acme, "PUT", "/v1/kv/temp", "t", "X-Qraft-Namespace", "scratch").statusCode(),
                "and nothing more is written there");
    }

    private boolean created(String path, String body) {
        for (int port : httpPorts) {
            try {
                HttpResponse<String> answer = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + path)).PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (answer.statusCode() == 200 || answer.statusCode() == 409) return true;
            } catch (IOException | InterruptedException unavailable) {
                // The next server is tried.
            }
        }
        return false;
    }

    private List<String> services(QraftClient client) {
        HttpResponse<byte[]> answer = local(client, "GET", "/v1/catalog/services", null);
        if (answer.statusCode() != 200) return List.of();
        try {
            JsonNode services = JSON.readTree(answer.body());
            List<String> names = new ArrayList<>();
            if (services.isObject()) services.fieldNames().forEachRemaining(names::add);
            else services.forEach(service -> names.add(service.isTextual() ? service.asText()
                    : service.path("serviceName").asText()));
            return names;
        } catch (IOException notJson) {
            return List.of();
        }
    }

    private void startCluster() throws Exception {
        Map<String, Integer> raftPorts = new LinkedHashMap<>();
        Map<String, String> nodes = new LinkedHashMap<>();
        for (String id : List.of("server1", "server2", "server3")) {
            int raft = freePort();
            raftPorts.put(id, raft);
            nodes.put(id, "localhost:" + raft);
        }
        for (String id : nodes.keySet()) {
            int port = freePort();
            httpPorts.add(port);
            Path configuration = root.resolve(id + ".json");
            JSON.writeValue(configuration.toFile(), Map.of("version", 1, "server", Map.of(
                    "id", id, "http", Map.of("port", port), "apiGrpcPort", freePort(),
                    "raft", Map.of("port", raftPorts.get(id), "nodes", nodes, "electionTimeoutMs", 600,
                            "heartbeatIntervalMs", 150, "storage", Map.of("path", root.resolve(id).toString())),
                    "telemetry", Map.of("enabled", false))));
            AppConfig config = AppConfig.fromFile(configuration);
            config.validate();
            RaftExecutorsFixture executor = RaftExecutorsFixture.create();
            executors.add(executor);
            servers.add(new QraftServerService(executor.stateLoop(), executor.blockingTasks(), config));
        }
        for (QraftServerService server : servers) server.start().get(30, TimeUnit.SECONDS);
    }

    private QraftClient startClient(String nodeName, String tenant, String namespace, List<Integer> serverOrder)
            throws Exception {
        Path data = Files.createDirectories(root.resolve(nodeName));
        QraftClient client = new QraftClient(ClientConfiguration.builder()
                .nodeName(nodeName).dataDirectory(data).address("127.0.0.1").clientPort(0)
                .serverUrls(serverOrder.stream().map(i -> URI.create("http://localhost:" + httpPorts.get(i))).toList())
                .heartbeatInterval(100).requestTimeoutMs(5_000).registrationRetryMinMs(50)
                .registrationRetryMaxMs(250).contactFreshnessMs(5_000).shutdownTimeoutMs(3_000)
                .tenant(tenant).namespace(namespace)
                .services(List.of(new ServiceDefinition(nodeName + "-billing", "billing", "127.0.0.1", 9090,
                        List.of(), Map.of(), true)))
                .build());
        clients.add(client);
        client.start().get(15, TimeUnit.SECONDS);
        return client;
    }

    private HttpResponse<byte[]> local(QraftClient client, String method, String path, String body,
                                       String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + client.localApi().port()
                + path)).method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("the local API did not answer", e);
        }
    }

    private String raw(QraftClient client, String path, String... headers) {
        HttpResponse<byte[]> answer = local(client, "GET", path, null, headers);
        return answer.statusCode() == 200 ? text(answer) : null;
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static void waitUntil(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(), description);
    }
}

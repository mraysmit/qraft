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
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.http.AclHttpSettings;
import dev.mars.qraft.server.http.HttpApiServer;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code GET /v1/client/metrics} on the client's local API (observability list, Phase 2, decision 4): a client
 * that has registered answers its readiness and its reconciliation passes, as JSON and, with
 * {@code ?format=prometheus}, as Prometheus text; any other query is refused, and only {@code GET} is taken.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class ClientMetricsRouteTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dataRoot;

    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private HttpApiServer server;
    private QraftClient client;
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() throws Exception {
        try {
            if (client != null) client.shutdown().get(10, TimeUnit.SECONDS);
            if (server != null) server.close();
            if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        } finally {
            if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
            http.close();
        }
    }

    private HttpResponse<String> local(String method, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + client.localApi().port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aRegisteredClientAnswersItsMetricsAsJsonAndAsPrometheusText() throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("metrics-server").clusterNodes(Set.of("metrics-server"))
                .transport(new PeerlessTransportFixture()).stateMachine(store)
                .commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null,
                HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, AclHttpSettings.disabled());
        server.start().get(10, TimeUnit.SECONDS);
        client = new QraftClient(ClientConfiguration.builder()
                .nodeName("metrics-client").dataDirectory(dataRoot.resolve("client")).address("127.0.0.1")
                .clientPort(0).serverUrl("http://127.0.0.1:" + server.port()).heartbeatInterval(50)
                .requestTimeoutMs(5_000).registrationRetryMinMs(20).registrationRetryMaxMs(100)
                .services(List.of(new ServiceDefinition("web-1", "web", "127.0.0.1", 9090, List.of(), Map.of(), true)))
                .build());
        client.start().get(10, TimeUnit.SECONDS);
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!client.localApi().isReady() && System.nanoTime() < deadline) Thread.sleep(20);

        HttpResponse<String> json = local("GET", "/v1/client/metrics");
        assertEquals(200, json.statusCode(), json.body());
        JsonNode metrics = JSON.readTree(json.body());
        boolean ready = false;
        for (JsonNode gauge : metrics.path("Gauges")) {
            if (gauge.path("Name").asText().equals("qraft.client.ready")) ready = gauge.path("Value").asLong() == 1;
        }
        assertTrue(ready, json.body());
        assertTrue(metrics.path("Counters").size() > 0, "a pass has run: " + json.body());

        HttpResponse<String> text = local("GET", "/v1/client/metrics?format=prometheus");
        assertEquals(200, text.statusCode(), text.body());
        assertTrue(text.headers().firstValue("Content-Type").orElseThrow().startsWith("text/plain"));
        assertTrue(text.body().contains("qraft_client_ready 1"), text.body());

        assertEquals(400, local("GET", "/v1/client/metrics?format=xml").statusCode());
        assertEquals(405, local("PUT", "/v1/client/metrics").statusCode());
    }
}

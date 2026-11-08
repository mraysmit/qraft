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

package dev.mars.qraft.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the indexes on the server's reads (catalog indexes list, Phase 1, decisions 2 and 4): a catalog read, a node's
 * services and a health read answer each instance's {@code createIndex} and {@code modifyIndex}, the latter moving when
 * the registration changes; and {@code /raft/status} answers the applied index.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class HttpApiServerCatalogIndexTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private HttpApiServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() throws Exception {
        try {
            if (server != null) server.close();
            if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        } finally {
            if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
            http.close();
        }
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String registration(int port) {
        return "{\"service\":{\"serviceId\":\"web-1\",\"serviceName\":\"web\",\"address\":\"127.0.0.1\",\"port\":"
                + port + "}}";
    }

    @Test
    void readsAnswerEachInstancesIndexesAndTheStatusItsAppliedIndex() throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("index-node").clusterNodes(Set.of("index-node")).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null);
        server.start().get(10, TimeUnit.SECONDS);

        assertEquals(200, send("PUT", "/v1/catalog/register", registration(8080), "X-Qraft-Node", "host-a").statusCode());
        JsonNode first = JSON.readTree(send("GET", "/v1/catalog/service/web", null).body()).get(0);
        long created = first.path("createIndex").asLong();
        assertTrue(created > 0, first.toString());
        assertEquals(created, first.path("modifyIndex").asLong());

        assertEquals(200, send("PUT", "/v1/catalog/register", registration(9090), "X-Qraft-Node", "host-a").statusCode());
        JsonNode changed = JSON.readTree(send("GET", "/v1/catalog/service/web", null).body()).get(0);
        assertEquals(created, changed.path("createIndex").asLong());
        assertTrue(changed.path("modifyIndex").asLong() > created);
        assertEquals(9090, changed.path("port").asInt(), "the instance's own fields are answered as before");

        JsonNode health = JSON.readTree(send("GET", "/v1/health/service/web", null).body()).get(0);
        assertEquals(changed.path("modifyIndex").asLong(), health.path("service").path("modifyIndex").asLong());
        JsonNode nodeServices = JSON.readTree(send("GET", "/v1/catalog/node-services/host-a", null).body());
        assertTrue(nodeServices.toString().contains("\"createIndex\":" + created), nodeServices.toString());

        HttpResponse<String> status = send("GET", "/raft/status", null);
        assertEquals(Long.toString(store.getLastAppliedIndex()), status.headers().firstValue("X-Qraft-Index")
                .orElseThrow(), "the status read answers its applied index");
    }
}

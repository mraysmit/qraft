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
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.health.ExpiryScheduler;
import dev.mars.qraft.server.http.AclHttpSettings;
import dev.mars.qraft.server.http.HttpApiServer;
import dev.mars.qraft.server.session.SessionTtlExpiry;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code /v1/session} through the client's local API (sessions list, Phase 3, decision 5): a session created
 * without a node is bound to the client's own, as Consul's agent binds it; one that names a node keeps it; the other
 * session routes and a lock's acquisition are forwarded, and their answers passed on as they came.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class ClientSessionForwardingTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dataRoot;

    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private QraftStateStore store;
    private HttpApiServer server;
    private SessionTtlExpiry ttl;
    private QraftClient client;
    private final ScheduledExecutorService ttlExecutor = Executors.newSingleThreadScheduledExecutor();
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() throws Exception {
        try {
            if (client != null) client.shutdown().get(10, TimeUnit.SECONDS);
            if (ttl != null) ttl.close();
            if (server != null) server.close();
            if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        } finally {
            ttlExecutor.shutdownNow();
            if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
            http.close();
        }
    }

    private void start() throws Exception {
        runtime = RaftExecutorsFixture.create();
        store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("session-server").clusterNodes(Set.of("session-server"))
                .transport(new PeerlessTransportFixture()).stateMachine(store)
                .commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null,
                HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, AclHttpSettings.disabled());
        ttl = SessionTtlExpiry.attach(node, store.getSessions(), node::submitCommand,
                ExpiryScheduler.of(ttlExecutor), Clock.systemUTC(), Duration.ofSeconds(1));
        server.useSessionTtl(ttl);
        server.start().get(10, TimeUnit.SECONDS);

        client = new QraftClient(ClientConfiguration.builder()
                .nodeName("session-client").dataDirectory(dataRoot.resolve("client")).address("127.0.0.1")
                .clientPort(0).serverUrl("http://127.0.0.1:" + server.port()).heartbeatInterval(50)
                .requestTimeoutMs(5_000).registrationRetryMinMs(20).registrationRetryMaxMs(100).build());
        client.start().get(10, TimeUnit.SECONDS);
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!store.getNodes().containsKey("session-client") && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(store.getNodes().containsKey("session-client"), "the client registered its node");
    }

    private HttpResponse<byte[]> local(String method, String pathAndQuery, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + client.localApi().port()
                + pathAndQuery)).method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private JsonNode ok(HttpResponse<byte[]> response) throws Exception {
        assertEquals(200, response.statusCode(), text(response));
        return JSON.readTree(response.body());
    }

    @Test
    void aSessionWithoutANodeIsTheClientsAndTheOtherRoutesAreForwarded() throws Exception {
        start();

        String id = ok(local("PUT", "/v1/session/create", "{\"Name\":\"leader\",\"TTL\":\"10s\"}")).path("ID").asText();
        assertEquals("session-client", store.getSessions().get(id).orElseThrow().node(), "the client's own node");
        String bare = ok(local("PUT", "/v1/session/create", null)).path("ID").asText();
        assertEquals("session-client", store.getSessions().get(bare).orElseThrow().node(), "an empty body too");
        assertEquals(400, local("PUT", "/v1/session/create", "{\"Node\":\"elsewhere\"}").statusCode(),
                "a named node is kept, and the server's refusal passed on");
        assertEquals(400, local("PUT", "/v1/session/create", "[1]").statusCode());

        assertEquals(id, ok(local("GET", "/v1/session/info/" + id, null)).get(0).path("ID").asText());
        assertEquals(2, ok(local("GET", "/v1/session/node/session-client", null)).size());
        assertEquals(2, ok(local("GET", "/v1/session/list", null)).size());
        assertEquals(id, ok(local("PUT", "/v1/session/renew/" + id, null)).get(0).path("ID").asText());

        assertEquals("true", text(local("PUT", "/v1/kv/locks/leader?acquire=" + id, "me")));
        assertEquals(id, ok(local("GET", "/v1/kv/locks/leader", null)).get(0).path("Session").asText());
        assertEquals("true", text(local("PUT", "/v1/session/destroy/" + id, null)));
        assertEquals(1, ok(local("GET", "/v1/session/list", null)).size());
    }
}

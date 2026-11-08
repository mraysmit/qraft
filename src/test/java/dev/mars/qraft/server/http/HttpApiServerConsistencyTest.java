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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests read consistency and blocking queries on the server (consistency list, Phase 3): {@code consistent},
 * {@code stale} and the default on the leader, {@code consistent} refused on a server without a leader, the
 * leader-contact headers, blocking key/value and catalog reads that wake on a change or answer at their wait, and the
 * refusal of malformed options.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class HttpApiServerConsistencyTest {
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

    private void start(Set<String> members) throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("c-node").clusterNodes(members).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        if (members.size() == 1) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        }
        server = new HttpApiServer(0, node, store);
        server.start().get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<String> send(String method, String pathAndQuery, String body, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                + pathAndQuery)).method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    private static String code(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("code").asText();
    }

    @Test
    void theLeaderAnswersEveryModeAndSaysItIsTheLeader() throws Exception {
        start(Set.of("c-node"));
        send("PUT", "/v1/kv/a", "1");

        for (String query : new String[] {"", "?stale", "?consistent"}) {
            HttpResponse<String> kv = send("GET", "/v1/kv/a" + query, null);
            assertEquals(200, kv.statusCode(), query + ": " + kv.body());
            assertEquals("true", header(kv, "X-Qraft-Known-Leader"), query);
            assertEquals("0", header(kv, "X-Qraft-Last-Contact"), query);
            HttpResponse<String> nodes = send("GET", "/v1/catalog/nodes" + query, null);
            assertEquals(200, nodes.statusCode(), query + ": " + nodes.body());
            assertEquals("true", header(nodes, "X-Qraft-Known-Leader"), query);
        }
        assertEquals(200, send("GET", "/v1/health/service/web?passing&consistent", null).statusCode());
    }

    @Test
    void aServerWithoutALeaderRefusesAConsistentReadAndAnswersAStaleOne() throws Exception {
        start(Set.of("c-node", "absent-peer"));

        HttpResponse<String> consistent = send("GET", "/v1/catalog/nodes?consistent", null);
        assertEquals(503, consistent.statusCode(), consistent.body());
        assertEquals("leader_unavailable", code(consistent));
        assertEquals(503, send("GET", "/v1/kv/a?consistent", null).statusCode());

        HttpResponse<String> stale = send("GET", "/v1/catalog/nodes?stale", null);
        assertEquals(200, stale.statusCode(), stale.body());
        assertEquals("false", header(stale, "X-Qraft-Known-Leader"));
    }

    @Test
    void aBlockingKeyValueReadWakesOnAWriteAndOtherwiseAnswersAtItsWait() throws Exception {
        start(Set.of("c-node"));
        send("PUT", "/v1/kv/a", "1");
        long index = Long.parseLong(header(send("GET", "/v1/kv/a", null), "X-Qraft-Index"));

        long started = System.nanoTime();
        HttpResponse<String> timedOut = send("GET", "/v1/kv/a?index=" + index + "&wait=300ms", null);
        assertEquals(200, timedOut.statusCode());
        assertEquals(Long.toString(index), header(timedOut, "X-Qraft-Index"));
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(300), "it waited");

        CompletableFuture<HttpResponse<String>> blocked = CompletableFuture.supplyAsync(() -> {
            try {
                return send("GET", "/v1/kv/a?index=" + index + "&wait=30s", null);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(200);
        assertFalse(blocked.isDone(), "nothing changed yet");
        send("PUT", "/v1/kv/a", "2");

        HttpResponse<String> woken = blocked.get(10, TimeUnit.SECONDS);
        assertTrue(Long.parseLong(header(woken, "X-Qraft-Index")) > index);
        assertTrue(woken.body().contains("\"Value\":\"Mg==\""), woken.body());
    }

    @Test
    void aBlockingCatalogReadWakesOnARegistration() throws Exception {
        start(Set.of("c-node"));
        long index = Long.parseLong(header(send("GET", "/v1/catalog/nodes", null), "X-Qraft-Index"));
        CompletableFuture<HttpResponse<String>> blocked = CompletableFuture.supplyAsync(() -> {
            try {
                return send("GET", "/v1/catalog/nodes?index=" + index + "&wait=30s", null);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(200);
        assertFalse(blocked.isDone());

        HttpResponse<String> registered = send("PUT", "/v1/catalog/register",
                "{\"node\":{\"address\":\"127.0.0.1\"}}", "X-Qraft-Node", "web-1");
        assertEquals(200, registered.statusCode(), registered.body());

        HttpResponse<String> woken = blocked.get(10, TimeUnit.SECONDS);
        assertTrue(Long.parseLong(header(woken, "X-Qraft-Index")) > index);
        assertTrue(woken.body().contains("web-1"), woken.body());
    }

    @Test
    void malformedOptionsAreRefused() throws Exception {
        start(Set.of("c-node"));

        for (String query : new String[] {"?stale&consistent", "?index=x", "?index=-1", "?wait=5", "?wait=11m",
                "?wait=x", "?other"}) {
            HttpResponse<String> nodes = send("GET", "/v1/catalog/nodes" + query, null);
            assertEquals(400, nodes.statusCode(), query + ": " + nodes.body());
            assertEquals("invalid_query", code(nodes), query);
            assertEquals(400, send("GET", "/v1/kv/a" + query, null).statusCode(), "kv " + query);
        }
    }
}

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

package dev.mars.qraft.client.api;

import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.http.HttpApiServer;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the client library's key/value calls (client library list, Phase 1, decisions 1 to 3) against a real server:
 * each call with its flags and compare-and-set, a blocking read woken by a write, the scope and token headers, and a
 * refusal answered as {@link QraftApiException}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class QraftApiKeyValueTest {
    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private HttpApiServer server;
    private QraftApi api;

    @AfterEach
    void stop() throws Exception {
        try {
            if (api != null) api.close();
            if (server != null) server.close();
            if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        } finally {
            if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
        }
    }

    private void start() throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("library-node").clusterNodes(Set.of("library-node")).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null);
        server.start().get(10, TimeUnit.SECONDS);
        api = QraftApi.builder().address(URI.create("http://127.0.0.1:" + server.port())).build();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void keysAreWrittenReadListedComparedAndDeleted() throws Exception {
        start();
        KeyValue kv = api.keyValue();
        assertEquals(Optional.empty(), kv.get("app/missing"));
        assertTrue(kv.put("app/config", bytes("one")));
        assertTrue(kv.put("app/flagged", bytes("two"), 42));
        assertTrue(kv.put("app/nested/deep", bytes("three")));

        KvEntry config = kv.get("app/config").orElseThrow();
        assertEquals("app/config", config.key());
        assertArrayEquals(bytes("one"), config.value());
        assertTrue(config.createIndex() > 0);
        assertEquals(config.createIndex(), config.modifyIndex());
        assertEquals(42, kv.get("app/flagged").orElseThrow().flags());
        assertEquals(List.of("app/config", "app/flagged", "app/nested/deep"),
                kv.list("app/").stream().map(KvEntry::key).toList());
        assertEquals(List.of("app/config", "app/flagged", "app/nested/"), kv.keys("app/", "/"));
        assertEquals(List.of(), kv.list("nothing/"));

        assertFalse(kv.cas("app/config", bytes("lost"), config.modifyIndex() + 100), "a stale index");
        assertTrue(kv.cas("app/config", bytes("won"), config.modifyIndex()));
        assertArrayEquals(bytes("won"), kv.get("app/config").orElseThrow().value());
        assertTrue(kv.cas("app/created", bytes("new"), 0), "index 0 creates only");
        assertFalse(kv.cas("app/created", bytes("again"), 0));

        assertTrue(kv.delete("app/config"));
        assertEquals(Optional.empty(), kv.get("app/config"));
        assertTrue(kv.deleteTree("app/"));
        assertEquals(List.of(), kv.list("app/"));
    }

    @Test
    void aBlockingReadWaitsPastItsIndexUntilAWrite() throws Exception {
        start();
        KeyValue kv = api.keyValue();
        kv.put("flag", bytes("off"));
        Indexed<Optional<KvEntry>> first = kv.get("flag", QueryOptions.DEFAULT);
        assertTrue(first.index() > 0);

        CompletableFuture<Indexed<Optional<KvEntry>>> blocked = CompletableFuture.supplyAsync(
                () -> kv.get("flag", QueryOptions.waitPast(first.index(), Duration.ofSeconds(20))));
        Thread.sleep(300);
        assertFalse(blocked.isDone(), "nothing has changed");
        kv.put("flag", bytes("on"));
        Indexed<Optional<KvEntry>> woken = blocked.get(15, TimeUnit.SECONDS);
        assertTrue(woken.index() > first.index());
        assertArrayEquals(bytes("on"), woken.value().orElseThrow().value());
        assertTrue(kv.get("flag", QueryOptions.consistent()).value().isPresent(), "a consistent read");
    }

    @Test
    void aRefusalIsAnExceptionWithTheServersCodeAndTheScopeHeadersAreSent() throws Exception {
        start();
        try (QraftApi scoped = QraftApi.builder().address(URI.create("http://127.0.0.1:" + server.port()))
                .tenant("acme").namespace("billing").build()) {
            QraftApiException refused = assertThrows(QraftApiException.class,
                    () -> scoped.keyValue().put("app/config", bytes("v")));
            assertEquals(404, refused.status());
            assertEquals("tenant_not_found", refused.code());
            assertTrue(refused.getMessage().contains("acme"), refused.getMessage());
        }
        QraftApiException invalid = assertThrows(QraftApiException.class,
                () -> api.keyValue().acquire("app/lock", bytes("me"), "no-such-session"));
        assertEquals(400, invalid.status());
        assertEquals("invalid_session", invalid.code());
    }

    @Test
    void theTokenIsSentOnEveryCall() throws Exception {
        Map<String, String> seen = new ConcurrentHashMap<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            seen.put(exchange.getRequestMethod() + " " + exchange.getRequestURI(),
                    String.valueOf(exchange.getRequestHeaders().getFirst("X-Qraft-Token")));
            byte[] body = bytes("true");
            exchange.getResponseHeaders().set("X-Qraft-Index", "7");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        try (QraftApi tokened = QraftApi.builder().address(URI.create("http://127.0.0.1:" + stub.getAddress().getPort()))
                .token("secret-token").build()) {
            assertTrue(tokened.keyValue().put("k", bytes("v")));
            assertTrue(tokened.keyValue().delete("k"));
        } finally {
            stub.stop(0);
        }
        assertEquals(Map.of("PUT /v1/kv/k", "secret-token", "DELETE /v1/kv/k", "secret-token"), seen);
    }
}

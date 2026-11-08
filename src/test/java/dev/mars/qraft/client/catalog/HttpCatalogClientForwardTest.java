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

package dev.mars.qraft.client.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests how a forwarded request moves across the server seeds (key/value list, Phase 4): a read moves past a failed
 * server; a write moves past one that has no leader, and stops at one whose outcome is unknown, so that no write is
 * applied twice.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class HttpCatalogClientForwardTest {
    private final List<HttpServer> servers = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private HttpCatalogClient client;

    @AfterEach
    void stop() {
        if (client != null) client.close();
        servers.forEach(server -> server.stop(0));
        http.close();
    }

    /** A server that answers every request with {@code status} and {@code body}, counting them. */
    private URI server(int status, String body, AtomicInteger hits) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        servers.add(server);
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private HttpCatalogClient client(URI... seeds) {
        client = new HttpCatalogClient(http, new ObjectMapper(), List.of(seeds), "node", "default", "default",
                "dc", "region", Duration.ofSeconds(5));
        return client;
    }

    private static String error(String code) {
        return "{\"code\":\"" + code + "\",\"message\":\"m\",\"retryable\":true}";
    }

    @Test
    void aWriteMovesPastAServerWithNoLeaderAndStopsAtAnUnknownOutcome() throws Exception {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        HttpCatalogClient forwarding = client(server(503, error("leader_unavailable"), first),
                server(200, "true", second));

        ForwardedResponse answer = forwarding.forward("PUT", "/v1/kv/a", new byte[] {1}, "r-1", Map.of())
                .join().orElseThrow();
        assertEquals(200, answer.status());
        assertEquals(1, first.get(), "a fresh client asks its seeds in order");
        assertEquals(1, second.get(), "and the write, which the first did not apply, reached the second");

        AtomicInteger unknown = new AtomicInteger();
        AtomicInteger never = new AtomicInteger();
        HttpCatalogClient stopping = client(server(503, error("outcome_unknown"), unknown), server(200, "true", never));
        ForwardedResponse passedOn = stopping.forward("PUT", "/v1/kv/a", new byte[] {1}, "r-2", Map.of())
                .join().orElseThrow();
        assertEquals(503, passedOn.status());
        assertEquals(1, unknown.get());
        assertEquals(0, never.get(), "a write whose outcome is unknown is not sent again");
    }

    @Test
    void aBlockingReadWaitsAsLongAsItAsksPlusTheRequestTimeout() throws Exception {
        // Consistency list, Phase 4: the server holds a blocking read until its wait; the client must not give up first.
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/", exchange -> {
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        slow.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        slow.start();
        servers.add(slow);
        client = new HttpCatalogClient(http, new ObjectMapper(),
                List.of(URI.create("http://127.0.0.1:" + slow.getAddress().getPort())), "node", "default", "default",
                "dc", "region", Duration.ofSeconds(1));

        assertEquals(200, client.forward("GET", "/v1/kv/a?index=5&wait=2s", new byte[0], "r-4", Map.of())
                .join().orElseThrow().status(), "the key/value read outlasts the 1 s request timeout");
        assertEquals(200, client.forwardRead("/v1/catalog/nodes?index=5&wait=2s", "r-5", Map.of())
                .join().orElseThrow().status(), "and so does the discovery read");
    }

    @Test
    void aReadMovesPastAFailedServer() throws Exception {
        AtomicInteger failing = new AtomicInteger();
        AtomicInteger answering = new AtomicInteger();
        HttpCatalogClient forwarding = client(server(500, error("internal"), failing), server(200, "[]", answering));

        ForwardedResponse answer = forwarding.forward("GET", "/v1/kv/a", new byte[0], "r-3", Map.of())
                .join().orElseThrow();

        assertEquals(200, answer.status());
        assertEquals(1, answering.get());
    }
}

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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a leader checks its hold on the key with consistent reads (client library list, Phase 4): against a
 * stub API whose default reads come from a server that has not yet applied the acquisition, as a client's first
 * server may be, a contender that acquired the key keeps its leadership, since only a consistent read sees the
 * acquisition, and a stale read is no reason to give it up.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class LeaderElectionStaleReadTest {
    private static final String HELD = "[{\"Key\":\"leader\",\"Value\":\"\",\"Flags\":0,\"CreateIndex\":5,"
            + "\"ModifyIndex\":5,\"LockIndex\":1,\"Session\":\"s1\"}]";
    private HttpServer stub;
    private QraftApi api;
    private LeaderElection election;

    @AfterEach
    void stop() {
        if (election != null) election.close();
        if (api != null) api.close();
        if (stub != null) stub.stop(0);
    }

    private static void answer(HttpExchange exchange, int status, String body, long index) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("X-Qraft-Index", Long.toString(index));
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void aLeaderKeepsItsLeadershipWhenADefaultReadIsBehindItsAcquisition() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1/session/", exchange -> answer(exchange, 200,
                exchange.getRequestURI().getPath().endsWith("/create") ? "{\"ID\":\"s1\"}" : "true", 5));
        stub.createContext("/v1/kv/leader", exchange -> {
            String query = String.valueOf(exchange.getRequestURI().getRawQuery());
            if (!exchange.getRequestMethod().equals("GET")) {
                answer(exchange, 200, "true", 5);
            } else if (query.contains("consistent")) {
                // A blocking read past the index answers after a while with nothing new, as a server would.
                if (query.contains("index=5")) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                answer(exchange, 200, HELD, 5);
            } else {
                answer(exchange, 404, "{\"code\":\"key_not_found\",\"message\":\"No such key: leader\"}", 4);
            }
        });
        stub.start();
        api = QraftApi.builder().address(URI.create("http://127.0.0.1:" + stub.getAddress().getPort())).build();
        BlockingQueue<String> heard = new LinkedBlockingQueue<>();
        election = LeaderElection.builder(api, "leader").ttl(Duration.ofSeconds(10)).retryWait(Duration.ofMillis(200))
                .listener(new LeaderElection.Listener() {
                    @Override
                    public void elected() {
                        heard.add("elected");
                    }

                    @Override
                    public void lost() {
                        heard.add("lost");
                    }
                }).start();

        assertEquals("elected", heard.poll(10, TimeUnit.SECONDS));
        assertNull(heard.poll(1500, TimeUnit.MILLISECONDS), "a stale read is no reason to give up leadership");
        assertTrue(election.isLeader());
    }
}

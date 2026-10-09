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

package dev.mars.qraft.client;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Test helper that answers the server's node routes, for tests that stand a plain HTTP server in for a Qraft
 * server. A node write succeeds only with the answer a server gives: the node's name and what happened to it.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-09
 * @version 1.0
 */
public final class NodeAnswerHelper {
    public static final String REGISTER = "/v1/catalog/register";
    public static final String DEREGISTER = "/v1/catalog/deregister";
    public static final String HEARTBEAT = "/v1/catalog/node/heartbeat";

    private NodeAnswerHelper() {
    }

    /** Accepts a node write as a server does: the answer follows from the route the request was sent to. */
    public static void accept(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        String outcome = path.equals(DEREGISTER) ? "deregistered" : path.equals(HEARTBEAT) ? "accepted" : "registered";
        answer(exchange, 200, outcome, true);
    }

    /**
     * Answers a node write. With status 200 the body names the node of the request's identity header and gives
     * {@code outcome} the value {@code value}; any other status is sent without a body.
     */
    public static void answer(HttpExchange exchange, int status, String outcome, boolean value) throws IOException {
        if (status != 200) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        String node = exchange.getRequestHeaders().getFirst("X-Qraft-Node");
        byte[] body = ("{\"node\":\"" + node + "\",\"" + outcome + "\":" + value + "}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}

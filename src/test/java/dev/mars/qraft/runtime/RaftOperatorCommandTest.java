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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link RaftOperatorCommand}, the {@code qraft operator raft} commands, after Consul's
 * {@code consul operator raft list-peers} and {@code remove-peer}, against a server that answers as a Qraft
 * server does:
 * <ul>
 *   <li>{@code list-peers} prints each server with its ID, address, state and vote;</li>
 *   <li>{@code remove-peer} sends the named server and the operator token, from {@code --token} or
 *       {@code --token-file}, to the address from {@code --http-addr};</li>
 *   <li>a server's refusal is printed with a failing exit code, and a misuse prints the usage.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
class RaftOperatorCommandTest {
    @TempDir
    Path directory;
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private volatile int removalStatus = 200;
    private volatile String removalBody = "{\"status\":\"REMOVED\",\"message\":\"Removed c (id-c)\"}";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/operator/raft/configuration", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            reply(exchange, 200, """
                    {"servers":[
                     {"serverId":"id-a","name":"a","address":"a:9080","voter":true,"leader":true},
                     {"serverId":"id-d","name":"d","address":"d:9080","voter":false,"leader":false}]}""");
        });
        server.createContext("/v1/operator/raft/peer", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            tokens.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Qraft-Token")));
            reply(exchange, removalStatus, removalBody);
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void listPeersPrintsEachServer() {
        int exit = run("raft", "list-peers", "--http-addr", address());

        assertEquals(0, exit, err());
        assertEquals(List.of("GET /v1/operator/raft/configuration"), requests);
        List<String> lines = out().lines().toList();
        assertEquals(3, lines.size(), out());
        assertEquals(List.of("Name", "ID", "Address", "State", "Voter"), List.of(lines.get(0).split("\\s+")));
        assertEquals(List.of("a", "id-a", "a:9080", "leader", "true"), List.of(lines.get(1).split("\\s+")));
        assertEquals(List.of("d", "id-d", "d:9080", "follower", "false"), List.of(lines.get(2).split("\\s+")));
    }

    @Test
    void removePeerSendsTheServerAndTheTokenFromAFile() throws Exception {
        Path tokenFile = Files.writeString(directory.resolve("token"), "from-the-file\n");

        int exit = run("raft", "remove-peer", "--name", "c", "--token-file", tokenFile.toString(),
                "--http-addr", address());

        assertEquals(0, exit, err());
        assertEquals(List.of("DELETE /v1/operator/raft/peer?name=c"), requests);
        assertEquals(List.of("from-the-file"), tokens, "the file's trailing newline is not part of the token");
        assertTrue(out().contains("Removed c (id-c)"), out());
    }

    @Test
    void removePeerSendsTheTokenOptionAndAnEncodedId() {
        int exit = run("raft", "remove-peer", "--id", "id with space", "--token", "from-the-option",
                "--http-addr", "http://" + address());

        assertEquals(0, exit, err());
        assertEquals(List.of("DELETE /v1/operator/raft/peer?id=id+with+space"), requests);
        assertEquals(List.of("from-the-option"), tokens);
    }

    @Test
    void anUnreadableTokenFileIsReportedAndNothingIsSent() {
        int exit = run("raft", "remove-peer", "--name", "c", "--token-file",
                directory.resolve("missing").toString(), "--http-addr", address());

        assertEquals(1, exit);
        assertTrue(err().contains("Could not read the token file"), err());
        assertTrue(requests.isEmpty());
    }

    @Test
    void aRefusalIsPrintedWithAFailingExitCode() {
        removalStatus = 403;
        removalBody = "{\"code\":\"permission_denied\",\"message\":\"The operator token is missing or wrong\"}";

        int exit = run("raft", "remove-peer", "--name", "c", "--http-addr", address());

        assertEquals(1, exit);
        assertTrue(err().contains("permission_denied: The operator token is missing or wrong"), err());
    }

    @Test
    void anUnreachableServerIsReported() {
        server.stop(0);

        int exit = run("raft", "list-peers", "--http-addr", address());

        assertEquals(1, exit);
        assertTrue(err().contains("Could not reach " + "http://" + address()), err());
    }

    @Test
    void misusePrintsTheUsage() {
        for (String[] misuse : new String[][] {
                {}, {"raft"}, {"raft", "rename-peer"}, {"clients", "list-peers"},
                {"raft", "remove-peer"}, {"raft", "remove-peer", "--id", "x", "--name", "y"},
                {"raft", "list-peers", "--http-addr"}, {"raft", "list-peers", "--verbose"},
                {"raft", "remove-peer", "--name", "c", "--token", "t", "--token-file", "f"}}) {
            err.reset();
            assertEquals(2, run(misuse), String.join(" ", misuse));
            assertTrue(err().contains("Usage: qraft operator raft"), String.join(" ", misuse) + ": " + err());
        }
        assertTrue(requests.isEmpty());
    }

    private int run(String... args) {
        return RaftOperatorCommand.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String address() {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

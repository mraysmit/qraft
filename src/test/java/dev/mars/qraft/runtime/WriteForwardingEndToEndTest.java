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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.common.tls.TlsMaterial;
import dev.mars.qraft.common.tls.TlsSettings;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of forwarding writes (write forwarding list, Phase 3): three servers serving only HTTPS, with mutual
 * TLS on the Raft port and ACLs under {@code deny}. A write through each follower is answered by the leader and applied
 * under the caller's token, and a write the token does not allow is refused by the leader, the follower answering
 * with that refusal.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
@Tag("e2e")
class WriteForwardingEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MANAGEMENT = "4e5f6a7b-8c9d-4e0f-a1b2-c3d4e5f6a7b8";

    @TempDir
    Path root;

    private final List<RaftExecutorsFixture> executors = new ArrayList<>();
    private final List<QraftServerService> servers = new ArrayList<>();
    private final List<Integer> httpPorts = new ArrayList<>();
    private HttpClient https;

    @AfterEach
    void stop() throws Exception {
        CleanupHelper cleanup = new CleanupHelper();
        for (QraftServerService server : servers.reversed()) cleanup.run(() -> server.stop().get(30, TimeUnit.SECONDS));
        for (RaftExecutorsFixture executor : executors) cleanup.run(() -> executor.close().get(30, TimeUnit.SECONDS));
        if (https != null) cleanup.run(https::close);
        cleanup.rethrow();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void aWriteThroughEachFollowerIsAnsweredByTheLeaderUnderTheCallersToken() throws Exception {
        startCluster();
        TlsMaterial caller = TlsMaterial.load(new TlsSettings("client.tls", TlsEndToEndTest.fixture("ca.pem"),
                Optional.of(TlsEndToEndTest.fixture("client.pem")), Optional.of(TlsEndToEndTest.fixture("client.key")),
                List.of("TLSv1.3", "TLSv1.2")));
        https = HttpClient.newBuilder().sslContext(caller.clientContext()).sslParameters(caller.clientParameters(true))
                .connectTimeout(Duration.ofSeconds(5)).build();
        waitUntil(() -> leader() >= 0, "a leader was elected and installed the management token");
        int leader = leader();
        String reader = JSON.readTree(send(leader, "PUT", "/v1/acl/token", "{\"description\":\"reads only\"}",
                MANAGEMENT).body()).path("secretId").asText();

        for (int follower = 0; follower < 3; follower++) {
            if (follower == leader) continue;
            int at = follower;
            waitUntil(() -> {
                HttpResponse<String> written = sendQuietly(at, "PUT", "/v1/kv/forwarded/server" + (at + 1), "v");
                return written != null && written.statusCode() == 200 && written.body().equals("true")
                        && written.headers().firstValue("X-Qraft-Forwarded-To").orElse("")
                        .equals("server" + (leader + 1));
            }, "a write through server " + (at + 1) + " is answered by the leader");
            HttpResponse<String> refused = send(follower, "PUT", "/v1/kv/forwarded/denied", "v", reader);
            assertEquals(403, refused.statusCode(), refused.body());
            assertEquals("permission_denied", JSON.readTree(refused.body()).path("code").asText());
            assertEquals("server" + (leader + 1), refused.headers().firstValue("X-Qraft-Forwarded-To").orElseThrow(),
                    "the leader refused it");
        }
        HttpResponse<String> keys = send(leader, "GET", "/v1/kv/forwarded/?keys", null, MANAGEMENT);
        assertEquals(200, keys.statusCode(), keys.body());
        assertEquals(2, JSON.readTree(keys.body()).size(), keys.body());
    }

    private int leader() {
        for (int i = 0; i < httpPorts.size(); i++) {
            HttpResponse<String> self = sendQuietly(i, "GET", "/v1/acl/token/self", null);
            HttpResponse<String> probe = sendQuietly(i, "GET", "/v1/kv/probe?consistent", null);
            if (self != null && self.statusCode() == 200 && probe != null && probe.statusCode() != 503) return i;
        }
        return -1;
    }

    private HttpResponse<String> sendQuietly(int server, String method, String path, String body) {
        try {
            return send(server, method, path, body, MANAGEMENT);
        } catch (Exception unanswered) {
            return null;
        }
    }

    private HttpResponse<String> send(int server, String method, String path, String body, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://localhost:" + httpPorts.get(server)
                + path)).timeout(Duration.ofSeconds(10)).method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("X-Qraft-Token", token);
        return https.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void startCluster() throws Exception {
        Path managementFile = Files.writeString(root.resolve("management-token"), MANAGEMENT);
        Map<String, Integer> raftPorts = new LinkedHashMap<>();
        Map<String, String> nodes = new LinkedHashMap<>();
        for (String id : List.of("server1", "server2", "server3")) {
            int raft = freePort();
            raftPorts.put(id, raft);
            nodes.put(id, "localhost:" + raft);
        }
        for (String id : nodes.keySet()) {
            int http = freePort();
            httpPorts.add(http);
            Path configuration = root.resolve(id + ".json");
            JSON.writeValue(configuration.toFile(), Map.of("version", 1, "server", Map.of(
                    "id", id, "http", Map.of("port", http), "apiGrpcPort", freePort(),
                    "raft", Map.of("port", raftPorts.get(id), "nodes", nodes, "electionTimeoutMs", 600,
                            "heartbeatIntervalMs", 150, "storage", Map.of("path", root.resolve(id).toString())),
                    "acl", Map.of("enabled", true, "defaultPolicy", "deny",
                            "initialManagementTokenFile", managementFile.toString()),
                    "tls", Map.of("caFile", TlsEndToEndTest.fixture("ca.pem").toString(),
                            "certFile", TlsEndToEndTest.fixture("server.pem").toString(),
                            "keyFile", TlsEndToEndTest.fixture("server.key").toString(),
                            "https", Map.of("enabled", true), "raft", Map.of("enabled", true)),
                    "telemetry", Map.of("enabled", false))));
            AppConfig config = AppConfig.fromFile(configuration);
            config.validate();
            RaftExecutorsFixture executor = RaftExecutorsFixture.create();
            executors.add(executor);
            servers.add(new QraftServerService(executor.stateLoop(), executor.blockingTasks(), config));
        }
        for (QraftServerService server : servers) server.start().get(30, TimeUnit.SECONDS);
    }

    private static void waitUntil(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(), description);
    }
}

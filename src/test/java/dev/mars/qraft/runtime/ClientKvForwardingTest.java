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
import dev.mars.qraft.client.acl.ClientAclSettings;
import dev.mars.qraft.client.acl.DownPolicy;
import dev.mars.qraft.client.acl.TokenKind;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.acl.AclRulesJson;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.http.AclHttpSettings;
import dev.mars.qraft.server.http.HttpApiServer;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.AclCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.acl.AclBuiltins;
import dev.mars.qraft.state.acl.AclPolicy;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;
import dev.mars.qraft.state.kv.KvScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code /v1/kv} through the client's local API (key/value list, Phase 4): the client forwards each read and
 * write to its servers with the caller's scope and token, and passes the answer on as it came, its raw value, index
 * and results filter among it; and the servers' ACL decisions reach the caller.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class ClientKvForwardingTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dataRoot;

    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private QraftStateStore store;
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

    private void startServer(boolean acl) throws Exception {
        runtime = RaftExecutorsFixture.create();
        store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("kv-server").clusterNodes(Set.of("kv-server")).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null,
                HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, acl
                ? new AclHttpSettings(true, dataRoot.resolve("server"), AclDefaultPolicy.DENY)
                : AclHttpSettings.disabled());
        server.start().get(10, TimeUnit.SECONDS);
    }

    private void startClient(String clientToken) throws Exception {
        ClientConfiguration.Builder configuration = ClientConfiguration.builder()
                .nodeName("kv-client").dataDirectory(dataRoot.resolve("client")).address("127.0.0.1").clientPort(0)
                .serverUrl("http://127.0.0.1:" + server.port()).heartbeatInterval(50).requestTimeoutMs(5_000)
                .registrationRetryMinMs(20).registrationRetryMaxMs(100);
        if (clientToken != null) {
            Path tokenFile = Files.writeString(Files.createDirectories(dataRoot.resolve("tokens")).resolve("client"),
                    clientToken);
            configuration.acl(new ClientAclSettings(true, AclDefaultPolicy.DENY, DownPolicy.EXTEND_CACHE,
                    ClientAclSettings.DEFAULT_TTL, ClientAclSettings.DEFAULT_TTL, ClientAclSettings.DEFAULT_TTL, false,
                    Map.of(TokenKind.CLIENT, tokenFile)));
        }
        client = new QraftClient(configuration.build());
        client.start().get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<byte[]> local(String method, String pathAndQuery, byte[] body, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + client.localApi().port()
                + pathAndQuery)).method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    @Test
    void readsAndWritesAreForwardedAndTheirAnswersPassedOnAsTheyCame() throws Exception {
        startServer(false);
        startClient(null);
        byte[] value = {0, 1, 2, (byte) 0xff};

        HttpResponse<byte[]> set = local("PUT", "/v1/kv/app/blob?flags=9", value);
        assertEquals(200, set.statusCode(), text(set));
        assertEquals("true", text(set));
        assertArrayEquals(value, store.getKv().get(KvScope.DEFAULT, "app/blob").orElseThrow().value(),
                "the raw body reached the server whole");

        HttpResponse<byte[]> raw = local("GET", "/v1/kv/app/blob?raw", null);
        assertEquals(200, raw.statusCode());
        assertArrayEquals(value, raw.body());
        HttpResponse<byte[]> entry = local("GET", "/v1/kv/app/blob", null);
        JsonNode first = JSON.readTree(entry.body()).get(0);
        assertEquals(9, first.path("Flags").asLong());
        assertEquals(first.path("ModifyIndex").asText(), entry.headers().firstValue("X-Qraft-Index").orElseThrow());

        submit(dev.mars.qraft.state.tenancy.TenancyCommand.createTenant("acme", ""));
        local("PUT", "/v1/kv/app/other", "x".getBytes(StandardCharsets.UTF_8), "X-Qraft-Tenant", "acme");
        assertTrue(store.getKv().get(new KvScope("acme", "default"), "app/other").isPresent(), "the scope is sent on");
        assertEquals("[\"app/blob\"]", text(local("GET", "/v1/kv/app/?keys", null)));
        assertEquals("true", text(local("DELETE", "/v1/kv/app/?recurse", null)));
        assertEquals(404, local("GET", "/v1/kv/app/blob", null).statusCode());
        assertEquals(400, local("GET", "/v1/kv/a?acquire=s", null).statusCode(), "the server's refusal is passed on");
    }

    @Test
    void theServersDecideUnderTheCallersTokenAndTheirFilterIsPassedOn() throws Exception {
        startServer(true);
        String management = UUID.randomUUID().toString();
        submit(AclCommand.createToken(token(management, AclBuiltins.GLOBAL_MANAGEMENT_ID)));
        String reader = UUID.randomUUID().toString();
        String policy = UUID.randomUUID().toString();
        submit(AclCommand.createPolicy(new AclPolicy(policy, "app-reader", "", AclRulesJson.parse(
                "{\"key_prefix\":{\"app/\":{\"policy\":\"read\"}},\"key\":{\"app/secret\":{\"policy\":\"deny\"}}}"))));
        submit(AclCommand.createToken(token(reader, policy)));
        startClient(management);
        for (String key : List.of("app/a", "app/secret"))
            assertEquals(200, local("PUT", "/v1/kv/" + key, new byte[] {1}, "X-Qraft-Token", management).statusCode());

        assertEquals(403, local("PUT", "/v1/kv/app/a", new byte[] {2}, "X-Qraft-Token", reader).statusCode());
        HttpResponse<byte[]> recursed = local("GET", "/v1/kv/app/?recurse", null, "X-Qraft-Token", reader);
        assertEquals(200, recursed.statusCode(), text(recursed));
        assertEquals(1, JSON.readTree(recursed.body()).size());
        assertEquals("true", recursed.headers().firstValue("X-Qraft-Results-Filtered-By-ACLs").orElseThrow());
    }

    private void submit(dev.mars.qraft.raft.RaftCommand command) throws Exception {
        assertInstanceOf(RaftCommandResult.Success.class, node.submitCommand(command).get(10, TimeUnit.SECONDS));
    }

    private static AclToken token(String secret, String policy) {
        return new AclToken(UUID.randomUUID().toString(), AclSecrets.hash(secret), "", List.of(policy), List.of(),
                List.of(), List.of(), Instant.now(), null, false);
    }
}

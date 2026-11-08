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
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.http.AclHttpSettings;
import dev.mars.qraft.server.http.HttpApiServer;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.kv.KvScope;
import dev.mars.qraft.state.tenancy.TenancyCommand;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests tenancy through the client's local API (tenancy list, Phase 3, decision 6): a request that names no tenant or
 * namespace acts in the client's own, and one that names another acts there; the tenancy routes are forwarded to the
 * servers, and their answers passed on as they came.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class ClientTenancyForwardingTest {
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

    private void start() throws Exception {
        runtime = RaftExecutorsFixture.create();
        store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("tenancy-server").clusterNodes(Set.of("tenancy-server"))
                .transport(new PeerlessTransportFixture()).stateMachine(store)
                .commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        for (TenancyCommand command : List.of(TenancyCommand.createTenant("acme", ""),
                TenancyCommand.createNamespace("acme", "prod", ""))) {
            assertInstanceOf(RaftCommandResult.Success.class, node.submitCommand(command).get(10, TimeUnit.SECONDS));
        }
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null,
                HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, AclHttpSettings.disabled());
        server.start().get(10, TimeUnit.SECONDS);
        client = new QraftClient(ClientConfiguration.builder()
                .nodeName("tenancy-client").dataDirectory(dataRoot.resolve("client")).address("127.0.0.1")
                .clientPort(0).serverUrl("http://127.0.0.1:" + server.port()).heartbeatInterval(50)
                .requestTimeoutMs(5_000).registrationRetryMinMs(20).registrationRetryMaxMs(100)
                .tenant("acme").namespace("prod").build());
        client.start().get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<String> local(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + client.localApi().port()
                + path)).method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> names(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        List<String> names = new ArrayList<>();
        JsonNode list = JSON.readTree(response.body());
        list.forEach(item -> names.add(item.path("Name").asText()));
        return names;
    }

    @Test
    void aRequestWithoutAScopeActsInTheClientsOwnAndTheTenancyRoutesAreForwarded() throws Exception {
        start();

        assertEquals("true", local("PUT", "/v1/kv/config", "own").body());
        assertTrue(store.getKv().get(new KvScope("acme", "prod"), "config").isPresent(), "the client's own scope");
        assertEquals("true", local("PUT", "/v1/kv/config", "default", "X-Qraft-Tenant", "default",
                "X-Qraft-Namespace", "default").body());
        assertTrue(store.getKv().get(KvScope.DEFAULT, "config").isPresent(), "the scope the caller names");

        assertEquals(200, local("PUT", "/v1/namespace", "{\"Name\":\"staging\"}").statusCode(),
                "a namespace in the client's tenant");
        assertEquals(List.of("default", "prod", "staging"), names(local("GET", "/v1/namespaces", null)));
        assertEquals(List.of("acme", "default"), names(local("GET", "/v1/tenants", null)));
        assertEquals(200, local("GET", "/v1/tenant/acme", null).statusCode());
        assertEquals("true", local("DELETE", "/v1/namespace/staging", null).body());
        assertEquals(404, local("GET", "/v1/namespace/staging", null).statusCode(), "the server's answer passed on");
    }
}

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

package dev.mars.qraft.controller.http;

import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.controller.raft.InMemoryTransportSimulatorFixture;
import dev.mars.qraft.controller.raft.ManualRaftClusterFixture;
import dev.mars.qraft.controller.raft.MembershipService;
import dev.mars.qraft.controller.raft.RaftConfiguration;
import dev.mars.qraft.controller.raft.RaftNode;
import dev.mars.qraft.controller.raft.RaftNodeMode;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.controller.ui.AdminUiConfig;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.controller.raft.ManualRaftClusterFixture.await;
import static dev.mars.qraft.controller.raft.ManualRaftClusterFixture.serverIdOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Raft operator endpoints of {@link HttpApiServer}, after Consul's {@code /v1/operator/raft}:
 * <ul>
 *   <li>{@code GET /v1/operator/raft/configuration} lists the servers, and is open, like {@code /raft/status};</li>
 *   <li>{@code DELETE /v1/operator/raft/peer?id=|name=} removes a server, and needs the operator token, sent as
 *       {@code X-Qraft-Token} or as a bearer token;</li>
 *   <li>each outcome of a removal has its own status code.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
class HttpApiServerOperatorTest {
    private static final Set<String> MEMBERS = Set.of("a", "b", "c");
    private static final String TOKEN = "operator-secret-token";
    private static final ObjectMapper JSON = new ObjectMapper();

    private JavaRuntime runtime;
    private ManualRaftClusterFixture cluster;
    private final Map<String, RaftNode> nodes = new HashMap<>();
    private RaftNode leader;
    private HttpApiServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        runtime = JavaRuntime.create();
        cluster = new ManualRaftClusterFixture(runtime);
        InMemoryTransportSimulatorFixture.clearAllTransports();
        for (String name : MEMBERS) {
            nodes.put(name, cluster.add(cluster.builder(name, MEMBERS, new InMemoryTransportSimulatorFixture(name),
                    new QraftStateStore(), RaftNodeMode.volatileMode())));
        }
        ManualRaftClusterFixture.startAll(nodes.values().toArray(RaftNode[]::new));
        leader = cluster.elect(nodes.get("a"));
        await(leader.submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put("k", "v"))));
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (server != null) server.close();
            cluster.close();
        } finally {
            runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
        }
    }

    @Test
    void theConfigurationIsListedWithoutAToken() throws Exception {
        serve(TOKEN);

        HttpResponse<String> response = send("GET", "/v1/operator/raft/configuration", Map.of());

        assertEquals(200, response.statusCode(), response.body());
        JsonNode servers = JSON.readTree(response.body()).get("servers");
        assertEquals(3, servers.size());
        JsonNode a = servers.get(0);
        assertEquals(serverIdOf("a"), a.get("serverId").asText());
        assertEquals("a", a.get("name").asText());
        assertEquals("a", a.get("address").asText());
        assertTrue(a.get("voter").asBoolean());
        assertTrue(a.get("leader").asBoolean());
        assertEquals(false, servers.get(1).get("leader").asBoolean());
    }

    @Test
    void aRemovalWithoutTheRightTokenIsForbidden() throws Exception {
        serve(TOKEN);

        assertEquals(403, send("DELETE", "/v1/operator/raft/peer?name=c", Map.of()).statusCode());
        HttpResponse<String> wrong = send("DELETE", "/v1/operator/raft/peer?name=c",
                Map.of("X-Qraft-Token", "not-the-token"));

        assertEquals(403, wrong.statusCode());
        assertEquals("permission_denied", JSON.readTree(wrong.body()).get("code").asText());
        assertTrue(leader.getConfiguration().orElseThrow().serverNamed("c").isPresent());
    }

    @Test
    void aRemovalWithTheTokenRemovesTheServer() throws Exception {
        serve(TOKEN);

        HttpResponse<String> response = send("DELETE", "/v1/operator/raft/peer?id=" + serverIdOf("c"),
                Map.of("Authorization", "Bearer " + TOKEN));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("REMOVED", JSON.readTree(response.body()).get("status").asText());
        assertTrue(leader.getConfiguration().orElseThrow().serverNamed("c").isEmpty());
    }

    @Test
    void eachRefusalHasItsOwnStatusCode() throws Exception {
        IntentionalErrorsHelper.expect(dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE);
        serve(TOKEN);
        Map<String, String> token = Map.of("X-Qraft-Token", TOKEN);

        assertEquals(400, send("DELETE", "/v1/operator/raft/peer", token).statusCode(), "no server named");
        assertEquals(404, send("DELETE", "/v1/operator/raft/peer?name=unknown", token).statusCode());
        assertEquals(405, send("POST", "/v1/operator/raft/peer?name=c", token).statusCode());
        assertEquals(405, send("DELETE", "/v1/operator/raft/configuration", token).statusCode());

        // Cut off from b and c, a cannot commit d's addition, so the removal after it is refused.
        InMemoryTransportSimulatorFixture.createPartition(Set.of("a"), Set.of("b", "c"));
        leader.admit(new RaftConfiguration.Server(serverIdOf("d"), "d", "d", false));
        cluster.heartbeatUntil(leader, () -> leader.getConfiguration().orElseThrow().serverNamed("d").isPresent(),
                "d's addition is appended");
        HttpResponse<String> refused = send("DELETE", "/v1/operator/raft/peer?name=c", token);
        assertEquals(409, refused.statusCode(), refused.body());
        assertTrue(JSON.readTree(refused.body()).get("message").asText().contains("already in progress"));
    }

    @Test
    void aFollowerThatCannotReachTheLeaderAsksTheOperatorToRetry() throws Exception {
        RaftNode follower = nodes.get("b");
        // The leader serves no membership changes over its transport, so forwarding fails.
        server = new HttpApiServer(0, follower, new QraftStateStore(), Clock.systemUTC(), AdminUiConfig.disabled(),
                null, HttpApiServer.DEFAULT_RAFT_TIMEOUT, new MembershipService(follower,
                InMemoryTransportSimulatorFixture.getAllTransports().get(follower.getNodeId()), TOKEN));
        server.start().join();

        HttpResponse<String> response = send("DELETE", "/v1/operator/raft/peer?name=c",
                Map.of("X-Qraft-Token", TOKEN));

        assertEquals(503, response.statusCode(), response.body());
        assertTrue(JSON.readTree(response.body()).get("retryable").asBoolean());
    }

    @Test
    void withNoTokenConfiguredRemovalsAreForbidden() throws Exception {
        serve(null);

        HttpResponse<String> response = send("DELETE", "/v1/operator/raft/peer?name=c",
                Map.of("X-Qraft-Token", TOKEN));

        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("no operator token is configured"), response.body());
    }

    @Test
    void aServerWithoutMembershipServesTheListButNotRemovals() throws Exception {
        server = new HttpApiServer(0, leader, new QraftStateStore(), Clock.systemUTC(), AdminUiConfig.disabled(),
                null, HttpApiServer.DEFAULT_RAFT_TIMEOUT, null);
        server.start().join();

        assertEquals(200, send("GET", "/v1/operator/raft/configuration", Map.of()).statusCode());
        assertEquals(503, send("DELETE", "/v1/operator/raft/peer?name=c",
                Map.of("X-Qraft-Token", TOKEN)).statusCode());
    }

    private void serve(String operatorToken) throws Exception {
        server = new HttpApiServer(0, leader, new QraftStateStore(), Clock.systemUTC(), AdminUiConfig.disabled(),
                null, HttpApiServer.DEFAULT_RAFT_TIMEOUT,
                new MembershipService(leader, InMemoryTransportSimulatorFixture.getAllTransports().get("a"), operatorToken));
        server.start().join();
    }

    private HttpResponse<String> send(String method, String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

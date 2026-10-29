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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.InMemoryTransportSimulatorFixture;
import dev.mars.qraft.raft.ManualRaftClusterFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the node ID on the client's write protocol (client list decision 8): a registration carries its node's ID
 * in {@code X-Qraft-Node-ID}, the server keeps it with the node, and a name that a node of another ID holds is
 * refused with 409 {@code node_name_reserved}, naming the holder as Consul does. The rules themselves are tested on
 * the state store in {@code NodeIdentityStateStoreTest}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-29
 * @version 1.0
 */
class HttpApiServerNodeIdentityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WEB_ID = "1b4e28ba-2fa1-4d2a-883f-0016d3cca427";
    private static final String OTHER_ID = "9c5b94b1-35ad-49bb-b118-8e8fc24abf80";
    private static final String NODE = """
            {"node":{"address":"10.0.0.5","datacenter":"dc-1","region":"eu-west"}}
            """;
    private static final String NODE_AND_SERVICE = """
            {"node":{"address":"10.0.0.5"},
             "service":{"serviceId":"web","serviceName":"frontend","address":"10.0.0.5","port":8080}}
            """;

    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private QraftStateStore store;
    private HttpApiServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void startAServerWithOneNode() throws Exception {
        runtime = RaftExecutorsFixture.create();
        cluster = new ManualRaftClusterFixture(runtime);
        InMemoryTransportSimulatorFixture.clearAllTransports();
        store = new QraftStateStore();
        RaftNode node = cluster.add(cluster.builder("a", Set.of("a"), new InMemoryTransportSimulatorFixture("a"),
                store, RaftNodeMode.volatileMode()));
        ManualRaftClusterFixture.startAll(node);
        RaftNode leader = cluster.elect(node);
        server = new HttpApiServer(0, leader, store);
        server.start().join();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (server != null) server.close();
            cluster.close();
        } finally {
            runtime.close().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
        }
    }

    @Test
    void aNodeRegistrationKeepsTheIdItsHeaderCarries() throws Exception {
        HttpResponse<String> response = register("web-01", WEB_ID, NODE);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(WEB_ID, store.findNode("web-01").orElseThrow().id());
    }

    @Test
    void aNodeRegistrationWithoutTheHeaderHasNoId() throws Exception {
        HttpResponse<String> response = register("web-01", null, NODE);

        assertEquals(200, response.statusCode(), response.body());
        assertNull(store.findNode("web-01").orElseThrow().id(), "a client from before node IDs sends none");
    }

    @Test
    void aNameANodeOfAnotherIdHoldsIsRefusedAsReservedAndTheServiceBesideItIsNotRegistered() throws Exception {
        register("web-01", WEB_ID, NODE);

        HttpResponse<String> response = register("web-01", OTHER_ID, NODE_AND_SERVICE);

        assertEquals(409, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("node_name_reserved", body.path("code").asText());
        assertEquals("Node name web-01 is reserved by node " + WEB_ID + " with name web-01",
                body.path("message").asText());
        assertEquals("web-01", body.path("holder").path("name").asText());
        assertEquals(WEB_ID, body.path("holder").path("id").asText());
        assertFalse(body.path("retryable").asBoolean(), "the name stays held until its holder is unreachable");
        assertEquals(WEB_ID, store.findNode("web-01").orElseThrow().id());
        assertTrue(store.getServiceCatalog().instances().isEmpty(), "nothing in the refused body is registered");
    }

    @Test
    void aNameANodeWithoutAnIdHoldsIsNamedSoInTheRefusal() throws Exception {
        register("web-01", WEB_ID, NODE);
        register("web-02", null, NODE);

        HttpResponse<String> response = register("web-02", WEB_ID, NODE);

        assertEquals(409, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("Node name web-02 is reserved by a node without an ID with name web-02",
                body.path("message").asText());
        assertTrue(body.path("holder").path("id").isNull(), response.body());
    }

    @Test
    void aNodeIdThatIsNotACanonicalUuidIsRefusedAndNothingIsRegistered() throws Exception {
        for (String id : new String[]{"not-a-uuid", WEB_ID.toUpperCase()}) {
            HttpResponse<String> response = register("web-01", id, NODE);

            assertEquals(400, response.statusCode(), id + ": " + response.body());
            assertEquals("invalid_registration", JSON.readTree(response.body()).path("code").asText());
            assertTrue(store.findNode("web-01").isEmpty(), id);
        }
    }

    private HttpResponse<String> register(String name, String id, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.port() + "/v1/catalog/register"))
                .header("Content-Type", "application/json")
                .header("X-Qraft-Node", name)
                .PUT(HttpRequest.BodyPublishers.ofString(body));
        if (id != null) request.header("X-Qraft-Node-ID", id);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

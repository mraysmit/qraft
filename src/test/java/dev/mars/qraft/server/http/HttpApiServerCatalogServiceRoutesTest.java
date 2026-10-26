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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the service half of the client's write protocol (Consul-style client list, Phase 1): a service is registered
 * under {@code service} in the body of {@code PUT /v1/catalog/register}, beside {@code node}, and removed with
 * {@code {"serviceId": ...}} in the body of {@code PUT /v1/catalog/deregister}, as design section 12.1.2 fixes the two
 * bodies for nodes and services together. The node routes themselves are tested in {@link HttpApiServerTest}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-26
 * @version 1.0
 */
class HttpApiServerCatalogServiceRoutesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> NODE = Map.of("X-Qraft-Node", "web-01");
    private static final String SERVICE = """
            {"serviceId":"web","serviceName":"frontend","address":"10.0.0.5","port":8080,
             "tags":["v1"],"metadata":{"team":"platform"}}
            """;
    private static final String NODE_DESCRIPTION = """
            {"address":"10.0.0.5","datacenter":"dc-1","region":"eu-west","metadata":{"qraft.version":"1.0.0"}}
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
    void aRegistrationCarryingANodeAndAServiceRegistersBoth() throws Exception {
        HttpResponse<String> response = put("/v1/catalog/register",
                "{\"node\":" + NODE_DESCRIPTION + ",\"service\":" + SERVICE + "}");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("web-01", body.path("node").asText());
        assertTrue(body.path("registered").asBoolean());
        assertEquals("web", body.path("service").path("serviceId").asText());
        assertEquals("frontend", body.path("service").path("serviceName").asText());
        assertTrue(body.path("service").path("registered").asBoolean());
        assertTrue(store.findNode("web-01").isPresent(), "the node is registered");
        assertEquals(1, store.getServiceCatalog().instances().size(), "and so is the service");
        assertEquals("web-01", store.getServiceCatalog().instances().getFirst().nodeId());
    }

    @Test
    void aRegistrationCarryingOnlyAServiceRegistersTheServiceAndLeavesTheNodeAlone() throws Exception {
        HttpResponse<String> response = put("/v1/catalog/register", "{\"service\":" + SERVICE + "}");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("web-01", body.path("node").asText());
        assertEquals("web", body.path("service").path("serviceId").asText());
        assertFalse(body.has("registered"), "no node was registered, so none is reported: " + response.body());
        assertTrue(store.findNode("web-01").isEmpty());
        assertEquals(1, store.getServiceCatalog().instances().size());
    }

    @Test
    void aRegistrationCarryingNeitherIsRefused() throws Exception {
        HttpResponse<String> response = put("/v1/catalog/register", "{}");

        assertRejected(response, 400, "invalid_registration");
        assertTrue(store.getServiceCatalog().instances().isEmpty());
        assertTrue(store.findNode("web-01").isEmpty());
    }

    @Test
    void anUnknownFieldInTheServiceIsRefusedAndNothingIsRegistered() throws Exception {
        HttpResponse<String> response = put("/v1/catalog/register",
                "{\"node\":" + NODE_DESCRIPTION + ",\"service\":{\"serviceId\":\"web\",\"serviceName\":\"frontend\","
                        + "\"address\":\"10.0.0.5\",\"port\":8080,\"colour\":\"red\"}}");

        assertRejected(response, 400, "invalid_registration");
        assertTrue(store.getServiceCatalog().instances().isEmpty());
        assertTrue(store.findNode("web-01").isEmpty(), "a refused body registers neither half");
    }

    @Test
    void aServiceThatTheBodyCannotDescribeIsRefusedBeforeTheNodeIsRegistered() throws Exception {
        HttpResponse<String> response = put("/v1/catalog/register",
                "{\"node\":" + NODE_DESCRIPTION + ",\"service\":{\"serviceId\":\" \",\"serviceName\":\"frontend\","
                        + "\"address\":\"10.0.0.5\",\"port\":8080}}");

        assertRejected(response, 400, "invalid_registration");
        assertTrue(store.findNode("web-01").isEmpty(), "the service was checked first, so the node was not registered");
    }

    @Test
    void aDeregistrationNamingAServiceRemovesItAndKeepsTheNode() throws Exception {
        assertEquals(200, put("/v1/catalog/register",
                "{\"node\":" + NODE_DESCRIPTION + ",\"service\":" + SERVICE + "}").statusCode());

        HttpResponse<String> response = put("/v1/catalog/deregister", "{\"serviceId\":\"web\"}");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("web-01", body.path("node").asText());
        assertEquals("web", body.path("serviceId").asText());
        assertTrue(body.path("deregistered").asBoolean());
        assertTrue(store.getServiceCatalog().instances().isEmpty());
        assertTrue(store.findNode("web-01").isPresent(), "a body that names a service removes only the service");
    }

    @Test
    void deregisteringAServiceTwiceAnswersThatTheSecondFoundNothing() throws Exception {
        assertEquals(200, put("/v1/catalog/register", "{\"service\":" + SERVICE + "}").statusCode());
        assertTrue(JSON.readTree(put("/v1/catalog/deregister", "{\"serviceId\":\"web\"}").body())
                .path("deregistered").asBoolean());

        HttpResponse<String> again = put("/v1/catalog/deregister", "{\"serviceId\":\"web\"}");

        assertEquals(200, again.statusCode(), again.body());
        assertFalse(JSON.readTree(again.body()).path("deregistered").asBoolean());
    }

    @Test
    void aBlankServiceIdCannotRemoveTheNodeInstead() throws Exception {
        assertEquals(200, put("/v1/catalog/register",
                "{\"node\":" + NODE_DESCRIPTION + ",\"service\":" + SERVICE + "}").statusCode());

        for (String body : new String[] {"{\"serviceId\":\"\"}", "{\"serviceId\":\"  \"}", "{\"serviceId\":null}",
                "{\"serviceId\":7}"}) {
            assertRejected(put("/v1/catalog/deregister", body), 400, "invalid_deregistration");
        }

        assertTrue(store.findNode("web-01").isPresent(), "the node is still there");
        assertEquals(1, store.getServiceCatalog().instances().size(), "and so is its service");
    }

    @Test
    void anotherFieldInADeregistrationIsStillRefused() throws Exception {
        assertRejected(put("/v1/catalog/deregister", "{\"serviceName\":\"web\"}"), 400, "invalid_deregistration");
    }

    @Test
    void theEmptyDeregistrationStillRemovesTheNodeAndItsServices() throws Exception {
        assertEquals(200, put("/v1/catalog/register",
                "{\"node\":" + NODE_DESCRIPTION + ",\"service\":" + SERVICE + "}").statusCode());

        assertEquals(200, put("/v1/catalog/deregister", "{}").statusCode());

        assertTrue(store.findNode("web-01").isEmpty());
        assertTrue(store.getServiceCatalog().instances().isEmpty());
    }

    @Test
    void aCheckObservationIsAcceptedOnTheCatalogPath() throws Exception {
        assertEquals(200, put("/v1/catalog/register", "{\"service\":" + SERVICE + "}").statusCode());

        HttpResponse<String> response = put("/v1/catalog/check/observe", """
                {"serviceId":"web","checkId":"http","status":"passing","sequenceNumber":1,
                 "observedAt":"2026-09-26T09:59:59Z","ttlMillis":30000,"output":"200 OK"}
                """);

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("web", body.path("serviceId").asText());
        assertEquals("http", body.path("checkId").asText());
        assertEquals("web-01", body.path("nodeId").asText());
        assertTrue(body.path("accepted").asBoolean());
        assertTrue(response.headers().firstValue("X-Qraft-Index").isPresent());
        assertEquals(1, store.healthChecks().size());
    }

    @Test
    void theClientPathsOfTheServerAreGone() throws Exception {
        assertEquals(404, put("/v1/client/service/register", SERVICE).statusCode());
        assertEquals(404, put("/v1/client/service/deregister/web", "").statusCode());
        assertEquals(404, put("/v1/client/check/observe", "{}").statusCode());
        assertTrue(store.getServiceCatalog().instances().isEmpty());
    }

    private HttpResponse<String> put(String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.port() + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
        NODE.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertRejected(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals(code, body.path("code").asText(), response.body());
        assertEquals(code, body.path("error").asText(), response.body());
    }
}

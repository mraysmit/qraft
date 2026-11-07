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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.NodeStatus;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.acl.AclRulesJson;
import dev.mars.qraft.common.acl.NodeIdentity;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.InMemoryTransportSimulatorFixture;
import dev.mars.qraft.raft.ManualRaftClusterFixture;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.AclCommand;
import dev.mars.qraft.state.CatalogCommand;
import dev.mars.qraft.state.NodeCommand;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.acl.AclPolicy;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a server enforces node identity (ACL list, Phase 7), with the tokens a client holds: a node identity
 * for its own node. A token for node A cannot register, renew, take over or rename node B; a token with
 * {@code service:write} and no {@code node:write} cannot create or change a node, and can register its service on a
 * node already registered (proposal 11, as Consul); a client's node identity removes an instance planted on its node,
 * and a token with neither {@code node:write} nor {@code service:write} on it cannot; and a takeover and a rename
 * succeed with the right tokens, under the client list's decision 8. This closes the "can the server trust the claim"
 * half of design section 22.
 *
 * <p>The guards were built in Phase 4; these tests state them in the terms of node identity, and passed when first
 * run.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class HttpApiServerNodeIdentityAclTest {
    private static final Set<String> MEMBERS = Set.of("a", "b", "c");
    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dataDirectory;

    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private final Map<String, RaftNode> nodes = new HashMap<>();
    private final Map<String, QraftStateStore> stores = new HashMap<>();
    private RaftNode leader;
    private HttpApiServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        runtime = RaftExecutorsFixture.create();
        cluster = new ManualRaftClusterFixture(runtime);
        InMemoryTransportSimulatorFixture.clearAllTransports();
        for (String name : MEMBERS) {
            stores.put(name, new QraftStateStore());
            nodes.put(name, cluster.add(cluster.builder(name, MEMBERS, new InMemoryTransportSimulatorFixture(name),
                    stores.get(name), RaftNodeMode.volatileMode())));
        }
        ManualRaftClusterFixture.startAll(nodes.values().toArray(RaftNode[]::new));
        leader = cluster.elect(nodes.get("a"));
        server = new HttpApiServer(0, leader, stores.get("a"), Clock.fixed(NOW, ZoneOffset.UTC),
                AdminUiConfig.disabled(), null, HttpApiServer.DEFAULT_RAFT_TIMEOUT, null,
                new AclHttpSettings(true, dataDirectory, AclDefaultPolicy.DENY));
        server.start().join();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            server.close();
            cluster.close();
        } finally {
            runtime.close().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
        }
    }

    @Test
    void aTokenForNodeACannotRegisterRenewTakeOverOrRenameNodeB() throws Exception {
        String a = identity("node-a");
        String bId = UUID.randomUUID().toString();
        arrangeNode("node-b", bId);

        assertDenied(register("node-c", UUID.randomUUID().toString(), a), "node-c", "a new node");
        assertDenied(heartbeat("node-b", a), "node-b", "a renewal");
        markUnreachable("node-b");
        assertDenied(register("node-b", UUID.randomUUID().toString(), a), "node-b", "a takeover");
        String aId = UUID.randomUUID().toString();
        assertEquals(200, register("node-a", aId, a).statusCode(), "its own node");
        assertDenied(register("node-b", aId, a), "node-b", "a rename to node-b");
        assertTrue(stores.get("a").findNode("node-b").isPresent());
        assertEquals(bId, stores.get("a").findNode("node-b").orElseThrow().id());
    }

    @Test
    void serviceWriteAloneCannotCreateOrChangeANodeAndRegistersOnOneAlreadyRegistered() throws Exception {
        String api = policyToken("{\"service\":{\"api\":{\"policy\":\"write\"}}}");
        String id = UUID.randomUUID().toString();

        assertDenied(send("PUT", "/v1/catalog/register", "{\"node\":{\"address\":\"10.0.0.1\"}}", headers("web-1", id,
                api)), "web-1", "creating a node");
        arrangeNode("web-1", id);
        assertDenied(send("PUT", "/v1/catalog/register", "{\"node\":{\"address\":\"10.0.0.2\"}}", headers("web-1", id,
                api)), "web-1", "changing a node");
        HttpResponse<String> service = send("PUT", "/v1/catalog/register", "{\"service\":{\"serviceId\":\"api-1\","
                + "\"serviceName\":\"api\",\"address\":\"10.0.0.1\",\"port\":8080}}", headers("web-1", id, api));
        assertEquals(200, service.statusCode(), "its service on a registered node: " + service.body());
    }

    @Test
    void aClientsNodeIdentityRemovesAPlantedInstanceAndATokenWithNeitherWriteCannot() throws Exception {
        String web = identity("web-1");
        String reader = policyToken("{\"service_prefix\":{\"\":{\"policy\":\"read\"}},"
                + "\"node_prefix\":{\"\":{\"policy\":\"read\"}}}");
        arrangeNode("web-1", null);
        assertSuccess(await(leader.submitCommand(CatalogCommand.register(new ServiceInstance("planted-1", "planted",
                "web-1", "10.0.0.9", 9999, List.of(), Map.of(), ServiceHealth.UNKNOWN), List.of()))));

        assertEquals(403, send("PUT", "/v1/catalog/deregister", "{\"serviceId\":\"planted-1\"}",
                headers("web-1", null, reader)).statusCode(), "reading is not writing");
        HttpResponse<String> removed = send("PUT", "/v1/catalog/deregister", "{\"serviceId\":\"planted-1\"}",
                headers("web-1", null, web));
        assertEquals(200, removed.statusCode(), "node:write removes anything on its node: " + removed.body());
        assertTrue(stores.get("a").getServiceCatalog().instances().isEmpty());
    }

    @Test
    void aTakeoverAndARenameSucceedWithTheRightTokens() throws Exception {
        arrangeNode("node-b", UUID.randomUUID().toString());
        markUnreachable("node-b");
        String claimant = UUID.randomUUID().toString();

        HttpResponse<String> takeover = register("node-b", claimant, identity("node-b"));
        assertEquals(200, takeover.statusCode(), "an unreachable holder is taken over with node:write on its name: "
                + takeover.body());
        assertEquals(claimant, stores.get("a").findNode("node-b").orElseThrow().id());

        String both = policyToken("{\"node\":{\"node-b\":{\"policy\":\"write\"},\"node-c\":{\"policy\":\"write\"}}}");
        HttpResponse<String> renamed = register("node-c", claimant, both);
        assertEquals(200, renamed.statusCode(), "a rename with node:write on the old name and the new: "
                + renamed.body());
        assertTrue(stores.get("a").findNode("node-b").isEmpty());
        assertEquals(claimant, stores.get("a").findNode("node-c").orElseThrow().id());
    }

    @Test
    void aNameThatDiffersOnlyInCaseNeedsNodeWriteOnTheNameItTakesOver() throws Exception {
        // The catalog holds a name regardless of case, so registering Host-A would take over host-a and remove what
        // is on it; a token for Host-A alone must not do that.
        arrangeNode("host-a", UUID.randomUUID().toString());
        markUnreachable("host-a");
        String claimant = UUID.randomUUID().toString();

        HttpResponse<String> refused = register("Host-A", claimant, identity("Host-A"));
        assertDenied(refused, "host-a", "a takeover by a name that differs only in case");
        assertTrue(stores.get("a").findNode("host-a").isPresent(), "a refused takeover is not proposed");

        String both = policyToken("{\"node\":{\"Host-A\":{\"policy\":\"write\"},\"host-a\":{\"policy\":\"write\"}}}");
        HttpResponse<String> takenOver = register("Host-A", claimant, both);
        assertEquals(200, takenOver.statusCode(), "with node:write on both spellings: " + takenOver.body());
        assertTrue(stores.get("a").findNode("host-a").isEmpty());
        assertEquals(claimant, stores.get("a").findNode("Host-A").orElseThrow().id());
    }

    private void arrangeNode(String name, String nodeId) throws Exception {
        assertSuccess(await(leader.submitCommand(NodeCommand.register(
                Node.of(name, "10.0.0.1", null, null, Map.of()).withId(nodeId), NOW))));
    }

    private void markUnreachable(String name) throws Exception {
        NodeStatus status = stores.get("a").findNode(name).orElseThrow().status();
        assertSuccess(await(leader.submitCommand(NodeCommand.updateStatus(name, status, NodeStatus.UNREACHABLE))));
    }

    private String identity(String nodeName) throws Exception {
        return token(List.of(), List.of(new NodeIdentity(nodeName)));
    }

    private String policyToken(String rules) throws Exception {
        String policy = UUID.randomUUID().toString();
        assertSuccess(await(leader.submitCommand(AclCommand.createPolicy(new AclPolicy(policy,
                "p-" + policy.substring(0, 8), "", AclRulesJson.parse(rules))))));
        return token(List.of(policy), List.of());
    }

    private String token(List<String> policies, List<NodeIdentity> identities) throws Exception {
        String secret = UUID.randomUUID().toString();
        assertSuccess(await(leader.submitCommand(AclCommand.createToken(new AclToken(UUID.randomUUID().toString(),
                AclSecrets.hash(secret), "", policies, List.of(), identities, List.of(), NOW, null, false)))));
        return secret;
    }

    private HttpResponse<String> register(String name, String nodeId, String token) throws Exception {
        return send("PUT", "/v1/catalog/register", "{\"node\":{\"address\":\"10.0.0.1\"}}", headers(name, nodeId,
                token));
    }

    private HttpResponse<String> heartbeat(String name, String token) throws Exception {
        return send("PUT", "/v1/catalog/node/heartbeat", "{\"sequenceNumber\":1}", headers(name, null, token));
    }

    private static Map<String, String> headers(String name, String nodeId, String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Qraft-Node", name);
        if (nodeId != null) headers.put("X-Qraft-Node-ID", nodeId);
        headers.put("X-Qraft-Token", token);
        return headers;
    }

    private HttpResponse<String> send(String method, String path, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertDenied(HttpResponse<String> response, String node, String what) throws Exception {
        assertEquals(403, response.statusCode(), what + ": " + response.body());
        assertEquals("permission_denied", JSON.readTree(response.body()).path("code").asText(), what);
        assertTrue(JSON.readTree(response.body()).path("message").asText()
                .contains("'node:write' on \"" + node + "\""), what + ": " + response.body());
    }

    private static void assertSuccess(RaftCommandResult<?> result) {
        assertInstanceOf(RaftCommandResult.Success.class, result, String.valueOf(result));
    }
}

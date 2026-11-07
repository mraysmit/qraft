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
import dev.mars.qraft.client.acl.AclLookup;
import dev.mars.qraft.client.catalog.CatalogLookupOutcome;
import dev.mars.qraft.client.catalog.CatalogOutcome;
import dev.mars.qraft.client.catalog.HttpCatalogClient;
import dev.mars.qraft.client.catalog.RequestTokens;
import dev.mars.qraft.client.catalog.ServerContactTracker;
import dev.mars.qraft.client.health.CheckObservation;
import dev.mars.qraft.client.health.CheckStatus;
import dev.mars.qraft.client.health.ObservationOutcome;
import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.ServiceDefinition;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.acl.AclRulesJson;
import dev.mars.qraft.common.acl.NodeIdentity;
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
import dev.mars.qraft.state.acl.AclPolicy;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test of every call a client makes to a server with ACLs enabled under {@code deny} (ACL list, Phase 6's
 * exit): its node's registration, renewal and removal, a service's registration and removal, a check's observation,
 * anti-entropy's read, a lookup, a forwarded read and a token's resolution, each with a sufficient token, an
 * insufficient one, and none. The client's own calls carry its node identity token, as proposal 13 intends, and a
 * service's calls its service's token.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class ClientServerAclContractTest {
    private static final String NODE = "client-1";
    private static final ServiceDefinition WEB = new ServiceDefinition("web-1", "web", "10.0.0.1", 80, List.of(),
            Map.of(), true, List.of("http"));

    @TempDir
    Path dataDirectory;

    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private HttpApiServer server;
    private HttpCatalogClient client;

    @AfterEach
    void closeResources() throws Exception {
        new CleanupHelper()
                .run(() -> { if (client != null) client.close(); })
                .run(() -> { if (server != null) server.close(); })
                .run(() -> { if (node != null) node.stop().get(10, TimeUnit.SECONDS); })
                .run(() -> { if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS); })
                .rethrow();
    }

    @Test
    void everyCallSucceedsWithASufficientTokenAndIsRefusedWithAnInsufficientOneOrNone() throws Exception {
        startServer();
        String nodeToken = token(List.of(), List.of(new NodeIdentity(NODE)));
        String webToken = token(List.of(policy("{\"service\":{\"web\":{\"policy\":\"write\"}}}")), List.of());
        String operatorToken = token(List.of(policy("{\"operator\":\"read\"}")), List.of());

        // Sufficient: the node identity for the node's calls, and a token writing web for web's.
        use(Optional.of(nodeToken), Optional.of(webToken));
        assertInstanceOf(CatalogOutcome.Success.class, client.registerNode(node("10.0.0.1")).join());
        assertInstanceOf(CatalogOutcome.Success.class, client.heartbeatNode(1, null).join());
        assertInstanceOf(CatalogOutcome.Success.class, client.register(WEB).join());
        assertInstanceOf(ObservationOutcome.Accepted.class, client.observe(observation(1)).join());
        assertEquals(Optional.of(Set.of("web-1")), client.nodeServices().join());
        assertInstanceOf(CatalogLookupOutcome.Present.class, client.lookup(WEB).join());
        assertTrue(client.forwardRead("/v1/catalog/nodes", "r-1", Map.of("X-Qraft-Token", nodeToken)).join()
                .orElseThrow().body().contains(NODE), "a node identity reads every node");
        assertInstanceOf(AclLookup.Found.class, client.resolveAcl(nodeToken).join());

        // Insufficient, and none: every write is refused, reads are filtered, and both still resolve.
        for (Optional<String> token : List.of(Optional.of(operatorToken), Optional.<String>empty())) {
            use(token, token);
            // Registering the node as it is recorded needs no node:write (proposal 11, as Consul), so it is changed.
            assertInstanceOf(CatalogOutcome.Success.class, client.registerNode(node("10.0.0.1")).join(), token
                    + ": an unchanged node needs no node:write");
            assertRefused(client.registerNode(node("10.0.0.2")).join(), token);
            assertRefused(client.heartbeatNode(2, null).join(), token);
            assertRefused(client.register(WEB).join(), token);
            ObservationOutcome observed = client.observe(observation(2)).join();
            assertInstanceOf(ObservationOutcome.Rejected.class, observed, token + ": " + observed);
            assertEquals("permission_denied", ((ObservationOutcome.Rejected) observed).code());
            assertEquals(Optional.of(Set.of()), client.nodeServices().join(),
                    token + ": without node:read the node's services are filtered out, as Consul's NodeServices");
            assertInstanceOf(CatalogLookupOutcome.Absent.class, client.lookup(WEB).join(),
                    token + ": the instance is filtered from the read");
            Map<String, String> caller = token.map(secret -> Map.of("X-Qraft-Token", secret)).orElse(Map.of());
            assertEquals("[]", client.forwardRead("/v1/catalog/nodes", "r-2", caller).join().orElseThrow().body()
                    .strip(), token + ": the nodes are filtered");
            assertInstanceOf(AclLookup.Found.class, client.resolveAcl(token.orElse(null)).join(),
                    token + ": any valid token resolves, and the anonymous token with none");
            assertRefused(client.deregister("web-1").join(), token);
            assertRefused(client.deregisterNode().join(), token);
        }
        assertInstanceOf(AclLookup.NotFound.class, client.resolveAcl(UUID.randomUUID().toString()).join());

        // Sufficient again: the node identity removes the service on its node, and the node itself.
        use(Optional.of(nodeToken), Optional.of(webToken));
        assertInstanceOf(CatalogOutcome.Success.class, client.deregister("web-1").join(),
                "the node identity's node:write removes anything on its node, as Consul's vetDeregisterWithACL");
        assertInstanceOf(CatalogOutcome.Success.class, client.deregisterNode().join());
    }

    private void use(Optional<String> nodeToken, Optional<String> serviceToken) {
        if (client != null) client.close();
        client = new HttpCatalogClient(HttpClient.newHttpClient(), new ObjectMapper(),
                List.of(URI.create("http://127.0.0.1:" + server.port())), NODE,
                "6a1b7c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d", "default", "default", "dc-1", "eu", Duration.ofSeconds(5),
                new ServerContactTracker(Clock.systemUTC()));
        client.useTokens(new RequestTokens() {
            @Override public Optional<String> node() { return nodeToken; }
            @Override public Optional<String> service(String serviceId) { return serviceToken; }
        });
    }

    private static void assertRefused(CatalogOutcome outcome, Optional<String> token) {
        assertInstanceOf(CatalogOutcome.Rejected.class, outcome, token + ": " + outcome);
        assertEquals("permission_denied", ((CatalogOutcome.Rejected) outcome).code(), token + ": " + outcome);
    }

    private static Node node(String address) {
        return Node.of(NODE, address, "dc-1", "eu", Map.of());
    }

    private static CheckObservation observation(long sequence) {
        return new CheckObservation("web-1", "http", CheckStatus.PASSING, sequence, Instant.now(),
                Duration.ofSeconds(30), true, "", Duration.ZERO);
    }

    private String policy(String rules) throws Exception {
        String id = UUID.randomUUID().toString();
        assertSuccess(node.submitCommand(AclCommand.createPolicy(new AclPolicy(id, "p-" + id.substring(0, 8), "",
                AclRulesJson.parse(rules)))).get(10, TimeUnit.SECONDS));
        return id;
    }

    private String token(List<String> policies, List<NodeIdentity> identities) throws Exception {
        String secret = UUID.randomUUID().toString();
        assertSuccess(node.submitCommand(AclCommand.createToken(new AclToken(UUID.randomUUID().toString(),
                AclSecrets.hash(secret), "", policies, List.of(), identities, List.of(), Instant.now(), null,
                false))).get(10, TimeUnit.SECONDS));
        return secret;
    }

    private static void assertSuccess(RaftCommandResult<?> result) {
        assertInstanceOf(RaftCommandResult.Success.class, result, String.valueOf(result));
    }

    private void startServer() throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder()
                .stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("contract-node")
                .clusterNodes(Set.of("contract-node"))
                .transport(new PeerlessTransportFixture())
                .stateMachine(store)
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode())
                .electionTimeout(25)
                .heartbeatInterval(10_000)
                .build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(node.isLeader(), "the single server leads");
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null,
                HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, new AclHttpSettings(true, dataDirectory,
                AclDefaultPolicy.DENY));
        server.start().get(10, TimeUnit.SECONDS);
    }
}

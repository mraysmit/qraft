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
import dev.mars.qraft.common.acl.NodeIdentity;
import dev.mars.qraft.common.acl.ServiceIdentity;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of ACLs (ACL list, Phase 9), with a server enforcing them under {@code deny} and clients holding
 * node identity tokens for their own nodes, over real HTTP:
 * <ul>
 *   <li>an application registers through its client under a service identity, is discovered through another host's
 *       client by a reader with {@code service:read}, and is invisible to one without it;</li>
 *   <li>a second host taking another node's name, with its own node's token, is refused, and the name keeps its
 *       node;</li>
 *   <li>with every server stopped, the application goes on reporting its TTL check under its cached token.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
@Tag("e2e")
class AclEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORDERS = """
            {"id":"orders-1","name":"orders","port":9000,"checks":[{"id":"app","type":"ttl","ttlMs":60000}]}""";

    @TempDir
    Path dataRoot;

    private final List<QraftClient> clients = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private QraftStateStore store;
    private HttpApiServer server;

    @AfterEach
    void closeResources() throws Exception {
        CleanupHelper cleanup = new CleanupHelper();
        for (QraftClient client : clients.reversed()) cleanup.run(() -> client.shutdown().get(10, TimeUnit.SECONDS));
        cleanup.run(this::stopServer)
                .run(() -> { if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS); })
                .rethrow();
    }

    @Test
    void anApplicationUnderAServiceIdentityIsDiscoveredByAReaderAndInvisibleWithoutRead() throws Exception {
        startServer();
        String application = token(List.of(), List.of(), List.of(new ServiceIdentity("orders", null, null)));
        String reader = token(List.of(policy("{\"service_prefix\":{\"\":{\"policy\":\"read\"}},"
                + "\"node_prefix\":{\"\":{\"policy\":\"read\"}}}")), List.of(), List.of());
        String nobody = token(List.of(policy("{\"operator\":\"read\"}")), List.of(), List.of());
        QraftClient hostA = startedClient("host-a", "host-a");
        QraftClient hostB = startedClient("host-b", "host-b");

        HttpResponse<String> registered = local(hostA, "PUT", "/v1/client/service/register", ORDERS, application);
        assertEquals(200, registered.statusCode(), registered.body());
        waitUntil(() -> store.getServiceCatalog().instances().stream()
                .anyMatch(instance -> instance.serviceId().equals("orders-1")));

        assertEquals(List.of("orders-1"), serviceIds(hostB, "/v1/catalog/service/orders", reader),
                "a reader with service:read discovers it through another host's client");
        assertEquals(List.of(), serviceIds(hostB, "/v1/catalog/service/orders", nobody),
                "and one without it does not");
        assertEquals(List.of(), serviceIds(hostB, "/v1/catalog/service/orders", null),
                "nor does an anonymous caller under deny");
    }

    @Test
    void aSecondHostWithAnotherNodesNameAndItsOwnTokenIsRefused() throws Exception {
        startServer();
        startedClient("host-a", "host-a");
        String heldBy = store.findNode("host-a").orElseThrow().id();

        QraftClient impostor = client("host-a", "host-c", dataRoot.resolve("impostor"));

        assertFalse(impostor.start().get(10, TimeUnit.SECONDS), "its registration is refused: it lacks node:write "
                + "on host-a, so the name is never judged");
        assertEquals(heldBy, store.findNode("host-a").orElseThrow().id(), "the name keeps its node");
        assertFalse(impostor.localApi().isReady(), "a host refused its registration is not ready");
    }

    @Test
    void withEveryServerStoppedAnApplicationGoesOnReportingItsCheckUnderItsCachedToken() throws Exception {
        startServer();
        String application = token(List.of(), List.of(), List.of(new ServiceIdentity("orders", null, null)));
        QraftClient hostA = startedClient("host-a", "host-a");
        assertEquals(200, local(hostA, "PUT", "/v1/client/service/register", ORDERS, application).statusCode());
        assertEquals(200, local(hostA, "PUT", "/v1/client/check/pass/app", null, application).statusCode(),
                "the token is resolved, and cached, while a server answers");

        stopServer();

        HttpResponse<String> report = local(hostA, "PUT", "/v1/client/check/pass/app", null, application);
        assertEquals(200, report.statusCode(), "extend-cache uses the cached token with no server: " + report.body());
        HttpResponse<String> refused = local(hostA, "PUT", "/v1/client/check/pass/app", null,
                UUID.randomUUID().toString());
        assertEquals(403, refused.statusCode(), "an uncached token gets the default policy, deny: " + refused.body());
    }

    // ---- Helpers ----

    private QraftClient startedClient(String nodeName, String identity) throws Exception {
        QraftClient client = client(nodeName, identity, dataRoot.resolve(nodeName));
        client.start().get(10, TimeUnit.SECONDS);
        waitUntil(() -> store.findNode(nodeName).isPresent());
        return client;
    }

    /** A client named {@code nodeName} whose own token is a node identity for {@code identity}. */
    private QraftClient client(String nodeName, String identity, Path dataDirectory) throws Exception {
        Files.createDirectories(dataDirectory);
        Path tokenFile = Files.writeString(dataDirectory.resolve("client-token"),
                token(List.of(), List.of(new NodeIdentity(identity)), List.of()));
        QraftClient client = new QraftClient(ClientConfiguration.builder()
                .nodeName(nodeName).dataDirectory(dataDirectory).address("127.0.0.1").clientPort(0)
                .serverUrls(List.of(URI.create("http://127.0.0.1:" + server.port())))
                .heartbeatInterval(40).requestTimeoutMs(5_000).registrationRetryMinMs(20)
                .registrationRetryMaxMs(100).contactFreshnessMs(2_000).shutdownTimeoutMs(2_000)
                .acl(new ClientAclSettings(true, AclDefaultPolicy.DENY, DownPolicy.EXTEND_CACHE,
                        ClientAclSettings.DEFAULT_TTL, ClientAclSettings.DEFAULT_TTL, ClientAclSettings.DEFAULT_TTL,
                        false, Map.of(TokenKind.CLIENT, tokenFile)))
                .build());
        clients.add(client);
        return client;
    }

    private HttpResponse<String> local(QraftClient client, String method, String pathAndQuery, String body,
                                       String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + client.localApi().port()
                        + pathAndQuery))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("X-Qraft-Token", token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private List<String> serviceIds(QraftClient client, String pathAndQuery, String token) throws Exception {
        HttpResponse<String> answer = local(client, "GET", pathAndQuery, null, token);
        assertEquals(200, answer.statusCode(), answer.body());
        List<String> ids = new ArrayList<>();
        for (JsonNode entry : JSON.readTree(answer.body())) ids.add(entry.path("serviceId").textValue());
        return ids;
    }

    private String policy(String rules) throws Exception {
        String id = UUID.randomUUID().toString();
        assertSuccess(node.submitCommand(AclCommand.createPolicy(new AclPolicy(id, "p-" + id.substring(0, 8), "",
                AclRulesJson.parse(rules)))).get(10, TimeUnit.SECONDS));
        return id;
    }

    private String token(List<String> policies, List<NodeIdentity> nodes, List<ServiceIdentity> services)
            throws Exception {
        String secret = UUID.randomUUID().toString();
        assertSuccess(node.submitCommand(AclCommand.createToken(new AclToken(UUID.randomUUID().toString(),
                AclSecrets.hash(secret), "", policies, List.of(), nodes, services, Instant.now(), null, false)))
                .get(10, TimeUnit.SECONDS));
        return secret;
    }

    private static void assertSuccess(RaftCommandResult<?> result) {
        assertInstanceOf(RaftCommandResult.Success.class, result, String.valueOf(result));
    }

    private void startServer() throws Exception {
        runtime = RaftExecutorsFixture.create();
        store = new QraftStateStore();
        node = RaftNode.builder()
                .stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("acl-server")
                .clusterNodes(Set.of("acl-server"))
                .transport(new PeerlessTransportFixture())
                .stateMachine(store)
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode())
                .electionTimeout(25)
                .heartbeatInterval(10_000)
                .build();
        node.start().get(10, TimeUnit.SECONDS);
        waitUntil(node::isLeader);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null,
                HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, new AclHttpSettings(true, dataRoot.resolve("server"),
                AclDefaultPolicy.DENY));
        server.start().get(10, TimeUnit.SECONDS);
    }

    private void stopServer() throws Exception {
        if (server != null) server.close();
        server = null;
        if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        node = null;
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "condition was not met before the deadline");
    }
}

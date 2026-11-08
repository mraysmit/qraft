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
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.tenancy.TenancyCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests the enforcement of tenancy on the server's HTTP API (tenancy list, Phase 2, decisions 2 and 3): a scope header
 * that is not a name is refused; a registration, a key set and an acquisition into a tenant or namespace that does not
 * exist are refused 404 {@code tenant_not_found} or {@code namespace_not_found}, and accepted once it is created; and
 * reads and deletes in a missing scope are answered as for an absent item.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class HttpApiServerTenancyEnforcementTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REGISTRATION = """
            {"service":{"serviceId":"web-1","serviceName":"web","address":"127.0.0.1","port":8080}}""";

    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private HttpApiServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        start(Set.of("tenancy-node"));
    }

    private void start(Set<String> members) throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("tenancy-node").clusterNodes(members).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (members.size() == 1 && !node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null);
        server.start().get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        try {
            if (server != null) server.close();
            if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        } finally {
            if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
            http.close();
        }
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void refused(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        if (code != null) assertEquals(code, JSON.readTree(response.body()).path("code").asText(), response.body());
    }

    private void submit(RaftCommand command) throws Exception {
        assertInstanceOf(RaftCommandResult.Success.class, node.submitCommand(command).get(10, TimeUnit.SECONDS));
    }

    @Test
    void aWriteIntoAScopeThatDoesNotExistIsRefusedUntilItIsCreated() throws Exception {
        refused(send("PUT", "/v1/kv/config", "v", "X-Qraft-Tenant", "acme"), 404, "tenant_not_found");
        refused(send("PUT", "/v1/catalog/register", REGISTRATION, "X-Qraft-Node", "host-a",
                "X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "prod"), 404, "tenant_not_found");

        submit(TenancyCommand.createTenant("acme", ""));
        assertEquals("true", send("PUT", "/v1/kv/config", "v", "X-Qraft-Tenant", "acme").body(),
                "a tenant comes with its default namespace");
        refused(send("PUT", "/v1/kv/config", "v", "X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "prod"), 404,
                "namespace_not_found");
        refused(send("PUT", "/v1/catalog/register", REGISTRATION, "X-Qraft-Node", "host-a",
                "X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "prod"), 404, "namespace_not_found");

        submit(TenancyCommand.createNamespace("acme", "prod", ""));
        assertEquals("true", send("PUT", "/v1/kv/config", "v", "X-Qraft-Tenant", "acme", "X-Qraft-Namespace",
                "prod").body());
        HttpResponse<String> registered = send("PUT", "/v1/catalog/register", REGISTRATION, "X-Qraft-Node", "host-a",
                "X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "prod");
        assertEquals(200, registered.statusCode(), registered.body());
        assertEquals(200, send("PUT", "/v1/catalog/register", REGISTRATION, "X-Qraft-Node", "host-a").statusCode(),
                "the default tenant and namespace always exist");
    }

    @Test
    void aFollowerAnswersAScopedWriteAsUnavailableRatherThanFromItsOwnStateWhichMayLag() throws Exception {
        // The single-server leader from the shared setup is replaced by a server that cannot win an election.
        server.close();
        node.stop().get(10, TimeUnit.SECONDS);
        runtime.close().get(10, TimeUnit.SECONDS);
        start(Set.of("tenancy-node", "absent-peer"));

        // A follower may not yet have applied a scope the leader created, so it must send the client on to the
        // leader, as for any write, and not refuse the scope itself.
        for (HttpResponse<String> write : List.of(
                send("PUT", "/v1/kv/config", "v", "X-Qraft-Tenant", "acme"),
                send("PUT", "/v1/catalog/register", REGISTRATION, "X-Qraft-Node", "host-a", "X-Qraft-Tenant", "acme"))) {
            refused(write, 503, "leader_unavailable");
        }
    }

    @Test
    void anAcquisitionIsRefusedInAScopeThatDoesNotExist() throws Exception {
        assertEquals(200, send("PUT", "/v1/catalog/register", "{\"node\":{\"address\":\"10.0.0.1\"}}",
                "X-Qraft-Node", "host-a").statusCode());
        String session = JSON.readTree(send("PUT", "/v1/session/create", "{\"Node\":\"host-a\"}").body())
                .path("ID").asText();

        refused(send("PUT", "/v1/kv/leader?acquire=" + session, "me", "X-Qraft-Tenant", "acme"), 404,
                "tenant_not_found");
        submit(TenancyCommand.createTenant("acme", ""));
        assertEquals("true", send("PUT", "/v1/kv/leader?acquire=" + session, "me", "X-Qraft-Tenant", "acme").body());
    }

    @Test
    void aScopeHeaderThatIsNotANameIsRefusedAndReadsInAMissingScopeFindNothing() throws Exception {
        refused(send("PUT", "/v1/kv/config", "v", "X-Qraft-Tenant", "Acme"), 400, "invalid_scope");
        refused(send("GET", "/v1/kv/config", null, "X-Qraft-Namespace", "prod_1"), 400, "invalid_scope");
        refused(send("PUT", "/v1/catalog/register", REGISTRATION, "X-Qraft-Node", "host-a",
                "X-Qraft-Tenant", "-acme"), 400, null);
        refused(send("GET", "/v1/catalog/services", null, "X-Qraft-Tenant", "a.b"), 400, null);

        refused(send("GET", "/v1/kv/config", null, "X-Qraft-Tenant", "absent"), 404, "key_not_found");
        assertEquals("true", send("DELETE", "/v1/kv/config", null, "X-Qraft-Tenant", "absent").body(),
                "a delete in a missing scope deletes nothing");
        HttpResponse<String> services = send("GET", "/v1/catalog/services", null, "X-Qraft-Tenant", "absent");
        assertEquals(200, services.statusCode(), services.body());
        assertEquals(0, JSON.readTree(services.body()).size());
    }
}

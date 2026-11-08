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

import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.acl.AclRulesJson;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.common.events.EventCategory;
import dev.mars.qraft.common.events.EventCursor;
import dev.mars.qraft.common.events.EventEnvelope;
import dev.mars.qraft.common.events.EventFilter;
import dev.mars.qraft.common.events.EventJournal;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the audit events of the server's requests (authentication list, Phase 3, decision 4): a request answered under
 * a token is recorded as an {@code AUDIT} event with the token's accessor ID, the method, the route, the status and the
 * request ID, and never the secret; a request without a token is not recorded.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class HttpApiServerAuditTest {
    @TempDir
    Path dataRoot;

    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private HttpApiServer server;
    private final EventJournal journal = EventJournal.forBoot("audit-node", Clock.systemUTC());
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() throws Exception {
        try {
            if (server != null) server.close();
            if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        } finally {
            journal.close();
            if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
            http.close();
        }
    }

    private List<EventEnvelope> audits() {
        return journal.recent(EventCursor.start(journal.bootId()), new EventFilter(Set.of(EventCategory.AUDIT), Set.of(),
                Optional.empty(), Optional.empty()), 100).events();
    }

    @Test
    void aRequestUnderATokenIsAuditedWithoutItsSecretAndOneWithoutATokenIsNot() throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("audit-node").clusterNodes(Set.of("audit-node")).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null,
                HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, new AclHttpSettings(true, dataRoot.resolve("server"),
                AclDefaultPolicy.DENY));
        server.useEventJournal(journal);
        server.start().get(10, TimeUnit.SECONDS);
        String accessor = UUID.randomUUID().toString();
        String secret = UUID.randomUUID().toString();
        String policy = UUID.randomUUID().toString();
        submit(AclCommand.createPolicy(new AclPolicy(policy, "kv", "", AclRulesJson.parse(
                "{\"key_prefix\":{\"\":{\"policy\":\"write\"}}}"))));
        submit(AclCommand.createToken(new AclToken(accessor, AclSecrets.hash(secret), "", List.of(policy), List.of(),
                List.of(), List.of(), Instant.now(), null, false)));

        HttpResponse<String> put = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                + "/v1/kv/app/config")).header("X-Qraft-Token", secret).header("X-Request-Id", "req-42")
                .PUT(HttpRequest.BodyPublishers.ofString("v")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, put.statusCode(), put.body());
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health/live")).build(),
                HttpResponse.BodyHandlers.ofString());

        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (audits().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(1, audits().size(), "only the request under a token: " + audits());
        EventEnvelope audit = audits().getFirst();
        AuditEvents.Request request = (AuditEvents.Request) audit.event();
        assertEquals(accessor, request.accessorId());
        assertEquals("PUT", request.method());
        assertEquals("/v1/kv", request.route());
        assertEquals(200, request.status());
        assertEquals(Optional.of("req-42"), audit.correlationId());
        assertFalse(audit.toString().contains(secret), "the secret is never recorded");
        assertTrue(audit.committedIndex().isEmpty(), "an audit event is not a replicated fact");
    }

    private void submit(RaftCommand command) throws Exception {
        assertEquals(RaftCommandResult.Success.class, node.submitCommand(command).get(10, TimeUnit.SECONDS).getClass());
    }
}

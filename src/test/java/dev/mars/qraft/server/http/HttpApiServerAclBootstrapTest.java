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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.InMemoryTransportSimulatorFixture;
import dev.mars.qraft.raft.ManualRaftClusterFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.acl.AclBuiltins;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code PUT /v1/acl/bootstrap} (ACL list, proposal 12, Phase 3), after Consul's {@code ACL.Bootstrap}: with ACLs
 * disabled it answers 401 {@code acl_disabled}; the first bootstrap creates a management token, with a generated or a
 * supplied secret, returned only in that answer; afterwards it answers 403 {@code permission_denied} naming the reset
 * index; a reset index written to {@code acl-bootstrap-reset} in the data directory allows one more bootstrap and the
 * file is removed, while a wrong one is refused and kept; a follower answers that the leader must be asked; and no log
 * line holds a secret.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
class HttpApiServerAclBootstrapTest {
    private static final Set<String> MEMBERS = Set.of("a", "b", "c");
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final String SUPPLIED = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dataDirectory;

    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private final Map<String, RaftNode> nodes = new HashMap<>();
    private final Map<String, QraftStateStore> stores = new HashMap<>();
    private final List<String> secretsUsed = new ArrayList<>();
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private RaftNode leader;
    private HttpApiServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        captured.start();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(captured);
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
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (server != null) server.close();
            cluster.close();
        } finally {
            runtime.close().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
            ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(captured);
        }
        for (String secret : secretsUsed) {
            for (ILoggingEvent event : captured.list) {
                assertFalse(event.getFormattedMessage().contains(secret), "a log line holds a secret: " + event);
            }
        }
    }

    @Test
    void withAclsDisabledTheBootstrapAnswers401() throws Exception {
        serve("a", AclHttpSettings.disabled());

        HttpResponse<String> response = bootstrap("");

        assertEquals(401, response.statusCode(), response.body());
        assertEquals("acl_disabled", JSON.readTree(response.body()).get("code").asText());
        assertEquals(0, stores.get("a").getAcl().bootstrapIndex());
    }

    @Test
    void theFirstBootstrapCreatesAManagementTokenAndReturnsItsSecretOnce() throws Exception {
        serve("a", enabled());

        HttpResponse<String> response = bootstrap("");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode token = JSON.readTree(response.body());
        String secret = token.get("secretId").asText();
        secretsUsed.add(secret);
        assertTrue(AclSecrets.isUuid(secret), "a generated secret is a UUID");
        assertEquals("Bootstrap Token (Global Management)", token.get("description").asText(), "Consul's");
        assertEquals(AclBuiltins.GLOBAL_MANAGEMENT_ID, token.get("policies").get(0).get("id").asText());
        assertEquals("global-management", token.get("policies").get(0).get("name").asText());
        assertEquals(NOW.toString(), token.get("createTime").asText());
        AclToken stored = stores.get("a").getAcl().tokenBySecret(secret).orElseThrow();
        assertEquals(stored.accessorId(), token.get("accessorId").asText());
        assertFalse(stored.installedFromFile());
        assertEquals(1, stores.get("a").getAcl().bootstrapIndex());
        assertFalse(response.body().contains(stored.secretHash()), "an answer never holds the hash");
    }

    @Test
    void aSuppliedSecretIsUsedWhenItIsAUuidOutsideTheReservedRange() throws Exception {
        serve("a", enabled());
        secretsUsed.add(SUPPLIED);
        secretsUsed.add("correct-horse-battery-staple");

        for (String body : List.of("{\"bootstrapSecret\":\"correct-horse-battery-staple\"}",
                "{\"bootstrapSecret\":\"00000000-0000-0000-0000-000000000009\"}",
                "{\"secret\":\"" + SUPPLIED + "\"}", "[]")) {
            HttpResponse<String> refused = bootstrap(body);
            assertEquals(400, refused.statusCode(), body + ": " + refused.body());
            assertEquals("invalid_bootstrap", JSON.readTree(refused.body()).get("code").asText());
            assertFalse(refused.body().contains("correct-horse"), "a refusal never echoes a secret");
        }
        HttpResponse<String> response = bootstrap("{\"bootstrapSecret\":\"" + SUPPLIED + "\"}");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(SUPPLIED, JSON.readTree(response.body()).get("secretId").asText());
        assertTrue(stores.get("a").getAcl().tokenBySecret(SUPPLIED).isPresent());
    }

    @Test
    void aSecondBootstrapIsRefusedWithTheResetIndex() throws Exception {
        serve("a", enabled());
        secretsUsed.add(JSON.readTree(bootstrap("").body()).get("secretId").asText());

        HttpResponse<String> again = bootstrap("");

        assertEquals(403, again.statusCode(), again.body());
        JsonNode error = JSON.readTree(again.body());
        assertEquals("permission_denied", error.get("code").asText());
        assertEquals("ACL bootstrap no longer allowed (reset index: 1)", error.get("message").asText());
        assertEquals(1, error.get("resetIndex").asLong());
        assertEquals(2, stores.get("a").getAcl().tokens().size(), "the anonymous token and the first bootstrap's");
    }

    @Test
    void theResetFileAllowsOneMoreBootstrapAndIsRemoved() throws Exception {
        serve("a", enabled());
        secretsUsed.add(JSON.readTree(bootstrap("").body()).get("secretId").asText());
        Path reset = dataDirectory.resolve("acl-bootstrap-reset");

        Files.writeString(reset, "5\n");
        HttpResponse<String> wrong = bootstrap("");
        assertEquals(403, wrong.statusCode(), wrong.body());
        assertEquals("permission_denied", JSON.readTree(wrong.body()).get("code").asText());
        assertTrue(Files.exists(reset), "a wrong reset index leaves the file for the operator to correct");

        Files.writeString(reset, " 1 \n");
        HttpResponse<String> allowed = bootstrap("");
        assertEquals(200, allowed.statusCode(), allowed.body());
        secretsUsed.add(JSON.readTree(allowed.body()).get("secretId").asText());
        assertFalse(Files.exists(reset), "the file is removed when used, as Consul removes it");
        assertEquals(2, stores.get("a").getAcl().bootstrapIndex());

        HttpResponse<String> once = bootstrap("");
        assertEquals(403, once.statusCode(), "the reset works once");
        assertEquals(2, JSON.readTree(once.body()).get("resetIndex").asLong());

        Files.writeString(reset, "two");
        HttpResponse<String> unreadable = bootstrap("");
        assertEquals(400, unreadable.statusCode(), unreadable.body());
        assertTrue(JSON.readTree(unreadable.body()).get("message").asText().contains("acl-bootstrap-reset"));
    }

    @Test
    void aFollowerAnswersThatTheLeaderMustBeAsked() throws Exception {
        serve("b", enabled());

        HttpResponse<String> response = bootstrap("");

        assertEquals(503, response.statusCode(), response.body());
        assertEquals("leader_unavailable", JSON.readTree(response.body()).get("code").asText());
        assertEquals("a", response.headers().firstValue("X-Qraft-Leader-Id").orElse(""));
        assertEquals(0, stores.get("a").getAcl().bootstrapIndex());
    }

    @Test
    void onlyPutBootstraps() throws Exception {
        serve("a", enabled());

        HttpResponse<String> response = send("GET", "");

        assertEquals(405, response.statusCode(), response.body());
    }

    private AclHttpSettings enabled() {
        return new AclHttpSettings(true, dataDirectory);
    }

    private void serve(String name, AclHttpSettings acl) throws Exception {
        server = new HttpApiServer(0, nodes.get(name), stores.get(name), Clock.fixed(NOW, ZoneOffset.UTC),
                AdminUiConfig.disabled(), null, HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, acl);
        server.start().join();
        await(leader.submitCommand(new dev.mars.qraft.state.DistributedStateRaftCommand(
                dev.mars.qraft.state.distributed.DistributedStateCommand.put("k", "v"))));
    }

    private HttpResponse<String> bootstrap(String body) throws Exception {
        return send("PUT", body);
    }

    private HttpResponse<String> send(String method, String body) throws Exception {
        return client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.port() + "/v1/acl/bootstrap"))
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}

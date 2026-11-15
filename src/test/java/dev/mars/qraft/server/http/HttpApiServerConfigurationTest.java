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
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.config.AppConfig;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
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
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code GET /v1/operator/configuration} (UI-4 list, decision 3): the answering server's effective configuration,
 * each setting with its value, its source and that it takes a restart to change, a secret never answered; the read
 * needs {@code operator:read}, takes no parameter, and a server not given its configuration says so.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-15
 * @version 1.0
 */
class HttpApiServerConfigurationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant STARTED = Instant.parse("2026-11-15T09:30:00Z");
    private static final String SECRET = "operator-secret-0123456789";

    @TempDir
    Path directory;

    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private QraftStateStore store;
    private HttpApiServer server;
    private final HttpClient http = HttpClient.newHttpClient();

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

    private void startNode() throws Exception {
        runtime = RaftExecutorsFixture.create();
        store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("conf-node").clusterNodes(Set.of("conf-node")).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
    }

    private void serve(AclHttpSettings acl) throws Exception {
        server = new HttpApiServer(0, node, store, Clock.fixed(STARTED, ZoneOffset.UTC), AdminUiConfig.disabled(), null,
                HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, acl);
        server.start().get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theReadAnswersEverySettingWithItsSourceAndNoSecret() throws Exception {
        startNode();
        serve(AclHttpSettings.disabled());
        server.useConfiguration(AppConfig.fromJson("""
                {"version":1,"server":{"id":"conf-node","raft":{"electionTimeoutMs":250},
                "operator":{"token":"%s"}}}""".formatted(SECRET)).effective());

        HttpResponse<String> answer = send("GET", "/v1/operator/configuration");
        assertEquals(200, answer.statusCode(), answer.body());
        assertFalse(answer.body().contains(SECRET), answer.body());
        JsonNode read = JSON.readTree(answer.body());
        assertEquals("conf-node", read.path("server").asText());
        assertTrue(read.path("file").isNull(), "this configuration was given as a document, not read from a file");
        assertEquals(STARTED.toString(), read.path("readAt").asText());
        Map<String, JsonNode> settings = new HashMap<>();
        read.path("settings").forEach(setting -> settings.put(setting.path("key").asText(), setting));
        JsonNode timeout = settings.get("server.raft.electionTimeoutMs");
        assertEquals(250, timeout.path("value").asLong());
        assertEquals("configured", timeout.path("source").asText());
        assertTrue(timeout.path("restartRequired").asBoolean());
        assertFalse(timeout.path("redacted").asBoolean());
        JsonNode port = settings.get("server.http.port");
        assertEquals(8080, port.path("value").asInt());
        assertEquals("default", port.path("source").asText());
        JsonNode token = settings.get("server.operator.token");
        assertTrue(token.path("value").isNull());
        assertTrue(token.path("redacted").asBoolean());
        settings.values().forEach(setting -> assertTrue(setting.path("restartRequired").asBoolean(),
                "no setting changes while the server runs: " + setting));

        assertEquals(400, send("GET", "/v1/operator/configuration?all").statusCode());
        assertEquals(405, send("PUT", "/v1/operator/configuration").statusCode());
    }

    @Test
    void aServerNotGivenItsConfigurationSaysSo() throws Exception {
        startNode();
        serve(AclHttpSettings.disabled());

        HttpResponse<String> answer = send("GET", "/v1/operator/configuration");
        assertEquals(503, answer.statusCode(), answer.body());
        assertEquals("configuration_unavailable", JSON.readTree(answer.body()).path("code").asText());
    }

    @Test
    void theReadNeedsOperatorRead() throws Exception {
        startNode();
        serve(new AclHttpSettings(true, directory, AclDefaultPolicy.DENY));
        server.useConfiguration(AppConfig.fromJson("{\"version\":1,\"server\":{\"id\":\"conf-node\"}}").effective());

        HttpResponse<String> refused = send("GET", "/v1/operator/configuration");
        assertEquals(403, refused.statusCode(), refused.body());
        assertEquals("permission_denied", JSON.readTree(refused.body()).path("code").asText());
    }
}

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

package dev.mars.qraft.server;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.server.api.AclGrpcGuard;
import dev.mars.qraft.server.api.grpc.DistributedStateServiceGrpc;
import dev.mars.qraft.server.api.grpc.GetRequest;
import dev.mars.qraft.server.config.AppConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
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
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests a real server started with ACLs enabled under {@code deny} and a management file: it installs the file's
 * token when it becomes leader, through the bootstrap, so that its bootstrap endpoint then refuses with reset index 1
 * (ACL list, Phase 3); and it enforces ACLs on each of its listeners, its HTTP API, its server removals and its gRPC
 * API, where the management token is allowed and a request without a token is not (Phase 4). No log line holds the
 * file's secret.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.1
 */
class QraftServerAclStartupTest {
    private static final String SECRET = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private RaftExecutorsFixture executors;
    private QraftServerService service;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void start() throws Exception {
        Path secretFile = Files.writeString(directory.resolve("management-token"), SECRET);
        Path configuration = directory.resolve("server.json");
        JSON.writeValue(configuration.toFile(), Map.of("version", 1, "server", Map.of(
                "id", "acl-startup", "http", Map.of("port", 0), "apiGrpcPort", 0,
                "raft", Map.of("port", 0, "electionTimeoutMs", 300, "heartbeatIntervalMs", 100,
                        "storage", Map.of("path", directory.resolve("raft").toString())),
                "acl", Map.of("enabled", true, "defaultPolicy", "deny",
                        "initialManagementTokenFile", secretFile.toString()),
                "telemetry", Map.of("enabled", false))));
        AppConfig config = AppConfig.fromFile(configuration);
        config.validate();
        captured.start();
        root.addAppender(captured);
        executors = RaftExecutorsFixture.create();
        service = new QraftServerService(executors.stateLoop(), executors.blockingTasks(), config);
        service.start().get(30, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        try {
            client.close();
            service.stop().get(30, TimeUnit.SECONDS);
        } finally {
            executors.close().get(30, TimeUnit.SECONDS);
            root.detachAppender(captured);
        }
        assertFalse(captured.list.stream().anyMatch(event -> event.getFormattedMessage().contains(SECRET)),
                "no log line holds the management secret");
    }

    @Test
    void aServerWithAManagementFileInstallsItsTokenWhenItLeads() throws Exception {
        HttpResponse<String> answer = refusedOnceInstalled();

        JsonNode error = JSON.readTree(answer.body());
        assertEquals("permission_denied", error.get("code").asText(), answer.body());
        assertEquals(1, error.get("resetIndex").asLong(), "the file's token used the bootstrap");
    }

    @Test
    void aServerWithAclsEnforcesThemOnItsHttpApiItsRemovalsAndItsGrpcApi() throws Exception {
        refusedOnceInstalled();

        HttpResponse<String> anonymous = http("GET", "/v1/operator/raft/configuration", null);
        assertEquals(403, anonymous.statusCode(), anonymous.body());
        assertEquals("permission_denied", JSON.readTree(anonymous.body()).get("code").asText());
        assertEquals(200, http("GET", "/v1/operator/raft/configuration", SECRET).statusCode());

        HttpResponse<String> removal = http("DELETE", "/v1/operator/raft/peer?name=absent", SECRET);
        assertEquals(404, removal.statusCode(), "the management token passes operator:write on the server and "
                + "in its membership service, which then finds no such server: " + removal.body());

        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", service.boundPorts().get("apiGrpc"))
                .usePlaintext().build();
        try {
            DistributedStateServiceGrpc.DistributedStateServiceBlockingStub stub =
                    DistributedStateServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
            GetRequest get = GetRequest.newBuilder().setKey("missing").build();
            StatusRuntimeException refused = assertThrows(StatusRuntimeException.class, () -> stub.get(get));
            assertEquals(Status.Code.PERMISSION_DENIED, refused.getStatus().getCode());

            Metadata token = new Metadata();
            token.put(Metadata.Key.of(AclGrpcGuard.TOKEN_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER), SECRET);
            assertFalse(stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(token)).get(get).getFound());
        } finally {
            channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Waits until the server, once leader, has installed the file's token, and then asks for a bootstrap. It must not
     * ask before: a bootstrap that reaches the new leader before its installer proposes is applied, and the file's
     * token is then created instead, as the installer's own tests show.
     */
    private HttpResponse<String> refusedOnceInstalled() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (captured.list.stream().noneMatch(event -> event.getFormattedMessage()
                .startsWith("Installed the management token from server.acl.initialManagementTokenFile"))) {
            assertTrue(System.nanoTime() < deadline, "the server installs the file's token within 20 s");
            Thread.sleep(50);
        }
        return http("PUT", "/v1/acl/bootstrap", null);
    }

    private HttpResponse<String> http(String method, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + service.boundPorts().get("http") + path))
                .timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("X-Qraft-Token", token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

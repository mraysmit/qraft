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

package dev.mars.qraft.raft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ComposeContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker test of ACLs (ACL list, Phase 9): three servers with ACLs enabled under {@code deny}, each installing the
 * management token from its mounted file, and a client in a container of its own holding a token with a node identity
 * for its node and a service identity for its service. The client registers and is ready; when its token is revoked,
 * the servers refuse its next heartbeat, it logs why and becomes not ready, as Phase 8 has it.
 *
 * <p>Requires Docker. Excluded from the default build; run with {@code mvn test -Dgroups=docker -Dtest.excludedGroups=}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
@Tag("docker")
class DockerAclTest {
    /** The test-only management secret in {@code docker/config/three-node-acl/management-token}. */
    private static final String MANAGEMENT = "3f1c6b2a-8d4e-4f7a-9b2c-5e6d7f8a9b0c";
    private static final String CLIENT_TOKEN_FILE = "/etc/qraft/client-token";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void aClientWhoseTokenIsRevokedLogsWhyAndBecomesNotReady() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeAclCluster();
        SharedDockerClusterFixture.DetachedClient client = null;
        try {
            List<String> servers = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> servers.stream().anyMatch(server ->
                    status(send(server, "GET", "/v1/acl/token/self", null, MANAGEMENT)) == 200));

            AtomicReference<JsonNode> created = new AtomicReference<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (String server : servers) {
                    HttpResponse<String> answer = send(server, "PUT", "/v1/acl/token", """
                            {"description":"acl-client","nodeIdentities":[{"nodeName":"acl-client"}],
                             "serviceIdentities":[{"serviceName":"web"}]}""", MANAGEMENT);
                    if (answer != null && answer.statusCode() == 200) {
                        created.set(JSON.readTree(answer.body()));
                        return true;
                    }
                }
                return false;
            });
            String accessor = created.get().path("accessorId").asText();
            client = SharedDockerClusterFixture.startDetachedClient(cluster, "acl-client", clientJson(),
                    Map.of(CLIENT_TOKEN_FILE, created.get().path("secretId").asText()));
            SharedDockerClusterFixture.DetachedClient started = client;

            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).until(() ->
                    readiness(started) == 200 && webOnClient(servers.getFirst()));

            await().atMost(Duration.ofSeconds(30)).until(() -> servers.stream().anyMatch(server ->
                    status(send(server, "DELETE", "/v1/acl/token/" + accessor, null, MANAGEMENT)) == 200));

            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).until(() ->
                    readiness(started) != 200);
            String logs = started.logs();
            assertTrue(logs.contains("The servers refused the client's token for its heartbeat"),
                    "the client says why it is not ready");
            assertTrue(logs.contains("token_not_found"), "the revoked token is not found");
            assertEquals(503, readiness(started));
        } finally {
            if (client != null) client.close();
            SharedDockerClusterFixture.stopAndCapture(cluster);
        }
    }

    /** Whether the catalog, read with the management token, lists the client's web instance. */
    private static boolean webOnClient(String server) throws Exception {
        HttpResponse<String> answer = send(server, "GET", "/v1/catalog/service/web", null, MANAGEMENT);
        if (answer == null || answer.statusCode() != 200) return false;
        for (JsonNode instance : JSON.readTree(answer.body())) {
            if ("acl-client".equals(instance.path("nodeId").asText())) return true;
        }
        return false;
    }

    private static int readiness(SharedDockerClusterFixture.DetachedClient client) {
        try {
            return Integer.parseInt(client.exec("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                    "--max-time", "2", "http://127.0.0.1:8500/health/ready").strip());
        } catch (Exception unanswered) {
            return -1;
        }
    }

    private static String clientJson() {
        return """
                {"version":1,
                 "client":{"nodeName":"acl-client","dataDirectory":"/app/data","address":"acl-client","httpPort":8500,
                           "bindAddress":"127.0.0.1","heartbeatIntervalMs":500,"shutdownTimeoutMs":5000,
                           "acl":{"enabled":true,"defaultPolicy":"deny",
                                  "tokens":{"clientFile":"%s","configFileRegistrationFile":"%s"}}},
                 "servers":{"urls":["http://server1:8080","http://server2:8080","http://server3:8080"],
                            "requestTimeoutMs":3000},
                 "catalog":{"registrationRetryMinMs":250,"registrationRetryMaxMs":2000,"contactFreshnessMs":90000,
                            "services":[{"id":"web","name":"web","address":"acl-client","port":8500}]}}"""
                .formatted(CLIENT_TOKEN_FILE, CLIENT_TOKEN_FILE);
    }

    private static int status(HttpResponse<String> response) {
        return response == null ? -1 : response.statusCode();
    }

    private static HttpResponse<String> send(String server, String method, String path, String body, String token) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server + path))
                    .timeout(Duration.ofSeconds(5))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
            if (token != null) request.header("X-Qraft-Token", token);
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception unreachable) {
            return null;
        }
    }
}

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
import dev.mars.qraft.common.tls.TlsMaterial;
import dev.mars.qraft.common.tls.TlsSettings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ComposeContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker test of transport security (transport security list, Phase 6): three servers that serve only TLS, with
 * HTTPS requiring a client certificate and mutual TLS on the Raft port, elect one leader that all of them name, which
 * only heartbeats over mutual TLS can bring about; each answers a caller with the test client certificate, and none
 * answers plain HTTP or a caller without a certificate.
 *
 * <p>Requires Docker. Excluded from the default build; run with {@code mvn test -Dgroups=docker -Dtest.excludedGroups=}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
@Tag("docker")
class DockerTlsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static Path fixture(String name) {
        try {
            return Path.of(DockerTlsTest.class.getResource("/tls/" + name).toURI());
        } catch (Exception e) {
            throw new IllegalStateException("test TLS fixture " + name + " is missing", e);
        }
    }

    private static HttpClient client(boolean withCertificate) {
        TlsMaterial material = TlsMaterial.load(new TlsSettings("test.tls", fixture("ca.pem"),
                withCertificate ? Optional.of(fixture("client.pem")) : Optional.empty(),
                withCertificate ? Optional.of(fixture("client.key")) : Optional.empty(), List.of("TLSv1.3", "TLSv1.2")));
        return HttpClient.newBuilder().sslContext(material.clientContext())
                .sslParameters(material.clientParameters(true)).connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Test
    void threeServersElectOverMutualTlsAndAnswerOnlyACallerWithACertificate() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeTlsCluster();
        try (HttpClient https = client(true); HttpClient noCertificate = client(false);
             HttpClient plain = HttpClient.newHttpClient()) {
            List<Integer> ports = List.of(cluster.getServicePort("server1", 8080),
                    cluster.getServicePort("server2", 8080), cluster.getServicePort("server3", 8080));
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions().until(() -> {
                Set<String> leaders = new HashSet<>();
                for (int port : ports) {
                    JsonNode status = JSON.readTree(get(https, "https", port, "/raft/status").body());
                    if (status.path("leaderId").isNull() || status.path("leaderId").isMissingNode()) return false;
                    leaders.add(status.path("leaderId").asText());
                }
                return leaders.size() == 1;
            });
            for (int port : ports) {
                assertEquals(200, get(https, "https", port, "/health/ready").statusCode(),
                        "a server with a leader is ready, answered over HTTPS");
                assertThrows(IOException.class, () -> get(noCertificate, "https", port, "/health/live"),
                        "a caller without a certificate is refused");
                assertThrows(IOException.class, () -> get(plain, "http", port, "/health/live"),
                        "plain HTTP is not served");
            }
            for (String service : List.of("server1", "server2", "server3")) {
                String logs = cluster.getContainerByServiceName(service).orElseThrow().getLogs();
                assertTrue(logs.contains("gRPC Raft server started on port 9080 with mutual TLS"),
                        service + " serves the Raft port over mutual TLS");
            }
        } finally {
            SharedDockerClusterFixture.stopAndCapture(cluster);
        }
    }

    private static HttpResponse<String> get(HttpClient client, String scheme, int port, String path)
            throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(scheme + "://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
    }
}

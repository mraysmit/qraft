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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.containers.ComposeContainer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.DockerHealthApiHelper.leaderIndex;
import static dev.mars.qraft.raft.SharedDockerClusterFixture.clientStatus;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker test of decision 9 of the client list: two client containers, each standing for a host, keep the default
 * {@code 127.0.0.1} bind, and their applications use only {@code localhost}. An application's requests are made from
 * inside its client's network namespace, by {@code exec} or from a container that joins the namespace. An application
 * registers through one client and is discovered through the other, its registration survives its client being killed,
 * and it deregisters cleanly. A container outside a client's namespace, on the same network, is refused.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-01
 * @version 1.0
 */
@Tag("docker")
// The test starts its own cluster and then waits on bounded conditions; the method budget exceeds their sum.
@Timeout(value = 10, unit = TimeUnit.MINUTES)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("shared-docker-clusters")
class DockerClientLocalApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIRST_CLIENT = "docker-client";
    private static final String SECOND_CLIENT = "docker-client-2";
    private static final int CONNECTION_REFUSED = 7;
    private static final String LOCAL_API = "http://127.0.0.1:8080";
    private static final String HEALTH = "/v1/health/service/orders";

    /** An answer of the local API: its status, and its body. */
    private record Answer(int status, String body) {
        static final Answer NONE = new Answer(-1, "");

        static Answer of(String curlOutput) {
            int line = curlOutput.stripTrailing().lastIndexOf('\n');
            return new Answer(Integer.parseInt(curlOutput.substring(line + 1).strip()),
                    curlOutput.substring(0, Math.max(line, 0)));
        }
    }

    @Test
    void applicationsOnTwoHostsFindEachOtherThroughLocalhostAndNothingOutsideReachesTheirClients() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeClusterWithClient();
        try {
            List<String> servers = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
            await().atMost(Duration.ofSeconds(60)).until(() -> leaderIndex(servers) >= 0
                    && clientStatus(cluster, "/health/ready") == 200);
            try (SharedDockerClusterFixture.DetachedClient second = SharedDockerClusterFixture.startDetachedClient(
                    cluster, "client2", secondClientJson())) {
                await().atMost(Duration.ofSeconds(60)).until(() -> onSecond(second, "/health/ready").status() == 200);

                // An application registers through its own client and is discovered through the other's.
                assertEquals(200, onFirst(cluster, "PUT", "/v1/client/service/register", """
                        {"id":"orders-1","name":"orders","port":9000,
                         "checks":[{"id":"app","type":"ttl","ttlMs":60000}]}
                        """).status());
                assertEquals(200, onFirst(cluster, "PUT", "/v1/client/check/pass/app?note=ready", null).status());
                await().atMost(Duration.ofSeconds(60)).until(() -> "ready".equals(passingOutput(second)));
                JsonNode entry = JSON.readTree(onSecond(second, HEALTH + "?passing").body()).get(0);
                assertEquals(FIRST_CLIENT, entry.path("service").path("nodeId").asText());
                assertEquals("healthy", entry.path("nodeStatus").asText());

                // An application in a container of its own joins its client's network namespace.
                SharedDockerClusterFixture.DockerCommandResult joined = SharedDockerClusterFixture.runQraftCommand(
                        "local-api-application", List.of("--network", "container:" + second.name(), "--entrypoint", "curl"),
                        "-sf", "--max-time", "10", LOCAL_API + "/v1/catalog/service/orders");
                assertTrue(joined.exited() && joined.exitCode() == 0, joined.output());
                assertTrue(joined.output().contains("orders-1"), joined.output());

                // Outside a client's namespace, on the same network, the name resolves and the port refuses.
                assertEquals(CONNECTION_REFUSED, refusal(cluster, "server1", "http://client:8080/health/live"));
                assertEquals(CONNECTION_REFUSED, refusal(cluster, "server1", "http://client2:8080/health/live"));
                assertEquals(CONNECTION_REFUSED, refusal(cluster, "client", "http://client2:8080/health/live"));

                // What the application registered survives its client being killed, and it tells its check again.
                SharedDockerClusterFixture.killContainer(cluster, "client");
                SharedDockerClusterFixture.startContainer(cluster, "client");
                await().atMost(Duration.ofSeconds(90)).until(() ->
                        onFirst(cluster, "PUT", "/v1/client/check/pass/app?note=restarted", null).status() == 200);
                await().atMost(Duration.ofSeconds(60)).until(() -> "restarted".equals(passingOutput(second)));
                assertTrue(JSON.readTree(onFirst(cluster, "GET", "/v1/client/services", null).body()).has("orders-1"));

                // It leaves cleanly, and the other host sees it go.
                assertEquals(200, onFirst(cluster, "PUT", "/v1/client/service/deregister/orders-1", null).status());
                await().atMost(Duration.ofSeconds(60)).until(() -> {
                    Answer answer = onSecond(second, HEALTH);
                    return answer.status() == 200 && JSON.readTree(answer.body()).isEmpty();
                });
            }
        } finally {
            SharedDockerClusterFixture.stopAndCapture(cluster);
        }
    }

    /** The output of the {@code app} check of the passing {@code orders} instance, or {@code null} without one. */
    private static String passingOutput(SharedDockerClusterFixture.DetachedClient client) throws Exception {
        Answer answer = onSecond(client, HEALTH + "?passing");
        if (answer.status() != 200) return null;
        JsonNode entries = JSON.readTree(answer.body());
        return entries.size() == 1 ? entries.get(0).path("checks").get(0).path("output").asText() : null;
    }

    /** What the application on the compose client's host does. {@link Answer#NONE} when nothing answers. */
    private static Answer onFirst(ComposeContainer cluster, String method, String pathAndQuery, String body) {
        try {
            String[] command = body == null
                    ? new String[] {"curl", "-s", "-w", "\n%{http_code}", "--max-time", "10", "-X", method,
                            LOCAL_API + pathAndQuery}
                    : new String[] {"curl", "-s", "-w", "\n%{http_code}", "--max-time", "10", "-X", method,
                            "-d", body, LOCAL_API + pathAndQuery};
            return Answer.of(SharedDockerClusterFixture.execInService(cluster, "client", command));
        } catch (Exception unanswered) {
            return Answer.NONE;
        }
    }

    /** What an application on the detached client's host reads. {@link Answer#NONE} when nothing answers. */
    private static Answer onSecond(SharedDockerClusterFixture.DetachedClient client, String pathAndQuery) {
        try {
            return Answer.of(client.exec("curl", "-s", "-w", "\n%{http_code}", "--max-time", "10",
                    LOCAL_API + pathAndQuery));
        } catch (Exception unanswered) {
            return Answer.NONE;
        }
    }

    private static int refusal(ComposeContainer cluster, String from, String url) throws Exception {
        return SharedDockerClusterFixture.execExitCode(cluster, from, "curl", "-s", "-o", "/dev/null",
                "--max-time", "10", url);
    }

    /** A client with the default bind and no services of its own, as a host before its applications arrive. */
    private static String secondClientJson() {
        return """
                {"version":1,
                 "client":{"nodeName":"%s","dataDirectory":"/app/data","address":"client2","httpPort":8080,
                          "heartbeatIntervalMs":1000,"shutdownTimeoutMs":8000},
                 "servers":{"urls":["http://server1:8080","http://server2:8080","http://server3:8080"],
                            "requestTimeoutMs":3000},
                 "catalog":{"registrationRetryMinMs":100,"registrationRetryMaxMs":1000,"contactFreshnessMs":5000,
                   "services":[]},
                 "logging":{"directory":"/app/logs"}}
                """.formatted(SECOND_CLIENT);
    }
}

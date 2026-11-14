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
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.InMemoryTransportSimulatorFixture;
import dev.mars.qraft.raft.ManualRaftClusterFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the services summary the administrative interface's Services list reads,
 * {@code GET /v1/internal/ui/services} (UI-2 task list, Step 1): one entry per service in the read's scope, with its
 * tags, its instances counted in all and by health, where they run, and the last change among them. ACL filtering is
 * tested in {@link HttpApiServerAclEnforcementTest}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-14
 * @version 1.0
 */
class HttpApiServerServicesSummaryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUMMARY = "/v1/internal/ui/services";

    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private HttpApiServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void startAServerWithServicesInTwoScopes() throws Exception {
        runtime = RaftExecutorsFixture.create();
        cluster = new ManualRaftClusterFixture(runtime);
        InMemoryTransportSimulatorFixture.clearAllTransports();
        QraftStateStore store = new QraftStateStore();
        RaftNode node = cluster.add(cluster.builder("a", Set.of("a"), new InMemoryTransportSimulatorFixture("a"),
                store, RaftNodeMode.volatileMode()));
        ManualRaftClusterFixture.startAll(node);
        RaftNode leader = cluster.elect(node);
        server = new HttpApiServer(0, leader, store);
        server.start().join();

        ok(send("PUT", "/v1/tenant", "{\"Name\":\"acme\"}", Map.of()));
        ok(send("PUT", "/v1/namespace", "{\"Name\":\"prod\"}", Map.of("X-Qraft-Tenant", "acme")));
        register("node-1", Map.of(), service("web-1", "web", "[\"v1\"]", "dc-1", "eu-west", "[\"http\"]"));
        register("node-2", Map.of(), service("web-2", "web", "[\"v2\",\"v1\"]", "dc-2", "eu-west", "[\"http\"]"));
        register("node-2", Map.of(), service("db-1", "db", "[]", "dc-2", "eu-west", "[\"tcp\"]"));
        register("node-3", Map.of("X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "prod"),
                service("pay-1", "payments", "[]", "dc-1", "us-east", "[]"));
        observe("node-1", "web-1", "http", "passing");
        observe("node-2", "web-2", "http", "critical");
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (server != null) server.close();
            cluster.close();
        } finally {
            runtime.close().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
        }
    }

    @Test
    void eachServiceInTheDefaultScopeIsSummarisedInNameOrder() throws Exception {
        HttpResponse<String> response = send("GET", SUMMARY, null, Map.of());

        JsonNode summary = ok(response);
        assertEquals(List.of("db", "web"), names(summary), "the other scope's service is not read: " + summary);
        assertTrue(response.headers().firstValue("X-Qraft-Index").orElse("").matches("\\d+"));
        JsonNode web = summary.get(1);
        assertEquals(List.of("v1", "v2"), texts(web.path("tags")), "distinct and sorted");
        assertEquals(2, web.path("instanceCount").asInt());
        assertEquals(1, web.path("health").path("passing").asInt());
        assertEquals(1, web.path("health").path("critical").asInt());
        assertEquals(0, web.path("health").path("warning").asInt());
        assertEquals(0, web.path("health").path("maintenance").asInt());
        assertEquals(0, web.path("health").path("unknown").asInt());
        assertEquals(List.of("dc-1", "dc-2"), texts(web.path("datacenters")));
        assertEquals(List.of("eu-west"), texts(web.path("regions")));
        assertEquals("default", web.path("tenantId").asText());
        assertEquals("default", web.path("namespace").asText());
        assertEquals(1, summary.get(0).path("health").path("unknown").asInt(), "a check with no result is unknown");
    }

    @Test
    void theLastChangeIsTheHighestModifyIndexAmongTheInstances() throws Exception {
        long highest = 0;
        for (JsonNode instance : ok(send("GET", "/v1/catalog/service/web", null, Map.of()))) {
            highest = Math.max(highest, instance.path("modifyIndex").asLong());
        }

        JsonNode web = ok(send("GET", SUMMARY, null, Map.of())).get(1);

        assertTrue(highest > 0);
        assertEquals(highest, web.path("modifyIndex").asLong());
    }

    @Test
    void theScopeTheHeadersNameIsTheOneRead() throws Exception {
        JsonNode summary = ok(send("GET", SUMMARY, null, Map.of("X-Qraft-Tenant", "acme", "X-Qraft-Namespace", "prod")));

        assertEquals(List.of("payments"), names(summary));
        assertEquals("acme", summary.get(0).path("tenantId").asText());
        assertEquals("prod", summary.get(0).path("namespace").asText());
        assertEquals(List.of("us-east"), texts(summary.get(0).path("regions")));
        assertEquals(List.of(), names(ok(send("GET", SUMMARY, null, Map.of("X-Qraft-Tenant", "acme")))),
                "acme's default namespace holds nothing");
    }

    @Test
    void itTakesTheReadOptionsAndRefusesAnyOtherParameter() throws Exception {
        assertEquals(200, send("GET", SUMMARY + "?stale", null, Map.of()).statusCode());
        HttpResponse<String> tagged = send("GET", SUMMARY + "?tag=v1", null, Map.of());
        assertEquals(400, tagged.statusCode());
        assertEquals("invalid_query", JSON.readTree(tagged.body()).path("code").asText());
        HttpResponse<String> blank = send("GET", SUMMARY, null, Map.of("X-Qraft-Tenant", ""));
        assertEquals(400, blank.statusCode());
        assertEquals("invalid_scope", JSON.readTree(blank.body()).path("code").asText());
    }

    @Test
    void aBlockingReadAnswersOnceTheCatalogPassesItsIndex() throws Exception {
        HttpResponse<String> first = send("GET", SUMMARY, null, Map.of());
        long index = Long.parseLong(first.headers().firstValue("X-Qraft-Index").orElseThrow());

        CompletableFuture<HttpResponse<String>> blocked = client.sendAsync(HttpRequest.newBuilder(
                URI.create("http://localhost:" + server.port() + SUMMARY + "?index=" + index + "&wait=30s")).GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        register("node-4", Map.of(), service("cache-1", "cache", "[]", "dc-1", "eu-west", "[]"));
        HttpResponse<String> answered = blocked.get(20, TimeUnit.SECONDS);

        assertTrue(names(ok(answered)).contains("cache"), answered.body());
        assertTrue(Long.parseLong(answered.headers().firstValue("X-Qraft-Index").orElseThrow()) > index);
    }

    private static String service(String id, String name, String tags, String datacenter, String region,
                                  String checks) {
        return """
                {"serviceId":"%s","serviceName":"%s","address":"10.0.0.5","port":8080,"tags":%s,\
                "datacenter":"%s","region":"%s","checks":%s}""".formatted(id, name, tags, datacenter, region, checks);
    }

    private void register(String node, Map<String, String> scope, String service) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>(scope);
        headers.put("X-Qraft-Node", node);
        ok(send("PUT", "/v1/catalog/register", "{\"service\":" + service + "}", headers));
    }

    private void observe(String node, String serviceId, String checkId, String status) throws Exception {
        ok(send("PUT", "/v1/catalog/check/observe", """
                {"serviceId":"%s","checkId":"%s","status":"%s","sequenceNumber":1,\
                "observedAt":"2026-11-14T09:59:59Z","ttlMillis":600000,"output":"out"}"""
                .formatted(serviceId, checkId, status), Map.of("X-Qraft-Node", node)));
    }

    private HttpResponse<String> send(String method, String path, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.request().uri() + " " + response.body());
        return JSON.readTree(response.body());
    }

    private static List<String> names(JsonNode summary) {
        List<String> names = new ArrayList<>();
        summary.forEach(entry -> names.add(entry.path("name").asText()));
        return names;
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(value -> texts.add(value.asText()));
        return texts;
    }
}

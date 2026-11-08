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
import dev.mars.qraft.common.events.EventJournal;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code GET /v1/operator/events} (event query and watch list, Phase 2, decision 2): one server's events, paged
 * by cursor and limit, filtered by category and type, with the cursor to ask from next and the page's status, a domain
 * event carrying its committed index and replicated identity; a cursor of another boot and a request that is not
 * valid are answered as such.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class HttpApiServerEventQueryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private HttpApiServer server;
    private final EventJournal journal = EventJournal.forBoot("events-node", Clock.systemUTC());
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

    private void start() throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("events-node").clusterNodes(Set.of("events-node")).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).events(journal).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null);
        server.useEventJournal(journal);
        server.start().get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode page(String query) throws Exception {
        HttpResponse<String> answer = send("GET", "/v1/operator/events" + query, null);
        assertEquals(200, answer.statusCode(), answer.body());
        return JSON.readTree(answer.body());
    }

    private static List<String> types(JsonNode page) {
        List<String> types = new ArrayList<>();
        page.path("events").forEach(event -> types.add(event.path("type").asText()));
        return types;
    }

    @Test
    void eventsArePagedFilteredAndAnsweredWithTheirCursorAndStatus() throws Exception {
        start();
        for (String key : List.of("a", "b", "c")) assertEquals("true", send("PUT", "/v1/kv/" + key, "v").body());

        JsonNode all = page("");
        assertEquals("complete", all.path("status").asText());
        assertTrue(types(all).contains("raft.leadership_gained"), types(all).toString());
        assertEquals(3, types(all).stream().filter("key.set"::equals).count());
        JsonNode set = all.path("events").get(types(all).indexOf("key.set"));
        assertEquals("domain", set.path("category").asText());
        assertTrue(set.path("committedIndex").asLong() > 0);
        assertEquals(set.path("committedIndex").asLong() + ":0:key.set", set.path("replicatedId").asText());
        assertEquals("a", set.path("event").path("key").asText());
        assertEquals("default", set.path("tenant").asText());
        String bootPrefix = journal.bootId() + ":";
        assertTrue(set.path("cursor").asText().startsWith(bootPrefix));

        JsonNode first = page("?limit=2");
        assertEquals(2, first.path("events").size());
        assertEquals("more", first.path("status").asText());
        JsonNode rest = page("?after=" + first.path("next").asText());
        assertEquals(all.path("events").size() - 2, rest.path("events").size(), "the next page follows on");
        assertEquals(List.of("key.set", "key.set", "key.set"), types(page("?type=key.set")));
        assertFalse(types(page("?category=system")).contains("key.set"));

        assertEquals("other_boot", page("?after=" + UUID.randomUUID() + ":0").path("status").asText());
        assertEquals(400, send("GET", "/v1/operator/events?after=nonsense", null).statusCode());
        assertEquals(400, send("GET", "/v1/operator/events?limit=1001", null).statusCode());
        assertEquals(400, send("GET", "/v1/operator/events?category=gossip", null).statusCode());
        assertEquals(405, send("PUT", "/v1/operator/events", "").statusCode());
    }
}

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
import dev.mars.qraft.common.events.EventJournal;
import dev.mars.qraft.common.events.EventSeverity;
import dev.mars.qraft.common.events.QraftEvent;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code GET /v1/operator/events/watch} (event query and watch list, Phase 3, decision 3): server-sent events that
 * replay what the journal holds after the cursor and then follow, each with its cursor as its {@code id}, a comment as a
 * heartbeat while nothing happens, and a final {@code end} event naming why the stream ends: the server stopping, or
 * history the journal no longer holds; a client that leaves has its watch ended.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class HttpApiServerEventWatchTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private HttpApiServer server;
    private EventJournal journal = EventJournal.forBoot("watch-node", Clock.systemUTC());
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

    private int put(String key) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/kv/" + key))
                .PUT(HttpRequest.BodyPublishers.ofString("v")).build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    /** The next line that {@code accepts}, skipping others, within ten seconds. */
    private static String next(BlockingQueue<String> lines, java.util.function.Predicate<String> accepts)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String line = lines.poll(100, TimeUnit.MILLISECONDS);
            if (line != null && accepts.test(line)) return line;
        }
        throw new AssertionError("no such line arrived");
    }

    /** A system event the tests publish themselves. */
    private record Marker(int n) implements QraftEvent {
        @Override
        public String type() {
            return "test.marker";
        }
    }

    private void start() throws Exception {
        start(Duration.ofMillis(200));
    }

    private void start(Duration heartbeat) throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("watch-node").clusterNodes(Set.of("watch-node")).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).events(journal).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null);
        server.useEventJournal(journal);
        server.useEventWatchHeartbeat(heartbeat);
        server.start().get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<Stream<String>> watch(String query, BlockingQueue<String> lines) throws Exception {
        HttpResponse<Stream<String>> watch = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + server.port() + "/v1/operator/events/watch" + query)).build(), HttpResponse.BodyHandlers.ofLines());
        Thread.ofVirtual().start(() -> watch.body().forEach(lines::add));
        return watch;
    }

    @Test
    void theWatchReplaysThenFollowsBeatsWhileQuietAndEndsWhenTheServerStops() throws Exception {
        start();
        assertEquals(200, put("before-1"));
        assertEquals(200, put("before-2"));

        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        HttpResponse<Stream<String>> watch = watch("?type=key.set", lines);
        assertEquals(200, watch.statusCode());
        assertTrue(watch.headers().firstValue("Content-Type").orElseThrow().startsWith("text/event-stream"));

        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            next(lines, line -> line.equals("event: key.set"));
            keys.add(JSON.readTree(next(lines, line -> line.startsWith("data: ")).substring(6)).path("event")
                    .path("key").asText());
        }
        assertEquals(List.of("before-1", "before-2"), keys, "replayed in order");
        next(lines, line -> line.startsWith(":"));
        assertEquals(200, put("after"));
        String id = next(lines, line -> line.startsWith("id: "));
        assertTrue(id.startsWith("id: " + journal.bootId() + ":"), id);
        assertEquals("after", JSON.readTree(next(lines, line -> line.startsWith("data: ")).substring(6)).path("event")
                .path("key").asText(), "and then followed");

        server.close();
        server = null;
        next(lines, line -> line.equals("event: end"));
        assertEquals("server_stopping", JSON.readTree(next(lines, line -> line.startsWith("data: ")).substring(6))
                .path("reason").asText());
    }

    @Test
    void aWatchFromHistoryTheJournalNoLongerHoldsEndsAtOnceWithWhereToResume() throws Exception {
        journal.close();
        journal = new EventJournal("watch-node", UUID.randomUUID(), Clock.systemUTC(), 3, 16);
        start();
        for (int n = 0; n < 6; n++) journal.publishSystem(EventSeverity.INFO, 1, new Marker(n));

        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        assertEquals(200, watch("?after=" + journal.bootId() + ":0", lines).statusCode());
        next(lines, line -> line.equals("event: end"));
        var end = JSON.readTree(next(lines, line -> line.startsWith("data: ")).substring(6));
        assertEquals("history_gap", end.path("reason").asText());
        assertTrue(end.path("resumeAfter").asText().startsWith(journal.bootId() + ":"), end.toString());
        assertEquals(400, http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                + "/v1/operator/events/watch?after=" + journal.bootId() + ":99")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode(), "a cursor past the journal's last event");
    }

    @Test
    void aQuietWatchIsAnsweredAtOnceRatherThanAtItsFirstHeartbeat() throws Exception {
        start(Duration.ofSeconds(30));
        HttpResponse<Stream<String>> watch = http.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + server.port() + "/v1/operator/events/watch?type=nothing.happens")).build(),
                HttpResponse.BodyHandlers.ofLines()).get(5, TimeUnit.SECONDS);
        try (Stream<String> body = watch.body()) {
            assertEquals(200, watch.statusCode());
        }
    }

    @Test
    void aClientThatLeavesHasItsWatchEnded() throws Exception {
        start();
        HttpResponse<InputStream> watch = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + server.port() + "/v1/operator/events/watch")).build(), HttpResponse.BodyHandlers.ofInputStream());
        BufferedReader body = new BufferedReader(new InputStreamReader(watch.body(), java.nio.charset.StandardCharsets.UTF_8));
        String line;
        do {
            line = body.readLine();
        } while (line != null && !line.startsWith(":"));
        assertEquals(1, server.openEventWatches());
        body.close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (server.openEventWatches() > 0 && System.nanoTime() < deadline) Thread.sleep(20);
        assertEquals(0, server.openEventWatches(), "the server noticed the client left");
    }
}

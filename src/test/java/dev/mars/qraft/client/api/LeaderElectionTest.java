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

package dev.mars.qraft.client.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link LeaderElection} (client library list, Phase 3, decision 5) against a real server: of two contenders for
 * one key, one leads and holds the key with its value; the other takes over when the leader closes, which releases the
 * key and destroys its session, and when the leader's session is destroyed under it, after which the old leader
 * campaigns again; the listener hears each change in order.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class LeaderElectionTest {
    private static final String KEY = "service/billing/leader";
    private LibraryServerFixture fixture;
    private QraftApi api;
    private final List<LeaderElection> elections = new ArrayList<>();

    @AfterEach
    void stop() throws Exception {
        try {
            for (LeaderElection election : elections) election.close();
            if (api != null) api.close();
        } finally {
            if (fixture != null) fixture.close();
        }
    }

    /** Records what a contender's listener hears. */
    private static final class Heard implements LeaderElection.Listener {
        final BlockingQueue<String> calls = new LinkedBlockingQueue<>();

        @Override
        public void elected() {
            calls.add("elected");
        }

        @Override
        public void lost() {
            calls.add("lost");
        }

        String next() throws InterruptedException {
            String call = calls.poll(20, TimeUnit.SECONDS);
            if (call == null) throw new AssertionError("the listener heard nothing");
            return call;
        }
    }

    private LeaderElection contender(String node, Heard heard) {
        LeaderElection election = LeaderElection.builder(api, KEY).value(node.getBytes(StandardCharsets.UTF_8))
                .node(node).ttl(Duration.ofSeconds(10)).lockDelay(Duration.ZERO).retryWait(Duration.ofSeconds(2))
                .listener(heard).start();
        elections.add(election);
        return election;
    }

    private static void waitUntil(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), description);
    }

    @Test
    void oneContenderLeadsAndTheOtherTakesOverWhenTheLeaderCloses() throws Exception {
        fixture = LibraryServerFixture.start();
        api = QraftApi.builder().address(fixture.address()).build();
        Heard heardA = new Heard();
        Heard heardB = new Heard();
        LeaderElection a = contender("host-a", heardA);
        assertEquals("elected", heardA.next());
        assertTrue(a.isLeader());
        LeaderElection b = contender("host-b", heardB);
        Thread.sleep(500);
        assertFalse(b.isLeader(), "the key is held");
        KvEntry held = api.keyValue().get(KEY).orElseThrow();
        assertEquals(a.session().orElseThrow(), held.session());
        assertArrayEquals("host-a".getBytes(StandardCharsets.UTF_8), held.value());

        String closedSession = a.session().orElseThrow();
        a.close();
        assertEquals("lost", heardA.next(), "closing a leader ends its leadership");
        assertFalse(a.isLeader());
        assertEquals("elected", heardB.next());
        assertTrue(b.isLeader());
        assertEquals(Optional.empty(), api.sessions().info(closedSession), "close destroyed the session");
        assertArrayEquals("host-b".getBytes(StandardCharsets.UTF_8), api.keyValue().get(KEY).orElseThrow().value());
        assertTrue(heardB.calls.isEmpty());
    }

    @Test
    void aLeaderWhoseSessionIsDestroyedLosesAndCampaignsAgain() throws Exception {
        fixture = LibraryServerFixture.start();
        api = QraftApi.builder().address(fixture.address()).build();
        Heard heardA = new Heard();
        Heard heardB = new Heard();
        LeaderElection a = contender("host-a", heardA);
        assertEquals("elected", heardA.next());
        LeaderElection b = contender("host-b", heardB);
        waitUntil(() -> b.session().isPresent(), "the second contender has its session");

        String first = a.session().orElseThrow();
        assertTrue(api.sessions().destroy(first));
        assertEquals("lost", heardA.next(), "the leader noticed its session was gone");
        assertEquals("elected", heardB.next(), "the other took over");
        assertTrue(b.isLeader());
        waitUntil(() -> a.session().isPresent() && !a.session().get().equals(first), "it campaigns with a new session");
        assertFalse(a.isLeader());
        assertNotEquals(first, api.keyValue().get(KEY).orElseThrow().session());
        assertTrue(heardA.calls.isEmpty() && heardB.calls.isEmpty(), "no further change");
    }
}

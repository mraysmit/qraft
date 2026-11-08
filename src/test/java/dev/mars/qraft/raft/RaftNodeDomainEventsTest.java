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

import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.common.events.EventCategory;
import dev.mars.qraft.common.events.EventCursor;
import dev.mars.qraft.common.events.EventEnvelope;
import dev.mars.qraft.common.events.EventFilter;
import dev.mars.qraft.common.events.EventJournal;
import dev.mars.qraft.common.events.ReplicatedEventId;
import dev.mars.qraft.state.CatalogCommand;
import dev.mars.qraft.state.CatalogEvents;
import dev.mars.qraft.state.NodeCommand;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the domain events a node offers for the entries it applies (events list, Phase 4; architecture section 6):
 * each carries the committed index of its entry and the entry's term, every replica offers the same events with the
 * same replicated identities, and the leader has offered them before the submitter's result completes.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class RaftNodeDomainEventsTest {
    private static final Set<String> MEMBERS = Set.of("d1", "d2", "d3");
    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private final Map<String, EventJournal> journals = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        runtime = RaftExecutorsFixture.create();
        cluster = new ManualRaftClusterFixture(runtime);
        InMemoryTransportSimulatorFixture.clearAllTransports();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            cluster.close();
        } finally {
            journals.values().forEach(EventJournal::close);
            runtime.close().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
        }
    }

    private List<EventEnvelope> domain(String nodeId) {
        EventJournal journal = journals.get(nodeId);
        return journal.recent(EventCursor.start(journal.bootId()), new EventFilter(Set.of(EventCategory.DOMAIN),
                Set.of(), Optional.empty(), Optional.empty()), 100).events();
    }

    @Test
    void everyReplicaOffersTheSameEventsAtTheirIndexAndTheLeaderBeforeTheResult() throws Exception {
        RaftNode[] nodes = new RaftNode[3];
        List<String> ids = List.of("d1", "d2", "d3");
        for (int i = 0; i < 3; i++) {
            EventJournal journal = EventJournal.forBoot(ids.get(i), Clock.systemUTC());
            journals.put(ids.get(i), journal);
            nodes[i] = cluster.add(cluster.builder(ids.get(i), MEMBERS, new InMemoryTransportSimulatorFixture(ids.get(i)),
                    new QraftStateStore(), RaftNodeMode.volatileMode()).events(journal));
        }
        ManualRaftClusterFixture.startAll(nodes);
        RaftNode leader = cluster.elect(nodes[0]);

        await(leader.submitCommand(NodeCommand.register(Node.of("host-a", "10.0.0.1", null, null, Map.of()),
                Instant.parse("2026-11-08T12:00:00Z"))));
        ServiceInstance web = new ServiceInstance("web-1", "web", "host-a", "10.0.0.1", 8080, List.of(), Map.of(),
                ServiceHealth.PASSING, "default", "default", "", "", true);
        boolean offeredFirst = await(leader.submitCommand(CatalogCommand.register(web))
                .thenApply(result -> domain("d1").stream().anyMatch(event ->
                        event.event() instanceof CatalogEvents.ServiceRegistered)));
        assertTrue(offeredFirst, "the leader offered the event before the submitter's result completed");
        cluster.settle();
        cluster.heartbeatUntil(leader, () -> domain("d2").size() == 2 && domain("d3").size() == 2,
                "the followers applied both entries");

        List<ReplicatedEventId> onLeader = domain("d1").stream().map(event -> event.replicatedId().orElseThrow())
                .toList();
        for (String follower : List.of("d2", "d3")) {
            assertEquals(onLeader, domain(follower).stream().map(event -> event.replicatedId().orElseThrow()).toList(),
                    "the same facts, with the same identities, on " + follower);
            assertEquals(domain("d1").stream().map(EventEnvelope::event).toList(),
                    domain(follower).stream().map(EventEnvelope::event).toList());
        }
        EventEnvelope registered = domain("d1").getLast();
        assertEquals(leader.getCurrentTerm(), registered.term());
        assertEquals(Optional.of("default"), registered.tenant());
        assertTrue(registered.committedIndex().getAsLong() > domain("d1").getFirst().committedIndex().getAsLong());
    }
}

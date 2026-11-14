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

import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.common.events.EventCursor;
import dev.mars.qraft.common.events.EventEnvelope;
import dev.mars.qraft.common.events.EventFilter;
import dev.mars.qraft.common.events.EventJournal;
import dev.mars.qraft.common.events.EventSeverity;
import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import dev.mars.qraft.testing.fault.InjectedFaultFixture;
import dev.mars.qraft.testing.fault.IntentionalErrorFixture;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Raft system events (events list, Phase 3, decision 4): a server that wins an election records it, with the
 * term, and one that loses its leadership records that; a published snapshot is recorded with its index; and a node
 * that fences records it once, however many transitions it then refuses. A server that never leads records none.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class RaftNodeSystemEventsTest {
    private static final Set<String> MEMBERS = Set.of("e1", "e2", "e3");
    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private final Map<String, EventJournal> journals = new LinkedHashMap<>();
    private RaftNode single;

    @BeforeEach
    void setUp() {
        runtime = RaftExecutorsFixture.create();
        cluster = new ManualRaftClusterFixture(runtime);
        InMemoryTransportSimulatorFixture.clearAllTransports();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            InMemoryTransportSimulatorFixture.healPartitions();
            if (single != null) single.stop().get(10, TimeUnit.SECONDS);
            cluster.close();
        } finally {
            journals.values().forEach(EventJournal::close);
            runtime.close().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
        }
    }

    private EventJournal journal(String nodeId) {
        return journals.computeIfAbsent(nodeId, id -> EventJournal.forBoot(id, Clock.systemUTC()));
    }

    private static List<EventEnvelope> events(EventJournal journal) {
        return journal.recent(EventCursor.start(journal.bootId()), EventFilter.all(), 100).events();
    }

    private static List<String> types(EventJournal journal) {
        return events(journal).stream().map(event -> event.event().type()).toList();
    }

    @Test
    void aLeaderRecordsItsElectionAndItsLossAndAFollowerRecordsNeither() throws Exception {
        RaftNode[] nodes = new RaftNode[3];
        List<String> ids = List.of("e1", "e2", "e3");
        for (int i = 0; i < 3; i++) {
            nodes[i] = cluster.add(cluster.builder(ids.get(i), MEMBERS, new InMemoryTransportSimulatorFixture(ids.get(i)),
                    new QraftStateStore(), RaftNodeMode.volatileMode()).events(journal(ids.get(i))));
        }
        ManualRaftClusterFixture.startAll(nodes);
        // A node records a leadership event after its state changes and before it tells its state listeners, so
        // isLeader() can change before the event is written; a listener's call marks the event recorded.
        CompletableFuture<RaftNode.State> led = new CompletableFuture<>();
        CompletableFuture<RaftNode.State> leftLeadership = new CompletableFuture<>();
        nodes[0].addStateChangeListener(state -> {
            if (state == RaftNode.State.LEADER) led.complete(state);
            else if (led.isDone()) leftLeadership.complete(state);
        });
        RaftNode leader = cluster.elect(nodes[0]);
        await(led);
        long term = leader.getCurrentTerm();

        assertEquals(List.of("raft.leadership_gained"), types(journal("e1")));
        EventEnvelope gained = events(journal("e1")).getFirst();
        assertEquals(term, gained.term());
        assertEquals(new RaftEvents.LeadershipGained(term), gained.event());
        assertEquals(List.of(), types(journal("e2")), "a follower records no leadership");

        IntentionalErrorsHelper.expect(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE);
        InMemoryTransportSimulatorFixture.createPartition(Set.of("e1"), Set.of("e2", "e3"));
        cluster.heartbeatUntilSteppedDown(leader);
        await(leftLeadership);
        assertEquals(List.of("raft.leadership_gained", "raft.leadership_lost"), types(journal("e1")));
        EventEnvelope lost = events(journal("e1")).getLast();
        assertEquals(EventSeverity.WARNING, lost.severity());
        assertEquals(term, ((RaftEvents.LeadershipLost) lost.event()).term());
    }

    @Test
    void aPublishedSnapshotIsRecordedWithItsIndex() throws Exception {
        TestRaftStorageFixture storage = new TestRaftStorageFixture();
        storage.open(null).get(10, TimeUnit.SECONDS);
        RaftNode node = cluster.add(cluster.builder("s1", Set.of("s1"), new InMemoryTransportSimulatorFixture("s1"),
                        new QraftStateStore(), RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(true).snapshotThreshold(5).snapshotCheckInterval(300).events(journal("s1")));
        ManualRaftClusterFixture.startAll(node);
        RaftNode leader = cluster.elect(node);
        for (int i = 0; i < 8; i++) await(leader.submitCommand(put("k" + i)));
        cluster.timers(leader).firePeriodic(300);

        awaitTrue(() -> leader.getSnapshotLastIndex() >= 5, "the leader takes a snapshot");
        awaitTrue(() -> types(journal("s1")).contains("raft.snapshot_published"), "the snapshot is recorded");
        RaftEvents.SnapshotPublished published = events(journal("s1")).stream()
                .filter(event -> event.event() instanceof RaftEvents.SnapshotPublished)
                .map(event -> (RaftEvents.SnapshotPublished) event.event()).findFirst().orElseThrow();
        assertTrue(published.lastIndex() >= 5);
        assertTrue(!published.installed(), "taken here, not installed from a leader");
    }

    @Test
    void aNodeThatFencesRecordsItOnce() throws Exception {
        IntentionalErrorsHelper.expect(IntentionalErrorFixture.RAFT_FENCED_OPERATION, 1);
        single = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("f1").clusterNodes(Set.of("f1")).transport(new PeerlessTransportFixture())
                .stateMachine(new PoisonedStoreFixture()).commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode()).electionTimeout(25).heartbeatInterval(10_000)
                .events(journal("f1")).build();
        single.start().get(10, TimeUnit.SECONDS);
        awaitTrue(single::isLeader, "the single server leads");

        assertThrows(ExecutionException.class, () -> single.submitCommand(put(PoisonedStoreFixture.POISON))
                .get(10, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> single.submitCommand(put("after")).get(10, TimeUnit.SECONDS),
                "a fenced node refuses what follows");

        awaitTrue(() -> types(journal("f1")).contains("raft.fenced"), "the fencing is recorded");
        List<EventEnvelope> fenced = events(journal("f1")).stream()
                .filter(event -> event.event() instanceof RaftEvents.Fenced).toList();
        assertEquals(1, fenced.size(), "once, however many transitions are refused");
        assertEquals(EventSeverity.ERROR, fenced.getFirst().severity());
        assertTrue(((RaftEvents.Fenced) fenced.getFirst().event()).reason().contains("cannot apply the poison"));
    }

    private static RaftCommand put(String key) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, "v"));
    }

    private static void awaitTrue(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), description);
    }

    /** A real store, except that applying a put of {@link #POISON} fails, as an injected fault. */
    private static final class PoisonedStoreFixture implements RaftLogApplicator {
        static final String POISON = "poison";
        private final QraftStateStore store = new QraftStateStore();

        @Override
        public RaftCommandResult<?> apply(RaftCommand command) {
            if (command instanceof DistributedStateRaftCommand(DistributedStateCommand delegate)
                    && POISON.equals(delegate.key())) {
                throw new IllegalStateException("cannot apply the poison command", new InjectedFaultFixture(
                        IntentionalErrorFixture.RAFT_STATE_MACHINE_APPLY_FAILURE, "cannot apply the poison command"));
            }
            return store.apply(command);
        }

        @Override public byte[] takeSnapshot() { return store.takeSnapshot(); }
        @Override public void restoreSnapshot(byte[] snapshot) { store.restoreSnapshot(snapshot); }
        @Override public long getLastAppliedIndex() { return store.getLastAppliedIndex(); }
        @Override public void setLastAppliedIndex(long index) { store.setLastAppliedIndex(index); }
        @Override public void reset() { store.reset(); }
    }
}

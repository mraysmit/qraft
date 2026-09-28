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

package dev.mars.qraft.controller.raft;

import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.runtime.Promise;
import dev.mars.qraft.controller.state.CatalogCommand;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link RaftNode} check-quorum rule, driven by manual heartbeat rounds: a leader that has not
 * heard from a majority, itself included, within one election timeout steps down in its current term,
 * fails pending writes, and notifies listeners; a responsive majority, rejected appends, and a
 * single-node cluster keep it leader.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
class RaftNodeCheckQuorumTest {
    /** Election timeout 300 ms over 100 ms heartbeats: contact must be no older than three rounds. */
    private static final long ELECTION_TIMEOUT_MS = 300;
    private static final long HEARTBEAT_MS = 100;

    private JavaRuntime runtime;
    private ManualTimers timers;
    private RaftNode node;

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) node.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (runtime != null) runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void aLeaderThatLosesItsMajorityStepsDownAfterOneElectionTimeoutOfRounds() throws Exception {
        PeerTransport transport = new PeerTransport();
        List<RaftNode.State> transitions = new CopyOnWriteArrayList<>();
        startLeader(Set.of("node-1", "peer-2", "peer-3"), transport);
        long term = node.getCurrentTerm();
        node.addStateChangeListener(transitions::add);
        heartbeats(5);
        assertTrue(node.isLeader(), "a responsive majority keeps the leader");

        transport.silence("peer-2", "peer-3");
        heartbeats(3);
        assertTrue(node.isLeader(), "contact three rounds old is still within the election timeout");
        heartbeats(1);

        assertEquals(RaftNode.State.FOLLOWER, node.getState());
        assertEquals(term, node.getCurrentTerm(), "stepping down for lost quorum does not change the term");
        assertEquals(null, node.getLeaderId());
        assertEquals(List.of(RaftNode.State.FOLLOWER), transitions);
    }

    @Test
    void oneResponsivePeerIsAMajorityOfThree() throws Exception {
        PeerTransport transport = new PeerTransport();
        startLeader(Set.of("node-1", "peer-2", "peer-3"), transport);

        transport.silence("peer-3");
        heartbeats(20);

        assertTrue(node.isLeader());
    }

    @Test
    void rejectedAppendsInTheCurrentTermCountAsContact() throws Exception {
        PeerTransport transport = new PeerTransport();
        startLeader(Set.of("node-1", "peer-2", "peer-3"), transport);

        transport.reject("peer-2", "peer-3");
        heartbeats(20);

        assertTrue(node.isLeader(), "a peer that answers, even with a rejection, is reachable");
    }

    @Test
    void aSingleNodeClusterIsAlwaysItsOwnMajority() throws Exception {
        startLeader(Set.of("node-1"), new PeerTransport());

        heartbeats(20);

        assertTrue(node.isLeader());
    }

    @Test
    void steppingDownFailsWritesThatCanNoLongerCommit() throws Exception {
        PeerTransport transport = new PeerTransport();
        startLeader(Set.of("node-1", "peer-2", "peer-3"), transport);
        transport.silence("peer-2", "peer-3");

        CompletableFuture<?> write = node.submitCommand(CatalogCommand.register(new ServiceInstance(
                "web", "web", "agent-1", "127.0.0.1", 8080, List.of(), Map.of(), ServiceHealth.UNKNOWN)))
                .toCompletionStage().toCompletableFuture();
        heartbeats(4);

        assertEquals(RaftNode.State.FOLLOWER, node.getState());
        Throwable failure = write.handle((ignored, error) -> error).get(10, TimeUnit.SECONDS);
        assertInstanceOf(CommandOutcomeUnknownException.class,
                failure instanceof java.util.concurrent.CompletionException ? failure.getCause() : failure);
    }

    @Test
    void aFollowerThatSteppedDownCanBeElectedAgainWhenPeersReturn() throws Exception {
        PeerTransport transport = new PeerTransport();
        startLeader(Set.of("node-1", "peer-2", "peer-3"), transport);
        transport.silence("peer-2", "peer-3");
        heartbeats(4);
        assertEquals(RaftNode.State.FOLLOWER, node.getState());

        transport.respond("peer-2", "peer-3");
        timers.fireNextOneShot();
        node.awaitState(RaftNode.State.LEADER, 10_000).toCompletionStage().toCompletableFuture()
                .get(15, TimeUnit.SECONDS);
        heartbeats(10);

        assertTrue(node.isLeader());
    }

    @Test
    void awaitingAStateNeverConsumesTheNodesRaftTimers() throws Exception {
        startLeader(Set.of("node-1", "peer-2", "peer-3"), new PeerTransport());
        int armed = timers.oneShotCount();

        CompletableFuture<RaftNode.State> leader = node.awaitState(RaftNode.State.LEADER, 60_000)
                .toCompletionStage().toCompletableFuture();
        node.awaitState(RaftNode.State.CANDIDATE, 60_000);

        assertEquals(RaftNode.State.LEADER, leader.get(10, TimeUnit.SECONDS), "the current state satisfies at once");
        assertEquals(armed, timers.oneShotCount(),
                "an observer's timeout must not be scheduled on the node's election and heartbeat timers");
    }

    private void startLeader(Set<String> members, PeerTransport transport) throws Exception {
        runtime = JavaRuntime.create();
        timers = new ManualTimers(runtime);
        node = RaftNode.builder()
                .runtime(runtime).nodeId("node-1").clusterNodes(members).transport(transport)
                .stateMachine(new QraftStateStore()).commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode()).snapshotEnabled(false)
                .electionTimeout(ELECTION_TIMEOUT_MS).heartbeatInterval(HEARTBEAT_MS)
                .timerScheduler(timers).build();
        node.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        timers.fireNextOneShot();
        node.awaitState(RaftNode.State.LEADER, 10_000).toCompletionStage().toCompletableFuture()
                .get(15, TimeUnit.SECONDS);
        settle();
    }

    private void heartbeats(int rounds) throws Exception {
        for (int round = 0; round < rounds; round++) {
            if (!timers.hasPeriodic(HEARTBEAT_MS)) return;
            timers.firePeriodic(HEARTBEAT_MS);
            settle();
        }
    }

    /** Lets heartbeat sends, responses, and sequenced transitions finish on the state loop. */
    private void settle() throws Exception {
        for (int pass = 0; pass < 5; pass++) {
            CompletableFuture<Void> marker = new CompletableFuture<>();
            runtime.runOnContext(ignored -> marker.complete(null));
            marker.get(10, TimeUnit.SECONDS);
        }
    }

    /** Grants every vote and answers AppendEntries per peer: success, rejection, or silence. */
    private static final class PeerTransport implements RaftTransport {
        private enum Mode { RESPOND, REJECT, SILENT }

        private final Map<String, Mode> modes = new ConcurrentHashMap<>();

        void silence(String... peers) { for (String peer : peers) modes.put(peer, Mode.SILENT); }
        void reject(String... peers) { for (String peer : peers) modes.put(peer, Mode.REJECT); }
        void respond(String... peers) { for (String peer : peers) modes.put(peer, Mode.RESPOND); }

        @Override public void start(Consumer<RaftMessage> messageHandler) { }
        @Override public void stop() { }

        @Override
        public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return Future.succeededFuture(VoteResponse.newBuilder()
                    .setTerm(request.getTerm()).setVoteGranted(true).build());
        }

        @Override
        public Future<AppendEntriesResponse> sendAppendEntries(String targetId, AppendEntriesRequest request) {
            Mode mode = modes.getOrDefault(targetId, Mode.RESPOND);
            if (mode == Mode.SILENT) return Promise.<AppendEntriesResponse>promise().future();
            long matchIndex = request.getEntriesCount() == 0
                    ? request.getPrevLogIndex()
                    : request.getEntries(request.getEntriesCount() - 1).getIndex();
            return Future.succeededFuture(AppendEntriesResponse.newBuilder().setTerm(request.getTerm())
                    .setSuccess(mode == Mode.RESPOND).setMatchIndex(mode == Mode.RESPOND ? matchIndex : 0).build());
        }

        @Override
        public Future<InstallSnapshotResponse> sendInstallSnapshot(String targetId, InstallSnapshotRequest request) {
            return Promise.<InstallSnapshotResponse>promise().future();
        }
    }

    /** Fires one-shot and periodic Raft timers only when the test asks, on the node's state loop. */
    private static final class ManualTimers implements RaftTimerScheduler {
        private final JavaRuntime runtime;
        private final AtomicLong ids = new AtomicLong();
        private final Map<Long, Scheduled> oneShots = new ConcurrentHashMap<>();
        private final Map<Long, Scheduled> periodics = new ConcurrentHashMap<>();

        private ManualTimers(JavaRuntime runtime) { this.runtime = runtime; }

        @Override
        public long setTimer(long delayMs, Consumer<Long> action) {
            long id = ids.incrementAndGet();
            oneShots.put(id, new Scheduled(delayMs, action));
            return id;
        }

        @Override
        public long setPeriodic(long periodMs, Consumer<Long> action) {
            long id = ids.incrementAndGet();
            periodics.put(id, new Scheduled(periodMs, action));
            return id;
        }

        @Override
        public boolean cancelTimer(long id) {
            return oneShots.remove(id) != null || periodics.remove(id) != null;
        }

        void fireNextOneShot() {
            long id = oneShots.keySet().stream().min(Long::compareTo).orElseThrow();
            Scheduled scheduled = oneShots.remove(id);
            runtime.runOnContext(ignored -> scheduled.action().accept(id));
        }

        int oneShotCount() {
            return oneShots.size();
        }

        boolean hasPeriodic(long periodMs) {
            return periodics.values().stream().anyMatch(scheduled -> scheduled.delayMs() == periodMs);
        }

        void firePeriodic(long periodMs) {
            Map.Entry<Long, Scheduled> scheduled = periodics.entrySet().stream()
                    .filter(entry -> entry.getValue().delayMs() == periodMs).findFirst().orElseThrow();
            runtime.runOnContext(ignored -> scheduled.getValue().action().accept(scheduled.getKey()));
        }

        private record Scheduled(long delayMs, Consumer<Long> action) { }
    }
}

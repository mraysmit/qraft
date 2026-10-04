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

import com.google.protobuf.ByteString;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests a follower's own log: it reports and commits only the entries a request verified, never a stale
 * uncommitted tail from an earlier term; it compacts its log on its own snapshot schedule; and it accepts
 * an append whose previous entry lies inside its snapshot, because everything a snapshot covers is
 * committed and identical on the leader.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.1
 */
class RaftNodeFollowerLogTest {
    private static final long SNAPSHOT_INTERVAL_MS = 200;
    private static final ProtobufRaftCommandCodec CODEC = new ProtobufRaftCommandCodec();

    private final QraftStateStore store = new QraftStateStore();
    private JavaRuntime runtime;
    private ManualRaftTimers timers;
    private RaftNode follower;

    @AfterEach
    void stop() throws Exception {
        if (follower != null) follower.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (runtime != null) runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void aFollowerReportsAndCommitsOnlyWhatTheRequestVerifiedNeverAStaleTail() throws Exception {
        startFollower();
        // Term 1: entries 2 to 4 follow the bootstrap configuration, but only entry 2 commits before that
        // leader is lost.
        assertTrue(append(1, 1, 0, 2, "k1", "k2-stale", "k3-stale").getSuccess());

        // Term 2: the new leader's log is only [1, 2], so its heartbeat verifies no more than entry 2.
        AppendEntriesResponse heartbeat = append(2, 2, 1, 4);

        assertTrue(heartbeat.getSuccess());
        assertEquals(2, heartbeat.getMatchIndex(),
                "reporting the stale tail would let the new leader count this follower for entries it lacks");
        assertEquals(2, follower.getCommitIndex(), "a follower commits no further than the request verified");
        assertEquals(Optional.empty(), store.findMetadata("k2-stale"), "a stale uncommitted entry is never applied");
    }

    @Test
    void aFollowerCompactsItsOwnLogOnTheSnapshotSchedule() throws Exception {
        startFollower();
        assertTrue(append(1, 1, 0, 4, "k1", "k2", "k3").getSuccess());

        assertTrue(timers.hasPeriodic(SNAPSHOT_INTERVAL_MS), "a follower schedules snapshots as a leader does");
        timers.firePeriodic(SNAPSHOT_INTERVAL_MS);

        awaitSnapshotAt(4);
        assertEquals(Optional.of("k3"), store.findMetadata("k3"));
    }

    @Test
    void aFollowerAcceptsAnAppendWhosePreviousEntryIsInsideItsSnapshot() throws Exception {
        startFollower();
        assertTrue(append(1, 1, 0, 4, "k1", "k2", "k3").getSuccess());
        timers.firePeriodic(SNAPSHOT_INTERVAL_MS);
        awaitSnapshotAt(4);

        // A retransmission from before the snapshot, carrying entries it covers and one new entry.
        AppendEntriesResponse overlapping = append(1, 2, 1, 5, "k2", "k3", "k4");
        assertTrue(overlapping.getSuccess(), "entries a snapshot covers are committed and match the leader");
        assertEquals(5, overlapping.getMatchIndex());
        assertEquals(5, follower.getCommitIndex());
        assertEquals(Optional.of("k4"), store.findMetadata("k4"));

        AppendEntriesResponse staleHeartbeat = append(1, 3, 1, 5);
        assertTrue(staleHeartbeat.getSuccess());
        assertEquals(4, staleHeartbeat.getMatchIndex(),
                "a heartbeat inside the snapshot verifies the log only through the snapshot boundary");
        assertFalse(follower.isFenced());
    }

    /**
     * Starts a bootstrapped follower: its log begins with the configuration at index 1 in term 0, as a real
     * leader's does, so every entry the test sends follows it.
     */
    private void startFollower() throws Exception {
        runtime = JavaRuntime.create();
        timers = new ManualRaftTimers(runtime);
        TestRaftStorage storage = new TestRaftStorage();
        storage.open(null).join();
        Set<String> members = Set.of("follower", "leader", "other");
        follower = RaftNode.builder().runtime(runtime).nodeId("follower")
                .serverId(ManualRaftCluster.serverIdOf("follower"))
                .initialConfiguration(ManualRaftCluster.configurationOf(members))
                .clusterNodes(members)
                .transport(new InMemoryTransportSimulator("follower"))
                .stateMachine(store).commandCodec(CODEC)
                .mode(RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(true).snapshotThreshold(1).snapshotCheckInterval(SNAPSHOT_INTERVAL_MS)
                .electionTimeout(60_000).heartbeatInterval(50).timerScheduler(timers).build();
        follower.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** Sends one AppendEntries whose entries follow {@code prevIndex} and each put {@code key=key}. */
    private AppendEntriesResponse append(long term, long prevIndex, long prevTerm, long leaderCommit,
                                         String... keys) throws Exception {
        AppendEntriesRequest.Builder request = AppendEntriesRequest.newBuilder().setTerm(term).setLeaderId("leader")
                .setPrevLogIndex(prevIndex).setPrevLogTerm(prevTerm).setLeaderCommit(leaderCommit);
        long index = prevIndex;
        for (String key : keys) {
            index++;
            byte[] data = CODEC.serialize(new DistributedStateRaftCommand(DistributedStateCommand.put(key, key)));
            request.addEntries(dev.mars.qraft.controller.raft.grpc.LogEntry.newBuilder()
                    .setTerm(term).setIndex(index).setData(ByteString.copyFrom(data)));
        }
        return follower.handleAppendEntriesRequest(request.build())
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** Waits until the follower, read on its own state loop, has compacted through {@code index}. */
    private void awaitSnapshotAt(long index) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long snapshotIndex = snapshotIndexOnLoop();
        while (snapshotIndex != index && System.nanoTime() < deadline) snapshotIndex = snapshotIndexOnLoop();
        assertEquals(index, snapshotIndex, "the follower compacts every applied entry");
    }

    private long snapshotIndexOnLoop() throws Exception {
        CompletableFuture<Long> read = new CompletableFuture<>();
        runtime.runOnContext(ignored -> read.complete(follower.getSnapshotLastIndex()));
        return read.get(10, TimeUnit.SECONDS);
    }
}

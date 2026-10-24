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
import dev.mars.qraft.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;
import dev.mars.qraft.testing.fault.IntentionalErrorFixture;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static dev.mars.qraft.raft.RaftAwaitHelper.await;
import static dev.mars.qraft.raft.RaftAwaitHelper.awaitStateLoop;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that a leader which cannot deliver a snapshot to a peer tries again after a delay that doubles with each
 * consecutive failure, and not at every opportunity. Time is the manual timer's, so each boundary is exact:
 * nothing is sent one millisecond before the delay has passed, and one is sent when it has.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
class RaftNodeSnapshotRetryBackoffTest {
    private RaftExecutorsFixture runtime;
    private ManualRaftTimersHelper timers;
    private FailingSnapshotTransportFixture transport;
    private RaftNode node;
    private int commands;

    @BeforeEach
    void setUp() {
        runtime = RaftExecutorsFixture.create();
        timers = new ManualRaftTimersHelper(runtime);
        TestRaftStorageFixture storage = new TestRaftStorageFixture();
        storage.open(null).join();
        transport = new FailingSnapshotTransportFixture();
        node = RaftNode.builder()
                .stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("node-1")
                .serverId(ManualRaftClusterFixture.serverIdOf("node-1"))
                .clusterNodes(Set.of("node-1", "peer-1"))
                .initialConfiguration(ManualRaftClusterFixture.configurationOf(Set.of("node-1", "peer-1")))
                .transport(transport)
                .stateMachine(new QraftStateStore())
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(storage, storage))
                .snapshotEnabled(false)
                .electionTimeout(30)
                .heartbeatInterval(10_000)
                .timerScheduler(timers)
                .build();
        await(node.start());
        timers.fireElectionTimeout();
        awaitLeader();
        await(node.submitCommand(put("before", "snapshot")));
        await(node.takeSnapshot());
        // Configuration, leadership no-op, then the first client command.
        assertEquals(3, node.getSnapshotLastIndex());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) await(node.stop());
        if (runtime != null) runtime.shutdown().get(10, TimeUnit.SECONDS);
    }

    @Test
    void eachConsecutiveFailureToDeliverASnapshotWaitsTwiceAsLongBeforeTheNextAttempt() throws Exception {
        // Every snapshot sent to the unreachable peer fails on purpose, and the leader logs each failure.
        IntentionalErrorsHelper.expect(IntentionalErrorFixture.RAFT_SNAPSHOT_TRANSFER_INTERRUPTED);
        // The peer rejects an append, so its next index falls behind the snapshot and a snapshot is due.
        transport.rejectNextAppend();
        submitUnawaited();
        transport.awaitRejectedAppend();
        awaitStateLoop(runtime);
        submitUnawaited();
        awaitAttempts(1);

        assertNoAttemptAfterOpportunities(1, "inside the first delay of 250 ms");
        timers.advanceTime(249);
        assertNoAttemptAfterOpportunities(1, "one millisecond before the first delay has passed");
        timers.advanceTime(1);
        submitUnawaited();
        awaitAttempts(2);

        timers.advanceTime(499);
        assertNoAttemptAfterOpportunities(2, "one millisecond before the second delay of 500 ms has passed");
        timers.advanceTime(1);
        submitUnawaited();
        awaitAttempts(3);

        timers.advanceTime(999);
        assertNoAttemptAfterOpportunities(3, "one millisecond before the third delay of 1000 ms has passed");
        timers.advanceTime(1);
        submitUnawaited();
        awaitAttempts(4);
    }

    /**
     * Gives the leader several chances to send, with real time passing between them, and checks that it took none
     * of them. Only the injected clock counts: the real time that passes here is longer than the first delay, so a
     * backoff measured on the wall clock would send again.
     */
    private void assertNoAttemptAfterOpportunities(int expectedAttempts, String when) throws Exception {
        for (int opportunity = 0; opportunity < 3; opportunity++) {
            submitUnawaited();
            for (int pass = 0; pass < 5; pass++) awaitStateLoop(runtime);
            Thread.sleep(100);
        }
        assertEquals(expectedAttempts, transport.attempts(), "no snapshot is sent " + when);
    }

    private void awaitAttempts(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (transport.attempts() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        for (int pass = 0; pass < 5; pass++) awaitStateLoop(runtime);
        assertEquals(expected, transport.attempts(), "snapshot attempts so far");
    }

    private void submitUnawaited() {
        node.submitCommand(put("key-" + commands++, "value"));
    }

    private void awaitLeader() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        if (!node.isLeader()) throw new AssertionError("node did not become leader");
    }

    private DistributedStateRaftCommand put(String key, String value) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, value));
    }

    /** A peer that grants votes and acknowledges appends, but whose every snapshot transfer fails at once. */
    private static final class FailingSnapshotTransportFixture implements RaftTransport {
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicBoolean rejectNextAppend = new AtomicBoolean();
        private volatile CompletableFuture<Void> rejectedAppend;

        void rejectNextAppend() {
            rejectedAppend = new CompletableFuture<>();
            rejectNextAppend.set(true);
        }

        void awaitRejectedAppend() throws Exception {
            rejectedAppend.get(10, TimeUnit.SECONDS);
        }

        int attempts() {
            return attempts.get();
        }

        @Override public void start(Consumer<RaftMessage> messageHandler) {}
        @Override public void stop() {}

        @Override
        public CompletableFuture<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return CompletableFuture.completedFuture(VoteResponse.newBuilder()
                    .setTerm(request.getTerm()).setVoterServerId(ManualRaftClusterFixture.serverIdOf(targetId))
                    .setVoteGranted(true).build());
        }

        @Override
        public CompletableFuture<AppendEntriesResponse> sendAppendEntries(
                String targetId, AppendEntriesRequest request) {
            if (rejectNextAppend.compareAndSet(true, false)) {
                rejectedAppend.complete(null);
                return CompletableFuture.completedFuture(AppendEntriesResponse.newBuilder()
                        .setTerm(request.getTerm()).setFollowerServerId(ManualRaftClusterFixture.serverIdOf(targetId))
                        .setSuccess(false).build());
            }
            long matchIndex = request.getEntriesCount() == 0
                    ? request.getPrevLogIndex()
                    : request.getEntries(request.getEntriesCount() - 1).getIndex();
            return CompletableFuture.completedFuture(AppendEntriesResponse.newBuilder()
                    .setTerm(request.getTerm()).setFollowerServerId(ManualRaftClusterFixture.serverIdOf(targetId))
                    .setSuccess(true).setMatchIndex(matchIndex).build());
        }

        @Override
        public CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(
                String targetId, InstallSnapshotRequest request) {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(new java.io.IOException("UNAVAILABLE: io exception"));
        }
    }
}

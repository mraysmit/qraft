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
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how a candidate answers AppendEntries (Raft section 5.2). A leader of the candidate's own term
 * won that election, so the candidate becomes its follower and accepts the append. A leader of a later
 * term is followed in that term. A leader of an earlier term is refused, and the candidate keeps
 * campaigning.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
class RaftNodeCandidateTest {
    private JavaRuntime runtime;
    private RaftNode node;
    private ManualRaftTimers timers;

    @BeforeEach
    void becomeCandidateInTermTwo() throws Exception {
        runtime = JavaRuntime.create();
        timers = new ManualRaftTimers(runtime);
        node = RaftNode.builder()
                .runtime(runtime).nodeId("node-1").clusterNodes(Set.of("node-1", "peer-2", "peer-3"))
                .transport(new RefusingTransport())
                .stateMachine(new QraftStateStore()).commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode()).snapshotEnabled(false)
                .electionTimeout(300).heartbeatInterval(100)
                .timerScheduler(timers).build();
        node.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        timers.fireElectionTimeout();
        awaitCandidacyInTerm(1);
        timers.fireElectionTimeout();
        awaitCandidacyInTerm(2);
    }

    /** An election persists its term in a sequenced transition, so the term is awaited, not the role. */
    private void awaitCandidacyInTerm(long term) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        RaftStatus status = status();
        while ((status.term() != term || status.state() != RaftNode.State.CANDIDATE)
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
            status = status();
        }
        assertEquals(term, status.term());
        assertEquals(RaftNode.State.CANDIDATE, status.state());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) node.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (runtime != null) runtime.shutdown().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void aLeaderOfTheCandidatesOwnTermIsFollowed() throws Exception {
        AppendEntriesResponse response = append(2);

        assertTrue(response.getSuccess(), response.toString());
        RaftStatus status = status();
        assertEquals(RaftNode.State.FOLLOWER, status.state());
        assertEquals(2, status.term(), "the term does not change");
        assertEquals("peer-2", status.leaderId());
    }

    @Test
    void aLeaderOfALaterTermIsFollowedInThatTerm() throws Exception {
        AppendEntriesResponse response = append(5);

        assertTrue(response.getSuccess(), response.toString());
        RaftStatus status = status();
        assertEquals(RaftNode.State.FOLLOWER, status.state());
        assertEquals(5, status.term());
        assertEquals("peer-2", status.leaderId());
    }

    @Test
    void aLeaderOfAnEarlierTermIsRefused() throws Exception {
        AppendEntriesResponse response = append(1);

        assertFalse(response.getSuccess());
        assertEquals(2, response.getTerm(), "the refusal tells the stale leader the current term");
        RaftStatus status = status();
        assertEquals(RaftNode.State.CANDIDATE, status.state());
        assertEquals(2, status.term());
        assertNull(status.leaderId());
    }

    private AppendEntriesResponse append(long term) throws Exception {
        return node.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                        .setTerm(term).setLeaderId("peer-2").setPrevLogIndex(0).setPrevLogTerm(0)
                        .setLeaderCommit(0).build())
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private RaftStatus status() throws Exception {
        return node.status().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** Refuses every vote, so the node stays a candidate, and never answers anything else. */
    private static final class RefusingTransport implements RaftTransport {
        @Override public void start(Consumer<RaftMessage> messageHandler) { }
        @Override public void stop() { }

        @Override
        public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return Future.succeededFuture(VoteResponse.newBuilder()
                    .setTerm(request.getTerm()).setVoteGranted(false).build());
        }

        @Override
        public Future<AppendEntriesResponse> sendAppendEntries(String targetId, AppendEntriesRequest request) {
            return Promise.<AppendEntriesResponse>promise().future();
        }

        @Override
        public Future<InstallSnapshotResponse> sendInstallSnapshot(String targetId, InstallSnapshotRequest request) {
            return Promise.<InstallSnapshotResponse>promise().future();
        }
    }

    /** Fires one-shot Raft timers only when the test asks, on the node's state loop. */
}

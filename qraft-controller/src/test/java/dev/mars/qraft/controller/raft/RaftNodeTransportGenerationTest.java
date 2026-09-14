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
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaftNodeTransportGenerationTest {
    private JavaRuntime runtime;
    private RaftNode node;

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) await(node.stop());
        if (runtime != null) {
            runtime.shutdown().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void delayedAppendSuccessFromPreviousLeadershipCannotAdvancePeerIndexes() throws Exception {
        runtime = JavaRuntime.create();
        ControlledTransport transport = new ControlledTransport();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("node-1")
                .clusterNodes(Set.of("node-1", "peer-1"))
                .transport(transport)
                .stateMachine(new QraftStateStore())
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode())
                .snapshotEnabled(false)
                .electionTimeout(30)
                .heartbeatInterval(10_000)
                .build();
        await(node.start());
        awaitLeaderAtOrAboveTerm(1);

        PendingAppend stale = transport.takeAppend();
        long firstLeadershipTerm = stale.request().getTerm();
        VoteResponse stepDown = await(node.handleVoteRequest(VoteRequest.newBuilder()
                .setTerm(firstLeadershipTerm + 1)
                .setCandidateId("peer-1")
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build()));
        assertTrue(stepDown.getVoteGranted());

        awaitLeaderAtOrAboveTerm(firstLeadershipTerm + 2);
        assertEquals(1, node.getNextIndex("peer-1"));

        stale.response().complete(AppendEntriesResponse.newBuilder()
                .setTerm(firstLeadershipTerm)
                .setSuccess(true)
                .setMatchIndex(100)
                .build());
        awaitStateLoop();

        assertEquals(1, node.getNextIndex("peer-1"),
                "a completion from an earlier leadership must be ignored");
    }

    private void awaitLeaderAtOrAboveTerm(long minimumTerm) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while ((!node.isLeader() || node.getCurrentTerm() < minimumTerm)
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        if (!node.isLeader() || node.getCurrentTerm() < minimumTerm) {
            throw new AssertionError("node did not become leader at term " + minimumTerm);
        }
    }

    private void awaitStateLoop() throws Exception {
        java.util.concurrent.CompletableFuture<Void> marker = new java.util.concurrent.CompletableFuture<>();
        runtime.runOnContext(ignored -> marker.complete(null));
        marker.get(2, TimeUnit.SECONDS);
    }

    private static <T> T await(Future<T> future) {
        return future.timeout(5, TimeUnit.SECONDS).toCompletionStage().toCompletableFuture().join();
    }

    private record PendingAppend(AppendEntriesRequest request, Promise<AppendEntriesResponse> response) {}

    private static final class ControlledTransport implements RaftTransport {
        private final BlockingQueue<PendingAppend> appends = new LinkedBlockingQueue<>();

        PendingAppend takeAppend() throws InterruptedException {
            PendingAppend append = appends.poll(2, TimeUnit.SECONDS);
            if (append == null) throw new AssertionError("leader did not send AppendEntries");
            return append;
        }

        @Override public void start(Consumer<RaftMessage> messageHandler) {}
        @Override public void stop() {}
        @Override public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return Future.succeededFuture(VoteResponse.newBuilder()
                    .setTerm(request.getTerm()).setVoteGranted(true).build());
        }
        @Override public Future<AppendEntriesResponse> sendAppendEntries(
                String targetId, AppendEntriesRequest request) {
            Promise<AppendEntriesResponse> response = Promise.promise();
            appends.add(new PendingAppend(request, response));
            return response.future();
        }
        @Override public Future<InstallSnapshotResponse> sendInstallSnapshot(
                String targetId, InstallSnapshotRequest request) {
            return Future.succeededFuture(InstallSnapshotResponse.newBuilder()
                    .setTerm(request.getTerm()).setSuccess(true)
                    .setNextChunkIndex(request.getTotalChunks()).build());
        }
    }
}

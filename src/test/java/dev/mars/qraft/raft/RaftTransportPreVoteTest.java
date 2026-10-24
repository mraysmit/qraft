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

import dev.mars.qraft.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.raft.grpc.PreVoteRequest;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the contract of {@link RaftTransport#sendPreVote}: a transport that does not implement it fails the call as
 * unsupported, as it does {@code describe}, {@code join} and {@code removeServer}, so a caller can tell a peer
 * that cannot answer from one that refused.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
class RaftTransportPreVoteTest {

    @Test
    void aTransportWithoutPreVoteFailsTheCallAsUnsupported() {
        RaftTransport transport = new VotingOnlyTransportFixture();

        ExecutionException failed = assertThrows(ExecutionException.class, () -> transport
                .sendPreVote("peer-2", PreVoteRequest.newBuilder().setTerm(2).build()).get());

        assertInstanceOf(UnsupportedOperationException.class, failed.getCause());
        assertTrue(failed.getCause().getMessage().contains("peer-2"), failed.getCause().getMessage());
    }

    /** A transport that sends votes, appends and snapshots and nothing else, as the interface's defaults allow. */
    private static final class VotingOnlyTransportFixture implements RaftTransport {
        @Override public void start(Consumer<RaftMessage> messageHandler) { }
        @Override public void stop() { }

        @Override
        public CompletableFuture<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return CompletableFuture.completedFuture(VoteResponse.getDefaultInstance());
        }

        @Override
        public CompletableFuture<AppendEntriesResponse> sendAppendEntries(
                String targetId, AppendEntriesRequest request) {
            return CompletableFuture.completedFuture(AppendEntriesResponse.getDefaultInstance());
        }

        @Override
        public CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(
                String targetId, InstallSnapshotRequest request) {
            return CompletableFuture.completedFuture(InstallSnapshotResponse.getDefaultInstance());
        }
    }
}

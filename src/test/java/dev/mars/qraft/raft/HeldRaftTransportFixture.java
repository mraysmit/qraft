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
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Test transport helper that holds every request a Raft node sends for the test to answer by hand,
 * in any order. A test that
 * answers each request itself decides exactly what the node hears and when.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
final class HeldRaftTransportFixture implements RaftTransport {

    /** A request sent to {@code target}, and the promise the test completes to answer it. */
    record Held<Q, R>(String target, Q request, CompletableFuture<R> response) {
        boolean answered() {
            return response.isDone();
        }
    }

    final List<Held<VoteRequest, VoteResponse>> votes = new CopyOnWriteArrayList<>();
    final List<Held<AppendEntriesRequest, AppendEntriesResponse>> appends = new CopyOnWriteArrayList<>();
    final List<Held<InstallSnapshotRequest, InstallSnapshotResponse>> snapshots = new CopyOnWriteArrayList<>();
    /** The peers the node told the transport it had heard from, in order. */
    final List<String> heardFrom = new CopyOnWriteArrayList<>();

    /** The first vote request sent to {@code target}. */
    CompletableFuture<VoteResponse> vote(String target) {
        return votes.stream().filter(held -> held.target().equals(target)).findFirst().orElseThrow().response();
    }

    /** The latest append to {@code target} carrying the entry at {@code index}, or null if none was sent. */
    CompletableFuture<AppendEntriesResponse> appendCarrying(long index, String target) {
        return appends.reversed().stream()
                .filter(held -> held.target().equals(target) && held.request().getEntriesList().stream()
                        .anyMatch(entry -> entry.getIndex() == index))
                .map(Held::response).findFirst().orElse(null);
    }

    /** Every append to {@code target} not yet answered, oldest first. */
    List<Held<AppendEntriesRequest, AppendEntriesResponse>> unansweredAppends(String target) {
        return appends.stream().filter(held -> held.target().equals(target) && !held.answered()).toList();
    }

    @Override
    public void start(Consumer<RaftMessage> messageHandler) {
    }

    @Override
    public void stop() {
    }

    @Override
    public void peerHeardFrom(String peerName) {
        heardFrom.add(peerName);
    }

    @Override
    public CompletableFuture<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
        CompletableFuture<VoteResponse> response = new CompletableFuture<>();
        votes.add(new Held<>(targetId, request, response));
        return response;
    }

    @Override
    public CompletableFuture<AppendEntriesResponse> sendAppendEntries(String targetId, AppendEntriesRequest request) {
        CompletableFuture<AppendEntriesResponse> response = new CompletableFuture<>();
        appends.add(new Held<>(targetId, request, response));
        return response;
    }

    @Override
    public CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(String targetId, InstallSnapshotRequest request) {
        CompletableFuture<InstallSnapshotResponse> response = new CompletableFuture<>();
        snapshots.add(new Held<>(targetId, request, response));
        return response;
    }
}

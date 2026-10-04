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
import dev.mars.qraft.controller.runtime.Promise;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A transport that holds every request a node sends, for the test to answer by hand, in any order. A test that
 * answers each request itself decides exactly what the node hears and when.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
final class HeldRaftTransport implements RaftTransport {

    /** A request sent to {@code target}, and the promise the test completes to answer it. */
    record Held<Q, R>(String target, Q request, Promise<R> response) {
        boolean answered() {
            return response.future().isComplete();
        }
    }

    final List<Held<VoteRequest, VoteResponse>> votes = new CopyOnWriteArrayList<>();
    final List<Held<AppendEntriesRequest, AppendEntriesResponse>> appends = new CopyOnWriteArrayList<>();
    final List<Held<InstallSnapshotRequest, InstallSnapshotResponse>> snapshots = new CopyOnWriteArrayList<>();

    /** The first vote request sent to {@code target}. */
    Promise<VoteResponse> vote(String target) {
        return votes.stream().filter(held -> held.target().equals(target)).findFirst().orElseThrow().response();
    }

    /** The latest append to {@code target} carrying the entry at {@code index}, or null if none was sent. */
    Promise<AppendEntriesResponse> appendCarrying(long index, String target) {
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
    public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
        Promise<VoteResponse> response = Promise.promise();
        votes.add(new Held<>(targetId, request, response));
        return response.future();
    }

    @Override
    public Future<AppendEntriesResponse> sendAppendEntries(String targetId, AppendEntriesRequest request) {
        Promise<AppendEntriesResponse> response = Promise.promise();
        appends.add(new Held<>(targetId, request, response));
        return response.future();
    }

    @Override
    public Future<InstallSnapshotResponse> sendInstallSnapshot(String targetId, InstallSnapshotRequest request) {
        Promise<InstallSnapshotResponse> response = Promise.promise();
        snapshots.add(new Held<>(targetId, request, response));
        return response.future();
    }
}

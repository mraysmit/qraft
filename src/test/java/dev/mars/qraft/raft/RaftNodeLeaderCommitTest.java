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

import com.google.protobuf.ByteString;
import dev.mars.qraft.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.raft.grpc.LogEntry;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;
import dev.mars.qraft.common.async.Future;
import dev.mars.qraft.common.async.JavaRuntime;
import dev.mars.qraft.common.async.Promise;
import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests when a leader may commit: only once a majority of the whole cluster holds an entry, counted
 * correctly for even cluster sizes, and never by counting replicas of an entry from an earlier term
 * (Raft section 5.4.2, Figure 8). The transport holds every AppendEntries until the test answers it, so
 * each test decides exactly which replicas the leader has heard from, and answers as the server configured
 * under that peer's name. The leader's log begins with its bootstrap configuration, committed at index 1.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.1
 */
class RaftNodeLeaderCommitTest {
    private static final ProtobufRaftCommandCodec CODEC = new ProtobufRaftCommandCodec();
    private static final long ELECTION_TIMEOUT_MS = 300;
    private static final long HEARTBEAT_MS = 100;

    private JavaRuntime runtime;
    private RaftNode node;
    private ManualRaftTimersHelper timers;
    private final HeldTransportFixture transport = new HeldTransportFixture();

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) node.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (runtime != null) runtime.shutdown().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void membershipRetriesUntilTheLeadershipNoOpCommits() throws Exception {
        start(Set.of("node-1", "peer-2", "peer-3"));
        becomeLeader();
        MembershipService service = new MembershipService(node, transport, null);
        var request = dev.mars.qraft.raft.grpc.JoinRequest.newBuilder()
                .setServerId("id-d").setName("d").setAddress("d:9080").build();
        assertEquals(dev.mars.qraft.raft.grpc.MembershipResponse.Status.NO_LEADER,
                ManualRaftClusterFixture.await(service.join(request)).getStatus());
        assertFalse(node.getConfiguration().orElseThrow().serverNamed("d").isPresent());

        transport.answer("peer-2", append -> append.getEntriesCount() > 0);
        settle();
        var admitted = service.join(request).toCompletionStage().toCompletableFuture();
        settle();
        transport.answer("peer-2", append -> append.getEntriesCount() > 0);
        assertEquals(dev.mars.qraft.raft.grpc.MembershipResponse.Status.JOINED,
                admitted.get(10, TimeUnit.SECONDS).getStatus());
    }

    @Test
    void anEntryFromAnEarlierTermIsNotCommittedByCountingReplicas() throws Exception {
        start(Set.of("node-1", "peer-2", "peer-3"));
        // peer-2 leads term 1 and replicates an entry after the bootstrap configuration, which it does not commit.
        AppendEntriesResponse followed = node.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                        .setTerm(1).setLeaderId("peer-2").setPrevLogIndex(1).setPrevLogTerm(0).setLeaderCommit(1)
                        .addEntries(entry(2, 1, "earlier-term")).build())
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(followed.getSuccess());
        becomeLeader();
        assertEquals(2, status().term());
        assertEquals(3, status().lastLogIndex(), "the new leader appended its own no-op at index 3");

        // A majority (this leader and peer-2) now holds index 2, which is from term 1.
        transport.answer("peer-2", request -> request.getEntriesCount() == 0 && request.getPrevLogIndex() == 2);
        settle();
        assertEquals(1, status().commitIndex(),
                "an entry from an earlier term is never committed by counting replicas");

        transport.answer("peer-2", request -> request.getEntriesCount() > 0);
        settle();
        assertEquals(3, status().commitIndex(),
                "committing an entry of the current term commits the earlier entry with it");
        assertEquals(3, status().lastApplied());
    }

    @Test
    void aFourMemberLeaderNeedsThreeCopiesToCommit() throws Exception {
        start(Set.of("node-1", "peer-2", "peer-3", "peer-4"));
        becomeLeader();
        CompletableFuture<RaftCommandResult<?>> write = submit("four-members");
        settle();

        transport.answer("peer-2", request -> request.getEntriesCount() > 0);
        settle();
        assertEquals(1, status().commitIndex(), "two copies of four are not a majority");
        assertFalse(write.isDone());

        transport.answer("peer-3", request -> request.getEntriesCount() > 0);
        settle();
        assertEquals(3, status().commitIndex(), "three copies of four commit the no-op and the write");
        write.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aTwoMemberLeaderCannotCommitAlone() throws Exception {
        start(Set.of("node-1", "peer-2"));
        becomeLeader();
        CompletableFuture<RaftCommandResult<?>> write = submit("two-members");
        settle();

        assertEquals(3, status().lastLogIndex());
        assertEquals(1, status().commitIndex(), "the leader's own copy is one of two, not a majority");
        assertFalse(write.isDone());

        transport.answer("peer-2", request -> request.getEntriesCount() > 0);
        settle();
        assertEquals(3, status().commitIndex());
        write.get(10, TimeUnit.SECONDS);
    }

    /** Starts node-1 with configuration at index 1; election appends a no-op before client writes. */
    private void start(Set<String> members) throws Exception {
        runtime = JavaRuntime.create();
        timers = new ManualRaftTimersHelper(runtime);
        node = RaftNode.builder()
                .runtime(runtime).nodeId("node-1").clusterNodes(members).transport(transport)
                .serverId(ManualRaftClusterFixture.serverIdOf("node-1"))
                .initialConfiguration(ManualRaftClusterFixture.configurationOf(members))
                .stateMachine(new QraftStateStore()).commandCodec(CODEC)
                .mode(RaftNodeMode.volatileMode()).snapshotEnabled(false)
                .electionTimeout(ELECTION_TIMEOUT_MS).heartbeatInterval(HEARTBEAT_MS)
                .timerScheduler(timers).build();
        node.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private void becomeLeader() throws Exception {
        timers.fireElectionTimeout();
        node.awaitState(RaftNode.State.LEADER, 10_000).toCompletionStage().toCompletableFuture()
                .get(15, TimeUnit.SECONDS);
        settle();
    }

    private CompletableFuture<RaftCommandResult<?>> submit(String key) {
        return node.submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put(key, key)))
                .toCompletionStage().toCompletableFuture();
    }

    private RaftStatus status() throws Exception {
        return node.status().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static LogEntry entry(long index, long term, String key) {
        byte[] data = CODEC.serialize(new DistributedStateRaftCommand(DistributedStateCommand.put(key, key)));
        return LogEntry.newBuilder().setIndex(index).setTerm(term).setData(ByteString.copyFrom(data)).build();
    }

    /** Lets sends, responses, and sequenced transitions finish on the state loop. */
    private void settle() throws Exception {
        for (int pass = 0; pass < 5; pass++) {
            CompletableFuture<Void> marker = new CompletableFuture<>();
            runtime.runOnContext(ignored -> marker.complete(null));
            marker.get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Test transport fixture that holds append responses until the test chooses to complete them.
     *
     * Grants every vote at once and holds every AppendEntries until the test answers it. An answer is a
     * follower's success: its match index is the prefix the request verified.
     */
    private static final class HeldTransportFixture implements RaftTransport {
        private record Held(String target, AppendEntriesRequest request, Promise<AppendEntriesResponse> response) { }

        private final List<Held> held = new CopyOnWriteArrayList<>();

        /** Answers every held request to {@code target} that matches, in the order they were sent. */
        void answer(String target, Predicate<AppendEntriesRequest> which) {
            List<Held> chosen = held.stream()
                    .filter(entry -> entry.target().equals(target) && which.test(entry.request())).toList();
            if (chosen.isEmpty()) throw new AssertionError("no held request to " + target + " matches");
            for (Held entry : chosen) {
                held.remove(entry);
                AppendEntriesRequest request = entry.request();
                entry.response().complete(AppendEntriesResponse.newBuilder()
                        .setTerm(request.getTerm()).setSuccess(true)
                        .setMatchIndex(request.getPrevLogIndex() + request.getEntriesCount())
                        .setFollowerServerId(ManualRaftClusterFixture.serverIdOf(target)).build());
            }
        }

        @Override public void start(Consumer<RaftMessage> messageHandler) { }
        @Override public void stop() { }

        @Override
        public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return Future.succeededFuture(VoteResponse.newBuilder()
                    .setTerm(request.getTerm()).setVoteGranted(true)
                    .setVoterServerId(ManualRaftClusterFixture.serverIdOf(targetId)).build());
        }

        @Override
        public Future<AppendEntriesResponse> sendAppendEntries(String targetId, AppendEntriesRequest request) {
            Promise<AppendEntriesResponse> response = Promise.promise();
            held.add(new Held(targetId, request, response));
            return response.future();
        }

        @Override
        public Future<InstallSnapshotResponse> sendInstallSnapshot(String targetId, InstallSnapshotRequest request) {
            return Promise.<InstallSnapshotResponse>promise().future();
        }
    }
}

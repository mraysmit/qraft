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
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.controller.raft.grpc.LogEntry;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.runtime.Promise;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.controller.state.RaftCommand;
import dev.mars.qraft.controller.state.RaftCommandResult;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a committed entry the state machine fails to apply fences the node instead of being
 * skipped. A replica that skipped it would diverge from every replica that applied it: one that failed
 * for a local reason, or an older server in a rolling upgrade that meets a command it does not know.
 * The node keeps its applied index before the entry, applies nothing after it, and stops serving.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
class RaftNodeApplyFailureTest {
    private static final ProtobufRaftCommandCodec CODEC = new ProtobufRaftCommandCodec();

    private JavaRuntime runtime;
    private RaftNode node;
    private PoisonedStateMachine state;

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) node.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (runtime != null) runtime.shutdown().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void aLeaderThatCannotApplyACommittedEntryFencesAndKeepsItsAppliedIndex() throws Exception {
        start(Set.of("node-1"));
        awaitLeader();
        submit("before").get(10, TimeUnit.SECONDS);
        long appliedBefore = status().lastApplied();

        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> submit(PoisonedStateMachine.POISON).get(10, TimeUnit.SECONDS));

        assertSame(PoisonedStateMachine.FAILURE, failed.getCause(), "the client learns why the entry failed");
        RaftStatus status = status();
        assertTrue(status.fenced(), "a node that cannot apply a committed entry must not keep serving");
        assertEquals(appliedBefore, status.lastApplied(), "the entry that failed is not counted as applied");
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> submit("after").get(10, TimeUnit.SECONDS));
        assertInstanceOf(RaftTransitionSequencer.FencedException.class, refused.getCause());
        assertNull(state.store.getMetadata("after"));
    }

    @Test
    void aFollowerStopsApplyingAtTheEntryItCannotApply() throws Exception {
        start(Set.of("node-1", "leader"));
        AppendEntriesResponse response = node.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                        .setTerm(1).setLeaderId("leader").setPrevLogIndex(1).setPrevLogTerm(0).setLeaderCommit(4)
                        .addEntries(entry(2, "first"))
                        .addEntries(entry(3, PoisonedStateMachine.POISON))
                        .addEntries(entry(4, "third"))
                        .build())
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(response.getSuccess(), "the entries were durably appended before application failed");
        RaftStatus status = status();
        assertTrue(status.fenced());
        assertEquals(2, status.lastApplied(), "the bootstrap configuration and the first command");
        assertEquals("first", state.store.getMetadata("first"));
        assertNull(state.store.getMetadata("third"), "nothing after the failed entry is applied");

        AppendEntriesResponse later = node.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                        .setTerm(1).setLeaderId("leader").setPrevLogIndex(4).setPrevLogTerm(1).setLeaderCommit(4)
                        .build())
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(false, later.getSuccess(), "a fenced follower accepts no further appends");
        assertEquals(2, status().lastApplied());
    }

    private void start(Set<String> members) throws Exception {
        runtime = JavaRuntime.create();
        state = new PoisonedStateMachine();
        node = RaftNode.builder()
                .runtime(runtime).nodeId("node-1").serverId(ManualRaftCluster.serverIdOf("node-1"))
                .initialConfiguration(ManualRaftCluster.configurationOf(members))
                .clusterNodes(members).transport(new SilentTransport())
                .stateMachine(state).commandCodec(CODEC)
                .mode(RaftNodeMode.volatileMode()).snapshotEnabled(false)
                .electionTimeout(members.size() == 1 ? 30 : 60_000).heartbeatInterval(60_000)
                .build();
        node.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private void awaitLeader() throws Exception {
        node.awaitState(RaftNode.State.LEADER, 10_000).toCompletionStage().toCompletableFuture()
                .get(15, TimeUnit.SECONDS);
    }

    private CompletableFuture<RaftCommandResult<?>> submit(String key) {
        return node.submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put(key, key)))
                .toCompletionStage().toCompletableFuture();
    }

    private RaftStatus status() throws Exception {
        return node.status().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static LogEntry entry(long index, String key) {
        byte[] data = CODEC.serialize(new DistributedStateRaftCommand(DistributedStateCommand.put(key, key)));
        return LogEntry.newBuilder().setIndex(index).setTerm(1).setData(ByteString.copyFrom(data)).build();
    }

    /** Applies commands to a real store, except that a put of {@link #POISON} throws. */
    private static final class PoisonedStateMachine implements RaftLogApplicator {
        static final String POISON = "poison";
        static final IllegalStateException FAILURE = new IllegalStateException("cannot apply the poison command");

        final QraftStateStore store = new QraftStateStore();

        @Override
        public RaftCommandResult<?> apply(RaftCommand command) {
            if (command instanceof DistributedStateRaftCommand(DistributedStateCommand delegate)
                    && POISON.equals(delegate.key())) {
                throw FAILURE;
            }
            return store.apply(command);
        }

        @Override public byte[] takeSnapshot() { return store.takeSnapshot(); }
        @Override public void restoreSnapshot(byte[] snapshot) { store.restoreSnapshot(snapshot); }
        @Override public long getLastAppliedIndex() { return store.getLastAppliedIndex(); }
        @Override public void setLastAppliedIndex(long index) { store.setLastAppliedIndex(index); }
        @Override public void reset() { store.reset(); }
    }

    /** Never answers; the tests drive the node directly. */
    private static final class SilentTransport implements RaftTransport {
        @Override public void start(Consumer<RaftMessage> messageHandler) { }
        @Override public void stop() { }
        @Override public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return Promise.<VoteResponse>promise().future();
        }
        @Override public Future<AppendEntriesResponse> sendAppendEntries(String targetId, AppendEntriesRequest request) {
            return Promise.<AppendEntriesResponse>promise().future();
        }
        @Override public Future<InstallSnapshotResponse> sendInstallSnapshot(String targetId, InstallSnapshotRequest request) {
            return Promise.<InstallSnapshotResponse>promise().future();
        }
    }
}

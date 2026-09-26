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

package dev.mars.qraft.controller.integration;

import dev.mars.qraft.controller.state.*;

import dev.mars.qraft.controller.raft.RaftMessage;
import dev.mars.qraft.controller.raft.RaftNode;
import dev.mars.qraft.controller.raft.RaftNodeMode;
import dev.mars.qraft.controller.raft.RaftLogApplicator;
import dev.mars.qraft.controller.raft.RaftTransport;

import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.RaftCommand;
import dev.mars.qraft.controller.state.RaftCommandResult;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.support.JavaRuntimeExtension;
import dev.mars.qraft.controller.support.JavaTestContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link RaftNode} start, stop, and timer cleanup on a {@link JavaRuntime} with stub
 * transport and log applicator.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(JavaRuntimeExtension.class)
public class RaftNodeIntegrationTest {

    private JavaRuntime runtime;

    @BeforeAll
    void setUp(JavaRuntime runtime) {
        this.runtime = runtime;
    }

    @Test
    void testRaftNodeStartStop(JavaTestContext testContext) {
        TestRaftTransport transport = new TestRaftTransport();
        TestRaftLogApplicator stateMachine = new TestRaftLogApplicator();
        RaftNode node = RaftNode.builder().runtime(runtime).nodeId("node1").clusterNodes(Set.of("node1")).transport(transport).stateMachine(stateMachine).mode(RaftNodeMode.volatileMode()).commandCodec(new ProtobufRaftCommandCodec()).build();

        node.start()
            .onComplete(testContext.succeeding(v -> {
                assertTrue(node.isRunning(), "Node should be running after start()");

                // Wait a bit to let timers fire (if any)
                runtime.setTimer(500, id -> {
                    node.stop()
                        .onComplete(testContext.succeeding(v2 -> {
                            assertFalse(node.isRunning(), "Node should not be running after stop()");
                            testContext.completeNow();
                        }));
                });
            }));
    }

    @Test
    void testTimerCleanup(JavaTestContext testContext) {
        TestRaftTransport transport = new TestRaftTransport();
        TestRaftLogApplicator stateMachine = new TestRaftLogApplicator();
        RaftNode node = RaftNode.builder().runtime(runtime).nodeId("node1").clusterNodes(Set.of("node1")).transport(transport).stateMachine(stateMachine).mode(RaftNodeMode.volatileMode()).commandCodec(new ProtobufRaftCommandCodec()).build();

        node.start()
            .onComplete(testContext.succeeding(v -> {
                assertTrue(node.isRunning());

                // Stop immediately
                node.stop()
                    .onComplete(testContext.succeeding(v2 -> {
                        assertFalse(node.isRunning());
                        
                        // Wait to ensure no late timer events cause issues (though hard to assert absence of events without spying)
                        runtime.setTimer(200, id -> {
                            testContext.completeNow();
                        });
                    }));
            }));
    }

    // Simple Test Implementations

    static class TestRaftTransport implements RaftTransport {
        @Override
        public void start(Consumer<RaftMessage> messageHandler) {
        }

        @Override
        public void stop() {
        }

        @Override
        public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return Future.succeededFuture(VoteResponse.newBuilder().setTerm(1).setVoteGranted(true).build());
        }

        @Override
        public Future<AppendEntriesResponse> sendAppendEntries(String targetId, AppendEntriesRequest request) {
            return Future.succeededFuture(AppendEntriesResponse.newBuilder().setTerm(1).setSuccess(true).build());
        }

        @Override
        public Future<InstallSnapshotResponse> sendInstallSnapshot(String targetId, InstallSnapshotRequest request) {
            return Future.succeededFuture(InstallSnapshotResponse.newBuilder()
                    .setTerm(1).setSuccess(true).setNextChunkIndex(request.getChunkIndex() + 1).build());
        }
    }

    static class TestRaftLogApplicator implements RaftLogApplicator {
        @Override
        public RaftCommandResult<?> apply(RaftCommand command) {
            return new RaftCommandResult.NoOp<>();
        }

        @Override
        public byte[] takeSnapshot() {
            return new byte[0];
        }

        @Override
        public void restoreSnapshot(byte[] snapshot) {
        }

        @Override
        public long getLastAppliedIndex() {
            return 0;
        }

        @Override
        public void setLastAppliedIndex(long index) {
        }

        @Override
        public void reset() {
        }
    }
}

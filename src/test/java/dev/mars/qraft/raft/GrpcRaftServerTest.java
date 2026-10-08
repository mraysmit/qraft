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

import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;

import dev.mars.qraft.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.raft.grpc.RaftServiceGrpc;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;


import dev.mars.qraft.state.QraftStateStore;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import dev.mars.qraft.common.async.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link GrpcRaftServer} in front of a real sole-member node: starting and stopping, the vote and
 * append decisions it returns (including terms adopted and votes spent), rejection of malformed requests as
 * {@code INVALID_ARGUMENT}, deadlines, concurrent and sustained load, reconnection, and two servers serving
 * separate nodes.
 *
 * <p>Nodes run on manual timers, so a node never elects itself during a test and each decision depends only
 * on the requests the test sends.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 1.1
 * @since 2026-01-08
 */
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class GrpcRaftServerTest {

    private JavaRuntime runtime;
    private ManualRaftClusterFixture cluster;
    private RaftNode raftNode;
    private GrpcRaftServer grpcServer;
    private ManagedChannel channel;
    private RaftServiceGrpc.RaftServiceBlockingStub blockingStub;
    private RaftServiceGrpc.RaftServiceStub asyncStub;

    @BeforeEach
    void setUp() throws Exception {
        runtime = JavaRuntime.create();
        cluster = new ManualRaftClusterFixture(runtime);
        // On manual timers the node never elects itself, so it stays a follower in term 0 with no vote until a
        // request changes that, and every vote decision below is the same on every run.
        raftNode = node("node1");
    }

    /** A started sole-member node on manual timers, stopped after the test. */
    private RaftNode node(String nodeId) throws Exception {
        RaftNode node = cluster.add(cluster.builder(nodeId, Set.of(nodeId), new InMemoryTransportSimulatorFixture(nodeId),
                new QraftStateStore(), RaftNodeMode.volatileMode()));
        ManualRaftClusterFixture.await(node.start());
        return node;
    }

    /** A vote request from a candidate whose log, like the node's, holds only the bootstrap configuration. */
    private static VoteRequest vote(long term, String candidateId) {
        return VoteRequest.newBuilder()
                .setTerm(term).setCandidateId(candidateId).setLastLogIndex(1).setLastLogTerm(0).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdownNow();
            channel.awaitTermination(10, TimeUnit.SECONDS);
        }
        if (grpcServer != null) {
            grpcServer.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        if (cluster != null) {
            cluster.close();
        }
        if (runtime != null) {
            runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /** Starts the server on any free port and connects to the port it actually bound. */
    private void startServerAndConnect() throws Exception {
        grpcServer = new GrpcRaftServer(runtime, 0, raftNode);
        grpcServer.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        channel = ManagedChannelBuilder.forAddress("localhost", grpcServer.port())
                .usePlaintext()
                .build();
        // The deadline is fixed now, not per call, so it must outlast any test using this stub.
        blockingStub = RaftServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(30, TimeUnit.SECONDS);
        asyncStub = RaftServiceGrpc.newStub(channel);
    }

    // ========== SERVER LIFECYCLE TESTS ==========

    @Test
    @DisplayName("Server should fail to start on occupied port")
    void startOnAPortAnotherServerHasBoundFails() throws Exception {
        // Start first server
        grpcServer = new GrpcRaftServer(runtime, 0, raftNode);
        grpcServer.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        // Try to start second server on the port the first one bound
        GrpcRaftServer secondServer = new GrpcRaftServer(runtime, grpcServer.port(), raftNode);

        CompletableFuture<Void> startFuture = secondServer.start()
                .toCompletionStage().toCompletableFuture();

        assertThrows(ExecutionException.class, () -> startFuture.get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Server should stop gracefully")
    void stopCompletesWithoutErrorAndLaterCallsAreRefused() throws Exception {
        startServerAndConnect();

        // Verify server is running
        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(1)
                .setCandidateId("candidate1")
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build();

        assertDoesNotThrow(() -> blockingStub.requestVote(request));

        // Stop server
        CompletableFuture<Void> stopFuture = grpcServer.stop()
                .toCompletionStage().toCompletableFuture();

        assertDoesNotThrow(() -> stopFuture.get(10, TimeUnit.SECONDS));

        // Verify server is stopped (requests should fail)
        assertThrows(StatusRuntimeException.class, () -> blockingStub.requestVote(request));
    }

    @Test
    @DisplayName("A server on port 0 reports the port it bound, and only while running")
    void reportsTheBoundPortOnlyWhileRunning() throws Exception {
        grpcServer = new GrpcRaftServer(runtime, 0, raftNode);
        assertThrows(IllegalStateException.class, grpcServer::port, "no port is bound before start");

        startServerAndConnect();
        assertTrue(grpcServer.port() > 0);
        assertDoesNotThrow(() -> blockingStub.requestVote(VoteRequest.newBuilder()
                .setTerm(1).setCandidateId("candidate").setLastLogIndex(0).setLastLogTerm(0).build()));

        grpcServer.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, grpcServer::port, "no port is bound after stop");
    }

    @Test
    @DisplayName("Stopping a server that never started leaves it able to start and serve")
    void stoppingAServerThatNeverStartedLeavesItAbleToStartAndServe() throws Exception {
        grpcServer = new GrpcRaftServer(runtime, 0, raftNode);

        grpcServer.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, grpcServer::port, "stopping did not bind a port");

        grpcServer.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        channel = ManagedChannelBuilder.forAddress("localhost", grpcServer.port()).usePlaintext().build();
        VoteResponse response = RaftServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                .requestVote(vote(1, "candidate1"));
        assertTrue(response.getVoteGranted());
    }

    @Test
    @DisplayName("Server should handle multiple start-stop cycles")
    void threeSuccessiveServersEachStartAnswerRequestVoteAndStop() throws Exception {
        for (int i = 0; i < 3; i++) {
            GrpcRaftServer server = new GrpcRaftServer(runtime, 0, raftNode);

            server.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            // Quick health check
            ManagedChannel ch = ManagedChannelBuilder.forAddress("localhost", server.port())
                    .usePlaintext()
                    .build();
            RaftServiceGrpc.RaftServiceBlockingStub stub = RaftServiceGrpc.newBlockingStub(ch);

            VoteRequest request = VoteRequest.newBuilder()
                    .setTerm(1)
                    .setCandidateId("candidate")
                    .setLastLogIndex(0)
                    .setLastLogTerm(0)
                    .build();

            assertDoesNotThrow(() -> stub.requestVote(request));

            ch.shutdownNow();
            ch.awaitTermination(10, TimeUnit.SECONDS);

            server.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    // ========== REQUEST VOTE TESTS ==========

    @Test
    @DisplayName("A fresh follower grants its term-1 vote and reports term 1")
    void aFreshFollowerGrantsItsTermOneVote() throws Exception {
        startServerAndConnect();

        VoteResponse response = blockingStub.requestVote(vote(1, "candidate1"));

        assertTrue(response.getVoteGranted());
        assertEquals(1, response.getTerm());
    }

    @Test
    @DisplayName("In the node's own term, the vote goes to the first candidate and is refused to the next")
    void inTheNodesOwnTermTheVoteGoesToTheFirstCandidateOnly() throws Exception {
        startServerAndConnect();

        VoteResponse first = blockingStub.requestVote(vote(0, "candidate1"));
        VoteResponse second = blockingStub.requestVote(vote(0, "candidate2"));
        VoteResponse again = blockingStub.requestVote(vote(0, "candidate1"));

        assertEquals(List.of(true, false, true),
                List.of(first.getVoteGranted(), second.getVoteGranted(), again.getVoteGranted()),
                "one vote per term, which the same candidate may ask for again");
        assertEquals(List.of(0L, 0L, 0L), List.of(first.getTerm(), second.getTerm(), again.getTerm()));
    }

    @Test
    @DisplayName("A candidate at the maximum term has that term adopted and is granted the vote")
    void aCandidateAtTheMaximumTermHasItAdoptedAndIsGrantedTheVote() throws Exception {
        startServerAndConnect();

        VoteResponse response = blockingStub.requestVote(vote(Long.MAX_VALUE, "candidate1"));

        assertTrue(response.getVoteGranted());
        assertEquals(Long.MAX_VALUE, response.getTerm());
        assertEquals(Long.MAX_VALUE, raftNode.getCurrentTerm());
    }

    @Test
    @DisplayName("A vote request naming no candidate is rejected as INVALID_ARGUMENT without spending the vote")
    void aVoteRequestNamingNoCandidateIsRejectedWithoutSpendingTheVote() throws Exception {
        startServerAndConnect();

        StatusRuntimeException rejected = assertThrows(StatusRuntimeException.class,
                () -> blockingStub.requestVote(vote(1, "")));

        assertEquals(Status.Code.INVALID_ARGUMENT, rejected.getStatus().getCode());
        assertEquals(0, raftNode.getCurrentTerm(), "a rejected request does not advance the term");
        assertTrue(blockingStub.requestVote(vote(1, "candidate1")).getVoteGranted(),
                "the term-1 vote is still free for a real candidate");
    }

    @Test
    @DisplayName("A candidate claiming a negative log index is refused, but its higher term is adopted")
    void aCandidateClaimingANegativeLogIndexIsRefusedButItsTermIsAdopted() throws Exception {
        startServerAndConnect();

        VoteResponse response = blockingStub.requestVote(VoteRequest.newBuilder()
                .setTerm(1).setCandidateId("candidate1").setLastLogIndex(-1).setLastLogTerm(0).build());

        assertFalse(response.getVoteGranted(), "a log ending before index 0 is behind the node's");
        assertEquals(1, response.getTerm());
    }

    @Test
    @DisplayName("RequestVote async should complete successfully")
    void asyncRequestVoteDeliversAResponseAndCompletesWithoutError() throws Exception {
        startServerAndConnect();

        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(1)
                .setCandidateId("candidate1")
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<VoteResponse> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        asyncStub.requestVote(request, new StreamObserver<>() {
            @Override
            public void onNext(VoteResponse response) {
                responseRef.set(response);
            }

            @Override
            public void onError(Throwable t) {
                errorRef.set(t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertNull(errorRef.get());
        assertNotNull(responseRef.get());
    }

    // ========== APPEND ENTRIES TESTS ==========

    @Test
    @DisplayName("A heartbeat from a leader of a newer term succeeds, and the node follows that leader")
    void aHeartbeatFromANewerTermLeaderSucceedsAndTheNodeFollowsIt() throws Exception {
        startServerAndConnect();

        AppendEntriesResponse response = blockingStub.appendEntries(AppendEntriesRequest.newBuilder()
                .setTerm(1).setLeaderId("leader1").setPrevLogIndex(0).setPrevLogTerm(0).setLeaderCommit(0).build());

        assertTrue(response.getSuccess());
        assertEquals(1, response.getTerm());
        assertEquals("leader1", raftNode.getLeaderId());
    }

    @Test
    @DisplayName("AppendEntries should handle entries with log entries")
    void undecodableEntryDataIsRejectedAsInvalidArgumentAndLaterHeartbeatsStillSucceed() throws Exception {
        startServerAndConnect();

        // The entry follows the bootstrap configuration at index 1 in term 0, as a real leader's would.
        dev.mars.qraft.raft.grpc.LogEntry entry = dev.mars.qraft.raft.grpc.LogEntry.newBuilder()
                .setTerm(1)
                .setIndex(2)
                .setData(com.google.protobuf.ByteString.copyFromUtf8("test-command"))
                .build();

        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
                .setTerm(1)
                .setLeaderId("leader1")
                .setPrevLogIndex(1)
                .setPrevLogTerm(0)
                .setLeaderCommit(0)
                .addEntries(entry)
                .build();

        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class,
                () -> blockingStub.withDeadlineAfter(2, TimeUnit.SECONDS).appendEntries(request));
        assertEquals(Status.Code.INVALID_ARGUMENT, failure.getStatus().getCode());

        AppendEntriesRequest heartbeat = AppendEntriesRequest.newBuilder()
                .setTerm(1)
                .setLeaderId("leader1")
                .setPrevLogIndex(0)
                .setPrevLogTerm(0)
                .setLeaderCommit(0)
                .build();
        AppendEntriesResponse response = blockingStub.appendEntries(heartbeat);
        assertTrue(response.getSuccess(), "Server must remain usable after rejecting malformed log data");
    }

    @Test
    @DisplayName("AppendEntries should handle stale term")
    void appendEntriesFromAStaleTermIsRejected() throws Exception {
        startServerAndConnect();

        // First, update the term with a higher term request
        VoteRequest voteRequest = VoteRequest.newBuilder()
                .setTerm(10)
                .setCandidateId("candidate1")
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build();
        VoteResponse raised = blockingStub.requestVote(voteRequest);
        assertEquals(10, raised.getTerm(), "the vote request raised the node to term 10");

        // Now send AppendEntries with lower term
        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
                .setTerm(1) // Lower than current term
                .setLeaderId("leader1")
                .setPrevLogIndex(0)
                .setPrevLogTerm(0)
                .setLeaderCommit(0)
                .build();

        AppendEntriesResponse response = blockingStub.appendEntries(request);

        assertFalse(response.getSuccess());
        assertEquals(10, response.getTerm(), "the refusal tells the stale leader the current term");
    }

    @Test
    @DisplayName("AppendEntries async should complete successfully")
    void asyncAppendEntriesDeliversAResponseAndCompletesWithoutError() throws Exception {
        startServerAndConnect();

        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
                .setTerm(1)
                .setLeaderId("leader1")
                .setPrevLogIndex(0)
                .setPrevLogTerm(0)
                .setLeaderCommit(0)
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AppendEntriesResponse> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        asyncStub.appendEntries(request, new StreamObserver<>() {
            @Override
            public void onNext(AppendEntriesResponse response) {
                responseRef.set(response);
            }

            @Override
            public void onError(Throwable t) {
                errorRef.set(t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertNull(errorRef.get());
        assertNotNull(responseRef.get());
    }

    // ========== CONCURRENT REQUEST TESTS ==========

    @Test
    @DisplayName("Server should handle concurrent RequestVote calls")
    void fiftyConcurrentRequestVoteCallsAreAllAnswered() throws Exception {
        startServerAndConnect();

        int numRequests = 50;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(numRequests);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < numRequests; i++) {
            final int term = i + 1;
            executor.submit(() -> {
                try {
                    VoteRequest request = VoteRequest.newBuilder()
                            .setTerm(term)
                            .setCandidateId("candidate" + term)
                            .setLastLogIndex(0)
                            .setLastLogTerm(0)
                            .build();

                    VoteResponse response = blockingStub.requestVote(request);
                    if (response != null) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(numRequests, successCount.get());
        assertEquals(0, errorCount.get());
    }

    @Test
    @DisplayName("Server should handle concurrent AppendEntries calls")
    void fiftyConcurrentHeartbeatAppendEntriesCallsAreAllAnswered() throws Exception {
        startServerAndConnect();

        int numRequests = 50;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(numRequests);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < numRequests; i++) {
            executor.submit(() -> {
                try {
                    AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
                            .setTerm(1)
                            .setLeaderId("leader1")
                            .setPrevLogIndex(0)
                            .setPrevLogTerm(0)
                            .setLeaderCommit(0)
                            .build();

                    AppendEntriesResponse response = blockingStub.appendEntries(request);
                    if (response != null) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(numRequests, successCount.get());
        assertEquals(0, errorCount.get());
    }

    @Test
    @DisplayName("Server should handle mixed concurrent requests")
    void oneHundredConcurrentMixedVoteAndAppendEntriesCallsAreAllAnswered() throws Exception {
        startServerAndConnect();

        int numRequests = 100;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(numRequests);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < numRequests; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    if (index % 2 == 0) {
                        VoteRequest request = VoteRequest.newBuilder()
                                .setTerm(index + 1)
                                .setCandidateId("candidate" + index)
                                .setLastLogIndex(0)
                                .setLastLogTerm(0)
                                .build();
                        blockingStub.requestVote(request);
                    } else {
                        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
                                .setTerm(1)
                                .setLeaderId("leader1")
                                .setPrevLogIndex(0)
                                .setPrevLogTerm(0)
                                .setLeaderCommit(0)
                                .build();
                        blockingStub.appendEntries(request);
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(numRequests, successCount.get());
        assertEquals(0, errorCount.get());
    }

    // ========== CONNECTION EDGE CASE TESTS ==========

    @Test
    @DisplayName("Client should handle server disconnect gracefully")
    void callsOnAnOpenChannelFailWithAStatusErrorOnceTheServerStops() throws Exception {
        startServerAndConnect();

        // Verify connection works
        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(1)
                .setCandidateId("candidate1")
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build();

        assertDoesNotThrow(() -> blockingStub.requestVote(request));

        // Stop server
        grpcServer.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        grpcServer = null;

        // Client should get an error
        assertThrows(StatusRuntimeException.class, () -> blockingStub.requestVote(request));
    }

    @Test
    @DisplayName("Server should handle client reconnection")
    void aNewChannelIsAnsweredAfterThePreviousChannelIsClosed() throws Exception {
        startServerAndConnect();

        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(1)
                .setCandidateId("candidate1")
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build();

        // First request
        assertDoesNotThrow(() -> blockingStub.requestVote(request));

        // Close channel
        channel.shutdownNow();
        channel.awaitTermination(10, TimeUnit.SECONDS);

        // Reconnect
        channel = ManagedChannelBuilder.forAddress("localhost", grpcServer.port())
                .usePlaintext()
                .build();
        blockingStub = RaftServiceGrpc.newBlockingStub(channel);

        // Second request on new connection
        assertDoesNotThrow(() -> blockingStub.requestVote(request));
    }

    @Test
    @DisplayName("Server should handle many sequential connections")
    void twentySequentialShortLivedChannelsAreEachAnswered() throws Exception {
        startServerAndConnect();
        channel.shutdownNow();
        channel.awaitTermination(10, TimeUnit.SECONDS);
        channel = null;

        for (int i = 0; i < 20; i++) {
            ManagedChannel ch = ManagedChannelBuilder.forAddress("localhost", grpcServer.port())
                    .usePlaintext()
                    .build();

            RaftServiceGrpc.RaftServiceBlockingStub stub = RaftServiceGrpc.newBlockingStub(ch);

            VoteRequest request = VoteRequest.newBuilder()
                    .setTerm(i + 1)
                    .setCandidateId("candidate" + i)
                    .setLastLogIndex(0)
                    .setLastLogTerm(0)
                    .build();

            assertDoesNotThrow(() -> stub.requestVote(request));

            ch.shutdownNow();
            ch.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // ========== TIMEOUT AND DEADLINE TESTS ==========

    @Test
    @DisplayName("Request with an expired deadline fails with DEADLINE_EXCEEDED")
    void requestVoteWithAnAlreadyExpiredDeadlineFailsWithDeadlineExceeded() throws Exception {
        startServerAndConnect();

        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(1)
                .setCandidateId("candidate1")
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build();

        // A deadline that has already passed fails the call before it is sent, whatever the timing.
        RaftServiceGrpc.RaftServiceBlockingStub stubWithDeadline =
                blockingStub.withDeadline(Deadline.after(-1, TimeUnit.SECONDS));

        StatusRuntimeException expired = assertThrows(StatusRuntimeException.class,
                () -> stubWithDeadline.requestVote(request));
        assertEquals(Status.Code.DEADLINE_EXCEEDED, expired.getStatus().getCode());
    }

    // ========== STRESS TESTS ==========

    @Test
    @DisplayName("Server should handle burst of requests")
    void twoHundredRequestVoteCallsIssuedAtOnceAreAllAnswered() throws Exception {
        startServerAndConnect();

        int burstSize = 200;
        List<CompletableFuture<VoteResponse>> futures = new ArrayList<>();

        for (int i = 0; i < burstSize; i++) {
            final int term = i + 1;
            CompletableFuture<VoteResponse> future = CompletableFuture.supplyAsync(() -> {
                VoteRequest request = VoteRequest.newBuilder()
                        .setTerm(term)
                        .setCandidateId("candidate" + term)
                        .setLastLogIndex(0)
                        .setLastLogTerm(0)
                        .build();
                return blockingStub.requestVote(request);
            });
            futures.add(future);
        }

        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(60, TimeUnit.SECONDS);

        // Verify all succeeded
        for (CompletableFuture<VoteResponse> future : futures) {
            assertNotNull(future.get());
        }
    }

    @Test
    @DisplayName("Server answers every one of a sustained stream of concurrent requests")
    void fiveWorkersSendingOneHundredRequestVoteCallsEachAreAllAnswered() throws Exception {
        startServerAndConnect();
        int workers = 5;
        int requestsPerWorker = 100;
        AtomicInteger successCount = new AtomicInteger();
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(1).setCandidateId("candidate").setLastLogIndex(0).setLastLogTerm(0).build();

        // A fixed amount of work rather than a fixed duration: the result does not depend on machine speed,
        // and each call carries its own deadline instead of sharing one fixed when the stub was built.
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            List<java.util.concurrent.Future<?>> running = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                running.add(executor.submit(() -> {
                    for (int sent = 0; sent < requestsPerWorker; sent++) {
                        try {
                            RaftServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                                    .requestVote(request);
                            successCount.incrementAndGet();
                        } catch (RuntimeException failure) {
                            failures.add(failure);
                        }
                    }
                }));
            }
            for (java.util.concurrent.Future<?> worker : running) worker.get(60, TimeUnit.SECONDS);
        }

        assertEquals(List.of(), failures);
        assertEquals(workers * requestsPerWorker, successCount.get());
    }

    // ========== MALFORMED REQUEST TESTS ==========

    @Test
    @DisplayName("An entirely empty vote request is rejected as INVALID_ARGUMENT")
    void anEntirelyEmptyVoteRequestIsRejectedAsInvalidArgument() throws Exception {
        startServerAndConnect();

        StatusRuntimeException rejected = assertThrows(StatusRuntimeException.class,
                () -> blockingStub.requestVote(VoteRequest.newBuilder().build()));

        assertEquals(Status.Code.INVALID_ARGUMENT, rejected.getStatus().getCode());
    }

    @Test
    @DisplayName("Server should handle AppendEntries with large entries")
    void aOneMegabyteCommandFromAHigherTermIsAppendedAtIndexTwo() throws Exception {
        startServerAndConnect();

        // A real encoded command about 1 MB long, well inside the transport's message limit. The term is
        // beyond any the single-member node can reach by electing itself. It follows the bootstrap
        // configuration at index 1 in term 0, as a real leader's entry would.
        byte[] command = new ProtobufRaftCommandCodec().serialize(new DistributedStateRaftCommand(
                dev.mars.qraft.state.distributed.DistributedStateCommand.put("large", "X".repeat(1024 * 1024))));
        dev.mars.qraft.raft.grpc.LogEntry entry = dev.mars.qraft.raft.grpc.LogEntry.newBuilder()
                .setTerm(100)
                .setIndex(2)
                .setData(com.google.protobuf.ByteString.copyFrom(command))
                .build();
        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
                .setTerm(100)
                .setLeaderId("leader1")
                .setPrevLogIndex(1)
                .setPrevLogTerm(0)
                .setLeaderCommit(0)
                .addEntries(entry)
                .build();

        AppendEntriesResponse response = blockingStub.withDeadlineAfter(10, TimeUnit.SECONDS).appendEntries(request);

        assertTrue(response.getSuccess(), response.toString());
        assertEquals(2, response.getMatchIndex());
    }

    @Test
    @DisplayName("A candidate with a 10,000-character ID is granted the vote")
    void aCandidateWithATenThousandCharacterIdIsGrantedTheVote() throws Exception {
        startServerAndConnect();

        VoteResponse response = blockingStub.requestVote(vote(1, "x".repeat(10000)));

        assertTrue(response.getVoteGranted());
        assertEquals(1, response.getTerm());
        assertFalse(blockingStub.requestVote(vote(1, "x".repeat(9999))).getVoteGranted(),
                "the whole ID was recorded as the vote, so a candidate differing only in length is refused");
    }

    // ========== RAPID START/STOP TESTS ==========

    // ========== MULTIPLE RAFT NODES TEST ==========

    @Test
    @DisplayName("Servers for two nodes serve each node's own state")
    void serversForTwoNodesServeEachNodesOwnState() throws Exception {
        RaftNode nodeA = node("nodeA");
        RaftNode nodeB = node("nodeB");
        GrpcRaftServer serverA = new GrpcRaftServer(runtime, 0, nodeA);
        GrpcRaftServer serverB = new GrpcRaftServer(runtime, 0, nodeB);
        ManagedChannel channelA = null;
        ManagedChannel channelB = null;
        try {
            serverA.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            serverB.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            channelA = ManagedChannelBuilder.forAddress("localhost", serverA.port()).usePlaintext().build();
            channelB = ManagedChannelBuilder.forAddress("localhost", serverB.port()).usePlaintext().build();
            RaftServiceGrpc.RaftServiceBlockingStub stubA =
                    RaftServiceGrpc.newBlockingStub(channelA).withDeadlineAfter(10, TimeUnit.SECONDS);
            RaftServiceGrpc.RaftServiceBlockingStub stubB =
                    RaftServiceGrpc.newBlockingStub(channelB).withDeadlineAfter(10, TimeUnit.SECONDS);

            assertTrue(stubA.requestVote(vote(5, "candidate1")).getVoteGranted());

            VoteResponse fromB = stubB.requestVote(vote(1, "candidate2"));
            assertTrue(fromB.getVoteGranted(), "node B has voted in no term, whatever node A did");
            assertEquals(1, fromB.getTerm());
            VoteResponse fromA = stubA.requestVote(vote(1, "candidate2"));
            assertFalse(fromA.getVoteGranted(), "term 1 is stale for node A");
            assertEquals(5, fromA.getTerm());
        } finally {
            if (channelA != null) channelA.shutdownNow();
            if (channelB != null) channelB.shutdownNow();
            serverA.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            serverB.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}

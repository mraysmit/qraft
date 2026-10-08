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

import dev.mars.qraft.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.raft.grpc.JoinRequest;
import dev.mars.qraft.raft.grpc.RemoveServerRequest;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;

import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.common.async.Future;
import dev.mars.qraft.common.async.JavaRuntime;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests GrpcRaftTransport (client-side gRPC transport) against live single-member targets on manual timers.
 * Tests cover:
 * - Vote and append requests reaching the named peer and its decision coming back intact
 * - Failures: nothing listening, a peer whose server stops, a node outside the cluster map, a request the
 *   peer rejects, and any send after the transport stopped
 * - The callback pool running an overflowing callback on the delivering thread
 * - Concurrent vote and append requests
 * - Transport lifecycle and pool size configuration
 * Retries and timeouts are not tested here.
 *
 * <p>Every transport, executor, server and node a test creates is released after it, even when the test
 * fails.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 1.3
 * @since 2026-01-08
 */
@Execution(ExecutionMode.SAME_THREAD)
class GrpcRaftTransportTest {

    private JavaRuntime runtime;
    private ManualRaftClusterFixture cluster;
    private GrpcRaftServer targetServer;
    private RaftNode targetNode;
    private int targetPort;
    private final List<GrpcRaftTransport> transports = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        runtime = JavaRuntime.create();
        cluster = new ManualRaftClusterFixture(runtime);
        // On manual timers the target never elects itself, so it stays a follower in term 0 with no vote until a
        // request changes that, and every decision it returns is the same on every run.
        targetNode = node("target");

        targetServer = new GrpcRaftServer(runtime, 0, targetNode);
        targetServer.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        targetPort = targetServer.port();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            executors.forEach(ExecutorService::shutdownNow);
            transports.forEach(GrpcRaftTransport::stop);
        } finally {
            try {
                if (targetServer != null) {
                    targetServer.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                }
            } finally {
                try {
                    if (cluster != null) {
                        cluster.close();
                    }
                } finally {
                    if (runtime != null) {
                        runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    }
                }
            }
        }
    }

    /** A started sole-member node on manual timers, stopped after the test. */
    private RaftNode node(String nodeId) throws Exception {
        RaftNode node = cluster.add(cluster.builder(nodeId, Set.of(nodeId), new InMemoryTransportSimulatorFixture(nodeId),
                new QraftStateStore(), RaftNodeMode.volatileMode()));
        ManualRaftClusterFixture.await(node.start());
        return node;
    }

    private GrpcRaftTransport startedTransport(Map<String, String> peers) {
        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", peers));
        transport.start(msg -> {});
        return transport;
    }

    /** Tracks {@code transport} so it is stopped after the test; stopping it twice is harmless. */
    private GrpcRaftTransport track(GrpcRaftTransport transport) {
        transports.add(transport);
        return transport;
    }

    /** A pool that is shut down after the test, even if the test fails before shutting it down. */
    private ExecutorService pool(int threads) {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        executors.add(executor);
        return executor;
    }

    private static dev.mars.qraft.raft.grpc.LogEntry encodedEntry(
            long index, long term, String key, String value) {
        byte[] command = new ProtobufRaftCommandCodec().serialize(new DistributedStateRaftCommand(
                dev.mars.qraft.state.distributed.DistributedStateCommand.put(key, value)));
        return dev.mars.qraft.raft.grpc.LogEntry.newBuilder()
                .setIndex(index).setTerm(term).setData(com.google.protobuf.ByteString.copyFrom(command)).build();
    }

    /**
     * A port with nothing listening. It was free a moment ago; should another process take it meanwhile,
     * that process does not speak the Raft gRPC service either, so a send to it still fails.
     */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ========== BASIC CONNECTIVITY TESTS ==========

    @Test
    @DisplayName("A vote request reaches the peer, and the peer's decision comes back")
    void aVoteRequestReachesThePeerAndItsDecisionComesBack() throws Exception {
        GrpcRaftTransport transport = startedTransport(Map.of("target", "localhost:" + targetPort));

        VoteResponse response = decided(transport.sendVoteRequest("target", vote(1, "client")));

        assertTrue(response.getVoteGranted());
        assertEquals(1, response.getTerm());
        assertEquals(1, targetNode.getCurrentTerm(), "the peer itself moved to the candidate's term");
    }

    @Test
    @DisplayName("An append reaches the peer, and the peer's result comes back")
    void anAppendReachesThePeerAndItsResultComesBack() throws Exception {
        GrpcRaftTransport transport = startedTransport(Map.of("target", "localhost:" + targetPort));

        AppendEntriesResponse response = decided(transport.sendAppendEntries("target", AppendEntriesRequest.newBuilder()
                .setTerm(1).setLeaderId("client").setPrevLogIndex(0).setPrevLogTerm(0).setLeaderCommit(0).build()));

        assertTrue(response.getSuccess());
        assertEquals(1, response.getTerm());
        assertEquals(0, response.getMatchIndex());
        assertEquals("client", targetNode.getLeaderId(), "the peer itself now follows the sender");
    }

    // ========== CONNECTION FAILURE TESTS ==========

    @Test
    @DisplayName("Transport should fail when target server is down")
    void aVoteRequestToAPortWithNothingListeningFailsTheReturnedFuture() throws Exception {
        int deadPort = closedPort();

        Map<String, String> cluster = new HashMap<>();
        cluster.put("dead", "localhost:" + deadPort);

        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport.start(msg -> {});

        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(1)
                .setCandidateId("client")
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build();

        Future<VoteResponse> future = transport.sendVoteRequest("dead", request);

        assertThrows(ExecutionException.class, () ->
                future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS));

        transport.stop();
    }

    @Test
    @DisplayName("A request sent after the peer's server stops fails the returned future")
    void aVoteRequestAfterThePeerServerStopsFailsTheReturnedFuture() throws Exception {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);

        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport.start(msg -> {});

        // First request should work
        VoteRequest request = vote(1, "client");

        VoteResponse response1 = transport.sendVoteRequest("target", request)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(response1.getVoteGranted(), "the peer answered while its server ran");

        // Shut down the server
        targetServer.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        targetServer = null;

        // Next request should fail
        Future<VoteResponse> failFuture = transport.sendVoteRequest("target", request);

        assertThrows(ExecutionException.class, () ->
                failFuture.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS));

        transport.stop();
    }

    @Test
    @DisplayName("Transport should throw for unknown node")
    void sendingToANodeOutsideTheClusterMapThrowsIllegalArgumentException() {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("known", "localhost:" + targetPort);

        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport.start(msg -> {});

        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(1)
                .setCandidateId("client")
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build();

        assertThrows(IllegalArgumentException.class, () ->
                transport.sendVoteRequest("unknown", request));

        transport.stop();
    }

    // ========== CONCURRENT REQUEST TESTS ==========

    @Test
    @DisplayName("Transport should handle concurrent requests to same target")
    void fiftyConcurrentVoteRequestsToOnePeerAllReturnAResponse() throws Exception {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);

        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport.start(msg -> {});

        int numRequests = 50;
        ExecutorService executor = pool(10);
        CountDownLatch latch = new CountDownLatch(numRequests);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < numRequests; i++) {
            final int term = i + 1;
            executor.submit(() -> {
                try {
                    VoteRequest request = VoteRequest.newBuilder()
                            .setTerm(term)
                            .setCandidateId("client")
                            .setLastLogIndex(0)
                            .setLastLogTerm(0)
                            .build();

                    VoteResponse response = transport.sendVoteRequest("target", request)
                            .toCompletionStage().toCompletableFuture()
                            .get(10, TimeUnit.SECONDS);

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

        assertTrue(latch.await(60, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(numRequests, successCount.get());
        assertEquals(0, errorCount.get());

        transport.stop();
    }

    @Test
    @DisplayName("Transport should handle mixed concurrent vote and append requests")
    void aHundredConcurrentMixedVoteAndAppendRequestsAllCompleteWithoutError() throws Exception {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);

        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport.start(msg -> {});

        int numRequests = 100;
        ExecutorService executor = pool(10);
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
                                .setCandidateId("client")
                                .setLastLogIndex(0)
                                .setLastLogTerm(0)
                                .build();
                        transport.sendVoteRequest("target", request)
                                .toCompletionStage().toCompletableFuture()
                                .get(10, TimeUnit.SECONDS);
                    } else {
                        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
                                .setTerm(1)
                                .setLeaderId("client")
                                .setPrevLogIndex(0)
                                .setPrevLogTerm(0)
                                .setLeaderCommit(0)
                                .build();
                        transport.sendAppendEntries("target", request)
                                .toCompletionStage().toCompletableFuture()
                                .get(10, TimeUnit.SECONDS);
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(numRequests, successCount.get());
        assertEquals(0, errorCount.get());

        transport.stop();
    }

    // ========== MULTI-TARGET TESTS ==========

    @Test
    @DisplayName("Each request goes to the peer it names")
    void eachRequestGoesToThePeerItNames() throws Exception {
        RaftNode secondNode = node("target2");
        GrpcRaftServer secondServer = new GrpcRaftServer(runtime, 0, secondNode);
        try {
            secondServer.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            GrpcRaftTransport transport = startedTransport(Map.of(
                    "target1", "localhost:" + targetPort, "target2", "localhost:" + secondServer.port()));

            assertTrue(decided(transport.sendVoteRequest("target1", vote(5, "client"))).getVoteGranted());

            VoteResponse fromSecond = decided(transport.sendVoteRequest("target2", vote(1, "client")));
            assertTrue(fromSecond.getVoteGranted(), "target2 has seen no term yet");
            assertEquals(1, fromSecond.getTerm());
            VoteResponse fromFirst = decided(transport.sendVoteRequest("target1", vote(1, "client")));
            assertFalse(fromFirst.getVoteGranted(), "term 1 is stale for target1");
            assertEquals(5, fromFirst.getTerm());
            assertEquals(List.of(5L, 1L), List.of(targetNode.getCurrentTerm(), secondNode.getCurrentTerm()));
        } finally {
            secondServer.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("A server known only from the configuration is reached, and redialled when its address changes")
    void aConfiguredServerIsReachedAtItsConfiguredAddressAndRedialledWhenItChanges() throws Exception {
        RaftNode secondNode = node("target2");
        GrpcRaftServer secondServer = new GrpcRaftServer(runtime, 0, secondNode);
        try {
            secondServer.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            GrpcRaftTransport transport = startedTransport(Map.of());

            transport.useAddresses(Map.of("peer", "localhost:" + targetPort));
            assertEquals(targetNode.getServerId(), decided(transport.describe("peer")).getServerId());

            // As a server that lost its storage rejoins under the same name at a new address.
            transport.useAddresses(Map.of("peer", "localhost:" + secondServer.port()));
            assertEquals(secondNode.getServerId(), decided(transport.describe("peer")).getServerId());
        } finally {
            secondServer.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("A join or removal for a server the transport cannot address fails the returned future")
    void aMembershipRequestToAnUnaddressableServerFailsTheReturnedFutureInsteadOfThrowing() {
        GrpcRaftTransport transport = startedTransport(Map.of());

        // A caller forwarding to the leader it was told of must get a failed future it can turn into an answer.
        ExecutionException join = assertThrows(ExecutionException.class, () -> transport
                .join("nobody", JoinRequest.getDefaultInstance())
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, join.getCause());
        ExecutionException removal = assertThrows(ExecutionException.class, () -> transport
                .removeServer("nobody", RemoveServerRequest.getDefaultInstance())
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, removal.getCause());
    }

    // ========== REQUEST CONTENT TESTS ==========

    @Test
    @DisplayName("Transport should handle large append entries")
    void anAppendCarryingAHundredKilobyteEntryIsAcceptedAtMatchIndexTwo() throws Exception {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);

        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport.start(msg -> {});

        // A real encoded command about 100 KB long. The term is beyond any the single-member target can
        // reach by electing itself, so the append is always from its current leader. Like the target, the
        // leader's log begins with the bootstrap configuration at index 1 in term 0.
        String largeValue = "X".repeat(100 * 1024);
        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
                .setTerm(100)
                .setLeaderId("client")
                .setPrevLogIndex(1)
                .setPrevLogTerm(0)
                .setLeaderCommit(0)
                .addEntries(encodedEntry(2, 100, "large", largeValue))
                .build();

        AppendEntriesResponse response = transport.sendAppendEntries("target", request)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(response.getSuccess(), response.toString());
        assertEquals(2, response.getMatchIndex());
        transport.stop();
    }

    @Test
    @DisplayName("Transport should handle multiple log entries in single request")
    void anAppendCarryingAHundredEntriesIsAcceptedAtMatchIndexOneHundredAndOne() throws Exception {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);

        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport.start(msg -> {});

        // The batch follows the bootstrap configuration at index 1 in term 0.
        AppendEntriesRequest.Builder requestBuilder = AppendEntriesRequest.newBuilder()
                .setTerm(100)
                .setLeaderId("client")
                .setPrevLogIndex(1)
                .setPrevLogTerm(0)
                .setLeaderCommit(0);
        for (int i = 0; i < 100; i++) {
            requestBuilder.addEntries(encodedEntry(i + 2, 100, "command-" + i, "value-" + i));
        }

        AppendEntriesResponse response = transport.sendAppendEntries("target", requestBuilder.build())
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(response.getSuccess(), response.toString());
        assertEquals(101, response.getMatchIndex(), "every entry of the batch is verified");
        transport.stop();
    }

    // ========== TRANSPORT LIFECYCLE TESTS ==========

    @Test
    @DisplayName("After stop, however often repeated, a send fails at once and never reaches the peer")
    void aSendAfterStopFailsAtOnceAndNeverReachesThePeer() throws Exception {
        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client",
                Map.of("target", "localhost:" + targetPort)));
        transport.start(msg -> {});
        assertTrue(decided(transport.sendVoteRequest("target", vote(1, "client"))).getVoteGranted());

        transport.stop();
        transport.stop();

        ExecutionException vote = assertThrows(ExecutionException.class,
                () -> decided(transport.sendVoteRequest("target", vote(2, "client"))));
        assertInstanceOf(IllegalStateException.class, vote.getCause());
        ExecutionException append = assertThrows(ExecutionException.class,
                () -> decided(transport.sendAppendEntries("target", AppendEntriesRequest.newBuilder()
                        .setTerm(2).setLeaderId("client").build())));
        assertInstanceOf(IllegalStateException.class, append.getCause());
        assertEquals(1, targetNode.getCurrentTerm(), "neither request reached the peer");
    }

    /** The outcome of {@code future}; the bound only diagnoses a send that never completes. */
    private static <T> T decided(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** A vote request from a candidate whose log, like the target's, holds only the bootstrap configuration. */
    private static VoteRequest vote(long term, String candidateId) {
        return VoteRequest.newBuilder()
                .setTerm(term).setCandidateId(candidateId).setLastLogIndex(1).setLastLogTerm(0).build();
    }

    @Test
    @DisplayName("A new transport reaches the peer after an earlier one for the same cluster stopped")
    void aNewTransportReachesThePeerAfterAnEarlierOneStopped() throws Exception {
        Map<String, String> peers = Map.of("target", "localhost:" + targetPort);
        GrpcRaftTransport first = startedTransport(peers);
        assertTrue(decided(first.sendVoteRequest("target", vote(1, "client"))).getVoteGranted());
        first.stop();

        GrpcRaftTransport second = startedTransport(peers);
        VoteResponse response = decided(second.sendVoteRequest("target", vote(2, "client")));

        assertTrue(response.getVoteGranted());
        assertEquals(2, response.getTerm());
    }

    // ========== EDGE CASE REQUEST TESTS ==========

    @Test
    @DisplayName("A vote request naming no candidate fails with the peer's INVALID_ARGUMENT")
    void aVoteRequestNamingNoCandidateFailsWithThePeersInvalidArgument() throws Exception {
        GrpcRaftTransport transport = startedTransport(Map.of("target", "localhost:" + targetPort));

        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> decided(transport.sendVoteRequest("target", vote(1, ""))));

        StatusRuntimeException status = assertInstanceOf(StatusRuntimeException.class, failed.getCause());
        assertEquals(Status.Code.INVALID_ARGUMENT, status.getStatus().getCode());
        assertEquals(0, targetNode.getCurrentTerm());
    }

    @Test
    @DisplayName("Maximum term and log position cross the wire intact")
    void maximumTermAndLogPositionCrossTheWireIntact() throws Exception {
        GrpcRaftTransport transport = startedTransport(Map.of("target", "localhost:" + targetPort));

        VoteResponse response = decided(transport.sendVoteRequest("target", VoteRequest.newBuilder()
                .setTerm(Long.MAX_VALUE).setCandidateId("client")
                .setLastLogIndex(Long.MAX_VALUE).setLastLogTerm(Long.MAX_VALUE).build()));

        assertTrue(response.getVoteGranted(), "a log at the maximum position is at least as up to date");
        assertEquals(Long.MAX_VALUE, response.getTerm());
    }

    // ========== T3.2 BOUNDED THREAD POOL TESTS ==========

    @Test
    @DisplayName("Transport should have configurable pool size")
    void getPoolSizeReportsThePoolSizeGivenToTheConstructor() {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);

        // Test with custom pool size
        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster, 5, 500));
        assertEquals(5, transport.getPoolSize(), "Pool size should be configurable");
        transport.stop();
    }

    @Test
    @DisplayName("Transport should use default pool size of 10")
    void theDefaultPoolSizeIsTen() {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);

        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        assertEquals(10, transport.getPoolSize(), "Default pool size should be 10");
        transport.stop();
    }

    @Test
    @DisplayName("When the callback pool and its queue are full, the delivering thread runs the callback")
    void whenTheCallbackPoolAndQueueAreFullTheDeliveringThreadRunsTheCallback() throws Exception {
        ThreadPoolExecutor callbacks = GrpcRaftTransport.callbackExecutor("client", 1, 1);
        executors.add(callbacks);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch busy = new CountDownLatch(1);
        AtomicReference<Thread> poolThread = new AtomicReference<>();
        callbacks.execute(() -> {
            poolThread.set(Thread.currentThread());
            busy.countDown();
            awaitQuietly(release);
        });
        assertTrue(busy.await(10, TimeUnit.SECONDS));
        callbacks.execute(() -> { });

        AtomicReference<Thread> overflowThread = new AtomicReference<>();
        callbacks.execute(() -> overflowThread.set(Thread.currentThread()));
        release.countDown();

        assertEquals(Thread.currentThread(), overflowThread.get(), "the overflow ran at once, on the caller");
        assertTrue(poolThread.get().getName().startsWith("raft-grpc-io-client-"), poolThread.get().getName());
        assertTrue(poolThread.get().isDaemon(), "a callback thread never keeps the JVM alive");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

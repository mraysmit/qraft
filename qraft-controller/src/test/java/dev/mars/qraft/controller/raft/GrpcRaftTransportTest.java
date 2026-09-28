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

import dev.mars.qraft.controller.state.*;

import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.raft.grpc.RaftServiceGrpc;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;

import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import io.grpc.*;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case tests for GrpcRaftTransport (client-side gRPC transport).
 * Tests various scenarios including:
 * - Connection handling
 * - Failure scenarios
 * - Retry behavior
 * - Timeout handling
 * - Concurrent operations
 * 
 * <p>Every transport, executor, server and node a test creates is released after it, even when the test
 * fails.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 1.1
 * @since 2026-01-08
 */
@Execution(ExecutionMode.SAME_THREAD)
class GrpcRaftTransportTest {

    private JavaRuntime runtime;
    private GrpcRaftServer targetServer;
    private RaftNode targetNode;
    private int targetPort;
    private final List<GrpcRaftTransport> transports = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        runtime = JavaRuntime.create();

        // Set up a target server to receive requests
        Set<String> clusterNodes = Set.of("target");
        InMemoryTransportSimulator transport = new InMemoryTransportSimulator("target");
        QraftStateStore stateMachine = new QraftStateStore();
        targetNode = RaftNode.builder().runtime(runtime).nodeId("target").clusterNodes(clusterNodes).transport(transport).stateMachine(stateMachine).mode(RaftNodeMode.volatileMode()).electionTimeout(5000).heartbeatInterval(1000).commandCodec(new ProtobufRaftCommandCodec()).build();
        targetNode.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

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
                    if (targetNode != null) {
                        targetNode.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    }
                } finally {
                    if (runtime != null) {
                        runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    }
                }
            }
        }
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

    private static dev.mars.qraft.controller.raft.grpc.LogEntry encodedEntry(
            long index, long term, String key, String value) {
        byte[] command = new ProtobufRaftCommandCodec().serialize(new DistributedStateRaftCommand(
                dev.mars.qraft.distributedstate.DistributedStateCommand.put(key, value)));
        return dev.mars.qraft.controller.raft.grpc.LogEntry.newBuilder()
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
    @DisplayName("Transport should successfully send vote request to live server")
    void testSendVoteRequestSuccess() throws Exception {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);
        
        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport.start(msg -> {});
        
        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(1)
                .setCandidateId("client")
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build();
        
        Future<VoteResponse> future = transport.sendVoteRequest("target", request);
        VoteResponse response = future.toCompletionStage().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        
        assertNotNull(response);
        transport.stop();
    }

    @Test
    @DisplayName("Transport should successfully send append entries to live server")
    void testSendAppendEntriesSuccess() throws Exception {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);
        
        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport.start(msg -> {});
        
        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
                .setTerm(1)
                .setLeaderId("client")
                .setPrevLogIndex(0)
                .setPrevLogTerm(0)
                .setLeaderCommit(0)
                .build();
        
        Future<AppendEntriesResponse> future = transport.sendAppendEntries("target", request);
        AppendEntriesResponse response = future.toCompletionStage().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        
        assertNotNull(response);
        transport.stop();
    }

    // ========== CONNECTION FAILURE TESTS ==========

    @Test
    @DisplayName("Transport should fail when target server is down")
    void testSendToDownServer() throws Exception {
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
    @DisplayName("Transport should handle server shutdown during request")
    void testServerShutdownDuringRequest() throws Exception {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);
        
        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport.start(msg -> {});
        
        // First request should work
        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(1)
                .setCandidateId("client")
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build();
        
        VoteResponse response1 = transport.sendVoteRequest("target", request)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertNotNull(response1);
        
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
    void testUnknownNode() {
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
    void testConcurrentRequestsSameTarget() throws Exception {
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
    void testMixedConcurrentRequests() throws Exception {
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
    @DisplayName("Transport should handle requests to multiple targets")
    void testMultipleTargets() throws Exception {
        // Set up second target
Set<String> cluster2 = Set.of("target2");
        InMemoryTransportSimulator transport2 = new InMemoryTransportSimulator("target2");
        QraftStateStore sm2 = new QraftStateStore();
        RaftNode node2 = RaftNode.builder().runtime(runtime).nodeId("target2").clusterNodes(cluster2).transport(transport2).stateMachine(sm2).mode(RaftNodeMode.volatileMode()).electionTimeout(5000).heartbeatInterval(1000).commandCodec(new ProtobufRaftCommandCodec()).build();
        GrpcRaftServer server2 = new GrpcRaftServer(runtime, 0, node2);
        try {
            node2.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            server2.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            int targetPort2 = server2.port();

            Map<String, String> cluster = new HashMap<>();
            cluster.put("target1", "localhost:" + targetPort);
            cluster.put("target2", "localhost:" + targetPort2);
            
            GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
            transport.start(msg -> {});
            
            VoteRequest request = VoteRequest.newBuilder()
                    .setTerm(1)
                    .setCandidateId("client")
                    .setLastLogIndex(0)
                    .setLastLogTerm(0)
                    .build();
            
            // Send to both targets
            VoteResponse response1 = transport.sendVoteRequest("target1", request)
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            VoteResponse response2 = transport.sendVoteRequest("target2", request)
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            
            assertNotNull(response1);
            assertNotNull(response2);
            
            transport.stop();
        } finally {
            try {
                server2.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } finally {
                node2.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    // ========== REQUEST CONTENT TESTS ==========

    @Test
    @DisplayName("Transport should handle large append entries")
    void testLargeAppendEntries() throws Exception {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);
        
        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport.start(msg -> {});
        
        // A real encoded command about 100 KB long. The term is beyond any the single-member target can
        // reach by electing itself, so the append is always from its current leader.
        String largeValue = "X".repeat(100 * 1024);
        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
                .setTerm(100)
                .setLeaderId("client")
                .setPrevLogIndex(0)
                .setPrevLogTerm(0)
                .setLeaderCommit(0)
                .addEntries(encodedEntry(1, 100, "large", largeValue))
                .build();

        AppendEntriesResponse response = transport.sendAppendEntries("target", request)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(response.getSuccess(), response.toString());
        assertEquals(1, response.getMatchIndex());
        transport.stop();
    }

    @Test
    @DisplayName("Transport should handle multiple log entries in single request")
    void testMultipleLogEntries() throws Exception {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);
        
        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport.start(msg -> {});
        
        AppendEntriesRequest.Builder requestBuilder = AppendEntriesRequest.newBuilder()
                .setTerm(100)
                .setLeaderId("client")
                .setPrevLogIndex(0)
                .setPrevLogTerm(0)
                .setLeaderCommit(0);
        for (int i = 0; i < 100; i++) {
            requestBuilder.addEntries(encodedEntry(i + 1, 100, "command-" + i, "value-" + i));
        }

        AppendEntriesResponse response = transport.sendAppendEntries("target", requestBuilder.build())
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(response.getSuccess(), response.toString());
        assertEquals(100, response.getMatchIndex(), "every entry of the batch is verified");
        transport.stop();
    }

    // ========== TRANSPORT LIFECYCLE TESTS ==========

    @Test
    @DisplayName("Transport stop should be idempotent")
    void testTransportStopIdempotent() {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);
        
        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport.start(msg -> {});
        
        // Stop multiple times should not throw
        assertDoesNotThrow(transport::stop);
        assertDoesNotThrow(transport::stop);
        assertDoesNotThrow(transport::stop);
    }

    @Test
    @DisplayName("Transport should work with new instance after old one stops")
    void testTransportRestart() throws Exception {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);
        
        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(1)
                .setCandidateId("client")
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build();
        
        // First transport instance
        GrpcRaftTransport transport1 = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport1.start(msg -> {});
        
        VoteResponse response1 = transport1.sendVoteRequest("target", request)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertNotNull(response1);
        
        // Stop first transport (properly shuts down executor - T3.2 fix)
        transport1.stop();
        
        // Create new transport instance (proper lifecycle management)
        GrpcRaftTransport transport2 = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport2.start(msg -> {});
        
        // Second use with new instance
        VoteResponse response2 = transport2.sendVoteRequest("target", request)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertNotNull(response2);
        
        transport2.stop();
    }

    // ========== EDGE CASE REQUEST TESTS ==========

    @Test
    @DisplayName("Transport should handle request with empty candidate ID")
    void testEmptyCandidateId() throws Exception {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);
        
        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport.start(msg -> {});
        
        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(1)
                .setCandidateId("")
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build();
        
        VoteResponse response = transport.sendVoteRequest("target", request)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        
        assertNotNull(response);
        
        transport.stop();
    }

    @Test
    @DisplayName("Transport should handle request with max values")
    void testMaxValues() throws Exception {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);
        
        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport.start(msg -> {});
        
        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(Long.MAX_VALUE)
                .setCandidateId("client")
                .setLastLogIndex(Long.MAX_VALUE)
                .setLastLogTerm(Long.MAX_VALUE)
                .build();
        
        VoteResponse response = transport.sendVoteRequest("target", request)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        
        assertNotNull(response);
        
        transport.stop();
    }

    @Test
    @DisplayName("Transport should handle request with zero values")
    void testZeroValues() throws Exception {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);
        
        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        transport.start(msg -> {});
        
        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(0)
                .setCandidateId("")
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build();
        
        VoteResponse response = transport.sendVoteRequest("target", request)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        
        assertNotNull(response);
        
        transport.stop();
    }

    // ========== T3.2 BOUNDED THREAD POOL TESTS ==========

    @Test
    @DisplayName("Transport should have configurable pool size")
    void testConfigurablePoolSize() {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);
        
        // Test with custom pool size
        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster, 5, 500));
        assertEquals(5, transport.getPoolSize(), "Pool size should be configurable");
        transport.stop();
    }

    @Test
    @DisplayName("Transport should use default pool size of 10")
    void testDefaultPoolSize() {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);
        
        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster));
        assertEquals(10, transport.getPoolSize(), "Default pool size should be 10");
        transport.stop();
    }

    @Test
    @DisplayName("Transport should handle concurrent requests within pool limits")
    void testConcurrentRequestsWithinLimits() throws Exception {
        Map<String, String> cluster = new HashMap<>();
        cluster.put("target", "localhost:" + targetPort);
        
        GrpcRaftTransport transport = track(new GrpcRaftTransport(runtime, "client", cluster, 5, 500));
        transport.start(msg -> {});
        
        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(1)
                .setCandidateId("client")
                .setLastLogIndex(0)
                .setLastLogTerm(0)
                .build();
        
        // Send multiple concurrent requests (within pool limits)
        CompletableFuture<?>[] futures = new CompletableFuture[5];
        for (int i = 0; i < 5; i++) {
            futures[i] = transport.sendVoteRequest("target", request)
                    .toCompletionStage().toCompletableFuture();
        }
        
        // All should complete within timeout
        CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);
        
        for (CompletableFuture<?> f : futures) {
            assertNotNull(f.get(), "All responses should be non-null");
        }
        
        transport.stop();
    }
}

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
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.grpc.ForwardRequest;
import dev.mars.qraft.raft.grpc.ForwardResponse;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Raft port's {@code Forward} call (write forwarding list, Phase 1, decision 1): a request sent to a peer by
 * ID reaches the forward target the peer's server was given, and its answer comes back whole; a server with no target
 * answers 503 {@code leader_unavailable}; and a stopped transport forwards nothing.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class GrpcRaftForwardTest {
    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private GrpcRaftServer targetServer;
    private GrpcRaftTransport transport;

    @BeforeEach
    void setUp() throws Exception {
        runtime = RaftExecutorsFixture.create();
        cluster = new ManualRaftClusterFixture(runtime);
        RaftNode targetNode = cluster.add(cluster.builder("target", Set.of("target"),
                new InMemoryTransportSimulatorFixture("target"), new QraftStateStore(), RaftNodeMode.volatileMode()));
        ManualRaftClusterFixture.await(targetNode.start());
        targetServer = new GrpcRaftServer(runtime.blockingTasks(), 0, targetNode);
        targetServer.start().get(10, TimeUnit.SECONDS);
        transport = new GrpcRaftTransport(runtime.stateLoop(), "follower",
                Map.of("target", "localhost:" + targetServer.port()));
        transport.start(message -> { });
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (transport != null) transport.stop();
            if (targetServer != null) targetServer.stop().get(10, TimeUnit.SECONDS);
        } finally {
            try {
                if (cluster != null) cluster.close();
            } finally {
                if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static ForwardRequest request() {
        return ForwardRequest.newBuilder().setMethod("PUT").setPathAndQuery("/v1/kv/app/config?flags=7")
                .putHeaders("X-Qraft-Token", "secret").putHeaders("X-Qraft-Forwarded-By", "follower")
                .setBody(ByteString.copyFrom("value", StandardCharsets.UTF_8)).setFrom("follower").build();
    }

    @Test
    void aForwardedRequestReachesTheTargetAndItsAnswerComesBackWhole() throws Exception {
        AtomicReference<ForwardRequest> received = new AtomicReference<>();
        targetServer.useForwardTarget(forwarded -> {
            received.set(forwarded);
            return CompletableFuture.completedFuture(ForwardResponse.newBuilder().setStatus(201)
                    .putHeaders("X-Qraft-Index", "42").setBody(ByteString.copyFromUtf8("true")).build());
        });

        ForwardResponse answer = transport.forward("target", request()).get(10, TimeUnit.SECONDS);
        assertEquals(request(), received.get(), "the request arrived as it was sent");
        assertEquals(201, answer.getStatus());
        assertEquals("42", answer.getHeadersOrThrow("X-Qraft-Index"));
        assertEquals("true", answer.getBody().toStringUtf8());
    }

    @Test
    void aServerWithNoTargetAnswersThatItCannotTakeTheWrite() throws Exception {
        ForwardResponse answer = transport.forward("target", request()).get(10, TimeUnit.SECONDS);
        assertEquals(503, answer.getStatus());
        assertTrue(answer.getBody().toStringUtf8().contains("\"code\":\"leader_unavailable\""),
                answer.getBody().toStringUtf8());
    }

    @Test
    void aForwardReachesAPeerThatWasNotListeningWhenTheChannelFirstTriedIt() throws Exception {
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        GrpcRaftTransport late = new GrpcRaftTransport(runtime.stateLoop(), "follower",
                Map.of("late", "localhost:" + port));
        late.start(message -> { });
        RaftNode lateNode = cluster.add(cluster.builder("late", Set.of("late"),
                new InMemoryTransportSimulatorFixture("late"), new QraftStateStore(), RaftNodeMode.volatileMode()));
        ManualRaftClusterFixture.await(lateNode.start());
        GrpcRaftServer lateServer = new GrpcRaftServer(runtime.blockingTasks(), port, lateNode);
        try {
            // A call that fails at once while nothing listens leaves the channel in its reconnect backoff.
            assertThrows(ExecutionException.class, () -> late.removeServer("late", dev.mars.qraft.raft.grpc
                    .RemoveServerRequest.newBuilder().setName("x").build()).get(10, TimeUnit.SECONDS),
                    "nothing listens yet, and the channel backs off");
            lateServer.start().get(10, TimeUnit.SECONDS);
            lateServer.useForwardTarget(forwarded -> CompletableFuture.completedFuture(
                    ForwardResponse.newBuilder().setStatus(200).build()));
            assertEquals(200, late.forward("late", request()).get(10, TimeUnit.SECONDS).getStatus(),
                    "a forward waits for the channel to connect rather than failing in its backoff");
        } finally {
            late.stop();
            lateServer.stop().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void aForwardToAPeerThatDoesNotListenFailsPromptlyAsNotSent() throws Exception {
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        GrpcRaftTransport toNobody = new GrpcRaftTransport(runtime.stateLoop(), "follower",
                Map.of("gone", "localhost:" + port));
        toNobody.start(message -> { });
        try {
            long started = System.nanoTime();
            ExecutionException failed = assertThrows(ExecutionException.class,
                    () -> toNobody.forward("gone", request()).get(10, TimeUnit.SECONDS));
            assertTrue(failed.getCause() instanceof ForwardNotSentException, failed.toString());
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(4), "within its wait for the connection");
        } finally {
            toNobody.stop();
        }
    }

    @Test
    void aStoppedTransportForwardsNothing() {
        transport.stop();
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> transport.forward("target", request()).get(10, TimeUnit.SECONDS));
        assertTrue(refused.getCause() instanceof IllegalStateException, refused.toString());
    }
}

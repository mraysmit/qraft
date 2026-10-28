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

import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.state.QraftStateStore;
import io.grpc.ConnectivityState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the keepalive of {@link GrpcRaftTransport}'s connections against a live {@link GrpcRaftServer}: a connection
 * that stops answering without a reset is closed and the peer is reached on a new one, as Consul's Raft transport
 * drops a connection whose call failed, and the server accepts the transport's pings on an idle connection instead
 * of refusing them as too many. Keepalive runs on real time, at least ten seconds a ping, so these tests are slow.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-28
 * @version 1.0
 */
@Tag("slow")
@Execution(ExecutionMode.SAME_THREAD)
class GrpcRaftKeepaliveTest {

    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private GrpcRaftServer targetServer;
    private int targetPort;
    private GrpcRaftTransport transport;

    @BeforeEach
    void setUp() throws Exception {
        runtime = RaftExecutorsFixture.create();
        cluster = new ManualRaftClusterFixture(runtime);
        // On manual timers the target stays a follower until a request changes that, so it grants every vote below.
        RaftNode target = cluster.add(cluster.builder("target", Set.of("target"),
                new InMemoryTransportSimulatorFixture("target"), new QraftStateStore(), RaftNodeMode.volatileMode()));
        ManualRaftClusterFixture.await(target.start());
        targetServer = new GrpcRaftServer(runtime.blockingTasks(), 0, target);
        targetServer.start().get(10, TimeUnit.SECONDS);
        targetPort = targetServer.port();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (transport != null) transport.stop();
        } finally {
            try {
                if (targetServer != null) targetServer.stop().get(10, TimeUnit.SECONDS);
            } finally {
                try {
                    if (cluster != null) cluster.close();
                } finally {
                    if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void aConnectionThatStopsAnsweringIsClosedAndThePeerIsReachedOnANewOne() throws Exception {
        try (SilencingRelay relay = new SilencingRelay(targetPort)) {
            transport = new GrpcRaftTransport(runtime.stateLoop(), "client",
                    Map.of("target", "127.0.0.1:" + relay.port()), 10, 1000, Duration.ofSeconds(1), Duration.ofSeconds(1));
            assertTrue(answered(), "the peer answers through the relay");

            relay.silenceOpenConnections();

            // Each call on the silent connection ends at its deadline. A ping finds no answer either, so the connection
            // is closed, and a later call dials the peer again: ten seconds before the ping, one for its answer.
            await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200)).until(this::answered);
            assertEquals(2, relay.connectionsAccepted(), "the peer was reached on a second connection");
        }
    }

    @Test
    void theServerAcceptsTheTransportsKeepalivePingsOnAnIdleConnection() throws Exception {
        transport = new GrpcRaftTransport(runtime.stateLoop(), "client", Map.of("target", "127.0.0.1:" + targetPort));
        assertTrue(answered());

        // A server that refused the pings would close the connection with too_many_pings after the third, at about
        // thirty seconds, and the channel would go idle.
        await().during(Duration.ofSeconds(35)).atMost(Duration.ofSeconds(45))
                .until(() -> transport.connectionState("target") == ConnectivityState.READY);
    }

    /** Whether a vote request is answered within five seconds; a failed one is not. */
    private boolean answered() throws Exception {
        VoteRequest vote = VoteRequest.newBuilder()
                .setTerm(1).setCandidateId("client").setLastLogIndex(1).setLastLogTerm(0).build();
        try {
            return transport.sendVoteRequest("target", vote).get(5, TimeUnit.SECONDS).getVoteGranted();
        } catch (ExecutionException failed) {
            return false;
        }
    }

    /**
     * A TCP relay to the target. It can stop relaying on the connections it holds while keeping them open, as a
     * network does that drops a connection's packets without a reset; a connection it accepts later is relayed.
     */
    private static final class SilencingRelay implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final int targetPort;
        private final List<Connection> connections = new CopyOnWriteArrayList<>();
        private final ExecutorService pumps = Executors.newCachedThreadPool();

        SilencingRelay(int targetPort) throws IOException {
            this.targetPort = targetPort;
            pumps.execute(this::accept);
        }

        int port() {
            return listener.getLocalPort();
        }

        int connectionsAccepted() {
            return connections.size();
        }

        void silenceOpenConnections() {
            connections.forEach(connection -> connection.silenced = true);
        }

        private void accept() {
            while (!listener.isClosed()) {
                try {
                    Socket client = listener.accept();
                    Socket target = new Socket(InetAddress.getLoopbackAddress(), targetPort);
                    Connection connection = new Connection(client, target);
                    connections.add(connection);
                    pumps.execute(() -> connection.relay(client, target));
                    pumps.execute(() -> connection.relay(target, client));
                } catch (IOException closed) {
                    return;
                }
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
            for (Connection connection : connections) connection.close();
            pumps.shutdownNow();
        }
    }

    private static final class Connection {
        private final Socket client;
        private final Socket target;
        /** Once set, what arrives is read and dropped, and nothing is sent: the connection stays open but silent. */
        volatile boolean silenced;

        Connection(Socket client, Socket target) {
            this.client = client;
            this.target = target;
        }

        void relay(Socket from, Socket to) {
            byte[] buffer = new byte[16 * 1024];
            try (InputStream in = from.getInputStream()) {
                OutputStream out = to.getOutputStream();
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (silenced) continue;
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException closed) {
                // The relay or one of its ends was closed; this direction ends with it.
            }
        }

        void close() throws IOException {
            try {
                client.close();
            } finally {
                target.close();
            }
        }
    }
}

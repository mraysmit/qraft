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

package dev.mars.qraft.client.api;

import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.health.ExpiryScheduler;
import dev.mars.qraft.server.http.HttpApiServer;
import dev.mars.qraft.server.session.SessionTtlExpiry;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.NodeCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One server for the client library's tests: a single-node leader with the nodes {@code host-a} and {@code host-b}
 * registered, the leader's session TTL timers, and its HTTP API.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
final class LibraryServerFixture implements AutoCloseable {
    private final RaftExecutorsFixture runtime;
    private final ScheduledExecutorService ttlExecutor = Executors.newSingleThreadScheduledExecutor();
    private RaftNode node;
    private SessionTtlExpiry ttl;
    private HttpApiServer server;

    private LibraryServerFixture() {
        this.runtime = RaftExecutorsFixture.create();
    }

    static LibraryServerFixture start() throws Exception {
        LibraryServerFixture fixture = new LibraryServerFixture();
        try {
            fixture.open();
        } catch (Exception failure) {
            fixture.close();
            throw failure;
        }
        return fixture;
    }

    private void open() throws Exception {
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("library-node").clusterNodes(Set.of("library-node")).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        for (String name : List.of("host-a", "host-b")) {
            node.submitCommand(NodeCommand.register(Node.of(name, "10.0.0.1", null, null, Map.of()), Instant.now()))
                    .get(10, TimeUnit.SECONDS);
        }
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null);
        ttl = SessionTtlExpiry.attach(node, store.getSessions(), node::submitCommand,
                ExpiryScheduler.of(ttlExecutor), Clock.systemUTC(), Duration.ofSeconds(1));
        server.useSessionTtl(ttl);
        server.start().get(10, TimeUnit.SECONDS);
    }

    URI address() {
        return URI.create("http://127.0.0.1:" + server.port());
    }

    int port() {
        return server.port();
    }

    @Override
    public void close() throws Exception {
        try {
            if (ttl != null) ttl.close();
            if (server != null) server.close();
            if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        } finally {
            ttlExecutor.shutdownNow();
            runtime.close().get(10, TimeUnit.SECONDS);
        }
    }
}

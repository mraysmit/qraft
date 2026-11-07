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
import dev.mars.qraft.common.tls.TlsMaterial;
import dev.mars.qraft.common.tls.TlsSettings;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.testing.fault.IntentionalErrorFixture;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests mutual TLS on the Raft port (transport security list, decision 4, Phase 5): three servers with certificates
 * from the cluster's CA elect a leader and replicate; a server whose certificate another CA signed takes no part,
 * and one that speaks without TLS cannot reach them; and a server whose certificate names another host is refused
 * while a peer checks the host it dialled, and takes part when that check is off.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class GrpcRaftTlsTest {
    private static final long HEARTBEAT_MS = 200;

    private RaftExecutorsFixture runtime;
    private final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
    private final Map<String, String> addresses = new ConcurrentHashMap<>();
    private final List<Member> members = new ArrayList<>();
    private RaftConfiguration configuration;

    private record Member(String id, RaftNode node, GrpcRaftServer server, ManualRaftTimersHelper timers,
                          QraftStateStore state) {
        void stop() throws Exception {
            server.stop().get(10, TimeUnit.SECONDS);
            node.stop().get(10, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void setUp() {
        runtime = RaftExecutorsFixture.create();
    }

    @AfterEach
    void tearDown() throws Exception {
        Exception failure = null;
        for (Member member : members) {
            try {
                member.stop();
            } catch (Exception error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        runtime.close().get(10, TimeUnit.SECONDS);
        if (failure != null) throw failure;
    }

    static Path fixture(String name) {
        try {
            return Path.of(GrpcRaftTlsTest.class.getResource("/tls/" + name).toURI());
        } catch (Exception e) {
            throw new IllegalStateException("test TLS fixture " + name + " is missing", e);
        }
    }

    private static Optional<RaftTls> tls(String ca, String certificate, boolean verifyServerHostname) {
        return Optional.of(new RaftTls(TlsMaterial.load(new TlsSettings("server.tls", fixture(ca),
                Optional.of(fixture(certificate + ".pem")), Optional.of(fixture(certificate + ".key")),
                List.of("TLSv1.3", "TLSv1.2"))), verifyServerHostname));
    }

    @Test
    void threeServersWithCertificatesFromTheCaElectAndReplicate() throws Exception {
        Optional<RaftTls> server = tls("ca.pem", "server", true);
        List<Member> cluster = startCluster(Map.of("node1", server, "node2", server, "node3", server));

        Member leader = elect(cluster.get(0));
        assertInstanceOf(RaftCommandResult.Success.class, submit(leader, "replicated", "over-tls"));
        leader.timers().firePeriodic(HEARTBEAT_MS);
        for (Member member : cluster) {
            awaitTrue(() -> "over-tls".equals(member.state().getMetadata("replicated")),
                    member.id() + " applies the command replicated over mutual TLS");
        }
    }

    @Test
    void aServerWithACertificateFromAnotherCaTakesNoPart() throws Exception {
        Optional<RaftTls> server = tls("ca.pem", "server", true);
        List<Member> cluster = startCluster(Map.of("node1", server, "node2", server,
                "node3", tls("other-ca.pem", "other-client", true)));

        assertOutsideTheCluster(cluster);
    }

    @Test
    void aServerWithoutTlsCannotReachServersThatRequireIt() throws Exception {
        // node3 speaks plaintext to two servers that require mutual TLS, so its pre-votes reach neither, and it fails
        // to retrieve them as from a partitioned peer. No TLS server campaigns, so none dials node3 (a TLS dial to a
        // plaintext gRPC port makes that port log a stack trace through java.util.logging, which is not this test's).
        IntentionalErrorsHelper.expect(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE);
        Optional<RaftTls> server = tls("ca.pem", "server", true);
        List<Member> cluster = startCluster(Map.of("node1", server, "node2", server, "node3", Optional.empty()));
        Member node3 = cluster.get(2);

        node3.timers().fireElectionTimeout();
        Thread.sleep(1_000);

        assertTrue(node3.node().getState() != RaftNode.State.LEADER, "node3 wins no pre-vote");
        assertEquals(0, node3.node().getCurrentTerm(), "a pre-vote no one answers starts no term");
        assertEquals(0, cluster.get(0).node().getCurrentTerm(), "node1 heard nothing from node3");
        assertEquals(0, cluster.get(1).node().getCurrentTerm(), "node2 heard nothing from node3");
    }

    @Test
    void aServerWhoseCertificateNamesAnotherHostIsNotReachedWhileTheHostIsChecked() throws Exception {
        Optional<RaftTls> server = tls("ca.pem", "server", true);
        List<Member> cluster = startCluster(Map.of("node1", server, "node2", server,
                "node3", tls("ca.pem", "wrong-host", true)));

        assertOutsideTheCluster(cluster);
    }

    @Test
    void withTheHostCheckOffAServerWhoseCertificateNamesAnotherHostTakesPart() throws Exception {
        Optional<RaftTls> server = tls("ca.pem", "server", false);
        List<Member> cluster = startCluster(Map.of("node1", server, "node2", server,
                "node3", tls("ca.pem", "wrong-host", false)));

        Member leader = elect(cluster.get(0));
        assertInstanceOf(RaftCommandResult.Success.class, submit(leader, "replicated", "unchecked-host"));
        leader.timers().firePeriodic(HEARTBEAT_MS);
        awaitTrue(() -> "unchecked-host".equals(cluster.get(2).state().getMetadata("replicated")),
                "node3 applies the command once hostnames are not checked");
    }

    /**
     * node1 leads with node2 and commits, and node3, which cannot be reached over mutual TLS, has nothing. node1's
     * pre-vote, vote and appends to node3 fail as they do to a partitioned peer, and are declared for node3 alone.
     */
    private void assertOutsideTheCluster(List<Member> cluster) throws Exception {
        IntentionalErrorsHelper.expect(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE, "node3");
        Member leader = elect(cluster.get(0));
        assertInstanceOf(RaftCommandResult.Success.class, submit(leader, "replicated", "without-node3"));
        for (int round = 0; round < 5; round++) {
            leader.timers().firePeriodic(HEARTBEAT_MS);
            Thread.sleep(100);
        }
        awaitTrue(() -> "without-node3".equals(cluster.get(1).state().getMetadata("replicated")),
                "node2 applies the command");
        assertNull(cluster.get(2).state().getMetadata("replicated"), "node3 received nothing");
        assertEquals(null, cluster.get(2).node().getLeaderId(), "node3 follows no leader");
    }

    private List<Member> startCluster(Map<String, Optional<RaftTls>> tlsById) throws Exception {
        Map<String, Optional<RaftTls>> ordered = new LinkedHashMap<>();
        for (String id : List.of("node1", "node2", "node3")) ordered.put(id, tlsById.get(id));
        Set<String> memberIds = ordered.keySet();
        List<Member> cluster = new ArrayList<>();
        for (Map.Entry<String, Optional<RaftTls>> entry : ordered.entrySet()) {
            String id = entry.getKey();
            GrpcRaftTransport transport = new GrpcRaftTransport(runtime.stateLoop(), id, addresses, 10, 1000,
                    entry.getValue());
            ManualRaftTimersHelper timers = new ManualRaftTimersHelper(runtime, clock);
            QraftStateStore state = new QraftStateStore();
            RaftNode node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                    .nodeId(id).clusterNodes(Set.copyOf(memberIds)).serverId(ManualRaftClusterFixture.serverIdOf(id))
                    .transport(transport).stateMachine(state).commandCodec(new ProtobufRaftCommandCodec())
                    .mode(RaftNodeMode.volatileMode()).snapshotEnabled(false)
                    .electionTimeout(1_000).heartbeatInterval(HEARTBEAT_MS).timerScheduler(timers)
                    .build();
            GrpcRaftServer server = new GrpcRaftServer(runtime.blockingTasks(), 0, node, null, entry.getValue());
            server.start().get(10, TimeUnit.SECONDS);
            addresses.put(id, "localhost:" + server.port());
            Member member = new Member(id, node, server, timers, state);
            cluster.add(member);
            members.add(member);
        }
        configuration = new RaftConfiguration(cluster.stream().map(member -> new RaftConfiguration.Server(
                ManualRaftClusterFixture.serverIdOf(member.id()), member.id(), addresses.get(member.id()), true))
                .toList());
        for (Member member : cluster) {
            member.node().start().get(10, TimeUnit.SECONDS);
            if (member.node().getConfiguration().isEmpty()) member.node().bootstrap(configuration).get(10, TimeUnit.SECONDS);
        }
        return cluster;
    }

    private static Member elect(Member candidate) throws Exception {
        candidate.timers().fireElectionTimeout();
        candidate.node().awaitState(RaftNode.State.LEADER, 10_000).get(15, TimeUnit.SECONDS);
        return candidate;
    }

    private static RaftCommandResult<?> submit(Member leader, String key, String value) throws Exception {
        return leader.node().submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put(key, value)))
                .get(10, TimeUnit.SECONDS);
    }

    private static void awaitTrue(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), description);
    }
}

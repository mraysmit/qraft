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

import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.controller.raft.grpc.JoinRequest;
import dev.mars.qraft.controller.raft.grpc.MembershipResponse;
import dev.mars.qraft.controller.raft.grpc.MembershipResponse.Status;
import dev.mars.qraft.controller.raft.grpc.RemoveServerRequest;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.testing.fault.IntentionalErrors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static dev.mars.qraft.controller.raft.ManualRaftCluster.await;
import static dev.mars.qraft.controller.raft.ManualRaftCluster.serverIdOf;
import static dev.mars.qraft.testing.fault.IntentionalError.RAFT_PEER_UNREACHABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link MembershipService}, which serves joins and removals on every server:
 * <ul>
 *   <li>the leader makes the change; any other server forwards the request to the leader, once;</li>
 *   <li>a removal needs the operator token, checked by the forwarding server and again by the leader, and
 *       is refused outright where no token is configured;</li>
 *   <li>a removal names the server by ID or by name, and the node's refusals come back as statuses.</li>
 * </ul>
 * Forwarded requests travel between the services of in-process nodes, as they would over the Raft port.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
class MembershipServiceTest {
    private static final Set<String> MEMBERS = Set.of("a", "b", "c");
    private static final String TOKEN = "operator-secret";
    private static final JoinRequest JOIN_D = JoinRequest.newBuilder()
            .setServerId(serverIdOf("d")).setName("d").setAddress("d").build();

    private JavaRuntime runtime;
    private ManualRaftCluster cluster;
    private RaftNode a;
    private RaftNode b;
    private final Map<String, MembershipService> services = new ConcurrentHashMap<>();
    private final List<String> forwardedTo = new CopyOnWriteArrayList<>();
    private final List<Boolean> markedForwarded = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        runtime = JavaRuntime.create();
        cluster = new ManualRaftCluster(runtime);
        InMemoryTransportSimulator.clearAllTransports();
        a = node("a");
        b = node("b");
        RaftNode c = node("c");
        ManualRaftCluster.startAll(a, b, c);
        services.put("a", new MembershipService(a, new Forwarder(), TOKEN));
        services.put("b", new MembershipService(b, new Forwarder(), TOKEN));
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            cluster.close();
        } finally {
            runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulator.clearAllTransports();
        }
    }

    @Test
    void theLeaderAdmitsAJoiningServer() throws Exception {
        IntentionalErrors.expect(RAFT_PEER_UNREACHABLE, 1);
        leadWithACommit();

        assertEquals(Status.JOINED, await(services.get("a").join(JOIN_D)).getStatus());

        assertTrue(a.getConfiguration().orElseThrow().server(serverIdOf("d")).isPresent());
        assertTrue(forwardedTo.isEmpty());
    }

    @Test
    void aFollowerForwardsAJoinToTheLeader() throws Exception {
        IntentionalErrors.expect(RAFT_PEER_UNREACHABLE, 1);
        leadWithACommit();

        assertEquals(Status.JOINED, await(services.get("b").join(JOIN_D)).getStatus());

        assertEquals(List.of("a"), forwardedTo);
        assertEquals(List.of(true), markedForwarded, "the leader's copy is marked, so it goes no further");
        assertTrue(a.getConfiguration().orElseThrow().server(serverIdOf("d")).isPresent());
    }

    @Test
    void aForwardedRequestIsNotForwardedAgain() throws Exception {
        leadWithACommit();

        MembershipResponse answer = await(services.get("b").join(JOIN_D.toBuilder().setForwarded(true).build()));

        assertEquals(Status.NO_LEADER, answer.getStatus());
        assertTrue(forwardedTo.isEmpty());
    }

    @Test
    void withNoLeaderKnownAJoinWaits() throws Exception {
        assertEquals(Status.NO_LEADER, await(services.get("b").join(JOIN_D)).getStatus());
        assertTrue(forwardedTo.isEmpty());
    }

    @Test
    void aForwarderThatThrowsIsAnsweredAsNoLeaderReachable() throws Exception {
        leadWithACommit();
        MembershipService follower = new MembershipService(b, new ThrowingForwarder(), TOKEN);

        MembershipResponse join = await(follower.join(JOIN_D));
        MembershipResponse removal = await(follower.remove(removal().setName("c").setToken(TOKEN).build()));

        assertEquals(Status.NO_LEADER, join.getStatus(), join.getMessage());
        assertTrue(join.getMessage().contains("could not be reached"), join.getMessage());
        assertEquals(Status.NO_LEADER, removal.getStatus(), removal.getMessage());
    }

    @Test
    void aMemberAtANewAddressIsToldItsAddressWasUpdated() throws Exception {
        leadWithACommit();

        MembershipResponse answer = await(services.get("a").join(JoinRequest.newBuilder()
                .setServerId(serverIdOf("c")).setName("c").setAddress("c-moved").build()));

        assertEquals(Status.ALREADY_MEMBER, answer.getStatus());
        assertTrue(answer.getMessage().contains("c-moved"), answer.getMessage());
    }

    @Test
    void aRejoiningServerCannotEvictAnExistingMember() throws Exception {
        leadWithACommit();

        MembershipResponse answer = await(services.get("b").join(JoinRequest.newBuilder()
                .setServerId("new-id-of-c").setName("c").setAddress("c").build()));

        assertEquals(Status.REFUSED, answer.getStatus());
        assertTrue(a.getConfiguration().orElseThrow().server(serverIdOf("c")).isPresent());
    }

    @Test
    void invalidJoinsAreRefusedBeforeForwardingOrChangingMembership() throws Exception {
        leadWithACommit();
        for (String receiver : List.of("a", "b")) {
            for (JoinRequest invalid : List.of(
                    JOIN_D.toBuilder().clearServerId().setName("c").setAddress("c").build(),
                    JOIN_D.toBuilder().clearName().build(), JOIN_D.toBuilder().clearAddress().build())) {
                assertEquals(Status.REFUSED, await(services.get(receiver).join(invalid)).getStatus());
            }
        }
        assertTrue(forwardedTo.isEmpty());
        assertTrue(a.getConfiguration().orElseThrow().server(serverIdOf("c")).isPresent());
    }

    @Test
    void aFollowerForwardsAnAuthorizedRemovalToTheLeader() throws Exception {
        leadWithACommit();

        MembershipResponse answer = await(services.get("b").remove(removal().setName("c").setToken(TOKEN).build()));

        assertEquals(Status.REMOVED, answer.getStatus(), answer.getMessage());
        assertEquals(List.of("a"), forwardedTo);
        assertEquals(List.of(true), markedForwarded);
        assertTrue(a.getConfiguration().orElseThrow().serverNamed("c").isEmpty());
    }

    @Test
    void aRemovalWithoutTheTokenIsRefusedBeforeItIsForwarded() throws Exception {
        leadWithACommit();

        assertEquals(Status.UNAUTHORIZED,
                await(services.get("b").remove(removal().setName("c").build())).getStatus());
        assertEquals(Status.UNAUTHORIZED,
                await(services.get("b").remove(removal().setName("c").setToken("wrong").build())).getStatus());

        assertTrue(forwardedTo.isEmpty());
        assertTrue(a.getConfiguration().orElseThrow().serverNamed("c").isPresent());
    }

    @Test
    void theLeaderChecksAForwardedTokenItself() throws Exception {
        leadWithACommit();
        services.put("a", new MembershipService(a, new Forwarder(), "the-leaders-token"));

        MembershipResponse answer = await(services.get("b").remove(removal().setName("c").setToken(TOKEN).build()));

        assertEquals(Status.UNAUTHORIZED, answer.getStatus());
        assertTrue(a.getConfiguration().orElseThrow().serverNamed("c").isPresent());
    }

    @Test
    void withNoTokenConfiguredEveryRemovalIsRefused() throws Exception {
        leadWithACommit();
        MembershipService unconfigured = new MembershipService(a, new Forwarder(), null);

        MembershipResponse answer = await(unconfigured.remove(removal().setName("c").setToken("").build()));

        assertEquals(Status.UNAUTHORIZED, answer.getStatus());
        assertTrue(answer.getMessage().contains("no operator token is configured"), answer.getMessage());
    }

    @Test
    void aRemovalNamesTheServerByIdOrByName() throws Exception {
        leadWithACommit();
        MembershipService leader = services.get("a");

        assertEquals(Status.REMOVED,
                await(leader.remove(removal().setServerId(serverIdOf("c")).setToken(TOKEN).build())).getStatus());
        assertEquals(Status.NOT_FOUND,
                await(leader.remove(removal().setName("unknown").setToken(TOKEN).build())).getStatus());
        assertEquals(Status.REFUSED, await(leader.remove(removal().setToken(TOKEN).build())).getStatus());
    }

    @Test
    void theNodesRefusalComesBackWithItsReason() throws Exception {
        IntentionalErrors.expect(RAFT_PEER_UNREACHABLE, 2);
        InMemoryTransportSimulator.createPartition(Set.of("a", "b"), Set.of("c"));
        leadWithACommit();

        MembershipResponse answer = await(services.get("a").remove(removal().setName("b").setToken(TOKEN).build()));

        assertEquals(Status.REFUSED, answer.getStatus());
        assertTrue(answer.getMessage().contains("quorum"), answer.getMessage());
        assertFalse(a.getConfiguration().orElseThrow().serverNamed("b").isEmpty());
    }

    private static RemoveServerRequest.Builder removal() {
        return RemoveServerRequest.newBuilder();
    }

    private void leadWithACommit() throws Exception {
        cluster.elect(a);
        await(a.submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put("k", "v"))));
    }

    private RaftNode node(String name) {
        return cluster.add(cluster.builder(name, MEMBERS, new InMemoryTransportSimulator(name),
                new QraftStateStore(), RaftNodeMode.volatileMode()));
    }

    /** Throws as it is asked to forward, as a transport given a leader it cannot address once did. */
    private static final class ThrowingForwarder implements RaftTransport {
        @Override
        public Future<MembershipResponse> join(String targetId, JoinRequest request) {
            throw new IllegalArgumentException("Unknown node: " + targetId);
        }

        @Override
        public Future<MembershipResponse> removeServer(String targetId, RemoveServerRequest request) {
            throw new IllegalArgumentException("Unknown node: " + targetId);
        }

        @Override public void start(Consumer<RaftMessage> messageHandler) { }
        @Override public void stop() { }

        @Override
        public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<AppendEntriesResponse> sendAppendEntries(String targetId, AppendEntriesRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<InstallSnapshotResponse> sendInstallSnapshot(String targetId, InstallSnapshotRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    /** Carries a forwarded request to the target's service, as the Raft port would. */
    private final class Forwarder implements RaftTransport {
        @Override
        public Future<MembershipResponse> join(String targetId, JoinRequest request) {
            forwardedTo.add(targetId);
            markedForwarded.add(request.getForwarded());
            return services.get(targetId).join(request);
        }

        @Override
        public Future<MembershipResponse> removeServer(String targetId, RemoveServerRequest request) {
            forwardedTo.add(targetId);
            markedForwarded.add(request.getForwarded());
            return services.get(targetId).remove(request);
        }

        @Override public void start(Consumer<RaftMessage> messageHandler) { }
        @Override public void stop() { }

        @Override
        public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<AppendEntriesResponse> sendAppendEntries(String targetId, AppendEntriesRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<InstallSnapshotResponse> sendInstallSnapshot(String targetId, InstallSnapshotRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}

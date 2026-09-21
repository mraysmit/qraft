package dev.mars.qraft.runtime;

import dev.mars.qraft.agent.AgentStatus;
import dev.mars.qraft.agent.QraftAgent;
import dev.mars.qraft.agent.config.AgentConfiguration;
import dev.mars.qraft.controller.http.HttpApiServer;
import dev.mars.qraft.controller.raft.RaftMessage;
import dev.mars.qraft.controller.raft.RaftNode;
import dev.mars.qraft.controller.raft.RaftNodeMode;
import dev.mars.qraft.controller.raft.RaftTransport;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentControllerContractTest {
    private JavaRuntime runtime;
    private RaftNode node;
    private HttpApiServer server;
    private QraftAgent agent;

    @AfterEach
    void closeResources() throws Exception {
        if (agent != null) agent.shutdown().get(5, TimeUnit.SECONDS);
        if (server != null) server.close();
        if (node != null) node.stop().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (runtime != null) runtime.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void realAgentCompletesRegistrationHeartbeatAndDeregistrationAgainstController() throws Exception {
        QraftStateStore store = startController();
        AgentConfiguration configuration = AgentConfiguration.builder()
                .agentId("contract-agent")
                .hostname("contract-host")
                .address("127.0.0.1")
                .agentPort(freePort())
                .controllerUrl("http://localhost:" + server.port() + "/api/v1")
                .heartbeatInterval(25)
                .httpConnectionTimeout(1_000)
                .build();
        agent = new QraftAgent(configuration);

        assertTrue(agent.start().get(5, TimeUnit.SECONDS),
                () -> "the real agent rejected the controller response; replicated state="
                        + store.findAgent("contract-agent"));
        assertTrue(agent.healthService().isReady());
        waitUntil(() -> store.findAgent("contract-agent")
                .filter(info -> info.getStatus() == AgentStatus.HEALTHY && info.getLastHeartbeat() != null)
                .isPresent());

        assertTrue(agent.shutdown().get(5, TimeUnit.SECONDS));
        waitUntil(() -> store.findAgent("contract-agent").isEmpty());
        assertFalse(agent.isRunning());
        assertEquals(0, store.getAgents().size());
    }

    private QraftStateStore startController() throws Exception {
        runtime = JavaRuntime.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("contract-node")
                .clusterNodes(Set.of("contract-node"))
                .transport(new SingleNodeTransport())
                .stateMachine(store)
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode())
                .electionTimeout(25)
                .heartbeatInterval(10_000)
                .build();
        node.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        waitUntil(node::isLeader);
        server = new HttpApiServer(0, node, store);
        server.start().get(5, TimeUnit.SECONDS);
        return store;
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "condition was not met before the deadline");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class SingleNodeTransport implements RaftTransport {
        @Override public void start(Consumer<RaftMessage> messageHandler) { }
        @Override public void stop() { }
        @Override public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return Future.failedFuture("single-node transport has no peers");
        }
        @Override public Future<AppendEntriesResponse> sendAppendEntries(
                String targetId, AppendEntriesRequest request) {
            return Future.failedFuture("single-node transport has no peers");
        }
        @Override public Future<InstallSnapshotResponse> sendInstallSnapshot(
                String targetId, InstallSnapshotRequest request) {
            return Future.failedFuture("single-node transport has no peers");
        }
    }
}

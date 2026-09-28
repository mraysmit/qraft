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

import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds in-memory Raft nodes on {@link ManualRaftTimers} for tests, and stops every one of them on
 * {@link #close()}, which also releases their storage, even when the test failed.
 *
 * <p>A node elects itself only when {@link #elect} fires its election timeout, and a leader heartbeats only
 * when {@link #heartbeatUntil} fires it. The election timeout is set long relative to the heartbeat because
 * it sizes the leader's check-quorum window in heartbeat rounds: fifty rounds, paced at one per
 * {@value #HEARTBEAT_POLL_MS} ms of polling, so a leader steps down only if a majority is silent for as long
 * as a test is prepared to wait. Every wait is bounded at ten seconds.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-28
 * @version 1.0
 */
public final class ManualRaftCluster implements AutoCloseable {
    public static final long HEARTBEAT_MS = 200;
    public static final long ELECTION_TIMEOUT_MS = 10_000;
    private static final long HEARTBEAT_POLL_MS = 200;
    private static final long WAIT_SECONDS = 10;

    private final JavaRuntime runtime;
    private final List<RaftNode> nodes = new ArrayList<>();
    private final Map<RaftNode, ManualRaftTimers> timers = new ConcurrentHashMap<>();

    public ManualRaftCluster(JavaRuntime runtime) {
        this.runtime = runtime;
    }

    /** A builder on this cluster's runtime and timing, with the protobuf codec; finish it with {@link #add}. */
    public RaftNode.Builder builder(String nodeId, Set<String> members, RaftTransport transport, QraftStateStore store,
                             RaftNodeMode mode) {
        return RaftNode.builder().runtime(runtime).nodeId(nodeId).clusterNodes(members).transport(transport)
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(mode)
                .electionTimeout(ELECTION_TIMEOUT_MS).heartbeatInterval(HEARTBEAT_MS);
    }

    /** Builds the node on manual timers and tracks it, so {@link #close()} stops it. */
    public RaftNode add(RaftNode.Builder builder) {
        ManualRaftTimers nodeTimers = new ManualRaftTimers(runtime);
        RaftNode node = builder.timerScheduler(nodeTimers).build();
        synchronized (nodes) {
            nodes.add(node);
        }
        timers.put(node, nodeTimers);
        return node;
    }

    public ManualRaftTimers timers(RaftNode node) {
        return timers.get(node);
    }

    public static void startAll(RaftNode... members) throws Exception {
        for (RaftNode node : members) await(node.start());
    }

    /** Fires {@code candidate}'s election timeout and waits until it leads. */
    public RaftNode elect(RaftNode candidate) throws Exception {
        timers(candidate).fireElectionTimeout();
        // The outer bound outlasts the node's own, so a failed election reports the node's timeout.
        candidate.awaitState(RaftNode.State.LEADER, TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
                .toCompletionStage().toCompletableFuture().get(WAIT_SECONDS + 5, TimeUnit.SECONDS);
        return candidate;
    }

    /**
     * Fires {@code leader}'s heartbeat until {@code condition} holds. A heartbeat carries the leader's commit
     * index, and any entries or snapshot a follower lacks; catching up can take several rounds.
     */
    public void heartbeatUntil(RaftNode leader, BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                timers(leader).firePeriodic(HEARTBEAT_MS);
            } catch (IllegalStateException disarmed) {
                throw new AssertionError(leader.getNodeId() + " stopped leading (" + leader.getState()
                        + ") before: " + description, disarmed);
            }
            long round = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_POLL_MS);
            while (!condition.getAsBoolean() && System.nanoTime() < Math.min(round, deadline)) Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), description);
    }

    /**
     * Fires {@code leader}'s heartbeat until it steps down for lost quorum, for a leader cut off from every
     * peer: no reply can arrive, so it steps down once its check-quorum window of rounds has passed. A fire
     * made while the previous round is still being handled is ignored, so rounds are counted by the node, not
     * here; stepping down cancels the heartbeat, which ends the firing.
     */
    public void heartbeatUntilSteppedDown(RaftNode leader) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (leader.isLeader() && System.nanoTime() < deadline) {
            try {
                timers(leader).firePeriodic(HEARTBEAT_MS);
            } catch (IllegalStateException disarmed) {
                break; // it stepped down between the check and the fire
            }
        }
        assertFalse(leader.isLeader(), "an isolated leader steps down after its check-quorum window");
    }

    /** Waits for {@code future}; the bound only diagnoses a hang. */
    public static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** Stops every node built here, reporting the first failure with the others suppressed. */
    @Override
    public void close() throws Exception {
        List<RaftNode> built;
        synchronized (nodes) {
            built = List.copyOf(nodes);
        }
        Exception failure = null;
        for (RaftNode node : built) {
            try {
                await(node.stop());
            } catch (Exception error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}

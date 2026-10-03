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
import dev.mars.qraft.controller.state.ConfigurationCommand;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds in-memory Raft nodes on {@link ManualRaftTimers} for tests, and stops every one of them on
 * {@link #close()}, which also releases their storage, even when the test failed.
 *
 * <p>A node elects itself only when {@link #elect} fires its election timeout, and a leader heartbeats only
 * when {@link #heartbeatUntil} fires it. The election timeout is set long relative to the heartbeat because
 * it sizes the leader's check-quorum window in heartbeat rounds: fifty rounds, each of which settles before
 * the next is fired, so a leader steps down only if a majority is silent for fifty whole rounds; a follower
 * that is merely slow still answers within its round. Every wait is bounded at ten seconds.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-28
 * @version 1.1
 */
public final class ManualRaftCluster implements AutoCloseable {
    public static final long HEARTBEAT_MS = 200;
    public static final long ELECTION_TIMEOUT_MS = 10_000;
    /** Passes over an idle network and state loop before a heartbeat round counts as settled. */
    private static final int QUIET_PASSES = 3;
    private static final long WAIT_SECONDS = 10;

    private final JavaRuntime runtime;
    private final AtomicLong clock = new AtomicLong();
    private final List<RaftNode> nodes = new ArrayList<>();
    private final Map<RaftNode, ManualRaftTimers> timers = new ConcurrentHashMap<>();

    public ManualRaftCluster(JavaRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * A builder on this cluster's runtime and timing, with the protobuf codec, for a bootstrapped member of
     * {@code members}: its server ID is {@link #serverIdOf} its name, and a node with no Raft state bootstraps
     * with {@link #configurationOf} the members, as every real member of a new cluster does. Finish it with
     * {@link #add}.
     */
    public RaftNode.Builder builder(String nodeId, Set<String> members, RaftTransport transport, QraftStateStore store,
                             RaftNodeMode mode) {
        return unconfiguredBuilder(nodeId, members, transport, store, mode)
                .serverId(serverIdOf(nodeId)).initialConfiguration(configurationOf(members));
    }

    /**
     * As {@link #builder}, but with a random server ID and no initial configuration, for a test that sets its
     * own or that needs a node with none; a sole member still bootstraps itself.
     */
    public RaftNode.Builder unconfiguredBuilder(String nodeId, Set<String> members, RaftTransport transport,
                                               QraftStateStore store, RaftNodeMode mode) {
        return RaftNode.builder().runtime(runtime).nodeId(nodeId).clusterNodes(members).transport(transport)
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(mode)
                .electionTimeout(ELECTION_TIMEOUT_MS).heartbeatInterval(HEARTBEAT_MS);
    }

    /** The server ID {@link #builder} gives the member named {@code name}; a simulated peer answers with it. */
    public static String serverIdOf(String name) {
        return UUID.nameUUIDFromBytes(("qraft-test:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Every member a voter, each addressed by its name, with the server IDs {@link #builder} gives them. */
    public static RaftConfiguration configurationOf(Set<String> members) {
        return new RaftConfiguration(members.stream()
                .map(name -> new RaftConfiguration.Server(serverIdOf(name), name, name, true))
                .toList());
    }

    /** The bootstrap entry at index 1 in term 0 that a member of {@code members} holds, for a stored log. */
    public static LogEntryData bootstrapEntry(Set<String> members) {
        return new LogEntryData(1, 0, new ProtobufRaftCommandCodec().serialize(
                new ConfigurationCommand(configurationOf(members))));
    }

    /** A state machine snapshot as a node stores and sends it, recording {@code members}' configuration. */
    public static byte[] snapshotOf(Set<String> members, byte[] stateMachineSnapshot) {
        return SnapshotEnvelope.wrap(configurationOf(members), stateMachineSnapshot);
    }

    /** Builds the node on manual timers and tracks it, so {@link #close()} stops it. */
    public RaftNode add(RaftNode.Builder builder) {
        ManualRaftTimers nodeTimers = new ManualRaftTimers(runtime, clock);
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
        settle(System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS));
        return candidate;
    }

    /**
     * Fires {@code leader}'s heartbeat until {@code condition} holds. A heartbeat carries the leader's commit
     * index, and any entries or snapshot a follower lacks; catching up can take several rounds. After each
     * heartbeat it waits only until the round has settled (see {@link #settle}), not for a fixed time, so a
     * round costs the simulated latency and no more.
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
            settle(deadline);
        }
        settle(deadline);
        assertTrue(condition.getAsBoolean(), description);
    }

    /**
     * Waits until the simulated network and the shared state loop are quiescent.
     * Tests that drive several nodes directly use this between deterministic timer steps.
     */
    void settle() throws InterruptedException {
        settle(System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS));
    }

    /**
     * Waits until no simulated message is in flight and the state loop has run everything queued, on
     * {@value #QUIET_PASSES} passes in a row, since a node's handling can send more. It only saves time: a
     * round that settles too early costs one more heartbeat in {@link #heartbeatUntil}, never a wrong answer.
     */
    private void settle(long deadline) throws InterruptedException {
        int quiet = 0;
        while (quiet < QUIET_PASSES && System.nanoTime() < deadline) {
            if (InMemoryTransportSimulator.hasMessagesInFlight()) {
                quiet = 0;
                Thread.sleep(1);
                continue;
            }
            drainStateLoop();
            quiet = InMemoryTransportSimulator.hasMessagesInFlight() ? 0 : quiet + 1;
        }
    }

    /** Returns once the state loop has run every task queued before this call. */
    private void drainStateLoop() throws InterruptedException {
        CompletableFuture<Void> marker = new CompletableFuture<>();
        runtime.runOnContext(ignored -> marker.complete(null));
        try {
            marker.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException stalled) {
            throw new AssertionError("the state loop did not run a queued task within " + WAIT_SECONDS + " s",
                    stalled);
        }
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

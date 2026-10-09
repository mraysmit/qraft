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

import dev.mars.qraft.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.raft.grpc.DescribeResponse;
import dev.mars.qraft.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;
import dev.mars.raftlog.storage.RaftStorage;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import dev.mars.raftlog.storage.AppendPlan;
import dev.mars.qraft.raft.api.SnapshotStore;
import dev.mars.qraft.raft.api.SnapshotStore.PublicationOutcome;
import dev.mars.qraft.raft.api.SnapshotStore.SnapshotData;
import dev.mars.qraft.raft.api.SnapshotStore.SnapshotPublicationException;
import dev.mars.qraft.raft.api.CommandCodec;
import com.google.protobuf.ByteString;
import dev.mars.qraft.common.async.Future;
import dev.mars.qraft.common.async.JavaRuntime;
import dev.mars.qraft.common.async.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import org.slf4j.MDC;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import static java.util.Objects.requireNonNull;

/**
 * Raft node implementation with durable WAL storage.
 * Raft state transitions run on the dedicated Java runtime state loop.
 * 
 * <p>Implements the "Persist-before-response" rule for all state-changing
 * Raft operations:
 * <ul>
 *   <li>Vote is never granted until metadata is durable</li>
 *   <li>AppendEntries is never ACK'd until log entries are durable</li>
 *   <li>In-memory state is only mutated AFTER durability is confirmed</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 1.0
 * @since 2025-08-20
 */
public class RaftNode {

    private static final Logger logger = LoggerFactory.getLogger(RaftNode.class);

    // ========== RAFT NODE STATES ==========

    public enum State {
        FOLLOWER, CANDIDATE, LEADER
    }

    // ========== NODE CONFIGURATION ==========

    private final JavaRuntime runtime;
    private final String nodeId;
    /** Durable identity from the data directory; every Raft message this node sends names it. */
    private final String serverId;
    /** The configuration to bootstrap with when there is no Raft state; null if none was given. */
    private final RaftConfiguration initialConfiguration;
    /** The listed servers' names and Raft addresses, in name order, as this server's configuration gives them. */
    private final Map<String, String> listedServers;
    private final Set<String> clusterNodes;
    private final RaftTransport transport;
    private final RaftLogApplicator stateMachine;
    private final CommandCodec<RaftCommand> commandCodec;
    private final RaftPersistence persistence;
    private final RaftTransitionSequencer transitionSequencer;
    private final RaftTimerScheduler timerScheduler;

    // ========== PERSISTENT STATE ==========
    private volatile long currentTerm = 0;
    private String votedFor = null;
    private final List<LogEntry> log = new ArrayList<>();

    // ========== CONFIGURATION STATE ==========
    /** Configuration entries in the in-memory log, by index. The latest, committed or not, is in force. */
    private final NavigableMap<Long, RaftConfiguration> logConfigurations = new TreeMap<>();
    /** The configuration at the snapshot boundary, from the snapshot's envelope; null before any snapshot. */
    private RaftConfiguration snapshotConfiguration;
    /** The configuration in force, published for readers off the state loop; null until there is one. */
    private volatile RaftConfiguration configuration;

    // ========== VOLATILE STATE ==========
    private volatile State state = State.FOLLOWER;
    private volatile long commitIndex = 0;
    private volatile long lastApplied = 0;
    private volatile String currentLeaderId = null;
    /** Leader contact remains live for the minimum election timeout. */
    private boolean leaderContactActive;
    private long lastLeaderContactNanos;

    // ========== SNAPSHOT STATE ==========
    /** The log index of the last entry included in the most recent snapshot.
     *  All in-memory log entries have indices > snapshotLastIndex.
     *  Array offset: log.get(logIndex - snapshotLastIndex). */
    private long snapshotLastIndex = 0;
    /** The term of the last entry included in the most recent snapshot. */
    private long snapshotLastTerm = 0;
    /** Timer ID for periodic snapshot eligibility checks. */
    private long snapshotTimerId = -1;

    // ========== LEADER STATE ==========
    private final Map<String, Long> nextIndex = new HashMap<>();
    private final Map<String, Long> matchIndex = new HashMap<>();
    private final Map<Long, Promise<RaftCommandResult<?>>> pendingCommands = new ConcurrentHashMap<>();
    /** Monotonically identifies each locally acquired leadership. */
    private long leadershipGeneration = 0;

    // ========== TIMING AND CONTROL ==========
    private volatile boolean running = false;
    private volatile Throwable startupFailure;
    private final Object stopLock = new Object();
    private Promise<Void> startPromise;
    private Promise<Void> stopPromise;
    private int ownedAsyncOperations;
    private boolean ownedAsyncDraining;
    private Promise<Void> ownedAsyncDrainPromise;
    private long electionTimerId = -1;
    private long heartbeatTimerId = -1;
    private long electionTimerGeneration = 0;
    private long heartbeatTimerGeneration = 0;
    private long snapshotTimerGeneration = 0;
    private boolean heartbeatTimerTransitionPending = false;
    private boolean snapshotTimerTransitionPending = false;
    private final java.util.Set<String> unavailablePeers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ========== CHECK-QUORUM (state loop only) ==========
    // Leadership is measured in applied heartbeat rounds rather than wall-clock time, so a delayed
    // state loop cannot cause a false step-down.
    private long heartbeatRound;
    private final Map<String, Long> lastContactRound = new HashMap<>();

    // ========== PROMOTION (state loop only) ==========
    /** A non-voter counts as in contact while it has answered within this many heartbeat rounds. */
    static final long PROMOTION_CONTACT_ROUNDS = 2;
    /** The peers that have answered in this leadership; a peer that has not has no contact to count. */
    private final Set<String> answeredPeers = new HashSet<>();
    /** The round from which each non-voter has been continuously healthy. */
    private final Map<String, Long> healthySinceRound = new HashMap<>();

    // ========== STATE CHANGE LISTENERS ==========
    private final List<java.util.function.Consumer<State>> stateChangeListeners = new CopyOnWriteArrayList<>();

    // ========== EDGE METRICS ==========
    private LongCounter rpcCounter;

    // ========== CONFIGURATION PARAMETERS ==========
    private final long electionTimeoutMs;
    /** Heartbeat rounds covering one election timeout; older peer contact no longer counts. */
    private final long quorumContactRounds;
    private final long heartbeatIntervalMs;
    private final boolean snapshotEnabled;
    private final long snapshotThreshold;
    private final long snapshotCheckIntervalMs;
    private final long logHardLimit;
    /** Heartbeat rounds a non-voter must stay healthy before it is promoted. */
    private final long promotionStabilizationRounds;
    private final long promotionMaxTrailingEntries;

    // ========== INSTALL SNAPSHOT STATE ==========
    /** Maximum chunk size for InstallSnapshot RPC (default 1 MB). */
    static final int SNAPSHOT_CHUNK_SIZE = 1024 * 1024;
    /** Tracks in-progress snapshot installs from a leader (follower side). */
    private final Map<String, SnapshotChunkAssembler> pendingInstalls = new HashMap<>();
    /** Tracks uniquely owned snapshot sends to followers (leader side). */
    private final Map<String, OutboundSnapshotTransfer> outboundSnapshotTransfers = new HashMap<>();
    /** Prevents heartbeat-driven retry loops after a follower rejects snapshot persistence. */
    private final Map<String, Long> outboundSnapshotRetryAfterNanos = new HashMap<>();
    private long outboundSnapshotTransferSequence = 0;
    private static final long SNAPSHOT_RETRY_BACKOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    // ========== SNAPSHOT METRICS ==========
    private LongCounter snapshotCounter;
    private LongHistogram snapshotDuration;
    private LongCounter logCompactedEntries;
    private LongCounter installSnapshotSent;
    private LongCounter installSnapshotReceived;

    // ========== BUILDER ==========

    /**
     * Creates a new builder for constructing a {@link RaftNode}.
     *
     * <p>All infrastructure parameters are set via fluent methods. The six required
     * parameters: {@code runtime}, {@code nodeId}, {@code clusterNodes}, {@code transport},
     * {@code stateMachine}, and {@code mode} — are validated at {@link Builder#build()} time.
     *
     * <p>Usage:
     * <pre>{@code
     * // Minimal (volatile, default timing)
     * RaftNode node = RaftNode.builder()
     *     .runtime(runtime)
     *     .nodeId("node1")
     *     .clusterNodes(cluster)
     *     .transport(transport)
     *     .stateMachine(sm)
     *     .mode(RaftNodeMode.volatileMode())
     *     .build();
     *
     * // Production (durable, custom timing, snapshots)
     * RaftNode node = RaftNode.builder()
     *     .runtime(runtime)
     *     .nodeId("node1")
     *     .clusterNodes(cluster)
     *     .transport(transport)
     *     .stateMachine(sm)
     *     .mode(RaftNodeMode.durable(wal, snapshots))
     *     .electionTimeout(5000)
     *     .heartbeatInterval(1000)
     *     .snapshotEnabled(true)
     *     .snapshotThreshold(10000)
     *     .snapshotCheckInterval(60000)
     *     .build();
     * }</pre>
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link RaftNode}.
     *
     * <p>Every setter returns {@code this} for chaining. The six required fields
     * ({@code runtime}, {@code nodeId}, {@code clusterNodes}, {@code transport},
     * {@code stateMachine}, {@code mode}) are validated when {@link #build()} is called;
     * omitting any of them produces an {@link IllegalStateException}.
     */
    public static final class Builder {
        // Required (validated at build())
        private JavaRuntime runtime;
        private String nodeId;
        private Set<String> clusterNodes;
        private RaftTransport transport;
        private RaftLogApplicator stateMachine;
        private CommandCodec<RaftCommand> commandCodec;
        private RaftNodeMode mode;

        // Optional with defaults
        private String serverId;               // null = a new random UUID
        private RaftConfiguration initialConfiguration; // null = none, unless this node is the sole member
        private Map<String, String> addresses = Map.of(); // listed servers' Raft addresses; default: the name
        private long electionTimeoutMs = 5000;
        private long heartbeatIntervalMs = 1000;
        private Boolean snapshotEnabled;       // null = derive from mode
        private long snapshotThreshold = 10000;
        private long snapshotCheckIntervalMs = 60000;
        private long logHardLimit = 100000;
        private long promotionStabilizationMs = 10_000;
        private long promotionMaxTrailingEntries = 250;
        private RaftTimerScheduler timerScheduler;
        private int transitionQueueCapacity = 1024;

        private Builder() {}

        /** The Java runtime that owns the node state loop (required). */
        public Builder runtime(JavaRuntime runtime) { this.runtime = runtime; return this; }

        /** Unique node identifier (required). */
        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }

        /**
         * The server's durable Raft identity, kept in its data directory by
         * {@link dev.mars.qraft.raft.storage.ServerIdentity} (default: a new random UUID, which suits
         * a node whose storage does not outlive it).
         */
        public Builder serverId(String serverId) {
            if (serverId == null || serverId.isBlank()) throw new IllegalArgumentException("serverId must not be blank");
            this.serverId = serverId;
            return this;
        }

        /**
         * The configuration a node with no Raft state bootstraps with: it is written at index 1 in term 0 and
         * treated as committed. It must include this server. A sole member bootstraps itself without one.
         */
        public Builder initialConfiguration(RaftConfiguration initialConfiguration) {
            this.initialConfiguration = requireNonNull(initialConfiguration, "initialConfiguration");
            return this;
        }

        /**
         * The Raft addresses of the listed servers ({@link #clusterNodes}), as this server's configuration gives
         * them. It reports them to servers bootstrapping a new cluster (default: each server's name).
         */
        public Builder addresses(Map<String, String> addresses) {
            this.addresses = Map.copyOf(requireNonNull(addresses, "addresses"));
            return this;
        }

        /** Set of all node IDs in the cluster (required). */
        public Builder clusterNodes(Set<String> clusterNodes) { this.clusterNodes = clusterNodes; return this; }

        /** Transport for inter-node communication (required). */
        public Builder transport(RaftTransport transport) { this.transport = transport; return this; }

        /** State machine for applying committed entries (required). */
        public Builder stateMachine(RaftLogApplicator stateMachine) { this.stateMachine = stateMachine; return this; }

        /** Command codec for log payload serialization (required). */
        public Builder commandCodec(CommandCodec<RaftCommand> commandCodec) { this.commandCodec = commandCodec; return this; }

        /** Storage mode — {@link RaftNodeMode#volatileMode()} or {@link RaftNodeMode#durable} (required). */
        public Builder mode(RaftNodeMode mode) { this.mode = mode; return this; }

        /** Base election timeout in milliseconds (default: 5000). */
        public Builder electionTimeout(long ms) { this.electionTimeoutMs = ms; return this; }

        /** Heartbeat interval in milliseconds (default: 1000). */
        public Builder heartbeatInterval(long ms) { this.heartbeatIntervalMs = ms; return this; }

        /** Whether to enable automatic snapshots (default: true for durable, false for volatile). */
        public Builder snapshotEnabled(boolean enabled) { this.snapshotEnabled = enabled; return this; }

        /** Number of log entries between snapshots (default: 10000). */
        public Builder snapshotThreshold(long threshold) { this.snapshotThreshold = threshold; return this; }

        /** Interval between snapshot eligibility checks in ms (default: 60000). */
        public Builder snapshotCheckInterval(long ms) { this.snapshotCheckIntervalMs = ms; return this; }

        /** Maximum in-memory log entries before rejecting commands (default: 100000). */
        public Builder logHardLimit(long limit) { this.logHardLimit = limit; return this; }

        /**
         * How long a non-voter must stay healthy before the leader promotes it to a voter, in milliseconds,
         * counted in heartbeat rounds (default: 10000, Consul's {@code ServerStabilizationTime}).
         */
        public Builder promotionStabilization(long ms) {
            if (ms < 0) throw new IllegalArgumentException("promotionStabilization must not be negative");
            this.promotionStabilizationMs = ms;
            return this;
        }

        /**
         * How many entries a healthy non-voter may trail the leader's log by (default: 250, Consul's
         * {@code MaxTrailingLogs}).
         */
        public Builder promotionMaxTrailingEntries(long entries) {
            if (entries < 0) throw new IllegalArgumentException("promotionMaxTrailingEntries must not be negative");
            this.promotionMaxTrailingEntries = entries;
            return this;
        }

        Builder timerScheduler(RaftTimerScheduler timerScheduler) {
            this.timerScheduler = requireNonNull(timerScheduler, "timerScheduler");
            return this;
        }

        Builder transitionQueueCapacity(int capacity) {
            if (capacity < 1) throw new IllegalArgumentException("capacity must be at least one");
            this.transitionQueueCapacity = capacity;
            return this;
        }

        /**
         * Builds the {@link RaftNode}.
         *
         * @throws IllegalStateException if any required parameter is missing
         */
        public RaftNode build() {
            if (runtime == null) throw new IllegalStateException("runtime is required");
            if (nodeId == null) throw new IllegalStateException("nodeId is required");
            if (clusterNodes == null) throw new IllegalStateException("clusterNodes is required");
            // The list is where a server learns its own address, which it gives when it bootstraps or joins.
            // Votes and commits are counted from the configuration in the log, not from this list.
            if (!clusterNodes.contains(nodeId)) {
                throw new IllegalStateException("clusterNodes must include this node, " + nodeId);
            }
            if (transport == null) throw new IllegalStateException("transport is required");
            if (stateMachine == null) throw new IllegalStateException("stateMachine is required");
                if (commandCodec == null) throw new IllegalStateException("commandCodec is required");
            if (mode == null) throw new IllegalStateException("mode is required");

            boolean snap = (snapshotEnabled != null) ? snapshotEnabled : mode.isDurable();
            String resolvedServerId = serverId != null ? serverId : java.util.UUID.randomUUID().toString();
            if (initialConfiguration != null && initialConfiguration.server(resolvedServerId).isEmpty()) {
                throw new IllegalStateException(
                        "initialConfiguration must include this server, " + resolvedServerId);
            }
            Map<String, String> listed = new TreeMap<>();
            for (String name : clusterNodes) listed.put(name, addresses.getOrDefault(name, name));
            return new RaftNode(runtime, nodeId, resolvedServerId, initialConfiguration, listed,
                    clusterNodes, transport, stateMachine,
                    commandCodec, mode, electionTimeoutMs, heartbeatIntervalMs, snap,
                    snapshotThreshold, snapshotCheckIntervalMs, logHardLimit, promotionStabilizationMs,
                    promotionMaxTrailingEntries, timerScheduler, transitionQueueCapacity);
        }
    }

    // ========== CONSTRUCTOR (private) ==========

    private RaftNode(JavaRuntime runtime, String nodeId, String serverId,
            RaftConfiguration initialConfiguration, Map<String, String> listedServers, Set<String> clusterNodes,
            RaftTransport transport,
            RaftLogApplicator stateMachine, CommandCodec<RaftCommand> commandCodec,
            RaftNodeMode mode, long electionTimeoutMs, long heartbeatIntervalMs,
            boolean snapshotEnabled, long snapshotThreshold, long snapshotCheckIntervalMs,
            long logHardLimit, long promotionStabilizationMs, long promotionMaxTrailingEntries,
            RaftTimerScheduler configuredTimerScheduler,
            int transitionQueueCapacity) {
        this.runtime = runtime;
        this.nodeId = nodeId;
        this.serverId = serverId;
        this.initialConfiguration = initialConfiguration;
        this.listedServers = listedServers;
        this.clusterNodes = new HashSet<>(clusterNodes);
        this.transport = transport;
        this.stateMachine = stateMachine;
        this.commandCodec = commandCodec;
        RaftNodeMode configuredMode = requireNonNull(mode, "mode");
        this.persistence = new RaftPersistence(
                configuredMode.storage(), configuredMode.snapshots());
        this.transitionSequencer = new RaftTransitionSequencer(runtime, transitionQueueCapacity);
        this.timerScheduler = configuredTimerScheduler == null ? new RaftTimerScheduler() {
            @Override public long setTimer(long delayMs, java.util.function.Consumer<Long> action) {
                return runtime.setTimer(delayMs, action);
            }
            @Override public long setPeriodic(long periodMs, java.util.function.Consumer<Long> action) {
                return runtime.setPeriodic(periodMs, action);
            }
            @Override public boolean cancelTimer(long id) { return runtime.cancelTimer(id); }
        } : configuredTimerScheduler;
        this.electionTimeoutMs = electionTimeoutMs;
        this.quorumContactRounds = Math.max(1, (electionTimeoutMs + heartbeatIntervalMs - 1) / heartbeatIntervalMs);
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.snapshotEnabled = snapshotEnabled && this.persistence.isDurable();
        this.snapshotThreshold = snapshotThreshold;
        this.snapshotCheckIntervalMs = snapshotCheckIntervalMs;
        this.logHardLimit = logHardLimit;
        this.promotionStabilizationRounds = (promotionStabilizationMs + heartbeatIntervalMs - 1) / heartbeatIntervalMs;
        this.promotionMaxTrailingEntries = promotionMaxTrailingEntries;

        // Initialize log with a dummy entry
        log.add(new LogEntry(0, 0, null));

        // Initialize OpenTelemetry Metrics
        Meter meter = GlobalOpenTelemetry.getMeter("qraft-raft");

        meter.gaugeBuilder("qraft.cluster.state")
                .setDescription("Current Raft state (0=FOLLOWER, 1=CANDIDATE, 2=LEADER)")
                .ofLongs()
                .buildWithCallback(measurement -> measurement.record(state.ordinal()));

        meter.gaugeBuilder("qraft.cluster.term")
                .setDescription("Current Raft term")
                .ofLongs()
                .buildWithCallback(measurement -> measurement.record(currentTerm));

        meter.gaugeBuilder("qraft.cluster.commit_index")
                .setDescription("Current Commit Index")
                .ofLongs()
                .buildWithCallback(measurement -> measurement.record(commitIndex));

        meter.gaugeBuilder("qraft.cluster.last_applied")
                .setDescription("Last Applied Log Index")
                .ofLongs()
                .buildWithCallback(measurement -> measurement.record(lastApplied));

        meter.gaugeBuilder("qraft.cluster.is_leader")
                .setDescription("Whether this node is the leader (1=Yes, 0=No)")
                .ofLongs()
                .buildWithCallback(measurement -> measurement.record(state == State.LEADER ? 1 : 0));

        meter.gaugeBuilder("qraft.cluster.log_size")
                .setDescription("Number of entries in the Raft log")
                .ofLongs()
                .buildWithCallback(measurement -> measurement.record(log.size()));

        // Snapshot metrics
        snapshotCounter = meter.counterBuilder("qraft.raft.snapshot.total")
                .setDescription("Total number of snapshots taken")
                .setUnit("1")
                .build();

        snapshotDuration = meter.histogramBuilder("qraft.raft.snapshot.duration")
                .setDescription("Time taken to create and persist a snapshot")
                .setUnit("ms")
                .ofLongs()
                .build();

        logCompactedEntries = meter.counterBuilder("qraft.raft.log.compacted.entries")
                .setDescription("Total number of log entries removed by compaction")
                .setUnit("1")
                .build();

        meter.gaugeBuilder("qraft.raft.snapshot.last_index")
                .setDescription("Log index of the last snapshot")
                .ofLongs()
                .buildWithCallback(measurement -> measurement.record(snapshotLastIndex));

        installSnapshotSent = meter.counterBuilder("qraft.raft.install_snapshot.sent.total")
                .setDescription("Total InstallSnapshot RPCs sent by leader")
                .setUnit("1")
                .build();

        installSnapshotReceived = meter.counterBuilder("qraft.raft.install_snapshot.received.total")
                .setDescription("Total InstallSnapshot RPCs received by follower")
                .setUnit("1")
                .build();

        // Edge metrics for nodeGraph visualization
        rpcCounter = meter.counterBuilder("qraft.raft.rpc.total")
                .setDescription("Total Raft RPC calls between nodes (for nodeGraph edges)")
                .setUnit("1")
                .build();
    }

    public Future<Void> start() {
        Promise<Void> shared;
        synchronized (stopLock) {
            if (stopPromise != null) {
                return Future.failedFuture(new RaftTransitionSequencer.DrainingException());
            }
            if (startPromise != null) return startPromise.future();
            startPromise = Promise.promise();
            shared = startPromise;
        }

        try {
            runOnContext(v -> beginStart(shared));
        } catch (Throwable error) {
            shared.tryFail(error);
        }
        return shared.future();
    }

    private void beginStart(Promise<Void> completion) {
        if (!beginOwnedAsyncOperation()) {
            completion.tryFail(new RaftTransitionSequencer.DrainingException());
            return;
        }
        try {
            logger.info("Starting Raft node: {}", nodeId);
            MDC.put("raftRole", "FOLLOWER");
            MDC.put("raftTerm", String.valueOf(currentTerm));
            transport.setRaftNode(this);
            composeOnStateLoop(recoverFromStorage(), ignored -> establishConfiguration()).onComplete(result ->
                    runOnContext(v -> finishStart(result, completion)));
        } catch (Throwable error) {
            finishOwnedAsyncOperation();
            completion.tryFail(error);
        }
    }

    private void finishStart(
            dev.mars.qraft.common.async.AsyncResult<Void> recovery,
            Promise<Void> completion) {
        Throwable startupFailure = null;
        try {
            if (recovery.failed()) {
                logger.error("Failed to recover Raft state from storage: {}",
                        recovery.cause().getMessage(), recovery.cause());
                startupFailure = recovery.cause();
            } else if (ownedAsyncDraining) {
                completion.tryFail(new RaftTransitionSequencer.DrainingException());
            } else {
                transport.start(this::handleMessage);
                running = true;
                resetElectionTimer();
                startSnapshotScheduler();
                logger.info("Raft node {} started successfully (term={}, logSize={})",
                        nodeId, currentTerm, log.size());
                completion.tryComplete();
            }
        } catch (Throwable error) {
            logger.error("Failed to start Raft transport: {}", error.getMessage(), error);
            startupFailure = error;
        } finally {
            finishOwnedAsyncOperation();
        }
        if (startupFailure != null) {
            this.startupFailure = startupFailure;
            rollbackFailedStart(completion, startupFailure);
        }
    }

    /**
     * After recovery: a node with Raft state must have a configuration, since data from before configurations
     * were recorded is not upgraded; a node with none bootstraps with its initial configuration, or as the sole
     * member of a cluster of one, or waits for a leader to replicate one.
     */
    private Future<Void> establishConfiguration() {
        if (hasRaftState()) {
            return configuration != null ? Future.succeededFuture() : Future.failedFuture(new IllegalStateException(
                    "Node " + nodeId + " holds Raft state but no cluster configuration: the data predates "
                            + "configurations in the log and is not upgraded; start it on an empty data directory"));
        }
        RaftConfiguration initial = initialConfiguration != null ? initialConfiguration
                : clusterNodes.equals(Set.of(nodeId))
                        ? new RaftConfiguration(List.of(
                                new RaftConfiguration.Server(serverId, nodeId, listedServers.get(nodeId), true)))
                        : null;
        return initial == null ? Future.succeededFuture() : writeBootstrapEntry(initial);
    }

    /**
     * Writes {@code initial} at index 1 in term 0 and treats it as committed. Every server that holds an entry
     * at index 1 wrote this same entry or received it from a leader, and a server with no configuration cannot
     * lead, so no other entry can ever take index 1.
     */
    private Future<Void> writeBootstrapEntry(RaftConfiguration initial) {
        ConfigurationCommand command = new ConfigurationCommand(initial);
        LogEntry entry = new LogEntry(0, 1, command, serialize(command).toByteArray());
        return transitionSequencer.submit(
                "bootstrap-configuration",
                RaftTransitionSequencer.FailurePolicy.FENCE,
                () -> hasRaftState()
                        // Checked in the transition, so an append that arrived first cannot be overwritten.
                        ? Future.failedFuture(new IllegalStateException(
                                "Node " + nodeId + " already holds Raft state and cannot be bootstrapped"))
                        : persistLogEntry(entry).map(ignored -> entry),
                persisted -> {
                    log.add(persisted);
                    recordIfConfiguration(persisted);
                    commitIndex = 1;
                    applyLog();
                    if (running) resetElectionTimer();
                    logger.info("Bootstrapped with configuration {}", initial);
                    return null;
                });
    }

    /**
     * Bootstraps a running node that holds no Raft state with {@code configuration}, as the first entry of a new
     * cluster's log. Fails if the node already holds state, for instance because a leader replicated to it first.
     */
    public Future<Void> bootstrap(RaftConfiguration configuration) {
        requireNonNull(configuration, "configuration");
        if (configuration.server(serverId).isEmpty()) {
            return Future.failedFuture(new IllegalArgumentException(
                    "The configuration must include this server, " + serverId));
        }
        return writeBootstrapEntry(configuration);
    }

    private boolean hasRaftState() {
        return lastLogIndex() > 0 || snapshotLastIndex > 0;
    }

    /**
     * What a server bootstrapping a new cluster needs to know of this one, read on the state loop: its server ID,
     * name and address, the servers it lists, and whether it holds Raft state.
     */
    public Future<DescribeResponse> describe() {
        Promise<DescribeResponse> description = Promise.promise();
        try {
            runtime.runOnContext(ignored -> description.tryComplete(DescribeResponse.newBuilder()
                    .setServerId(serverId)
                    .setName(nodeId)
                    .setAddress(listedServers.get(nodeId))
                    .addAllListedServers(listedServers.entrySet().stream()
                            .map(listed -> listed.getKey() + "=" + listed.getValue()).toList())
                    .setHasState(hasRaftState())
                    .build()));
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            description.tryFail(stopped);
        }
        return description.future();
    }

    /** Whether index 1 holds a bootstrap configuration: a configuration entry written in term 0. */
    private boolean holdsBootstrapEntry() {
        return hasLogEntry(1) && log.get(toArrayIndex(1)).getTerm() == 0
                && log.get(toArrayIndex(1)).getCommand() instanceof ConfigurationCommand;
    }

    private void rollbackFailedStart(Promise<Void> completion, Throwable startupFailure) {
        stop().onComplete(rollback -> {
            Throwable combined = combineFailures(startupFailure, rollback.cause());
            completion.tryFail(combined);
        });
    }

    /**
     * Recovers persistent state from WAL storage.
     * <p>Order: Metadata → Snapshot (if any) → Log Replay → State Machine Rebuild
     */
    private Future<Void> recoverFromStorage() {
        if (!persistence.isDurable()) {
            logger.info("No storage configured, running in volatile mode");
            return Future.succeededFuture();
        }

        logger.info("Recovering Raft state from storage...");

        Future<Optional<SnapshotData>> snapshotLoad =
                composeOnStateLoop(toFuture(persistence.loadMetadata()), meta -> {
                this.currentTerm = meta.currentTerm();
                this.votedFor = meta.votedFor().orElse(null);
                logger.info("Recovered metadata: term={}, votedFor={}", currentTerm, votedFor);

                // Try to load snapshot
                return toFuture(persistence.loadLatestSnapshot());
            });
        Future<List<LogEntryData>> replay = composeOnStateLoop(snapshotLoad, snapshotOpt -> {
                if (snapshotOpt.isPresent()) {
                    SnapshotData snapshot = snapshotOpt.get();
                    logger.info("Restoring from snapshot: lastIncludedIndex={}, lastIncludedTerm={}",
                            snapshot.lastIncludedIndex(), snapshot.lastIncludedTerm());

                    // Restore the state machine and the configuration the snapshot covers
                    SnapshotEnvelope envelope = SnapshotEnvelope.unwrap(snapshot.data());
                    stateMachine.restoreSnapshot(envelope.stateMachineSnapshot());
                    snapshotConfiguration = envelope.configuration();
                    logConfigurations.clear();
                    refreshConfiguration();

                    // Set snapshot boundaries
                    snapshotLastIndex = snapshot.lastIncludedIndex();
                    snapshotLastTerm = snapshot.lastIncludedTerm();
                    lastApplied = snapshot.lastIncludedIndex();
                    commitIndex = snapshot.lastIncludedIndex();

                    // Initialize log with sentinel at snapshot boundary
                    log.clear();
                    log.add(new LogEntry(snapshotLastTerm, snapshotLastIndex, null));
                } else {
                    logger.info("No snapshot found, will rebuild from full log replay");
                }

                return toFuture(persistence.replayLog());
            });
        Future<Void> recovered = composeOnStateLoop(replay, entries -> {
                if (snapshotLastIndex > 0 && conflictsWithSnapshotBoundary(entries)) {
                    return discardInterruptedInstallationSuffix(entries.size());
                }
                if (snapshotLastIndex > 0) {
                    // Snapshot recovery: only replay entries AFTER the snapshot
                    int replayedCount = 0;
                    long expectedIndex = snapshotLastIndex + 1;
                    for (LogEntryData entry : entries) {
                        if (entry.index() > snapshotLastIndex) {
                            if (entry.index() != expectedIndex) {
                                return Future.failedFuture(new IllegalStateException(
                                        "Non-contiguous WAL replay after snapshot: expected index "
                                                + expectedIndex + " but found " + entry.index()));
                            }
                            RaftCommand command = deserialize(ByteString.copyFrom(entry.payload()));
                            LogEntry replayed = new LogEntry(entry.term(), entry.index(), command, entry.payload());
                            log.add(replayed);
                            recordIfConfiguration(replayed);
                            replayedCount++;
                            expectedIndex++;
                        }
                    }
                    logger.info("Replayed {} post-snapshot entries (skipped {} compacted entries)",
                            replayedCount, entries.size() - replayedCount);

                    // Safety first: on multi-node recovery, do not assume replayed
                    // entries were committed before crash. Wait for leaderCommit updates.
                    if (isSoleVoter()) {
                        commitIndex = Math.max(snapshotLastIndex, lastLogIndex());
                        applyLog();
                    } else {
                        commitIndex = snapshotLastIndex;
                        logger.info("Snapshot recovery loaded {} post-snapshot entries; awaiting leader commit to apply", replayedCount);
                    }
                } else {
                    // Full rebuild: no snapshot, replay everything
                    log.clear();
                    log.add(new LogEntry(0, 0, null));
                    long expectedIndex = 1;
                    for (LogEntryData entry : entries) {
                        if (entry.index() != expectedIndex) {
                            return Future.failedFuture(new IllegalStateException(
                                    "Non-contiguous WAL replay: expected index " + expectedIndex
                                            + " but found " + entry.index()));
                        }
                        RaftCommand command = deserialize(ByteString.copyFrom(entry.payload()));
                        LogEntry replayed = new LogEntry(entry.term(), entry.index(), command, entry.payload());
                        log.add(replayed);
                        recordIfConfiguration(replayed);
                        expectedIndex++;
                    }
                    logger.info("Recovered {} log entries from storage", entries.size());
                    return rebuildStateMachine();
                }

                return Future.<Void>succeededFuture();
            });
        return recovered.onSuccess(v -> logger.info(
                                "Recovery complete: term={}, logSize={}, lastApplied={}, snapshotLastIndex={}",
                                        currentTerm, log.size(), lastApplied, snapshotLastIndex))
            .onFailure(err -> {
                logger.error("Recovery failed: {}", err.getMessage(), err);
            });
    }

    /**
     * Whether the WAL holds an entry at the snapshot boundary whose term differs from the
     * snapshot's. An installed snapshot is published before the WAL suffix that conflicts with it is
     * truncated, so a crash between the two leaves such an entry, and every later one, from a history
     * the snapshot replaced.
     */
    private boolean conflictsWithSnapshotBoundary(List<LogEntryData> entries) {
        for (LogEntryData entry : entries) {
            if (entry.index() == snapshotLastIndex) return entry.term() != snapshotLastTerm;
        }
        return false;
    }

    /**
     * Completes the WAL compaction of an installation interrupted after its snapshot was published:
     * removes the conflicting suffix durably, then the prefix the snapshot covers. The in-memory log
     * keeps only the snapshot sentinel, and the retained snapshot state is what is applied.
     */
    private Future<Void> discardInterruptedInstallationSuffix(int walEntries) {
        long boundary = snapshotLastIndex;
        logger.warn("WAL entry at snapshot boundary {} conflicts with snapshot term {}; "
                        + "discarding {} WAL entries from the history the snapshot replaced",
                boundary, snapshotLastTerm, walEntries);
        return transitionSequencer.submit(
                "recovery-discard-divergent-suffix:" + boundary,
                RaftTransitionSequencer.FailurePolicy.FENCE,
                () -> {
                    RaftTransitionSequencer.Ownership ownership = transitionSequencer.currentOwnership();
                    return toFuture(persistence.truncateSuffix(ownership, boundary + 1))
                            .compose(ignored -> toFuture(persistence.sync(ownership)))
                            .compose(ignored -> toFuture(persistence.truncatePrefix(ownership, boundary)));
                });
    }

    /**
     * Rebuilds state machine by replaying all committed entries.
     * <p>Note: State machine operations should be idempotent.
     */
    private Future<Void> rebuildStateMachine() {
        logger.info("Rebuilding state machine from {} entries...", log.size() - 1);
        
        // Reset state machine to blank state
        stateMachine.reset();
        lastApplied = snapshotLastIndex;

        // Safety first: only single-node recovery can treat the full local log
        // as committed without additional quorum confirmation.
        if (isSoleVoter()) {
            commitIndex = Math.max(snapshotLastIndex, lastLogIndex());
            logger.info("Single-node recovery: restoring committed log up to index {}", commitIndex);
        } else {
            // The bootstrap configuration at index 1 is committed by definition (see bootstrap).
            commitIndex = Math.max(snapshotLastIndex, holdsBootstrapEntry() ? 1 : 0);
            logger.info("Multi-node recovery: deferring log application until leader commit advances");
        }

        applyLog();
        return Future.succeededFuture();
    }

    public Future<Void> stop() {
        Promise<Void> shared;
        synchronized (stopLock) {
            if (stopPromise != null) return stopPromise.future();
            stopPromise = Promise.promise();
            shared = stopPromise;
        }

        try {
            runOnContext(v -> beginShutdown(shared));
        } catch (Throwable error) {
            shared.tryFail(error);
        }
        return shared.future();
    }

    private void beginShutdown(Promise<Void> completion) {
        running = false;
        cancelTimers();
        failPendingCommands(new CommandOutcomeUnknownException(
                "Node stopped before command commitment; outcome may be unknown"));

        Future.all(transitionSequencer.drain(), drainOwnedAsyncOperations()).onComplete(drainResult -> {
            // Writes applied while draining re-register their promises; with replication stopped they
            // can no longer commit, so they are failed here rather than left pending forever.
            failPendingCommands(new CommandOutcomeUnknownException(
                    "Node stopped before command commitment; outcome may be unknown"));
            state = State.FOLLOWER;
            currentLeaderId = null;
            pendingInstalls.clear();
            outboundSnapshotTransfers.clear();

            closeNodeResources().onComplete(closeResult -> {
                Throwable failure = combineFailures(
                        drainResult.cause(), closeResult.cause());
                if (failure == null) {
                    logger.info("Raft node stopped: {}", nodeId);
                    completion.tryComplete();
                } else {
                    logger.warn("Raft node shutdown completed with errors: {}",
                            failure.getMessage(), failure);
                    completion.tryFail(failure);
                }
            });
        });
    }

    public Future<RaftCommandResult<?>> submitCommand(RaftCommand command) {
        requireNonNull(command, "command");
        Promise<RaftCommandResult<?>> promise = Promise.promise();

        transitionSequencer.submit(
                        "leader-append",
                        RaftTransitionSequencer.FailurePolicy.FENCE,
                        RaftNode::isAmbiguousLeaderAppendFailure,
                        () -> prepareAndPersistLeaderAppend(command),
                        decision -> applyLeaderAppend(decision, promise))
                .onFailure(error -> {
                    if (error instanceof RaftTransitionSequencer.DrainingException) {
                        logger.debug("Command rejected while Raft transitions are draining: {}",
                                error.getMessage());
                    } else {
                        logger.error("Failed to persist command to WAL: {}", error.getMessage(), error);
                    }
                    promise.tryFail(error);
                });

        return promise.future();
    }

    /**
     * Proposes {@code next} as the cluster's configuration. It takes effect as soon as it is in this leader's
     * log, and completes once committed. It is refused unless this node leads, has committed an entry in its
     * current term, has no other change in flight, and {@code next} adds, removes, or alters exactly one server.
     */
    public Future<Void> proposeConfiguration(RaftConfiguration next) {
        return submitCommand(new ConfigurationCommand(requireNonNull(next, "next"))).mapEmpty();
    }

    /**
     * Admits a server asking to join, as a non-voter; {@link #considerPromotions} promotes it once it has caught
     * up. A server already configured under its server ID is left as it is, or has its address updated (see
     * {@link #readmit}). If another server ID holds its name
     * or address, admission is refused until an operator removes that entry. Each change completes once committed.
     */
    public Future<JoinResult> admit(RaftConfiguration.Server joining) {
        requireNonNull(joining, "joining");
        if (joining.voter()) {
            return Future.failedFuture(new IllegalArgumentException(
                    "A server joins as a non-voter and is promoted once it has caught up"));
        }
        return onStateLoop(() -> {
            RaftConfiguration current = configuration;
            if (state != State.LEADER || current == null) {
                return Future.failedFuture(new IllegalStateException("Not the leader. Current state: " + state));
            }
            Optional<RaftConfiguration.Server> known = current.server(joining.serverId());
            if (known.isPresent()) return readmit(current, known.get(), joining);
            Optional<RaftConfiguration.Server> displaced = current.servers().stream()
                    .filter(server -> server.name().equals(joining.name())
                            || server.address().equals(joining.address()))
                    .findFirst();
            if (displaced.isPresent()) {
                return Future.failedFuture(new IllegalArgumentException(
                        "Name or address belongs to server " + displaced.get().serverId()
                                + "; an operator must remove the existing member before replacement"));
            }
            List<RaftConfiguration.Server> servers = new ArrayList<>(current.servers());
            servers.add(joining);
            return proposeConfiguration(new RaftConfiguration(servers)).map(ignored -> JoinResult.JOINED);
        });
    }

    /**
     * A configured server asking to join again: left as it is, or, if it asks from a new address, as after it
     * moved with its storage, given that address and its vote kept, as Consul's {@code AddServer} does. Its name
     * is how every server addresses it, so a request under another name is refused, as is an address another
     * server holds.
     */
    private Future<JoinResult> readmit(RaftConfiguration current, RaftConfiguration.Server known,
                                       RaftConfiguration.Server joining) {
        if (!known.name().equals(joining.name())) {
            return Future.failedFuture(new IllegalArgumentException("Server " + known.serverId()
                    + " is configured as " + known.name() + "; it cannot rejoin as " + joining.name()));
        }
        if (known.address().equals(joining.address())) return Future.succeededFuture(JoinResult.ALREADY_MEMBER);
        Optional<RaftConfiguration.Server> holder = current.servers().stream()
                .filter(server -> !server.serverId().equals(known.serverId())
                        && server.address().equals(joining.address()))
                .findFirst();
        if (holder.isPresent()) {
            return Future.failedFuture(new IllegalArgumentException("Address " + joining.address()
                    + " belongs to server " + holder.get().name() + " (" + holder.get().serverId() + ")"));
        }
        logger.info("Server {} ({}) moved from {} to {}; updating its address", known.name(), known.serverId(),
                known.address(), joining.address());
        List<RaftConfiguration.Server> servers = new ArrayList<>();
        for (RaftConfiguration.Server server : current.servers()) {
            servers.add(server.serverId().equals(known.serverId())
                    ? new RaftConfiguration.Server(known.serverId(), known.name(), joining.address(), known.voter())
                    : server);
        }
        return proposeConfiguration(new RaftConfiguration(servers)).map(ignored -> JoinResult.ADDRESS_UPDATED);
    }

    /**
     * Removes the server with {@code removedServerId}, completing once the change is committed. A leader that
     * removes itself steps down then. The removal is refused if the voters left, among those this leader has
     * heard from recently, would not be a quorum.
     */
    public Future<Void> removeServer(String removedServerId) {
        requireNonNull(removedServerId, "removedServerId");
        return onStateLoop(() -> {
            RaftConfiguration current = configuration;
            if (state != State.LEADER || current == null) {
                return Future.failedFuture(new IllegalStateException("Not the leader. Current state: " + state));
            }
            if (current.server(removedServerId).isEmpty()) {
                return Future.failedFuture(new IllegalArgumentException(
                        "Server " + removedServerId + " is not in the configuration"));
            }
            return proposeConfiguration(without(current, removedServerId));
        });
    }

    private static RaftConfiguration without(RaftConfiguration configuration, String removedServerId) {
        return new RaftConfiguration(configuration.servers().stream()
                .filter(server -> !server.serverId().equals(removedServerId)).toList());
    }

    /** Runs {@code step} on the state loop, so it sees one moment of the node's state. */
    private <T> Future<T> onStateLoop(java.util.function.Supplier<Future<T>> step) {
        Promise<T> result = Promise.promise();
        try {
            runOnContext(ignored -> {
                try {
                    step.get().onComplete(outcome -> {
                        if (outcome.succeeded()) result.tryComplete(outcome.result());
                        else result.tryFail(outcome.cause());
                    });
                } catch (RuntimeException error) {
                    result.tryFail(error);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            result.tryFail(stopped);
        }
        return result.future();
    }

    private Future<LeaderAppendDecision> prepareAndPersistLeaderAppend(RaftCommand command) {
        if (state != State.LEADER) {
            return Future.succeededFuture(LeaderAppendDecision.rejected(
                    new IllegalStateException("Not the leader. Current state: " + state)));
        }
        if (command instanceof ConfigurationCommand change) {
            // Checked here, in the sequenced transition, so every configuration entry meets the rules however
            // it was submitted.
            Throwable refusal = configurationChangeRefusal(change.configuration());
            if (refusal != null) return Future.succeededFuture(LeaderAppendDecision.rejected(refusal));
        }

        if (log.size() >= logHardLimit) {
            logger.warn("Raft log at capacity ({}/{}), rejecting command", log.size(), logHardLimit);
            return Future.succeededFuture(LeaderAppendDecision.rejected(
                    new IllegalStateException(
                            "Raft log at capacity (" + log.size() + "/" + logHardLimit
                                    + "). Wait for snapshot.")));
        }

        // The leader applies exactly what it logs: the command decoded from its own encoding. Any
        // field the codec normalizes is then identical on the leader, its followers, and after replay.
        LogEntry entry;
        try {
            byte[] payload = serialize(command).toByteArray();
            entry = new LogEntry(currentTerm, lastLogIndex() + 1, deserialize(ByteString.copyFrom(payload)), payload);
        } catch (RuntimeException error) {
            return Future.succeededFuture(LeaderAppendDecision.rejected(new CommandEncodingException(error)));
        }
        return persistLogEntry(entry).map(ignored -> LeaderAppendDecision.accepted(entry));
    }

    private Void applyLeaderAppend(
            LeaderAppendDecision decision, Promise<RaftCommandResult<?>> commandPromise) {
        if (decision.rejection() != null) {
            commandPromise.tryFail(decision.rejection());
            return null;
        }

        LogEntry entry = decision.entry();
        log.add(entry);
        recordIfConfiguration(entry);
        pendingCommands.put(entry.getIndex(), commandPromise);
        logger.info("Command submitted at index {} term {}", entry.getIndex(), entry.getTerm());

        for (RaftConfiguration.Server peer : peers()) sendAppendEntries(peer.name(), false);

        updateCommitIndex();
        return null;
    }

    /**
     * Why {@code next} may not follow the configuration in force, or null if it may. A single-server change is
     * safe only if the leader has committed an entry in its own term (Raft thesis 4.1, and Ongaro's correction
     * of 2015) and no other change is uncommitted.
     */
    private Throwable configurationChangeRefusal(RaftConfiguration next) {
        long committedTerm = commitIndex == snapshotLastIndex ? snapshotLastTerm
                : hasLogEntry(commitIndex) ? log.get(toArrayIndex(commitIndex)).getTerm() : -1;
        if (committedTerm != currentTerm) {
            return new IllegalStateException("A configuration change waits until this leader has committed an "
                    + "entry in its term " + currentTerm);
        }
        if (!logConfigurations.isEmpty() && logConfigurations.lastKey() > commitIndex) {
            return new IllegalStateException("A configuration change is already in progress at index "
                    + logConfigurations.lastKey());
        }
        Set<RaftConfiguration.Server> before = new HashSet<>(configuration.servers());
        Set<RaftConfiguration.Server> after = new HashSet<>(next.servers());
        Set<String> changed = new HashSet<>();
        before.stream().filter(server -> !after.contains(server)).forEach(server -> changed.add(server.serverId()));
        after.stream().filter(server -> !before.contains(server)).forEach(server -> changed.add(server.serverId()));
        if (changed.isEmpty()) {
            return new IllegalArgumentException("The configuration is unchanged");
        }
        if (changed.size() > 1) {
            return new IllegalArgumentException("A configuration change may add, remove, or alter one server at a "
                    + "time; this one changes " + changed.size() + ": " + changed);
        }
        String changedId = changed.iterator().next();
        if (configuration.server(changedId).isEmpty() && next.isVoter(changedId)) {
            return new IllegalArgumentException("A server joins as a non-voter and is promoted once it has caught "
                    + "up; " + changedId + " was added as a voter");
        }
        if (configuration.isVoter(changedId) && !next.isVoter(changedId)) {
            // The voters left must still be able to commit: those this leader has heard from must be a quorum.
            Set<String> reachable = new HashSet<>();
            reachable.add(serverId);
            for (RaftConfiguration.Server peer : peers()) {
                Long round = lastContactRound.get(peer.name());
                if (answeredPeers.contains(peer.name()) && round != null
                        && heartbeatRound - round <= quorumContactRounds) {
                    reachable.add(peer.serverId());
                }
            }
            reachable.retainAll(next.voterIds());
            if (!next.hasQuorum(reachable)) {
                return new IllegalStateException("Removing voter " + changedId + " would leave "
                        + next.voterIds().size() + " voters, of which this leader has recently heard from "
                        + reachable.size() + ": not a quorum");
            }
        }
        return null;
    }

    private record LeaderAppendDecision(LogEntry entry, Throwable rejection) {
        private static LeaderAppendDecision accepted(LogEntry entry) {
            return new LeaderAppendDecision(entry, null);
        }

        private static LeaderAppendDecision rejected(Throwable error) {
            return new LeaderAppendDecision(null, error);
        }
    }

    /**
     * Persists a log entry to the WAL with sync barrier.
     */
    private Future<Void> persistLogEntry(LogEntry entry) {
        RaftTransitionSequencer.Ownership ownership = transitionSequencer.currentOwnership();
        if (!persistence.isDurable()) return Future.succeededFuture();

        ByteString serialized;
        try {
            serialized = payloadOf(entry);
        } catch (RuntimeException error) {
            return Future.failedFuture(new CommandEncodingException(error));
        }
        LogEntryData entryData = new LogEntryData(
                entry.getIndex(), entry.getTerm(), serialized.toByteArray());
        return toFuture(persistence.appendEntries(ownership, List.of(entryData)))
                .compose(v -> toFuture(persistence.sync(ownership)));
    }

    // ... Getters ...
    public boolean isRunning() {
        return running;
    }

    /**
     * Captures this node's Raft state on its state loop, so every field belongs to the same moment. Fails
     * if the state loop no longer accepts work.
     */
    public Future<RaftStatus> status() {
        Promise<RaftStatus> status = Promise.promise();
        try {
            runtime.runOnContext(ignored -> status.tryComplete(new RaftStatus(nodeId, serverId, state, currentTerm,
                    currentLeaderId, commitIndex, lastApplied, lastLogIndex(), snapshotLastIndex, isFenced(),
                    running, isRemoved())));
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            status.tryFail(stopped);
        }
        return status.future();
    }

    public boolean isFenced() {
        return startupFailure != null || transitionSequencer.isFenced();
    }

    public String getLeaderId() {
        return state == State.LEADER ? nodeId : currentLeaderId;
    }

    public RaftLogApplicator getStateStore() {
        return stateMachine;
    }

    public State getState() {
        return state;
    }

    public long getCurrentTerm() {
        return currentTerm;
    }

    public String getNodeId() {
        return nodeId;
    }

    /** The configuration in force: the latest in the log, committed or not, or else the snapshot's. */
    public Optional<RaftConfiguration> getConfiguration() {
        return Optional.ofNullable(configuration);
    }

    /** The configuration in force at {@code index}: the latest entry at or before it, or else the snapshot's. */
    private RaftConfiguration configurationAt(long index) {
        Map.Entry<Long, RaftConfiguration> entry = logConfigurations.floorEntry(index);
        return entry != null ? entry.getValue() : snapshotConfiguration;
    }

    /** The configured servers other than this one: every server a leader replicates to. */
    private List<RaftConfiguration.Server> peers() {
        RaftConfiguration current = configuration;
        if (current == null) return List.of();
        return current.servers().stream().filter(server -> !server.serverId().equals(serverId)).toList();
    }

    private boolean isVoter() {
        RaftConfiguration current = configuration;
        return current != null && current.isVoter(serverId);
    }

    private boolean isSoleVoter() {
        RaftConfiguration current = configuration;
        return current != null && current.voterIds().equals(Set.of(serverId));
    }

    /**
     * Whether a response from the peer named {@code peerName} came from the server configured under that name.
     * A server that lost its storage answers with a new server ID, so it is neither counted nor listened to.
     */
    private boolean fromConfiguredServer(String peerName, String senderServerId) {
        RaftConfiguration current = configuration;
        return current != null && current.serverNamed(peerName)
                .map(server -> server.serverId().equals(senderServerId)).orElse(false);
    }

    private void recordIfConfiguration(LogEntry entry) {
        if (entry.getCommand() instanceof ConfigurationCommand command) {
            logConfigurations.put(entry.getIndex(), command.configuration());
            refreshConfiguration();
            if (state == State.LEADER) trackNewPeers();
        }
    }

    /**
     * Brings the leader's per-peer tracking in line with the configuration in force. A server no longer
     * configured is forgotten, so a server later added under the same name starts afresh rather than inheriting
     * its predecessor's match index and contact round. A server just added gets no check-quorum credit until it
     * answers: its last contact is set to round 0, long past.
     */
    private void trackNewPeers() {
        Set<String> configured = new HashSet<>();
        for (RaftConfiguration.Server peer : peers()) configured.add(peer.name());
        nextIndex.keySet().retainAll(configured);
        matchIndex.keySet().retainAll(configured);
        lastContactRound.keySet().retainAll(configured);
        answeredPeers.retainAll(configured);
        healthySinceRound.keySet().retainAll(configured);
        unavailablePeers.retainAll(configured);
        for (RaftConfiguration.Server peer : peers()) {
            nextIndex.putIfAbsent(peer.name(), lastLogIndex() + 1);
            matchIndex.putIfAbsent(peer.name(), 0L);
            lastContactRound.putIfAbsent(peer.name(), 0L);
        }
    }

    private void forgetConfigurationsFrom(long index) {
        logConfigurations.tailMap(index, true).clear();
        refreshConfiguration();
    }

    private void refreshConfiguration() {
        configuration = logConfigurations.isEmpty() ? snapshotConfiguration : logConfigurations.lastEntry().getValue();
        RaftConfiguration current = configuration;
        if (current != null) {
            if (currentLeaderId != null && current.serverNamed(currentLeaderId)
                    .filter(RaftConfiguration.Server::voter).isEmpty()) {
                leaderContactActive = false;
            }
            Map<String, String> addresses = new HashMap<>();
            for (RaftConfiguration.Server server : current.servers()) {
                if (!server.serverId().equals(serverId)) addresses.put(server.name(), server.address());
            }
            transport.useAddresses(addresses);
        }
    }

    /** The server's durable Raft identity, which every message it sends names as the sender. */
    public String getServerId() {
        return serverId;
    }

    public boolean isLeader() {
        return state == State.LEADER;
    }

    /**
     * Returns the current size of the Raft log (number of entries).
     * Useful for monitoring replication progress across the cluster.
     */
    public int getLogSize() {
        return log.size();
    }

    /**
     * Returns the index of the last log entry applied to the state machine.
     * This trails commitIndex and indicates local state machine progress.
     */
    public long getLastApplied() {
        return lastApplied;
    }

    /**
     * Returns the index of the highest log entry known to be committed.
     * Entries up to this index are safe to apply to the state machine.
     */
    public long getCommitIndex() {
        return commitIndex;
    }

    /**
     * Returns the candidate ID this node voted for in the current term.
     * Returns null if no vote has been cast in this term.
     * Useful for debugging election issues and split vote scenarios.
     */
    public String getVotedFor() {
        return votedFor;
    }

    /**
     * Returns the log index of the last entry included in the most recent snapshot.
     * Returns 0 if no snapshot has been taken.
     */
    public long getSnapshotLastIndex() {
        return snapshotLastIndex;
    }

    /**
     * Returns the term of the last entry included in the most recent snapshot.
     * Returns 0 if no snapshot has been taken.
     */
    public long getSnapshotLastTerm() {
        return snapshotLastTerm;
    }

    /**
     * Returns the leader's nextIndex for a given peer.
     * Used in tests to verify index tracking after InstallSnapshot.
     *
     * @param peerId the peer node ID
     * @return the nextIndex for the peer, or -1 if not tracked
     */
    public long getNextIndex(String peerId) {
        return nextIndex.getOrDefault(peerId, -1L);
    }

    /**
     * Returns the index of the last entry in the log.
     * This is used during elections for log comparison (§5.4.1).
     * Returns 0 if the log only contains the sentinel entry.
     */
    public long getLastLogIndex() {
        return lastLogIndex();
    }

    /**
     * Returns the term of the last entry in the log.
     * This is used during elections for log comparison (§5.4.1).
     * Returns 0 if the log only contains the sentinel entry.
     */
    public long getLastLogTerm() {
        if (log.isEmpty()) {
            return snapshotLastTerm;
        }
        return log.get(log.size() - 1).getTerm();
    }

    // ========== STATE CHANGE OBSERVATION ==========

    /**
     * Registers a listener that is notified whenever this node's Raft state changes.
     * <p>Listeners are invoked on the node's Java runtime state-loop context, so
     * listeners observe serialized Raft state changes.
     *
     * @param listener handler that receives the new {@link State}
     */
    public void addStateChangeListener(java.util.function.Consumer<State> listener) {
        stateChangeListeners.add(listener);
    }

    /**
     * Removes a previously registered state change listener.
     *
     * @param listener the listener to remove
     */
    public void removeStateChangeListener(java.util.function.Consumer<State> listener) {
        stateChangeListeners.remove(listener);
    }

    /**
     * Returns a Future that completes when this node transitions to the target state,
     * or fails if the timeout expires. This is the primary reactive alternative to
     * polling loops with Thread.sleep.
     *
     * <p>Usage in tests:
     * <pre>{@code
     * node.awaitState(State.LEADER, 10000)
     *     .onComplete(ctx.succeedingThenComplete());
     * }</pre>
     *
     * @param targetState the state to wait for
     * @param timeoutMs maximum time to wait in milliseconds
     * @return a Future that completes with the target state or fails on timeout
     */
    public Future<State> awaitState(State targetState, long timeoutMs) {
        Promise<State> promise = Promise.promise();
        java.util.function.Consumer<State> listener = newState -> {
            if (newState == targetState) promise.tryComplete(targetState);
        };
        addStateChangeListener(listener);
        // Checked after registering, so a transition between the check and the registration is not missed.
        if (state == targetState) promise.tryComplete(targetState);
        // The timeout is the observer's own; it never occupies the node's election and heartbeat timers.
        Future<State> result = promise.future().timeout(timeoutMs, TimeUnit.MILLISECONDS);
        result.onComplete(ignored -> removeStateChangeListener(listener));
        return result;
    }

    /**
     * Notifies all registered state change listeners of a state transition.
     * Invoked internally after every state change (becomeLeader, startElection, stepDown).
     */
    private void notifyStateChangeListeners(State newState) {
        for (java.util.function.Consumer<State> listener : stateChangeListeners) {
            try {
                listener.accept(newState);
            } catch (Exception e) {
                logger.warn("State change listener threw exception: {}", e.getMessage(), e);
            }
        }
    }

    // ========== LOG OFFSET HELPERS ==========

    /**
     * Converts a Raft log index to the in-memory array index.
     * The in-memory log starts at snapshotLastIndex (the sentinel/snapshot boundary).
     * Entry at snapshotLastIndex is at array position 0 (the sentinel).
     */
    private int toArrayIndex(long logIndex) {
        return (int) (logIndex - snapshotLastIndex);
    }

    /**
     * Returns the Raft log index of the last entry in the in-memory log.
     */
    private long lastLogIndex() {
        return snapshotLastIndex + log.size() - 1;
    }

    /**
     * Returns true if the given Raft log index is present in the in-memory log.
     */
    private boolean hasLogEntry(long logIndex) {
        int arrayIdx = toArrayIndex(logIndex);
        return arrayIdx >= 0 && arrayIdx < log.size();
    }

    private void cancelTimers() {
        cancelRoleTimers();
        cancelSnapshotTimer();
    }

    /** Cancels the timers of the current role; every role keeps compacting its own log. */
    private void cancelRoleTimers() {
        cancelElectionTimer();
        cancelHeartbeatTimer();
        outboundSnapshotTransfers.clear();
    }

    private void cancelElectionTimer() {
        if (electionTimerId != -1) timerScheduler.cancelTimer(electionTimerId);
        electionTimerId = -1;
        electionTimerGeneration++;
    }

    private void cancelHeartbeatTimer() {
        if (heartbeatTimerId != -1) timerScheduler.cancelTimer(heartbeatTimerId);
        heartbeatTimerId = -1;
        heartbeatTimerGeneration++;
    }

    private void cancelSnapshotTimer() {
        if (snapshotTimerId != -1) timerScheduler.cancelTimer(snapshotTimerId);
        snapshotTimerId = -1;
        snapshotTimerGeneration++;
    }

    private void resetElectionTimer() {
        cancelElectionTimer();
        // A transition applied while stopping must not re-arm the timer that shutdown cancelled.
        if (!running) return;

        long timeout = electionTimeoutMs + (long) (Math.random() * electionTimeoutMs);
        long timerGeneration = electionTimerGeneration;

        electionTimerId = setTimer(timeout, id -> onElectionTimer(id, timerGeneration));
    }

    private void onElectionTimer(long timerId, long timerGeneration) {
        if (timerId != electionTimerId || timerGeneration != electionTimerGeneration) return;
        electionTimerId = -1;
        leaderContactActive = false;
        if (transitionSequencer.isFenced()) {
            logger.debug("Election timer stopped because the Raft transition sequencer is fenced");
            return;
        }
        startElection(timerGeneration);
    }

    private void startElection(long timerGeneration) {
        transitionSequencer.submitEssential(
                        "start-election",
                        RaftTransitionSequencer.FailurePolicy.FENCE,
                        () -> prepareAndPersistElection(timerGeneration),
                        this::applyElection)
                .onFailure(error -> {
                    if (error instanceof RaftTransitionSequencer.QueueFullException
                            && running && state != State.LEADER
                            && timerGeneration == electionTimerGeneration) {
                        resetElectionTimer();
                    } else if (error instanceof RaftTransitionSequencer.FencedException
                            || error instanceof RaftTransitionSequencer.DrainingException) {
                        logger.debug("Election transition rejected: {}", error.getMessage());
                    } else {
                        logger.error("Failed to start election because metadata durability is uncertain: {}",
                                error.getMessage(), error);
                    }
                });
    }

    private Future<ElectionDecision> prepareAndPersistElection(long timerGeneration) {
        if (!running || state == State.LEADER
                || timerGeneration != electionTimerGeneration) {
            return Future.succeededFuture(new ElectionDecision(false, false, currentTerm));
        }
        if (!isVoter()) {
            logger.debug("Not campaigning: this server is not a voter in its configuration");
            return Future.succeededFuture(new ElectionDecision(false, true, currentTerm));
        }

        long electionTerm = currentTerm + 1;
        logger.info("Preparing election for node {} at term {}", nodeId, electionTerm);
        return persistMetadata(electionTerm, Optional.of(nodeId))
                .map(ignored -> new ElectionDecision(true, false, electionTerm));
    }

    private Void applyElection(ElectionDecision decision) {
        if (!running) return null;
        if (!decision.start()) {
            if (decision.leaderSilent()) forgetSilentLeader();
            return null;
        }

        state = State.CANDIDATE;
        currentLeaderId = null;
        currentTerm = decision.term();
        votedFor = nodeId;
        MDC.put("raftRole", "CANDIDATE");
        MDC.put("raftTerm", String.valueOf(currentTerm));
        notifyStateChangeListeners(State.CANDIDATE);
        resetElectionTimer();
        requestVotes();
        return null;
    }

    /**
     * Forgets the leader of a server that does not campaign. Its election timer fired, so the leader has been
     * silent for a whole election timeout. A campaigning server forgets its leader by becoming a candidate; one
     * that does not would otherwise report that leader, and so report itself ready, for as long as it stayed cut
     * off. The next message from a leader makes the leader known again.
     */
    private void forgetSilentLeader() {
        if (state != State.FOLLOWER || currentLeaderId == null) return;
        logger.info("Leader {} has been silent for an election timeout; this server does not campaign and now"
                + " knows no leader", currentLeaderId);
        currentLeaderId = null;
    }

    /**
     * What an election timeout leads to: an election at {@code term}, or none. {@code leaderSilent} is true
     * when no election starts because this server does not campaign, which leaves it without a known leader.
     */
    private record ElectionDecision(boolean start, boolean leaderSilent, long term) {}

    private void requestVotes() {
        long term = currentTerm;
        long lastLogIdx = lastLogIndex();
        long lastLogTrm = lastLogIdx > 0 && hasLogEntry(lastLogIdx)
                ? log.get(toArrayIndex(lastLogIdx)).getTerm()
                : snapshotLastTerm;

        Set<String> granted = ConcurrentHashMap.newKeySet();
        granted.add(serverId); // Self vote

        if (isSoleVoter()) {
            becomeLeader();
            return;
        }

        for (RaftConfiguration.Server peer : peers()) {
            if (peer.voter()) {
                String peerId = peer.name();
                VoteRequest request = VoteRequest.newBuilder()
                        .setTerm(term)
                        .setCandidateId(nodeId)
                        .setCandidateServerId(serverId)
                        .setLastLogIndex(lastLogIdx)
                        .setLastLogTerm(lastLogTrm)
                        .build();

                // Using transport (Wait for Future integration)
                transport.sendVoteRequest(peerId, request)
                        .onSuccess(response -> sequenceVoteResponse(peerId, response, term, granted))
                        .onFailure(e -> logger.error("Failed to retrieve vote from {}", peerId, e));

                // Record edge metric for nodeGraph visualization
                rpcCounter.add(1, Attributes.of(
                        AttributeKey.stringKey("source"), nodeId,
                        AttributeKey.stringKey("target"), peerId,
                        AttributeKey.stringKey("type"), "request_vote"
                ));
            }
        }
    }

    private void sequenceVoteResponse(
            String peerId, VoteResponse response, long electionTerm, Set<String> granted) {
        transitionSequencer.submit(
                        "vote-response:" + electionTerm + ":" + response.getTerm(),
                        RaftTransitionSequencer.FailurePolicy.FENCE,
                        () -> prepareVoteResponse(peerId, response),
                        decision -> applyVoteResponse(decision, electionTerm, granted))
                .onFailure(error -> logger.error(
                        "Failed to process vote response for election term {}: {}",
                        electionTerm, error.getMessage(), error));
    }

    private Future<VoteResponseDecision> prepareVoteResponse(String peerId, VoteResponse response) {
        if (!fromConfiguredServer(peerId, response.getVoterServerId())) {
            logger.warn("Ignoring a vote response from {} with server ID {}, which is not the configured server",
                    peerId, response.getVoterServerId());
            return Future.succeededFuture(null);
        }
        if (response.getTerm() <= currentTerm) {
            return Future.succeededFuture(new VoteResponseDecision(response, false));
        }
        return persistMetadata(response.getTerm(), Optional.empty())
                .map(ignored -> new VoteResponseDecision(response, true));
    }

    private Void applyVoteResponse(
            VoteResponseDecision decision, long electionTerm, Set<String> granted) {
        if (decision == null) return null;
        VoteResponse response = decision.response();
        if (decision.higherTerm()) {
            applyDurableHigherTerm(response.getTerm(), null);
            return null;
        }
        if (state != State.CANDIDATE || currentTerm != electionTerm) {
            return null;
        }
        RaftConfiguration current = configuration;
        if (response.getVoteGranted() && current != null) {
            granted.add(response.getVoterServerId());
            if (current.hasQuorum(granted)) {
                becomeLeader();
            }
        }
        return null;
    }

    private record VoteResponseDecision(VoteResponse response, boolean higherTerm) {}

    private void becomeLeader() {
        // Serialize initial admission with a foreign caller's stop request. If stop already won,
        // do not establish leadership; otherwise its shutdown is queued after the no-op admission.
        synchronized (stopLock) {
            if (state != State.CANDIDATE || stopPromise != null) return;

            outboundSnapshotTransfers.clear();
            leadershipGeneration++;
            currentLeaderId = nodeId;
            MDC.put("raftRole", "LEADER");
            MDC.put("raftTerm", String.valueOf(currentTerm));
            cancelElectionTimer();

            initializeLeaderState();
            heartbeatRound = 0;
            lastContactRound.clear();
            for (RaftConfiguration.Server peer : peers()) lastContactRound.put(peer.name(), 0L);
            answeredPeers.clear();
            healthySinceRound.clear();
            appendLeadershipNoOp();
            state = State.LEADER;
        }
        // Call listeners outside stopLock: they may ask another thread to stop the node.
        logger.info("Node {} became LEADER for term {}", nodeId, currentTerm);
        notifyStateChangeListeners(State.LEADER);
        startHeartbeats();
        sendHeartbeats(); // Immediate
    }

    /**
     * Establishes an entry in every new leader's term, including on an idle cluster,
     * so membership changes can proceed. Committing it also commits the retained prefix.
     */
    private void appendLeadershipNoOp() {
        long leadershipTerm = currentTerm;
        long generation = leadershipGeneration;
        transitionSequencer.submit(
                        "leader-no-op:" + leadershipTerm + ":" + generation,
                        RaftTransitionSequencer.FailurePolicy.FENCE,
                        RaftNode::isAmbiguousLeaderAppendFailure,
                        () -> {
                            if (!isCurrentLeadership(leadershipTerm, generation)) {
                                return Future.succeededFuture((LogEntry) null);
                            }
                            LogEntry noOp = new LogEntry(leadershipTerm, lastLogIndex() + 1, null,
                                    serialize(null).toByteArray());
                            return persistLogEntry(noOp).map(ignored -> noOp);
                        },
                        noOp -> {
                            if (noOp == null
                                    || !isCurrentLeadership(leadershipTerm, generation)) {
                                return null;
                            }
                            log.add(noOp);
                            logger.info("Leadership no-op submitted at index {} term {}",
                                    noOp.getIndex(), noOp.getTerm());
                            for (RaftConfiguration.Server peer : peers()) {
                                sendAppendEntries(peer.name(), false);
                            }
                            updateCommitIndex();
                            return null;
                        })
                .onFailure(error -> logger.error(
                        "Failed to establish leadership no-op for term {}: {}",
                        leadershipTerm, error.getMessage(), error));
    }

    private void initializeLeaderState() {
        long nextIndexValue = lastLogIndex() + 1;
        for (RaftConfiguration.Server peer : peers()) {
            nextIndex.put(peer.name(), nextIndexValue);
            matchIndex.put(peer.name(), 0L);
        }
    }

    private void startHeartbeats() {
        cancelHeartbeatTimer();
        long timerGeneration = heartbeatTimerGeneration;
        long term = currentTerm;
        long leaderGeneration = leadershipGeneration;
        heartbeatTimerId = setPeriodic(heartbeatIntervalMs,
                id -> onHeartbeatTimer(id, timerGeneration, term, leaderGeneration));
    }

    private void onHeartbeatTimer(
            long timerId, long timerGeneration, long term, long leaderGeneration) {
        if (timerId != heartbeatTimerId
                || timerGeneration != heartbeatTimerGeneration
                || heartbeatTimerTransitionPending) {
            return;
        }
        if (transitionSequencer.isFenced()) {
            cancelHeartbeatTimer();
            logger.debug("Heartbeat timer stopped because the Raft transition sequencer is fenced");
            return;
        }
        heartbeatTimerTransitionPending = true;
        transitionSequencer.submit(
                        "heartbeat-timer:" + term + ":" + leaderGeneration,
                        RaftTransitionSequencer.FailurePolicy.CONTINUE,
                        () -> Future.succeededFuture(new HeartbeatTimerDecision(
                                timerGeneration, term, leaderGeneration)),
                        this::applyHeartbeatTimer)
                .onComplete(result -> {
                    heartbeatTimerTransitionPending = false;
                    if (result.failed()) {
                        logger.debug("Could not admit heartbeat timer event: {}",
                                result.cause().toString());
                    }
                });
    }

    private Void applyHeartbeatTimer(HeartbeatTimerDecision decision) {
        if (decision.timerGeneration() == heartbeatTimerGeneration
                && isCurrentLeadership(decision.term(), decision.leaderGeneration())) {
            heartbeatRound++;
            if (!majorityRecentlyContacted()) {
                stepDownForLostQuorum();
                return null;
            }
            sendHeartbeats();
            considerPromotions();
        }
        return null;
    }

    /**
     * Promotes a non-voter that has stayed healthy for the stabilization period, as Consul's autopilot does.
     * Healthy means it has answered this leader within {@link #PROMOTION_CONTACT_ROUNDS} heartbeat rounds, which
     * also means its term matches the leader's, since only replies to this leadership are recorded; and its log
     * trails the leader's by at most the allowed number of entries. One server is promoted at a time; a
     * promotion refused, for example because another change is uncommitted, is tried again next round.
     */
    private void considerPromotions() {
        RaftConfiguration current = configuration;
        if (current == null) return;
        RaftConfiguration.Server promote = null;
        for (RaftConfiguration.Server peer : peers()) {
            if (peer.voter()) continue;
            if (!isHealthyNonVoter(peer.name())) {
                healthySinceRound.remove(peer.name());
                continue;
            }
            long since = healthySinceRound.computeIfAbsent(peer.name(), ignored -> heartbeatRound);
            if (promote == null && heartbeatRound - since >= promotionStabilizationRounds) promote = peer;
        }
        if (promote == null) return;
        List<RaftConfiguration.Server> servers = new ArrayList<>();
        for (RaftConfiguration.Server server : current.servers()) {
            servers.add(server.serverId().equals(promote.serverId())
                    ? new RaftConfiguration.Server(server.serverId(), server.name(), server.address(), true)
                    : server);
        }
        String promoted = promote.name();
        proposeConfiguration(new RaftConfiguration(servers))
                .onSuccess(ignored -> logger.info("Promoted {} to a voter", promoted))
                .onFailure(error -> logger.debug("Promotion of {} deferred: {}", promoted, error.getMessage()));
    }

    private boolean isHealthyNonVoter(String peerName) {
        Long contact = lastContactRound.get(peerName);
        return answeredPeers.contains(peerName) && contact != null
                && heartbeatRound - contact <= PROMOTION_CONTACT_ROUNDS
                && matchIndex.getOrDefault(peerName, 0L) >= lastLogIndex() - promotionMaxTrailingEntries;
    }

    /**
     * Check-quorum: true while this leader plus the peers that answered within the last election
     * timeout's worth of heartbeat rounds form a majority. A single-node cluster is always its own
     * majority.
     */
    private boolean majorityRecentlyContacted() {
        RaftConfiguration current = configuration;
        if (current == null) return false;
        Set<String> reached = new HashSet<>();
        reached.add(serverId);
        for (RaftConfiguration.Server peer : peers()) {
            Long round = lastContactRound.get(peer.name());
            if (round != null && heartbeatRound - round <= quorumContactRounds) reached.add(peer.serverId());
        }
        return current.hasQuorum(reached);
    }

    private void recordPeerContact(String peerId) {
        if (lastContactRound.containsKey(peerId)) {
            lastContactRound.put(peerId, heartbeatRound);
            answeredPeers.add(peerId);
        }
    }

    /**
     * Steps down in the current term when a majority has been unreachable for an election timeout,
     * so a partitioned leader stops accepting writes and a majority can elect a replacement without
     * this node competing as leader.
     */
    private void stepDownForLostQuorum() {
        state = State.FOLLOWER;
        currentLeaderId = null;
        MDC.put("raftRole", "FOLLOWER");
        MDC.put("raftTerm", String.valueOf(currentTerm));
        logger.warn("Node {} stepping down in term {}: a majority has not responded for {} heartbeat rounds",
                nodeId, currentTerm, quorumContactRounds);
        notifyStateChangeListeners(State.FOLLOWER);
        failPendingCommands(new CommandOutcomeUnknownException(
                "Leadership lost because a majority is unreachable; outcome may be unknown"));
        cancelRoleTimers();
        if (running) resetElectionTimer();
    }

    private record HeartbeatTimerDecision(
            long timerGeneration, long term, long leaderGeneration) {}

    private void sendHeartbeats() {
        if (state != State.LEADER)
            return;

        for (RaftConfiguration.Server peer : peers()) sendAppendEntries(peer.name(), true);
    }

    private void failPendingCommands(Throwable cause) {
        pendingCommands.values().forEach(promise -> promise.tryFail(cause));
        pendingCommands.clear();
    }

    // Message Handlers running on Event Loop

    /**
     * Handles incoming Raft messages using pattern matching.
     * <p>Uses Java 21+ sealed interface pattern matching for type-safe,
     * exhaustive message handling.
     * 
     * @param message the incoming RaftMessage
     */
    private void handleMessage(RaftMessage message) {
        // Ensure we are on the node's Java runtime state-loop context.
        if (JavaRuntime.currentContext() != runtime) {
            runOnContext(v -> handleMessage(message));
            return;
        }

        // Exhaustive pattern matching - compiler enforces all cases handled
        switch (message) {
            case RaftMessage.Vote vote -> handleVoteRequest(vote.request());
            case RaftMessage.AppendEntries ae -> handleAppendEntriesRequest(ae.request());
        }
    }

    /**
     * Handles RequestVote RPC from a Candidate.
     * <p>Implements Persist-before-Grant:
     * <ul>
     *   <li>Vote is only granted AFTER metadata is durable</li>
     *   <li>Prevents double-voting after crash/restart</li>
     * </ul>
     */
    public Future<VoteResponse> handleVoteRequest(VoteRequest request) {
        requireNonNull(request, "request");
        if (request.getCandidateId().isBlank()) {
            // Granting it would record a blank vote and refuse every real candidate for the rest of the term.
            return Future.failedFuture(new IllegalArgumentException("A vote request must name its candidate"));
        }
        return transitionSequencer.submitEssential(
                "request-vote:" + request.getCandidateId() + ":" + request.getTerm(),
                RaftTransitionSequencer.FailurePolicy.FENCE,
                () -> prepareAndPersistVote(request),
                this::applyVoteDecision);
    }

    private Future<VoteDecision> prepareAndPersistVote(VoteRequest request) {
        if (!running) {
            // Before recovery completes, term and vote are defaults, not durable state; after stop they are
            // no longer served. Deciding now could persist a second vote in an already-voted term.
            logger.debug("Rejecting vote from {} for term {}: node is not running",
                    request.getCandidateId(), request.getTerm());
            return Future.succeededFuture(new VoteDecision(currentTerm, votedFor, false, false));
        }
        long requestedTerm = request.getTerm();
        // A removed server may retain an old configuration and campaign indefinitely.
        // Ignore campaigns while a leader is live, before adopting even a higher term.
        // The election timeout releases followers; check-quorum releases leaders.
        if (state == State.LEADER || (leaderContactActive
                && timerScheduler.nanoTime() - lastLeaderContactNanos
                < TimeUnit.MILLISECONDS.toNanos(electionTimeoutMs))) {
            return Future.succeededFuture(new VoteDecision(currentTerm, votedFor, false, false));
        }
        logger.debug("Handling vote request: candidateId={}, requestTerm={}, localTerm={}, localVotedFor={}, candidateLastLogTerm={}, candidateLastLogIndex={}",
                request.getCandidateId(), requestedTerm, currentTerm, votedFor,
                request.getLastLogTerm(), request.getLastLogIndex());

        if (requestedTerm < currentTerm) {
            logger.debug("Rejecting vote: stale term {} < {}", requestedTerm, currentTerm);
            return Future.succeededFuture(new VoteDecision(
                    currentTerm, null, false, false));
        }

        boolean higherTerm = requestedTerm > currentTerm;
        String effectiveVotedFor = higherTerm ? null : votedFor;
        boolean candidateAvailable = effectiveVotedFor == null
                || effectiveVotedFor.equals(request.getCandidateId());
        boolean candidateLogUpToDate = isCandidateLogUpToDate(
                request.getLastLogTerm(), request.getLastLogIndex());
        boolean grant = candidateAvailable && candidateLogUpToDate;

        logger.debug("Vote decision inputs: candidateAvailable={}, candidateLogUpToDate={}, grant={}, effectiveTerm={}",
                candidateAvailable, candidateLogUpToDate, grant, requestedTerm);

        if (!grant && !higherTerm) {
            return Future.succeededFuture(new VoteDecision(
                    currentTerm, votedFor, false, false));
        }

        Optional<String> durableVote = grant
                ? Optional.of(request.getCandidateId())
                : Optional.empty();
        return persistMetadata(requestedTerm, durableVote)
                .map(ignored -> new VoteDecision(
                        requestedTerm, durableVote.orElse(null), grant, higherTerm));
    }

    private VoteResponse applyVoteDecision(VoteDecision decision) {
        if (decision.higherTerm()) {
            applyDurableHigherTerm(decision.term(), decision.votedFor());
        } else if (decision.granted()) {
            votedFor = decision.votedFor();
        }

        if (decision.granted()) {
            resetElectionTimer();
            logger.info("Vote granted to {} for term {}", decision.votedFor(), decision.term());
        }
        return VoteResponse.newBuilder()
                .setTerm(currentTerm)
                .setVoterServerId(serverId)
                .setVoteGranted(decision.granted())
                .build();
    }

    private void applyDurableHigherTerm(long newTerm, String durableVote) {
        currentTerm = newTerm;
        votedFor = durableVote;
        state = State.FOLLOWER;
        currentLeaderId = null;
        leaderContactActive = false;
        MDC.put("raftRole", "FOLLOWER");
        MDC.put("raftTerm", String.valueOf(currentTerm));
        notifyStateChangeListeners(State.FOLLOWER);
        failPendingCommands(new CommandOutcomeUnknownException(
                "Leadership lost before command commit; outcome may be unknown"));
        cancelRoleTimers();
        if (running) resetElectionTimer();
        logger.info("Applied durable higher term {}; node is FOLLOWER", currentTerm);
    }

    private record VoteDecision(long term, String votedFor, boolean granted, boolean higherTerm) {}

    private boolean isCandidateLogUpToDate(long candidateLastLogTerm, long candidateLastLogIndex) {
        long localLastLogTerm = getLastLogTerm();
        if (candidateLastLogTerm != localLastLogTerm) {
            return candidateLastLogTerm > localLastLogTerm;
        }
        return candidateLastLogIndex >= getLastLogIndex();
    }

    private Future<Void> persistMetadata(long term, Optional<String> votedForCandidate) {
        RaftTransitionSequencer.Ownership ownership = transitionSequencer.currentOwnership();
        if (logger.isDebugEnabled()) {
            logger.debug("Persisting raft metadata: term={}, votedForPresent={}, votedFor={}",
                    term, votedForCandidate.isPresent(), votedForCandidate.orElse("<none>"));
        }
        return persistence.isDurable()
                ? toFuture(persistence.updateMetadata(ownership, term, votedForCandidate))
                : Future.succeededFuture();
    }

    /**
     * Handles AppendEntries RPC from the Leader.
     * <p>Follows the Prepare → Persist → Apply sequence:
     * <ol>
     *   <li>Consistency check (prevLogIndex/prevLogTerm)</li>
     *   <li>Prepare entries to persist (handle conflicts)</li>
     *   <li>Persist to WAL with sync barrier</li>
     *   <li>Apply to in-memory log</li>
     *   <li>Update commitIndex and trigger applier</li>
     * </ol>
     */
    public Future<AppendEntriesResponse> handleAppendEntriesRequest(AppendEntriesRequest request) {
        requireNonNull(request, "request");
        Promise<AppendEntriesResponse> response = Promise.promise();
        Future<FollowerAppendResult> transition = transitionSequencer.submitEssential(
                        "append-entries:" + request.getLeaderId() + ":" + request.getTerm(),
                        RaftTransitionSequencer.FailurePolicy.FENCE,
                        () -> prepareAndPersistFollowerAppend(request),
                        this::applyFollowerAppend);
        onStateLoopComplete(transition, result -> {
            if (result.failed()) {
                Throwable error = result.cause();
                if (error instanceof RaftTransitionSequencer.DrainingException) {
                    logger.debug("AppendEntries rejected while draining: {}", error.getMessage());
                } else {
                    logger.error("AppendEntries failed during durable transition: {}",
                            error.getMessage(), error);
                }
                response.tryComplete(AppendEntriesResponse.newBuilder()
                        .setTerm(currentTerm)
                        .setSuccess(false)
                        .build());
            } else if (result.result().requestFailure() != null) {
                response.tryFail(result.result().requestFailure());
            } else {
                response.tryComplete(result.result().response());
            }
        });
        return response.future().map(this::fromThisFollower);
    }

    /**
     * Whether a leader addressed a request to a server ID that is not this server's. A server that lost its
     * storage answers at its old address under a new server ID. If it took the log or snapshot meant for the
     * entry it replaced, it would hold a configuration that leaves it out, and stop asking to join. A request
     * that names no server is taken as before.
     */
    private boolean isMeantForAnotherServer(String targetServerId) {
        return !targetServerId.isEmpty() && !targetServerId.equals(serverId);
    }

    /** The server ID the configuration records for the peer named {@code target}, or empty if it has none. */
    private String serverIdOfPeer(String target) {
        RaftConfiguration current = configuration;
        return current == null ? "" : current.serverNamed(target).map(RaftConfiguration.Server::serverId).orElse("");
    }

    private AppendEntriesResponse fromThisFollower(AppendEntriesResponse response) {
        return response.toBuilder().setFollowerServerId(serverId).build();
    }

    private Future<FollowerAppendDecision> prepareAndPersistFollowerAppend(
            AppendEntriesRequest request) {
        if (!running) {
            // Recovery has not rebuilt the durable term and log yet, or the node has stopped.
            logger.debug("Rejecting AppendEntries from {}: node is not running", request.getLeaderId());
            return Future.succeededFuture(FollowerAppendDecision.stale(request));
        }
        if (isMeantForAnotherServer(request.getTargetServerId())) {
            logger.debug("Rejecting AppendEntries from {}: it is meant for server {}, not this one",
                    request.getLeaderId(), request.getTargetServerId());
            return Future.succeededFuture(FollowerAppendDecision.stale(request));
        }
        if (request.getTerm() < currentTerm) {
            logger.debug("Rejecting AppendEntries: stale term {} < {}", request.getTerm(), currentTerm);
            return Future.succeededFuture(FollowerAppendDecision.stale(request));
        }

        boolean higherTerm = request.getTerm() > currentTerm;
        Future<Void> termPersistence = higherTerm
                ? persistMetadata(request.getTerm(), Optional.empty())
                : Future.succeededFuture();

        // An entry a snapshot covers is committed, so by the Log Matching Property it is identical on the
        // leader; a retransmission from before this node compacted must not be refused as inconsistent.
        boolean previousEntryInSnapshot = request.getPrevLogIndex() < snapshotLastIndex;
        if (!previousEntryInSnapshot && (!hasLogEntry(request.getPrevLogIndex()) ||
                log.get(toArrayIndex(request.getPrevLogIndex())).getTerm() != request.getPrevLogTerm())) {
            logger.debug("Rejecting AppendEntries: log inconsistent at prevLogIndex={}",
                    request.getPrevLogIndex());
            return termPersistence.map(ignored ->
                    FollowerAppendDecision.inconsistent(request, higherTerm));
        }

        long startIndex = request.getPrevLogIndex() + 1;
        List<LogEntry> incomingEntries = new ArrayList<>();
        List<LogEntryData> incomingEntryData = new ArrayList<>();

        long currentIndex = startIndex;
        try {
            for (dev.mars.qraft.raft.grpc.LogEntry entryProto : request.getEntriesList()) {
                RaftCommand command = deserialize(entryProto.getData());
                LogEntry newEntry = new LogEntry(entryProto.getTerm(), currentIndex, command,
                        entryProto.getData().toByteArray());
                incomingEntries.add(newEntry);
                incomingEntryData.add(new LogEntryData(
                        currentIndex, entryProto.getTerm(), entryProto.getData().toByteArray()));
                currentIndex++;
            }
        } catch (RuntimeException error) {
            logger.warn("Rejecting AppendEntries with invalid command payload from leader {} at index {}",
                    request.getLeaderId(), currentIndex);
            IllegalArgumentException requestFailure = new IllegalArgumentException(
                    "Invalid command payload at log index " + currentIndex, error);
            return termPersistence.map(ignored ->
                    FollowerAppendDecision.invalid(request, higherTerm, requestFailure));
        }

        for (LogEntry incoming : incomingEntries) {
            if (incoming.getIndex() == 1
                    && (incoming.getTerm() != 0 || !(incoming.getCommand() instanceof ConfigurationCommand))) {
                // Every cluster's log begins with its bootstrap configuration; anything else would leave this
                // server holding Raft state without a configuration.
                logger.warn("Rejecting AppendEntries from leader {}: index 1 is not a bootstrap configuration",
                        request.getLeaderId());
                IllegalArgumentException requestFailure = new IllegalArgumentException(
                        "Log index 1 must hold the bootstrap configuration, in term 0");
                return termPersistence.map(ignored ->
                        FollowerAppendDecision.invalid(request, higherTerm, requestFailure));
            }
        }

        // Exclude Qraft's in-memory snapshot sentinel. RaftLog receives absolute
        // indices plus the inclusive snapshot/compaction boundary.
        List<LogEntryData> currentEntryData = log.stream().skip(1)
                .map(entry -> new LogEntryData(entry.getIndex(), entry.getTerm(),
                        payloadOf(entry).toByteArray()))
                .toList();
        AppendPlan appendPlan = AppendPlan.from(
                startIndex, incomingEntryData, currentEntryData, snapshotLastIndex);
        Long truncateFromIndex = appendPlan.truncateFromIndex();
        if (truncateFromIndex != null && truncateFromIndex <= commitIndex) {
            // A correct leader never conflicts with a committed entry. Replacing it would lose the entry, which
            // is never applied again, and at index 1 the cluster's configuration with it.
            logger.error("Refusing AppendEntries from leader {}: it would replace the committed entry at index {} "
                    + "(commit index {})", request.getLeaderId(), truncateFromIndex, commitIndex);
            IllegalStateException refusal = new IllegalStateException("AppendEntries would replace the committed "
                    + "entry at index " + truncateFromIndex + " (commit index " + commitIndex + ")");
            return termPersistence.map(ignored -> FollowerAppendDecision.invalid(request, higherTerm, refusal));
        }
        Set<Long> indicesToAppend = appendPlan.entriesToAppend().stream()
                .map(LogEntryData::index)
                .collect(java.util.stream.Collectors.toSet());
        List<LogEntry> entriesToPersist = incomingEntries.stream()
                .filter(entry -> indicesToAppend.contains(entry.getIndex()))
                .toList();

        FollowerAppendDecision decision = FollowerAppendDecision.accepted(
                request, higherTerm, truncateFromIndex, entriesToPersist);
        return composeOnStateLoop(termPersistence, ignored ->
                persistAppendEntries(truncateFromIndex, entriesToPersist)
                        .map(decision)
                        .recover(error -> {
                            if (isAmbiguousFollowerAppendFailure(
                                    error, truncateFromIndex, entriesToPersist.size())) {
                                return Future.failedFuture(error);
                            }
                            logger.warn("Rejecting AppendEntries before a WAL mutation: {}",
                                    error.getMessage());
                            return Future.succeededFuture(
                                    FollowerAppendDecision.persistenceRejected(request, higherTerm));
                        }));
    }

    private FollowerAppendResult applyFollowerAppend(FollowerAppendDecision decision) {
        AppendEntriesRequest request = decision.request();
        if (!decision.termAccepted()) {
            return FollowerAppendResult.response(AppendEntriesResponse.newBuilder()
                    .setTerm(currentTerm)
                    .setSuccess(false)
                    .build());
        }

        if (decision.higherTerm()) {
            applyDurableHigherTerm(request.getTerm(), null);
        } else if (state != State.FOLLOWER) {
            state = State.FOLLOWER;
            MDC.put("raftRole", "FOLLOWER");
            MDC.put("raftTerm", String.valueOf(currentTerm));
            notifyStateChangeListeners(State.FOLLOWER);
            cancelHeartbeatTimer();
        }
        currentLeaderId = request.getLeaderId();
        leaderContactActive = true;
        lastLeaderContactNanos = timerScheduler.nanoTime();
        resetElectionTimer();

        if (decision.requestFailure() != null) {
            return FollowerAppendResult.failure(decision.requestFailure());
        }
        if (!decision.logAccepted()) {
            return FollowerAppendResult.response(AppendEntriesResponse.newBuilder()
                    .setTerm(currentTerm)
                    .setSuccess(false)
                    .setMatchIndex(lastLogIndex())
                    .build());
        }

        if (decision.truncateFromIndex() != null) {
            int truncateArrayIdx = toArrayIndex(decision.truncateFromIndex());
            log.subList(truncateArrayIdx, log.size()).clear();
            forgetConfigurationsFrom(decision.truncateFromIndex());
        }
        for (LogEntry entry : decision.entriesToPersist()) {
            if (!hasLogEntry(entry.getIndex())) {
                log.add(entry);
                recordIfConfiguration(entry);
            }
        }

        // The request verifies this log only through its last entry, or through the snapshot it overlaps.
        // A tail beyond that may be an uncommitted entry from an earlier term that no conflict has
        // truncated yet: reporting it would let the leader count this node for entries it does not hold,
        // and committing it would apply an entry the cluster never committed.
        long verifiedIndex = Math.max(request.getPrevLogIndex() + request.getEntriesCount(), snapshotLastIndex);
        long leaderCommit = Math.min(request.getLeaderCommit(), verifiedIndex);
        if (leaderCommit > commitIndex) {
            commitIndex = leaderCommit;
            applyLog();
        }

        if (request.getEntriesCount() == 0) {
            logger.trace("AppendEntries heartbeat accepted: commitIndex={}", commitIndex);
        } else {
            logger.debug("AppendEntries success: entries={}, logSize={}, commitIndex={}",
                    request.getEntriesCount(), log.size(), commitIndex);
        }
        return FollowerAppendResult.response(AppendEntriesResponse.newBuilder()
                .setTerm(currentTerm)
                .setSuccess(true)
                .setMatchIndex(verifiedIndex)
                .build());
    }

    private record FollowerAppendDecision(
            AppendEntriesRequest request,
            boolean termAccepted,
            boolean higherTerm,
            boolean logAccepted,
            Long truncateFromIndex,
            List<LogEntry> entriesToPersist,
            Throwable requestFailure) {

        private static FollowerAppendDecision stale(AppendEntriesRequest request) {
            return new FollowerAppendDecision(request, false, false, false,
                    null, List.of(), null);
        }

        private static FollowerAppendDecision inconsistent(
                AppendEntriesRequest request, boolean higherTerm) {
            return new FollowerAppendDecision(request, true, higherTerm, false,
                    null, List.of(), null);
        }

        private static FollowerAppendDecision invalid(
                AppendEntriesRequest request, boolean higherTerm, Throwable failure) {
            return new FollowerAppendDecision(request, true, higherTerm, false,
                    null, List.of(), failure);
        }

        private static FollowerAppendDecision persistenceRejected(
                AppendEntriesRequest request, boolean higherTerm) {
            return new FollowerAppendDecision(request, true, higherTerm, false,
                    null, List.of(), null);
        }

        private static FollowerAppendDecision accepted(
                AppendEntriesRequest request, boolean higherTerm,
                Long truncateFromIndex, List<LogEntry> entriesToPersist) {
            return new FollowerAppendDecision(request, true, higherTerm, true,
                    truncateFromIndex, List.copyOf(entriesToPersist), null);
        }
    }

    private record FollowerAppendResult(
            AppendEntriesResponse response, Throwable requestFailure) {
        private static FollowerAppendResult response(AppendEntriesResponse response) {
            return new FollowerAppendResult(response, null);
        }

        private static FollowerAppendResult failure(Throwable error) {
            return new FollowerAppendResult(null, error);
        }
    }

    /**
     * Persists append entries to WAL with optional truncation.
     */
    private Future<Void> persistAppendEntries(Long truncateFromIndex, List<LogEntry> entries) {
        RaftTransitionSequencer.Ownership ownership = transitionSequencer.currentOwnership();
        if (!persistence.isDurable()) {
            return Future.succeededFuture();  // Volatile mode
        }
        
        if (entries.isEmpty() && truncateFromIndex == null) {
            return Future.succeededFuture();  // Nothing to persist (heartbeat)
        }

        Future<Void> f = Future.succeededFuture();

        // Truncate if needed
        if (truncateFromIndex != null) {
            f = f.compose(v -> toFuture(
                    persistence.truncateSuffix(ownership, truncateFromIndex)));
        }

        // Append entries
        if (!entries.isEmpty()) {
            List<LogEntryData> entryDataList = entries.stream()
                .map(e -> new LogEntryData(e.getIndex(), e.getTerm(), payloadOf(e).toByteArray()))
                .toList();
            f = f.compose(v -> toFuture(persistence.appendEntries(ownership, entryDataList)));
        }

        // Sync for durability
        return f.compose(v -> toFuture(persistence.sync(ownership)));
    }

    private void sendAppendEntries(String target, boolean heartbeat) {
        long originatingTerm = currentTerm;
        long originatingGeneration = leadershipGeneration;
        long nextIdx = nextIndex.getOrDefault(target, 1L);

        // If the follower needs entries we've already compacted, send a snapshot
        if (snapshotLastIndex > 0 && nextIdx <= snapshotLastIndex) {
            sendInstallSnapshot(target);
            return;
        }

        long prevLogIndex = nextIdx - 1;
        long prevLogTerm = 0;
        if (prevLogIndex >= 0 && hasLogEntry(prevLogIndex)) {
            prevLogTerm = log.get(toArrayIndex(prevLogIndex)).getTerm();
        } else if (prevLogIndex == snapshotLastIndex) {
            prevLogTerm = snapshotLastTerm;
        }

        AppendEntriesRequest.Builder builder = AppendEntriesRequest.newBuilder()
                .setTerm(originatingTerm)
                .setLeaderId(nodeId)
                .setLeaderServerId(serverId)
                .setTargetServerId(serverIdOfPeer(target))
                .setPrevLogIndex(prevLogIndex)
                .setPrevLogTerm(prevLogTerm)
                .setLeaderCommit(commitIndex);

        long lastIdx = lastLogIndex();
        boolean includeEntries = !heartbeat || nextIdx <= lastIdx;
        if (includeEntries) {
            for (long i = nextIdx; i <= lastIdx; i++) {
                if (hasLogEntry(i)) {
                    LogEntry entry = log.get(toArrayIndex(i));
                    builder.addEntries(dev.mars.qraft.raft.grpc.LogEntry.newBuilder()
                            .setTerm(entry.getTerm())
                            .setIndex(entry.getIndex())
                            .setData(payloadOf(entry))
                            .build());
                }
            }
        }

        transport.sendAppendEntries(target, builder.build())
                .onSuccess(response -> handleAppendEntriesResponse(
                        target, response, originatingTerm, originatingGeneration))
                .onFailure(error -> handleAppendEntriesFailure(
                        target, error, originatingTerm, originatingGeneration));

        // Record edge metric for nodeGraph visualization
        rpcCounter.add(1, Attributes.of(
                AttributeKey.stringKey("source"), nodeId,
                AttributeKey.stringKey("target"), target,
                AttributeKey.stringKey("type"), includeEntries ? "append_entries" : "heartbeat"
        ));
    }

    private void handleAppendEntriesResponse(
            String peerId, AppendEntriesResponse response,
            long originatingTerm, long originatingGeneration) {
        transitionSequencer.submit(
                        "append-response:" + peerId + ":" + originatingTerm,
                        RaftTransitionSequencer.FailurePolicy.FENCE,
                        () -> prepareAppendEntriesResponse(
                                peerId, response, originatingTerm, originatingGeneration),
                        this::applyAppendEntriesResponse)
                .onFailure(error -> {
                    if (error instanceof RaftTransitionSequencer.DrainingException) {
                        logger.debug("Ignoring AppendEntries response from {} while draining: {}",
                                peerId, error.getMessage());
                    } else {
                        logger.error("Failed to process AppendEntries response from {}: {}",
                                peerId, error.getMessage(), error);
                    }
                });
    }

    private Future<AppendResponseDecision> prepareAppendEntriesResponse(
            String peerId, AppendEntriesResponse response,
            long originatingTerm, long originatingGeneration) {
        if (!fromConfiguredServer(peerId, response.getFollowerServerId())) {
            logger.warn("Ignoring an AppendEntries response from {} with server ID {}, which is not the "
                    + "configured server", peerId, response.getFollowerServerId());
            return Future.succeededFuture(null);
        }
        if (response.getTerm() > currentTerm) {
            return persistMetadata(response.getTerm(), Optional.empty())
                    .map(ignored -> new AppendResponseDecision(
                            peerId, response, originatingTerm, originatingGeneration, true));
        }
        return Future.succeededFuture(new AppendResponseDecision(
                peerId, response, originatingTerm, originatingGeneration, false));
    }

    private Void applyAppendEntriesResponse(AppendResponseDecision decision) {
        if (decision == null) return null;
        AppendEntriesResponse response = decision.response();
        if (decision.higherTerm()) {
            applyDurableHigherTerm(response.getTerm(), null);
            return null;
        }
        if (!isCurrentLeadership(decision.originatingTerm(), decision.originatingGeneration())) {
            logger.debug("Ignoring stale AppendEntries response from {} for term {} generation {}",
                    decision.peerId(), decision.originatingTerm(), decision.originatingGeneration());
            return null;
        }

        recordPeerContact(decision.peerId());
        if (unavailablePeers.remove(decision.peerId())) {
            logger.info("Raft peer {} is reachable again", decision.peerId());
        }
        if (response.getSuccess()) {
            // Replies can arrive out of order: one to an earlier, shorter request must not move the follower
            // back, or the leader stops counting entries the follower holds until they are sent again.
            long matched = Math.max(matchIndex.getOrDefault(decision.peerId(), 0L), response.getMatchIndex());
            matchIndex.put(decision.peerId(), matched);
            nextIndex.put(decision.peerId(), Math.max(nextIndex.getOrDefault(decision.peerId(), 1L), matched + 1));
            updateCommitIndex();
        } else {
            long next = nextIndex.getOrDefault(decision.peerId(), 1L);
            nextIndex.put(decision.peerId(), Math.max(1, next - 1));
        }
        return null;
    }

    private void handleAppendEntriesFailure(
            String peerId, Throwable error, long originatingTerm, long originatingGeneration) {
        transitionSequencer.submit(
                        "append-failure:" + peerId + ":" + originatingTerm,
                        RaftTransitionSequencer.FailurePolicy.CONTINUE,
                        () -> Future.succeededFuture(new AppendFailureDecision(
                                peerId, error, originatingTerm, originatingGeneration)),
                        this::applyAppendEntriesFailure)
                .onFailure(rejected -> logger.debug(
                        "Could not admit AppendEntries failure notification for {}: {}",
                        peerId, rejected.toString()));
    }

    private Void applyAppendEntriesFailure(AppendFailureDecision decision) {
        if (!isCurrentLeadership(decision.originatingTerm(), decision.originatingGeneration())) {
            return null;
        }
        if (unavailablePeers.add(decision.peerId())) {
            logger.error("Raft peer {} became unreachable during AppendEntries",
                    decision.peerId(), decision.error());
        } else {
            logger.debug("Raft peer {} remains unreachable during AppendEntries: {}",
                    decision.peerId(), decision.error().toString());
        }
        return null;
    }

    private boolean isCurrentLeadership(long term, long generation) {
        return state == State.LEADER && currentTerm == term && leadershipGeneration == generation;
    }

    private record AppendResponseDecision(
            String peerId, AppendEntriesResponse response,
            long originatingTerm, long originatingGeneration, boolean higherTerm) {}

    private record AppendFailureDecision(
            String peerId, Throwable error, long originatingTerm, long originatingGeneration) {}

    private void updateCommitIndex() {
        // If there exists an N such that N > commitIndex, a majority of matchIndex[i]
        // >= N,
        // and log[N].term == currentTerm: set commitIndex = N

        RaftConfiguration current = configuration;
        if (current == null) return;
        List<Long> indices = new ArrayList<>();
        for (RaftConfiguration.Server server : current.servers()) {
            if (!server.voter()) continue;
            indices.add(server.serverId().equals(serverId)
                    ? lastLogIndex()
                    : matchIndex.getOrDefault(server.name(), 0L));
        }
        Collections.sort(indices);
        // The highest index held by a majority of the voters: in ascending order, the voters at and after
        // this position number exactly the quorum, voters / 2 + 1. Taking voters / 2 instead counts only
        // half of an even-sized cluster, letting two of four voters, or the leader of two alone, commit.
        long N = indices.get(indices.size() - current.quorum());

        if (N > commitIndex && hasLogEntry(N) && log.get(toArrayIndex(N)).getTerm() == currentTerm) {
            commitIndex = N;
            applyLog();
            stepDownIfRemoved();
        }
    }

    /**
     * Whether this server knows that its removal has committed: both the latest configuration and the one in
     * force at its commit index leave it out. A leader that removes itself knows this; a follower removed by
     * another leader is no longer replicated to, so it may never learn it.
     */
    private boolean isRemoved() {
        RaftConfiguration current = configuration;
        if (current == null || current.server(serverId).isPresent()) return false;
        RaftConfiguration committed = configurationAt(commitIndex);
        return committed != null && committed.server(serverId).isEmpty();
    }

    /**
     * A leader whose removal has committed steps down. Outside the configuration it never campaigns, so the
     * servers left elect a leader among themselves.
     */
    private void stepDownIfRemoved() {
        RaftConfiguration current = configuration;
        if (state != State.LEADER || !isRemoved()) return;
        state = State.FOLLOWER;
        currentLeaderId = null;
        MDC.put("raftRole", "FOLLOWER");
        logger.info("Node {} stepping down in term {}: its removal from the configuration has committed",
                nodeId, currentTerm);
        notifyStateChangeListeners(State.FOLLOWER);
        failPendingCommands(new CommandOutcomeUnknownException(
                "Leadership ended because this server was removed; outcome may be unknown"));
        cancelRoleTimers();
        if (running) resetElectionTimer();
    }

    /**
     * Applies committed entries in order. An entry the state machine fails to apply fences the node:
     * skipping it would leave this replica different from every replica that applied it, whether the
     * failure was local or the command is one an older server does not know. The applied index stays
     * before the entry, nothing after it is applied, and a fenced node applies nothing more.
     */
    private void applyLog() {
        if (transitionSequencer.isFenced()) return;
        while (lastApplied < commitIndex) {
            long index = lastApplied + 1;
            if (!hasLogEntry(index)) {
                // Entry already compacted by snapshot - skip
                lastApplied = index;
                continue;
            }
            LogEntry entry = log.get(toArrayIndex(index));
            RaftCommandResult<?> result = null;
            try {
                if (entry.getCommand() != null && !(entry.getCommand() instanceof ConfigurationCommand)) {
                    result = stateMachine.apply(entry.getCommand());
                }
                stateMachine.setLastAppliedIndex(index);
            } catch (Exception e) {
                fenceOnApplyFailure(index, e);
                return;
            }
            lastApplied = index;

            // Complete future if this node is leader
            Promise<RaftCommandResult<?>> promise = pendingCommands.remove(index);
            if (promise != null) promise.complete(result);
        }
    }

    private void fenceOnApplyFailure(long index, Exception failure) {
        logger.error("Fencing node {}: committed entry {} could not be applied; this replica must not continue "
                + "without it: {}", nodeId, index, failure.getMessage(), failure);
        Promise<RaftCommandResult<?>> failed = pendingCommands.remove(index);
        if (failed != null) failed.fail(failure);
        failPendingCommands(new CommandOutcomeUnknownException(
                "Node fenced after failing to apply committed entry " + index + "; outcome may be unknown"));
        transitionSequencer.fence(failure);
    }

    // ========== SNAPSHOT SCHEDULING ==========

    /**
     * Starts periodic snapshot eligibility checks, which run in every role for as long as the node runs.
     * Each server compacts its own log independently (Raft section 7): a follower that never compacted
     * would hold every entry in memory and in its WAL, replay all of them on restart, and, once elected,
     * refuse writes until its first snapshot. The check fires at the configured interval and takes a
     * snapshot of applied state when (lastApplied - snapshotLastIndex) reaches the threshold.
     */
    private void startSnapshotScheduler() {
        if (!snapshotEnabled) {
            return;
        }
        cancelSnapshotTimer();
        long timerGeneration = snapshotTimerGeneration;
        snapshotTimerId = setPeriodic(snapshotCheckIntervalMs,
                id -> onSnapshotTimer(id, timerGeneration));
        logger.info("Snapshot scheduler started: threshold={}, checkInterval={}ms",
                snapshotThreshold, snapshotCheckIntervalMs);
    }

    /**
     * Checks whether a snapshot is needed and takes one if the threshold is reached. The snapshot covers
     * only applied, and therefore committed, entries, so it is safe in any role.
     */
    private void onSnapshotTimer(long timerId, long timerGeneration) {
        if (timerId != snapshotTimerId
                || timerGeneration != snapshotTimerGeneration
                || snapshotTimerTransitionPending) {
            return;
        }
        if (transitionSequencer.isFenced()) {
            cancelSnapshotTimer();
            logger.debug("Snapshot timer stopped because the Raft transition sequencer is fenced");
            return;
        }
        snapshotTimerTransitionPending = true;
        transitionSequencer.submit(
                        "snapshot-timer",
                        RaftTransitionSequencer.FailurePolicy.FENCE,
                        () -> prepareScheduledSnapshot(timerGeneration),
                        this::applyLocalSnapshot)
                .onComplete(result -> {
                    snapshotTimerTransitionPending = false;
                    if (result.failed()) {
                        if (result.cause() instanceof RaftTransitionSequencer.FencedException) {
                            logger.debug("Scheduled snapshot rejected because the sequencer is fenced");
                        } else {
                            logger.error("Scheduled snapshot failed: {}",
                                    result.cause().getMessage(), result.cause());
                        }
                    } else if (result.result().failure() != null) {
                        logger.error("Scheduled snapshot failed: {}",
                                result.result().failure().getMessage(), result.result().failure());
                    }
                });
    }

    private Future<LocalSnapshotDecision> prepareScheduledSnapshot(long timerGeneration) {
        if (!snapshotEnabled || !running || timerGeneration != snapshotTimerGeneration) {
            return Future.succeededFuture(LocalSnapshotDecision.skipped());
        }

        long entriesSinceSnapshot = lastApplied - snapshotLastIndex;
        if (entriesSinceSnapshot < snapshotThreshold) {
            logger.debug("Snapshot check: {} entries since last snapshot (threshold: {})",
                    entriesSinceSnapshot, snapshotThreshold);
            return Future.succeededFuture(LocalSnapshotDecision.skipped());
        }

        logger.info("Snapshot threshold reached: {} entries since last snapshot, triggering snapshot",
                entriesSinceSnapshot);
        return preparePublishAndCompactLocalSnapshot();
    }

    /**
     * Takes a snapshot of the current state machine state, persists it, and
     * truncates the log prefix up to the snapshot index.
     *
     * <p>Flow: takeSnapshot() → saveSnapshot() → truncatePrefix() → trim in-memory log</p>
     *
     * @return a Future that completes when the snapshot is saved and log is compacted
     */
    public Future<Void> takeSnapshot() {
        if (!persistence.isDurable()) {
            return Future.failedFuture(new IllegalStateException("Cannot take snapshot in volatile mode"));
        }

        return transitionSequencer.submit(
                        "local-snapshot",
                        RaftTransitionSequencer.FailurePolicy.FENCE,
                        this::preparePublishAndCompactLocalSnapshot,
                        this::applyLocalSnapshot)
                .compose(result -> result.failure() == null
                        ? Future.<Void>succeededFuture()
                        : Future.<Void>failedFuture(result.failure()))
                .onFailure(err -> logger.error("Snapshot failed: {}", err.getMessage(), err));
    }

    private Future<LocalSnapshotDecision> preparePublishAndCompactLocalSnapshot() {
        RaftTransitionSequencer.Ownership ownership = transitionSequencer.currentOwnership();
        long snapshotIndex = lastApplied;
        if (snapshotIndex <= snapshotLastIndex) {
            logger.debug("No new entries to snapshot (lastApplied={}, snapshotLastIndex={})",
                    lastApplied, snapshotLastIndex);
            return Future.succeededFuture(LocalSnapshotDecision.skipped());
        }

        if (!hasLogEntry(snapshotIndex)) {
            return Future.succeededFuture(LocalSnapshotDecision.failedBeforePublication(
                    new IllegalStateException("Cannot resolve exact term for snapshot boundary "
                            + snapshotIndex + " (snapshotLastIndex=" + snapshotLastIndex
                            + ", lastLogIndex=" + lastLogIndex() + ")")));
        }
        long snapshotTerm = log.get(toArrayIndex(snapshotIndex)).getTerm();

        long startTime = System.currentTimeMillis();
        SnapshotData snapshot;
        try {
            RaftConfiguration covered = configurationAt(snapshotIndex);
            if (covered == null) {
                throw new IllegalStateException("No configuration is in force at snapshot index " + snapshotIndex);
            }
            snapshot = new SnapshotData(
                    SnapshotEnvelope.wrap(covered, stateMachine.takeSnapshot()), snapshotIndex, snapshotTerm);
        } catch (RuntimeException error) {
            return Future.succeededFuture(
                    LocalSnapshotDecision.failedBeforePublication(error));
        }

        logger.info("Snapshot taken at index={}, term={}, size={}bytes, compacting {} entries",
                snapshotIndex, snapshotTerm, snapshot.data().length,
                snapshotIndex - snapshotLastIndex);

        LocalSnapshotDecision captured = LocalSnapshotDecision.captured(
                snapshot, snapshotLastIndex, startTime);
        Future<LocalSnapshotDecision> publication;
        try {
            publication = toFuture(persistence.saveSnapshot(ownership, snapshot))
                    .map(ignored -> captured)
                    .recover(error -> recoverableSnapshotPublicationCause(error)
                            .map(cause -> Future.succeededFuture(
                                    LocalSnapshotDecision.failedBeforePublication(cause)))
                            .orElseGet(() -> Future.failedFuture(error)));
        } catch (RuntimeException error) {
            publication = recoverableSnapshotPublicationCause(error)
                    .map(cause -> Future.succeededFuture(
                            LocalSnapshotDecision.failedBeforePublication(cause)))
                    .orElseGet(() -> Future.failedFuture(error));
        }

        return publication.compose(decision -> {
            if (decision.failure() != null) return Future.succeededFuture(decision);
            return toFuture(persistence.truncatePrefix(ownership, snapshotIndex)).map(decision);
        });
    }

    private LocalSnapshotResult applyLocalSnapshot(LocalSnapshotDecision decision) {
        if (decision.noOp() || decision.failure() != null) {
            return new LocalSnapshotResult(decision.failure());
        }

        SnapshotData snapshot = decision.snapshot();
        long snapshotIndex = snapshot.lastIncludedIndex();
        long snapshotTerm = snapshot.lastIncludedTerm();
        if (snapshotLastIndex != decision.previousSnapshotIndex()
                || !hasLogEntry(snapshotIndex)
                || log.get(toArrayIndex(snapshotIndex)).getTerm() != snapshotTerm) {
            throw new IllegalStateException("Snapshot boundary changed before application: index="
                    + snapshotIndex + ", term=" + snapshotTerm);
        }

        snapshotConfiguration = configurationAt(snapshotIndex);
        logConfigurations.headMap(snapshotIndex, true).clear();
        refreshConfiguration();
        int removeCount = toArrayIndex(snapshotIndex);
        if (removeCount > 0) log.subList(0, removeCount).clear();
        snapshotLastIndex = snapshotIndex;
        snapshotLastTerm = snapshotTerm;
        log.set(0, new LogEntry(snapshotTerm, snapshotIndex, null));

        long duration = System.currentTimeMillis() - decision.startTime();
        snapshotCounter.add(1);
        snapshotDuration.record(duration);
        logCompactedEntries.add(snapshotIndex - decision.previousSnapshotIndex());

        logger.info("Snapshot complete: snapshotIndex={}, logSize={}, duration={}ms",
                snapshotIndex, log.size(), duration);
        return new LocalSnapshotResult(null);
    }

    private record LocalSnapshotDecision(
            SnapshotData snapshot,
            long previousSnapshotIndex,
            long startTime,
            boolean noOp,
            Throwable failure) {

        private static LocalSnapshotDecision captured(
                SnapshotData snapshot, long previousSnapshotIndex, long startTime) {
            return new LocalSnapshotDecision(
                    snapshot, previousSnapshotIndex, startTime, false, null);
        }

        private static LocalSnapshotDecision skipped() {
            return new LocalSnapshotDecision(null, 0, 0, true, null);
        }

        private static LocalSnapshotDecision failedBeforePublication(Throwable error) {
            return new LocalSnapshotDecision(null, 0, 0, false, error);
        }
    }

    private record LocalSnapshotResult(Throwable failure) {}

    /**
     * Returns whether snapshot scheduling is enabled for this node.
     */
    public boolean isSnapshotEnabled() {
        return snapshotEnabled;
    }

    // ========== INSTALL SNAPSHOT (Leader Side) ==========

    /**
     * Sends a snapshot to a lagging follower using chunked transfer.
     * Called from {@link #sendAppendEntries(String, boolean)} when the follower's
     * nextIndex is behind the compacted snapshot boundary.
     *
     * <p>Flow: load snapshot from storage → split into chunks → send sequentially →
     * on final ACK, update nextIndex/matchIndex for the follower.</p>
     *
     * @param target the follower node ID
     */
    private void sendInstallSnapshot(String target) {
        long retryAfter = outboundSnapshotRetryAfterNanos.getOrDefault(target, 0L);
        if (System.nanoTime() < retryAfter) {
            logger.debug("InstallSnapshot retry for {} is in backoff", target);
            return;
        }
        outboundSnapshotRetryAfterNanos.remove(target);
        // Prevent concurrent snapshot installs to the same follower
        if (outboundSnapshotTransfers.containsKey(target)) {
            logger.debug("InstallSnapshot already in progress for {}, skipping", target);
            return;
        }
        if (!persistence.isDurable()) {
            logger.warn("Cannot send InstallSnapshot: no storage configured");
            return;
        }

        OutboundSnapshotTransfer transfer = new OutboundSnapshotTransfer(
                target, currentTerm, leadershipGeneration, ++outboundSnapshotTransferSequence);
        if (!beginOwnedAsyncOperation()) return;
        outboundSnapshotTransfers.put(target, transfer);
        logger.info("Sending InstallSnapshot to lagging follower {} (nextIndex={}, snapshotLastIndex={})",
                target, nextIndex.getOrDefault(target, 1L), snapshotLastIndex);

        Future<Optional<SnapshotData>> load;
        try {
            load = toFuture(persistence.loadLatestSnapshot());
        } catch (Throwable error) {
            sequenceOutboundSnapshotLoad(transfer, Optional.empty(), error);
            return;
        }
        onStateLoopComplete(load, result -> sequenceOutboundSnapshotLoad(
                transfer,
                result.succeeded() ? result.result() : Optional.empty(),
                result.cause()));
    }

    private void sequenceOutboundSnapshotLoad(
            OutboundSnapshotTransfer transfer,
            Optional<SnapshotData> snapshot,
            Throwable error) {
        Future<Void> transition = transitionSequencer.submit(
                        "snapshot-load:" + transfer.target() + ":" + transfer.sequence(),
                        RaftTransitionSequencer.FailurePolicy.CONTINUE,
                        () -> Future.succeededFuture(
                                new OutboundSnapshotLoadDecision(transfer, snapshot, error)),
                        this::applyOutboundSnapshotLoad);
        onStateLoopComplete(transition, result -> {
            finishOwnedAsyncOperation();
            if (result.failed()) {
                removeOutboundSnapshotTransfer(transfer);
                logger.debug("Could not admit snapshot load completion for {}: {}",
                        transfer.target(), result.cause().toString());
            }
        });
    }

    private boolean beginOwnedAsyncOperation() {
        if (ownedAsyncDraining) {
            logger.debug("Rejecting new owned asynchronous operation while draining");
            return false;
        }
        ownedAsyncOperations++;
        return true;
    }

    private void finishOwnedAsyncOperation() {
        if (ownedAsyncOperations <= 0) {
            throw new IllegalStateException("Owned asynchronous operation completed without admission");
        }
        ownedAsyncOperations--;
        completeOwnedAsyncDrainIfIdle();
    }

    private Future<Void> drainOwnedAsyncOperations() {
        ownedAsyncDraining = true;
        if (ownedAsyncDrainPromise == null) ownedAsyncDrainPromise = Promise.promise();
        completeOwnedAsyncDrainIfIdle();
        return ownedAsyncDrainPromise.future();
    }

    private void completeOwnedAsyncDrainIfIdle() {
        if (ownedAsyncDrainPromise != null && ownedAsyncOperations == 0) {
            ownedAsyncDrainPromise.tryComplete();
        }
    }

    private Void applyOutboundSnapshotLoad(OutboundSnapshotLoadDecision decision) {
        OutboundSnapshotTransfer transfer = decision.transfer();
        if (!isCurrentOutboundTransfer(transfer)) return null;
        if (!isCurrentLeadership(transfer.term(), transfer.leaderGeneration())) {
            removeOutboundSnapshotTransfer(transfer);
            return null;
        }
        if (decision.error() != null) {
            logger.error("Failed to load snapshot for InstallSnapshot to {}: {}",
                    transfer.target(), decision.error().getMessage(), decision.error());
            removeOutboundSnapshotTransfer(transfer);
            return null;
        }
        if (decision.snapshot().isEmpty()) {
            logger.warn("No snapshot available to send to {}", transfer.target());
            removeOutboundSnapshotTransfer(transfer);
            return null;
        }

        SnapshotData snapshot = decision.snapshot().orElseThrow();
        byte[] data = snapshot.data();
        int totalChunks = Math.max(1,
                (int) Math.ceil((double) data.length / SNAPSHOT_CHUNK_SIZE));
        logger.info("Sending snapshot to {}: {} bytes in {} chunk(s), lastIncludedIndex={}, lastIncludedTerm={}",
                transfer.target(), data.length, totalChunks,
                snapshot.lastIncludedIndex(), snapshot.lastIncludedTerm());
        sendSnapshotChunk(transfer, snapshot, data, 0, totalChunks);
        return null;
    }

    /**
     * Sends a single snapshot chunk sequentially. On success, sends the next chunk
     * or completes the install if this was the last chunk.
     */
    private void sendSnapshotChunk(OutboundSnapshotTransfer transfer,
                                    SnapshotData snapshot, byte[] data,
                                    int chunkIndex, int totalChunks) {
        String target = transfer.target();
        if (!isCurrentOutboundTransfer(transfer)
                || !isCurrentLeadership(transfer.term(), transfer.leaderGeneration())) {
            logger.debug("Leadership ownership changed, aborting InstallSnapshot to {}", target);
            removeOutboundSnapshotTransfer(transfer);
            return;
        }
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            logger.warn("Invalid InstallSnapshot chunk {} of {} for {}",
                    chunkIndex, totalChunks, target);
            removeOutboundSnapshotTransfer(transfer);
            return;
        }

        int offset = chunkIndex * SNAPSHOT_CHUNK_SIZE;
        int length = Math.min(SNAPSHOT_CHUNK_SIZE, data.length - offset);
        boolean isLast = (chunkIndex == totalChunks - 1);

        InstallSnapshotRequest request = InstallSnapshotRequest.newBuilder()
                .setTerm(transfer.term())
                .setLeaderId(nodeId)
                .setLeaderServerId(serverId)
                .setTargetServerId(serverIdOfPeer(target))
                .setLastIncludedIndex(snapshot.lastIncludedIndex())
                .setLastIncludedTerm(snapshot.lastIncludedTerm())
                .setChunkIndex(chunkIndex)
                .setTotalChunks(totalChunks)
                .setData(com.google.protobuf.ByteString.copyFrom(data, offset, length))
                .setDone(isLast)
                .build();

        installSnapshotSent.add(1);

        transport.sendInstallSnapshot(target, request)
                .onSuccess(response -> sequenceOutboundSnapshotResponse(
                        transfer, snapshot, data, chunkIndex, totalChunks, isLast, response))
                .onFailure(error -> sequenceOutboundSnapshotFailure(
                        transfer, chunkIndex, totalChunks, error));

        rpcCounter.add(1, Attributes.of(
                AttributeKey.stringKey("source"), nodeId,
                AttributeKey.stringKey("target"), target,
                AttributeKey.stringKey("type"), "install_snapshot"
        ));
    }

    private void sequenceOutboundSnapshotResponse(
            OutboundSnapshotTransfer transfer, SnapshotData snapshot, byte[] data,
            int chunkIndex, int totalChunks, boolean last,
            InstallSnapshotResponse response) {
        transitionSequencer.submit(
                        "snapshot-response:" + transfer.target() + ":" + transfer.sequence()
                                + ":" + chunkIndex,
                        RaftTransitionSequencer.FailurePolicy.FENCE,
                        () -> prepareOutboundSnapshotResponse(
                                transfer, snapshot, data, chunkIndex, totalChunks, last, response),
                        this::applyOutboundSnapshotResponse)
                .onFailure(error -> logger.error(
                        "Failed to process InstallSnapshot response from {}: {}",
                        transfer.target(), error.getMessage(), error));
    }

    private Future<OutboundSnapshotResponseDecision> prepareOutboundSnapshotResponse(
            OutboundSnapshotTransfer transfer, SnapshotData snapshot, byte[] data,
            int chunkIndex, int totalChunks, boolean last,
            InstallSnapshotResponse response) {
        if (!fromConfiguredServer(transfer.target(), response.getFollowerServerId())) {
            logger.warn("Ignoring an InstallSnapshot response from {} with server ID {}, which is not the "
                    + "configured server", transfer.target(), response.getFollowerServerId());
            return Future.succeededFuture(null);
        }
        OutboundSnapshotResponseDecision decision = new OutboundSnapshotResponseDecision(
                transfer, snapshot, data, chunkIndex, totalChunks, last, response,
                response.getTerm() > currentTerm);
        if (!decision.higherTerm()) return Future.succeededFuture(decision);
        return persistMetadata(response.getTerm(), Optional.empty()).map(decision);
    }

    private Void applyOutboundSnapshotResponse(OutboundSnapshotResponseDecision decision) {
        if (decision == null) return null;
        InstallSnapshotResponse response = decision.response();
        if (decision.higherTerm()) {
            applyDurableHigherTerm(response.getTerm(), null);
            return null;
        }

        OutboundSnapshotTransfer transfer = decision.transfer();
        if (!isCurrentOutboundTransfer(transfer)
                || !isCurrentLeadership(transfer.term(), transfer.leaderGeneration())) {
            return null;
        }
        recordPeerContact(transfer.target());

        if (!response.getSuccess()) {
            int retryChunk = response.getNextChunkIndex();
            if (retryChunk < 0 || retryChunk >= decision.totalChunks()) {
                logger.warn("InstallSnapshot response from {} requested invalid chunk {} of {}",
                        transfer.target(), retryChunk, decision.totalChunks());
                removeOutboundSnapshotTransfer(transfer);
                return null;
            }
            if (response.getRejectionReason()
                    == InstallSnapshotResponse.RejectionReason.ASSEMBLER_STATE_LOST) {
                logger.warn("InstallSnapshot assembler state was lost by {}; restarting at chunk zero",
                        transfer.target());
                sendSnapshotChunk(transfer, decision.snapshot(), decision.data(),
                        0, decision.totalChunks());
                return null;
            }
            if (retryChunk == 0) {
                logger.warn("InstallSnapshot persistence rejected by {}; abandoning transfer and backing off",
                        transfer.target());
                outboundSnapshotRetryAfterNanos.put(transfer.target(),
                        System.nanoTime() + SNAPSHOT_RETRY_BACKOFF_NANOS);
                removeOutboundSnapshotTransfer(transfer);
                return null;
            }
            logger.warn("InstallSnapshot chunk {}/{} rejected by {}, retrying from chunk {}",
                    decision.chunkIndex() + 1, decision.totalChunks(),
                    transfer.target(), retryChunk);
            sendSnapshotChunk(transfer, decision.snapshot(), decision.data(),
                    retryChunk, decision.totalChunks());
            return null;
        }

        if (decision.last()) {
            long snapshotIndex = decision.snapshot().lastIncludedIndex();
            nextIndex.put(transfer.target(), snapshotIndex + 1);
            matchIndex.put(transfer.target(), snapshotIndex);
            removeOutboundSnapshotTransfer(transfer);
            logger.info("InstallSnapshot to {} complete: nextIndex={}, matchIndex={}",
                    transfer.target(), snapshotIndex + 1, snapshotIndex);
            updateCommitIndex();
        } else {
            sendSnapshotChunk(transfer, decision.snapshot(), decision.data(),
                    decision.chunkIndex() + 1, decision.totalChunks());
        }
        return null;
    }

    private void sequenceOutboundSnapshotFailure(
            OutboundSnapshotTransfer transfer,
            int chunkIndex, int totalChunks, Throwable error) {
        transitionSequencer.submit(
                        "snapshot-failure:" + transfer.target() + ":" + transfer.sequence()
                                + ":" + chunkIndex,
                        RaftTransitionSequencer.FailurePolicy.CONTINUE,
                        () -> Future.succeededFuture(new OutboundSnapshotFailureDecision(
                                transfer, chunkIndex, totalChunks, error)),
                        this::applyOutboundSnapshotFailure)
                .onFailure(rejected -> logger.debug(
                        "Could not admit InstallSnapshot failure for {}: {}",
                        transfer.target(), rejected.toString()));
    }

    private Void applyOutboundSnapshotFailure(OutboundSnapshotFailureDecision decision) {
        OutboundSnapshotTransfer transfer = decision.transfer();
        if (!isCurrentOutboundTransfer(transfer)
                || !isCurrentLeadership(transfer.term(), transfer.leaderGeneration())) {
            return null;
        }
        logger.error("Failed to send InstallSnapshot chunk {}/{} to {}: {}",
                decision.chunkIndex() + 1, decision.totalChunks(), transfer.target(),
                decision.error().getMessage(), decision.error());
        outboundSnapshotRetryAfterNanos.put(transfer.target(),
                System.nanoTime() + SNAPSHOT_RETRY_BACKOFF_NANOS);
        removeOutboundSnapshotTransfer(transfer);
        return null;
    }

    private boolean isCurrentOutboundTransfer(OutboundSnapshotTransfer transfer) {
        return outboundSnapshotTransfers.get(transfer.target()) == transfer;
    }

    private void removeOutboundSnapshotTransfer(OutboundSnapshotTransfer transfer) {
        outboundSnapshotTransfers.remove(transfer.target(), transfer);
    }

    private record OutboundSnapshotTransfer(
            String target, long term, long leaderGeneration, long sequence) {}

    private record OutboundSnapshotLoadDecision(
            OutboundSnapshotTransfer transfer,
            Optional<SnapshotData> snapshot,
            Throwable error) {}

    private record OutboundSnapshotResponseDecision(
            OutboundSnapshotTransfer transfer,
            SnapshotData snapshot,
            byte[] data,
            int chunkIndex,
            int totalChunks,
            boolean last,
            InstallSnapshotResponse response,
            boolean higherTerm) {}

    private record OutboundSnapshotFailureDecision(
            OutboundSnapshotTransfer transfer,
            int chunkIndex,
            int totalChunks,
            Throwable error) {}

    // ========== INSTALL SNAPSHOT (Follower Side) ==========

    /**
     * Handles an incoming InstallSnapshot RPC from the leader.
     * Reassembles chunks, saves the snapshot to storage, restores the state machine,
     * and resets the in-memory log to the snapshot boundary.
     *
     * @param request the InstallSnapshot request (single chunk)
     * @return Future containing the response
     */
    public Future<InstallSnapshotResponse> handleInstallSnapshot(InstallSnapshotRequest request) {
        requireNonNull(request, "request");
        Promise<InstallSnapshotResponse> promise = Promise.promise();
        Future<InstallSnapshotResponse> transition = transitionSequencer.submitEssential(
                        "install-snapshot:" + request.getLeaderId() + ":" + request.getTerm()
                                + ":" + request.getChunkIndex(),
                        RaftTransitionSequencer.FailurePolicy.FENCE,
                        () -> prepareAndPersistInstalledSnapshot(request),
                        this::applyInstalledSnapshot);
        onStateLoopComplete(transition, result -> {
            if (result.succeeded()) {
                promise.tryComplete(result.result());
            } else {
                Throwable error = result.cause();
                if (error instanceof RaftTransitionSequencer.DrainingException) {
                    logger.debug("InstallSnapshot rejected while draining: {}", error.getMessage());
                } else {
                    logger.error("InstallSnapshot durable transition failed: {}",
                            error.getMessage(), error);
                }
                promise.tryComplete(InstallSnapshotResponse.newBuilder()
                        .setTerm(currentTerm)
                        .setSuccess(false)
                        .setNextChunkIndex(0)
                        .setRejectionReason(
                                InstallSnapshotResponse.RejectionReason.PERSISTENCE_REJECTED)
                        .build());
            }
        });
        return promise.future().map(this::fromThisFollower);
    }

    private InstallSnapshotResponse fromThisFollower(InstallSnapshotResponse response) {
        return response.toBuilder().setFollowerServerId(serverId).build();
    }

    private Future<InstalledSnapshotPlan> prepareAndPersistInstalledSnapshot(
            InstallSnapshotRequest request) {
        installSnapshotReceived.add(1);
        if (!running) {
            logger.debug("Rejecting InstallSnapshot from {}: node is not running", request.getLeaderId());
            return Future.succeededFuture(InstalledSnapshotPlan.rejectedWithoutStateChange(request));
        }
        if (isMeantForAnotherServer(request.getTargetServerId())) {
            logger.debug("Rejecting InstallSnapshot from {}: it is meant for server {}, not this one",
                    request.getLeaderId(), request.getTargetServerId());
            return Future.succeededFuture(InstalledSnapshotPlan.rejectedWithoutStateChange(request));
        }
        if (request.getTerm() < currentTerm) {
            logger.debug("Rejecting InstallSnapshot: stale term {} < {}",
                    request.getTerm(), currentTerm);
            return Future.succeededFuture(
                    InstalledSnapshotPlan.rejectedWithoutStateChange(request));
        }

        boolean higherTerm = request.getTerm() > currentTerm;
        InstalledSnapshotPlan plan = planInstalledSnapshot(request, higherTerm);
        Future<Void> termPersistence = higherTerm
                ? persistMetadata(request.getTerm(), Optional.empty())
                : Future.succeededFuture();
        return composeOnStateLoop(termPersistence, ignored -> persistInstalledSnapshot(plan));
    }

    private InstalledSnapshotPlan planInstalledSnapshot(
            InstallSnapshotRequest request, boolean higherTerm) {
        String leaderId = request.getLeaderId();
        int chunkIndex = request.getChunkIndex();
        int totalChunks = request.getTotalChunks();
        boolean clearAssemblers = higherTerm;

        logger.debug("InstallSnapshot from {}: chunk {}/{}, lastIncludedIndex={}",
                leaderId, chunkIndex + 1, totalChunks, request.getLastIncludedIndex());

        if (!higherTerm && currentLeaderId != null && !currentLeaderId.equals(leaderId)) {
            logger.warn("Rejecting same-term InstallSnapshot from {} while following {}",
                    leaderId, currentLeaderId);
            return InstalledSnapshotPlan.rejectedWithoutStateChange(request);
        }

        if (totalChunks <= 0 || chunkIndex < 0 || chunkIndex >= totalChunks
                || request.getDone() != (chunkIndex == totalChunks - 1)) {
            return InstalledSnapshotPlan.rejected(
                    request, higherTerm, clearAssemblers, true, 0);
        }

        if (snapshotLastIndex > 0
                && request.getLastIncludedIndex() == snapshotLastIndex
                && request.getLastIncludedTerm() == snapshotLastTerm) {
            return InstalledSnapshotPlan.duplicate(
                    request, higherTerm, clearAssemblers, totalChunks);
        }

        long protectedIndex = Math.max(snapshotLastIndex, Math.max(lastApplied, commitIndex));
        if (request.getLastIncludedIndex() <= protectedIndex) {
            logger.warn("Rejecting stale InstallSnapshot boundary {} at or below protected index {}",
                    request.getLastIncludedIndex(), protectedIndex);
            return InstalledSnapshotPlan.rejected(
                    request, higherTerm, clearAssemblers, true, 0);
        }

        SnapshotChunkAssembler assembler = higherTerm ? null : pendingInstalls.get(leaderId);
        if (assembler != null && !assembler.matches(request)) {
            if (chunkIndex != 0) {
                return InstalledSnapshotPlan.assemblerStateLost(
                        request, higherTerm, clearAssemblers);
            }
            assembler = null;
        }
        if (assembler == null) {
            if (chunkIndex != 0) {
                return InstalledSnapshotPlan.assemblerStateLost(
                        request, higherTerm, clearAssemblers);
            }
            assembler = new SnapshotChunkAssembler(request);
        }
        if (chunkIndex != assembler.getNextExpectedChunk()) {
            logger.warn("Out-of-order snapshot chunk: expected {}, got {}",
                    assembler.getNextExpectedChunk(), chunkIndex);
            return InstalledSnapshotPlan.rejected(request, higherTerm, clearAssemblers,
                    false, assembler.getNextExpectedChunk());
        }

        SnapshotChunkAssembler advanced = assembler.withChunk(
                chunkIndex, request.getData().toByteArray());
        if (!request.getDone()) {
            return InstalledSnapshotPlan.acceptedChunk(
                    request, higherTerm, clearAssemblers, advanced);
        }

        SnapshotData snapshot = new SnapshotData(
                advanced.assemble(), request.getLastIncludedIndex(), request.getLastIncludedTerm());
        logger.info("InstallSnapshot complete: assembling {} bytes at index={}, term={}",
                snapshot.data().length, snapshot.lastIncludedIndex(), snapshot.lastIncludedTerm());
        boolean retainSuffix = hasLogEntry(snapshot.lastIncludedIndex())
                && log.get(toArrayIndex(snapshot.lastIncludedIndex())).getTerm()
                        == snapshot.lastIncludedTerm();
        return InstalledSnapshotPlan.completed(
                request, higherTerm, clearAssemblers, snapshot, retainSuffix);
    }

    private Future<InstalledSnapshotPlan> persistInstalledSnapshot(InstalledSnapshotPlan plan) {
        RaftTransitionSequencer.Ownership ownership = transitionSequencer.currentOwnership();
        if (plan.snapshot() == null) return Future.succeededFuture(plan);
        try {
            // Refused before it is saved, so a damaged snapshot never replaces this node's state.
            SnapshotEnvelope.unwrap(plan.snapshot().data());
        } catch (IllegalStateException unreadable) {
            logger.error("Refusing an installed snapshot: {}", unreadable.getMessage());
            return Future.succeededFuture(plan.publicationFailed(unreadable));
        }
        // Read loop-owned log state here, on the state loop: the continuations below run on the
        // snapshot store's thread once publication completes.
        long boundary = plan.snapshot().lastIncludedIndex();
        boolean hasSuffixToRemove = lastLogIndex() >= boundary + 1;

        Future<Void> publication;
        try {
            publication = persistence.isDurable()
                    ? toFuture(persistence.saveSnapshot(ownership, plan.snapshot()))
                    : Future.succeededFuture();
        } catch (RuntimeException error) {
            return recoverableSnapshotPublicationCause(error)
                    .map(cause -> Future.succeededFuture(plan.publicationFailed(cause)))
                    .orElseGet(() -> Future.failedFuture(error));
        }

        return publication
                .map(ignored -> plan)
                .recover(error -> recoverableSnapshotPublicationCause(error)
                        .map(cause -> Future.succeededFuture(plan.publicationFailed(cause)))
                        .orElseGet(() -> Future.failedFuture(error)))
                .compose(published -> {
                    if (published.failure() != null) return Future.succeededFuture(published);
                    if (!persistence.isDurable()) return Future.succeededFuture(published);
                    Future<Void> durability = published.retainSuffix() || !hasSuffixToRemove
                            ? Future.succeededFuture()
                            : toFuture(persistence.truncateSuffix(ownership, boundary + 1))
                                    .compose(ignored -> toFuture(persistence.sync(ownership)));
                    return durability
                            .compose(ignored -> toFuture(
                                    persistence.truncatePrefix(ownership, boundary)))
                            .map(published);
                });
    }

    private InstallSnapshotResponse applyInstalledSnapshot(InstalledSnapshotPlan plan) {
        InstallSnapshotRequest request = plan.request();
        if (!plan.termAccepted()) {
            return installSnapshotResponse(false, 0);
        }

        if (plan.higherTerm()) {
            applyDurableHigherTerm(request.getTerm(), null);
        } else if (state != State.FOLLOWER) {
            state = State.FOLLOWER;
            MDC.put("raftRole", "FOLLOWER");
            MDC.put("raftTerm", String.valueOf(currentTerm));
            notifyStateChangeListeners(State.FOLLOWER);
            failPendingCommands(new CommandOutcomeUnknownException(
                    "Leadership lost before command commit; outcome may be unknown"));
            cancelHeartbeatTimer();
        }
        if (plan.clearAssemblers()) pendingInstalls.clear();
        currentLeaderId = request.getLeaderId();
        leaderContactActive = true;
        lastLeaderContactNanos = timerScheduler.nanoTime();
        resetElectionTimer();

        if (plan.removeAssembler()) pendingInstalls.remove(request.getLeaderId());
        if (plan.assemblerToStore() != null) {
            pendingInstalls.put(request.getLeaderId(), plan.assemblerToStore());
        }
        if (plan.failure() != null) {
            logger.error("Failed to publish installed snapshot: {}",
                    plan.failure().getMessage(), plan.failure());
            return installSnapshotResponse(false, 0, plan.rejectionReason());
        }
        if (plan.snapshot() == null) {
            return installSnapshotResponse(
                    plan.success(), plan.nextChunkIndex(), plan.rejectionReason());
        }

        SnapshotData snapshot = plan.snapshot();
        long lastIncludedIndex = snapshot.lastIncludedIndex();
        long lastIncludedTerm = snapshot.lastIncludedTerm();

        List<LogEntry> retainedSuffix = List.of();
        if (plan.retainSuffix()) {
            int suffixStart = toArrayIndex(lastIncludedIndex) + 1;
            retainedSuffix = new ArrayList<>(log.subList(suffixStart, log.size()));
        }

        SnapshotEnvelope envelope = SnapshotEnvelope.unwrap(snapshot.data());
        stateMachine.restoreSnapshot(envelope.stateMachineSnapshot());
        stateMachine.setLastAppliedIndex(lastIncludedIndex);
        log.clear();
        log.add(new LogEntry(lastIncludedTerm, lastIncludedIndex, null));
        log.addAll(retainedSuffix);
        snapshotConfiguration = envelope.configuration();
        logConfigurations.clear();
        refreshConfiguration();
        retainedSuffix.forEach(this::recordIfConfiguration);
        snapshotLastIndex = lastIncludedIndex;
        snapshotLastTerm = lastIncludedTerm;
        lastApplied = lastIncludedIndex;
        commitIndex = Math.max(commitIndex, lastIncludedIndex);

        logger.info("Snapshot installed: snapshotLastIndex={}, snapshotLastTerm={}, commitIndex={}",
                snapshotLastIndex, snapshotLastTerm, commitIndex);
        return installSnapshotResponse(true, request.getTotalChunks());
    }

    private InstallSnapshotResponse installSnapshotResponse(boolean success, int nextChunkIndex) {
        return installSnapshotResponse(success, nextChunkIndex,
                InstallSnapshotResponse.RejectionReason.UNSPECIFIED);
    }

    private InstallSnapshotResponse installSnapshotResponse(
            boolean success, int nextChunkIndex,
            InstallSnapshotResponse.RejectionReason rejectionReason) {
        return InstallSnapshotResponse.newBuilder()
                .setTerm(currentTerm)
                .setSuccess(success)
                .setNextChunkIndex(nextChunkIndex)
                .setRejectionReason(rejectionReason)
                .build();
    }

    private record InstalledSnapshotPlan(
            InstallSnapshotRequest request,
            boolean termAccepted,
            boolean higherTerm,
            boolean clearAssemblers,
            boolean removeAssembler,
            SnapshotChunkAssembler assemblerToStore,
            SnapshotData snapshot,
            boolean retainSuffix,
            boolean success,
            int nextChunkIndex,
            InstallSnapshotResponse.RejectionReason rejectionReason,
            Throwable failure) {

        private static InstalledSnapshotPlan rejectedWithoutStateChange(
                InstallSnapshotRequest request) {
            return new InstalledSnapshotPlan(request, false, false, false, false,
                    null, null, false, false, 0,
                    InstallSnapshotResponse.RejectionReason.UNSPECIFIED, null);
        }

        private static InstalledSnapshotPlan rejected(
                InstallSnapshotRequest request, boolean higherTerm,
                boolean clearAssemblers, boolean removeAssembler, int nextChunkIndex) {
            return new InstalledSnapshotPlan(request, true, higherTerm, clearAssemblers,
                    removeAssembler, null, null, false, false, nextChunkIndex,
                    InstallSnapshotResponse.RejectionReason.UNSPECIFIED, null);
        }

        private static InstalledSnapshotPlan assemblerStateLost(
                InstallSnapshotRequest request, boolean higherTerm, boolean clearAssemblers) {
            return new InstalledSnapshotPlan(request, true, higherTerm, clearAssemblers,
                    true, null, null, false, false, 0,
                    InstallSnapshotResponse.RejectionReason.ASSEMBLER_STATE_LOST, null);
        }

        private static InstalledSnapshotPlan duplicate(
                InstallSnapshotRequest request, boolean higherTerm,
                boolean clearAssemblers, int nextChunkIndex) {
            return new InstalledSnapshotPlan(request, true, higherTerm, clearAssemblers,
                    true, null, null, false, true, nextChunkIndex,
                    InstallSnapshotResponse.RejectionReason.UNSPECIFIED, null);
        }

        private static InstalledSnapshotPlan acceptedChunk(
                InstallSnapshotRequest request, boolean higherTerm,
                boolean clearAssemblers, SnapshotChunkAssembler assembler) {
            return new InstalledSnapshotPlan(request, true, higherTerm, clearAssemblers,
                    false, assembler, null, false, true, assembler.getNextExpectedChunk(),
                    InstallSnapshotResponse.RejectionReason.UNSPECIFIED, null);
        }

        private static InstalledSnapshotPlan completed(
                InstallSnapshotRequest request, boolean higherTerm,
                boolean clearAssemblers, SnapshotData snapshot, boolean retainSuffix) {
            return new InstalledSnapshotPlan(request, true, higherTerm, clearAssemblers,
                    true, null, snapshot, retainSuffix, true, request.getTotalChunks(),
                    InstallSnapshotResponse.RejectionReason.UNSPECIFIED, null);
        }

        private InstalledSnapshotPlan publicationFailed(Throwable error) {
            return new InstalledSnapshotPlan(request, termAccepted, higherTerm, clearAssemblers,
                    true, null, snapshot, retainSuffix, false, 0,
                    InstallSnapshotResponse.RejectionReason.PERSISTENCE_REJECTED, error);
        }
    }

    private static boolean isAmbiguousLeaderAppendFailure(Throwable error) {
        return !(unwrapCompletionFailure(error) instanceof CommandEncodingException)
                && !isKnownPrewriteRejection(error);
    }

    private static final class CommandEncodingException extends IllegalArgumentException {
        private CommandEncodingException(Throwable cause) {
            super("Command could not be encoded before WAL mutation", cause);
        }
    }

    private static boolean isAmbiguousFollowerAppendFailure(
            Throwable error, Long truncateFromIndex, int entriesToPersist) {
        if (!isKnownPrewriteRejection(error) || truncateFromIndex != null) {
            return true;
        }
        var rejection = (dev.mars.raftlog.storage.WriteRejection)
                unwrapCompletionFailure(error);
        if (rejection.reason()
                == dev.mars.raftlog.storage.WriteRejectionReason.INSUFFICIENT_DISK_SPACE) {
            // FileRaftStorage performs this check per large record. In a batch,
            // earlier records may already have been written before a later check fails.
            return entriesToPersist != 1;
        }
        // Payload sizes are validated for the complete batch before any record is written.
        return false;
    }

    private static boolean isKnownPrewriteRejection(Throwable error) {
        Throwable cause = unwrapCompletionFailure(error);
        return cause instanceof dev.mars.raftlog.storage.WriteRejection;
    }

    private static Optional<Throwable> recoverableSnapshotPublicationCause(Throwable error) {
        Throwable cause = unwrapCompletionFailure(error);
        if (!(cause instanceof SnapshotPublicationException publicationFailure)
                || publicationFailure.outcome() != PublicationOutcome.NOT_PUBLISHED) {
            return Optional.empty();
        }
        return Optional.ofNullable(publicationFailure.getCause()).or(() -> Optional.of(cause));
    }

    private static Throwable unwrapCompletionFailure(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    // ========== SNAPSHOT CHUNK ASSEMBLER ==========

    /**
     * Reassembles snapshot chunks received via InstallSnapshot RPCs.
     * Tracks which chunks have been received and produces the complete
     * snapshot byte array when all chunks are present.
     */
    static class SnapshotChunkAssembler {
        private final long requestTerm;
        private final long lastIncludedIndex;
        private final long lastIncludedTerm;
        private final int totalChunks;
        private final byte[][] chunks;
        private final int nextExpectedChunk;

        SnapshotChunkAssembler(InstallSnapshotRequest request) {
            this(request.getTerm(), request.getLastIncludedIndex(), request.getLastIncludedTerm(),
                    request.getTotalChunks(), new byte[request.getTotalChunks()][], 0);
        }

        private SnapshotChunkAssembler(
                long requestTerm, long lastIncludedIndex, long lastIncludedTerm,
                int totalChunks, byte[][] chunks, int nextExpectedChunk) {
            this.requestTerm = requestTerm;
            this.lastIncludedIndex = lastIncludedIndex;
            this.lastIncludedTerm = lastIncludedTerm;
            this.totalChunks = totalChunks;
            this.chunks = chunks;
            this.nextExpectedChunk = nextExpectedChunk;
        }

        boolean matches(InstallSnapshotRequest request) {
            return requestTerm == request.getTerm()
                    && lastIncludedIndex == request.getLastIncludedIndex()
                    && lastIncludedTerm == request.getLastIncludedTerm()
                    && totalChunks == request.getTotalChunks();
        }

        SnapshotChunkAssembler withChunk(int index, byte[] data) {
            byte[][] advanced = chunks.clone();
            advanced[index] = data.clone();
            return new SnapshotChunkAssembler(requestTerm, lastIncludedIndex, lastIncludedTerm,
                    totalChunks, advanced, index + 1);
        }

        byte[] assemble() {
            int totalSize = 0;
            for (byte[] chunk : chunks) {
                if (chunk != null) totalSize += chunk.length;
            }
            byte[] result = new byte[totalSize];
            int offset = 0;
            for (byte[] chunk : chunks) {
                if (chunk != null) {
                    System.arraycopy(chunk, 0, result, offset, chunk.length);
                    offset += chunk.length;
                }
            }
            return result;
        }

        int getTotalChunks() { return totalChunks; }
        int getNextExpectedChunk() { return nextExpectedChunk; }
        long getLastIncludedIndex() { return lastIncludedIndex; }
    }

    // ========== SERIALIZATION ==========

    private Future<Void> closeNodeResources() {
        Future<Throwable> transportClose = runtime.executeBlocking(() -> {
            try {
                transport.stop();
                return null;
            } catch (Throwable error) {
                return error;
            }
        });

        return transportClose.compose(failure -> closeSnapshots()
                .compose(snapshotFailure -> closeWal()
                        .compose(walFailure -> {
                            Throwable combined = combineFailures(
                                    combineFailures(failure, snapshotFailure), walFailure);
                            return combined == null
                                    ? Future.succeededFuture()
                                    : Future.failedFuture(combined);
                        })));
    }

    private Future<Throwable> closeSnapshots() {
        SnapshotStore snapshots = persistence.snapshotStoreForClose();
        if (snapshots == null) return Future.succeededFuture(null);
        return runtime.executeBlocking(snapshots::closeAsync)
                .compose(RaftNode::toFuture)
                .map(ignored -> (Throwable) null)
                .recover(error -> Future.succeededFuture(error));
    }

    private Future<Throwable> closeWal() {
        RaftStorage wal = persistence.walForClose();
        if (wal == null || persistence.sharesCloseTarget()) {
            return Future.succeededFuture(null);
        }
        return runtime.executeBlocking(wal::closeAsync)
                .compose(RaftNode::toFuture)
                .map(ignored -> (Throwable) null)
                .recover(error -> Future.succeededFuture(error));
    }

    private static Throwable combineFailures(Throwable first, Throwable second) {
        if (first == null) return second;
        if (second != null && second != first) first.addSuppressed(second);
        return first;
    }

    private static <T> Future<T> toFuture(CompletableFuture<T> future) {
        return Future.fromCompletionStage(future);
    }

    /** The replicated bytes of an entry; only in-memory sentinels fall back to encoding the command. */
    private ByteString payloadOf(LogEntry entry) {
        byte[] payload = entry.getPayload();
        return payload != null ? ByteString.copyFrom(payload) : serialize(entry.getCommand());
    }

    private ByteString serialize(RaftCommand cmd) {
        return ByteString.copyFrom(commandCodec.serialize(cmd));
    }

    private void runOnContext(java.util.function.Consumer<Void> action) {
        runtime.runOnContext(ignored -> withLoggingContext(() -> action.accept(null)));
    }

    private <T> void onStateLoopComplete(
            Future<T> future,
            java.util.function.Consumer<dev.mars.qraft.common.async.AsyncResult<T>> action) {
        future.onComplete(result -> {
            if (JavaRuntime.currentContext() == runtime) {
                action.accept(result);
            } else {
                runOnContext(ignored -> action.accept(result));
            }
        });
    }

    private <T, U> Future<U> composeOnStateLoop(
            Future<T> source,
            Function<? super T, Future<U>> continuation) {
        Promise<U> result = Promise.promise();
        Map<String, String> capturedMdc = Optional.ofNullable(MDC.getCopyOfContextMap())
                .map(HashMap::new)
                .orElseGet(HashMap::new);
        io.opentelemetry.context.Context capturedTrace = io.opentelemetry.context.Context.current();
        source.onComplete(sourceResult -> dispatchRecoveryContinuation(() -> {
            if (sourceResult.failed()) {
                result.tryFail(sourceResult.cause());
                return;
            }

            Future<U> next;
            try {
                next = requireNonNull(continuation.apply(sourceResult.result()),
                        "recovery continuation returned null");
            } catch (Throwable error) {
                result.tryFail(error);
                return;
            }

            next.onComplete(nextResult -> dispatchRecoveryContinuation(() -> {
                if (nextResult.succeeded()) result.tryComplete(nextResult.result());
                else result.tryFail(nextResult.cause());
            }, result, capturedMdc, capturedTrace));
        }, result, capturedMdc, capturedTrace));
        return result.future();
    }

    private void dispatchRecoveryContinuation(
            Runnable action,
            Promise<?> rejectionTarget,
            Map<String, String> capturedMdc,
            io.opentelemetry.context.Context capturedTrace) {
        if (JavaRuntime.currentContext() == runtime) {
            runWithCapturedContext(action, capturedMdc, capturedTrace);
            return;
        }
        try {
            runWithCapturedContext(
                    () -> runOnContext(ignored -> action.run()), capturedMdc, capturedTrace);
        } catch (Throwable error) {
            rejectionTarget.tryFail(error);
        }
    }

    private void runWithCapturedContext(
            Runnable action,
            Map<String, String> capturedMdc,
            io.opentelemetry.context.Context capturedTrace) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        try (io.opentelemetry.context.Scope ignored = capturedTrace.makeCurrent()) {
            if (capturedMdc.isEmpty()) MDC.clear();
            else MDC.setContextMap(capturedMdc);
            action.run();
        } finally {
            if (previousMdc == null || previousMdc.isEmpty()) MDC.clear();
            else MDC.setContextMap(previousMdc);
        }
    }

    private long setTimer(long delayMs, java.util.function.Consumer<Long> action) {
        return timerScheduler.setTimer(delayMs, id -> withLoggingContext(() -> action.accept(id)));
    }

    private long setPeriodic(long periodMs, java.util.function.Consumer<Long> action) {
        return timerScheduler.setPeriodic(periodMs, id -> withLoggingContext(() -> action.accept(id)));
    }

    private void withLoggingContext(Runnable action) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            MDC.put("nodeId", nodeId);
            MDC.put("raftRole", state.name());
            MDC.put("raftTerm", Long.toString(currentTerm));
            action.run();
        } finally {
            if (previous == null || previous.isEmpty()) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }

    private RaftCommand deserialize(ByteString data) {
        return commandCodec.deserialize(data.toByteArray());
    }
}

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
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.raftlog.storage.RaftStorage;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import dev.mars.raftlog.storage.AppendPlan;
import dev.mars.qraft.raft.api.SnapshotStore;
import dev.mars.qraft.raft.api.SnapshotStore.SnapshotData;
import dev.mars.qraft.controller.state.RaftCommandResult;
import dev.mars.qraft.controller.state.RaftCommand;
import dev.mars.qraft.raft.api.CommandCodec;
import com.google.protobuf.ByteString;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.runtime.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import org.slf4j.MDC;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import static java.util.Objects.requireNonNull;

/**
 * Reactive Raft Node implementation with durable WAL storage.
 * Runs on the Vert.x Event Loop (Single Threaded), removing the need for
 * synchronization.
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
    private final Set<String> clusterNodes;
    private final RaftTransport transport;
    private final RaftLogApplicator stateMachine;
    private final CommandCodec<RaftCommand> commandCodec;
    private final Optional<RaftStorage> storage;  // External WAL storage (empty for volatile mode)
    private final Optional<SnapshotStore> snapshotStore;
    private final RaftTransitionSequencer transitionSequencer;

    // ========== PERSISTENT STATE ==========
    private volatile long currentTerm = 0;
    private String votedFor = null;
    private final List<LogEntry> log = new ArrayList<>();

    // ========== VOLATILE STATE ==========
    private volatile State state = State.FOLLOWER;
    private volatile long commitIndex = 0;
    private volatile long lastApplied = 0;
    private volatile String currentLeaderId = null;

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
    private long electionTimerId = -1;
    private long heartbeatTimerId = -1;
    private final java.util.Set<String> unavailablePeers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ========== STATE CHANGE LISTENERS ==========
    private final List<java.util.function.Consumer<State>> stateChangeListeners = new CopyOnWriteArrayList<>();

    // ========== EDGE METRICS ==========
    private LongCounter rpcCounter;

    // ========== CONFIGURATION PARAMETERS ==========
    private final long electionTimeoutMs;
    private final long heartbeatIntervalMs;
    private final boolean snapshotEnabled;
    private final long snapshotThreshold;
    private final long snapshotCheckIntervalMs;
    private final long logHardLimit;

    // ========== INSTALL SNAPSHOT STATE ==========
    /** Maximum chunk size for InstallSnapshot RPC (default 1 MB). */
    static final int SNAPSHOT_CHUNK_SIZE = 1024 * 1024;
    /** Tracks in-progress snapshot installs from a leader (follower side). */
    private final Map<String, SnapshotChunkAssembler> pendingInstalls = new HashMap<>();
    /** Tracks in-progress snapshot sends to followers (leader side). */
    private final Set<String> installSnapshotInProgress = new HashSet<>();

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
        private long electionTimeoutMs = 5000;
        private long heartbeatIntervalMs = 1000;
        private Boolean snapshotEnabled;       // null = derive from mode
        private long snapshotThreshold = 10000;
        private long snapshotCheckIntervalMs = 60000;
        private long logHardLimit = 100000;

        private Builder() {}

        /** The Vert.x instance (required). */
        public Builder runtime(JavaRuntime runtime) { this.runtime = runtime; return this; }

        /** Unique node identifier (required). */
        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }

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
         * Builds the {@link RaftNode}.
         *
         * @throws IllegalStateException if any required parameter is missing
         */
        public RaftNode build() {
            if (runtime == null) throw new IllegalStateException("runtime is required");
            if (nodeId == null) throw new IllegalStateException("nodeId is required");
            if (clusterNodes == null) throw new IllegalStateException("clusterNodes is required");
            if (transport == null) throw new IllegalStateException("transport is required");
            if (stateMachine == null) throw new IllegalStateException("stateMachine is required");
                if (commandCodec == null) throw new IllegalStateException("commandCodec is required");
            if (mode == null) throw new IllegalStateException("mode is required");

            boolean snap = (snapshotEnabled != null) ? snapshotEnabled : mode.isDurable();
            return new RaftNode(runtime, nodeId, clusterNodes, transport, stateMachine,
                    commandCodec, mode, electionTimeoutMs, heartbeatIntervalMs, snap, snapshotThreshold, snapshotCheckIntervalMs, logHardLimit);
        }
    }

    // ========== CONSTRUCTOR (private) ==========

    private RaftNode(JavaRuntime runtime, String nodeId, Set<String> clusterNodes, RaftTransport transport,
            RaftLogApplicator stateMachine, CommandCodec<RaftCommand> commandCodec,
            RaftNodeMode mode, long electionTimeoutMs, long heartbeatIntervalMs,
            boolean snapshotEnabled, long snapshotThreshold, long snapshotCheckIntervalMs, long logHardLimit) {
        this.runtime = runtime;
        this.nodeId = nodeId;
        this.clusterNodes = new HashSet<>(clusterNodes);
        this.transport = transport;
        this.stateMachine = stateMachine;
        this.commandCodec = commandCodec;
        this.storage = requireNonNull(mode, "mode").storage();
        this.snapshotStore = mode.snapshots();
        this.transitionSequencer = new RaftTransitionSequencer(runtime, 1024);
        this.electionTimeoutMs = electionTimeoutMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.snapshotEnabled = snapshotEnabled && this.storage.isPresent();
        this.snapshotThreshold = snapshotThreshold;
        this.snapshotCheckIntervalMs = snapshotCheckIntervalMs;
        this.logHardLimit = logHardLimit;

        // Initialize log with a dummy entry
        log.add(new LogEntry(0, 0, null));

        // Initialize OpenTelemetry Metrics
        Meter meter = GlobalOpenTelemetry.getMeter("qraft-controller");

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
        Promise<Void> promise = Promise.promise();
        runOnContext(v -> {
            try {
                if (running) {
                    promise.complete();
                    return;
                }

                logger.info("Starting Raft node: {}", nodeId);

                // Set initial Raft MDC context
                MDC.put("raftRole", "FOLLOWER");
                MDC.put("raftTerm", String.valueOf(currentTerm));

                // Set reference to this node in transport
                transport.setRaftNode(this);

                // Recover state from WAL if storage is configured
                recoverFromStorage()
                    .onSuccess(v2 -> {
                        try {
                            // Start transport listener
                            transport.start(this::handleMessage);

                            // Start election timer
                            resetElectionTimer();

                            running = true;
                            logger.info("Raft node {} started successfully (term={}, logSize={})",
                                        nodeId, currentTerm, log.size());
                            promise.complete();
                        } catch (Exception e) {
                            logger.error("Failed to start Raft transport: {}", e.getMessage(), e);
                            promise.fail(e);
                        }
                    })
                    .onFailure(err -> {
                        logger.error("Failed to recover Raft state from storage: {}", err.getMessage(), err);
                        promise.fail(err);
                    });

            } catch (Exception e) {
                logger.error("Failed to start Raft node: {}", e.getMessage(), e);
                promise.fail(e);
            }
        });
        return promise.future();
    }

    /**
     * Recovers persistent state from WAL storage.
     * <p>Order: Metadata → Snapshot (if any) → Log Replay → State Machine Rebuild
     */
    private Future<Void> recoverFromStorage() {
        if (storage.isEmpty()) {
            logger.info("No storage configured, running in volatile mode");
            return Future.succeededFuture();
        }

        RaftStorage store = storage.get();
        SnapshotStore snapshots = snapshotStore.orElseThrow();
        logger.info("Recovering Raft state from storage...");

        return toFuture(store.loadMetadata())
            .compose(meta -> {
                this.currentTerm = meta.currentTerm();
                this.votedFor = meta.votedFor().orElse(null);
                logger.info("Recovered metadata: term={}, votedFor={}", currentTerm, votedFor);

                // Try to load snapshot
                return toFuture(snapshots.loadLatest());
            })
            .compose(snapshotOpt -> {
                if (snapshotOpt.isPresent()) {
                    SnapshotData snapshot = snapshotOpt.get();
                    logger.info("Restoring from snapshot: lastIncludedIndex={}, lastIncludedTerm={}",
                            snapshot.lastIncludedIndex(), snapshot.lastIncludedTerm());

                    // Restore state machine from snapshot
                    stateMachine.restoreSnapshot(snapshot.data());

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

                return toFuture(store.replayLog());
            })
            .compose(entries -> {
                if (snapshotLastIndex > 0) {
                    // Snapshot recovery: only replay entries AFTER the snapshot
                    int replayedCount = 0;
                    for (LogEntryData entry : entries) {
                        if (entry.index() > snapshotLastIndex) {
                            RaftCommand command = deserialize(ByteString.copyFrom(entry.payload()));
                            log.add(new LogEntry(entry.term(), entry.index(), command));
                            replayedCount++;
                        }
                    }
                    logger.info("Replayed {} post-snapshot entries (skipped {} compacted entries)",
                            replayedCount, entries.size() - replayedCount);

                    // Safety first: on multi-node recovery, do not assume replayed
                    // entries were committed before crash. Wait for leaderCommit updates.
                    if (clusterNodes.size() == 1) {
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
                    for (LogEntryData entry : entries) {
                        RaftCommand command = deserialize(ByteString.copyFrom(entry.payload()));
                        log.add(new LogEntry(entry.term(), entry.index(), command));
                    }
                    logger.info("Recovered {} log entries from storage", entries.size());
                    return rebuildStateMachine();
                }

                return Future.<Void>succeededFuture();
            })
            .onSuccess(v -> logger.info("Recovery complete: term={}, logSize={}, lastApplied={}, snapshotLastIndex={}",
                                        currentTerm, log.size(), lastApplied, snapshotLastIndex))
            .onFailure(err -> {
                logger.error("Recovery failed: {}", err.getMessage(), err);
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
        if (clusterNodes.size() == 1) {
            commitIndex = Math.max(snapshotLastIndex, lastLogIndex());
            logger.info("Single-node recovery: restoring committed log up to index {}", commitIndex);
        } else {
            commitIndex = snapshotLastIndex;
            logger.info("Multi-node recovery: deferring log application until leader commit advances");
        }
        
        applyLog();
        return Future.succeededFuture();
    }

    public Future<Void> stop() {
        Promise<Void> promise = Promise.promise();
        runOnContext(v -> {
            try {
                if (!running) {
                    promise.complete();
                    return;
                }
                running = false;
                state = State.FOLLOWER;
                currentLeaderId = null;

                cancelTimers();
                transport.stop();
                
                // Close application snapshots before the WAL. Both are owned by durable mode.
                closeDurableStorage()
                    .onSuccess(v2 -> {
                        logger.info("Raft node stopped: {}", nodeId);
                        promise.complete();
                    })
                    .onFailure(err -> {
                        logger.warn("Error closing storage during shutdown: {}", err.getMessage(), err);
                        promise.complete(); // Still complete, just log the warning
                    });
            } catch (Exception e) {
                logger.error("Failed to stop Raft node: {}", e.getMessage(), e);
                promise.fail(e);
            }
        });
        return promise.future();
    }

    public Future<RaftCommandResult<?>> submitCommand(RaftCommand command) {
        requireNonNull(command, "command");
        Promise<RaftCommandResult<?>> promise = Promise.promise();

        transitionSequencer.submit(
                        "leader-append",
                        RaftTransitionSequencer.FailurePolicy.FENCE,
                        () -> prepareAndPersistLeaderAppend(command),
                        decision -> applyLeaderAppend(decision, promise))
                .onFailure(error -> {
                    logger.error("Failed to persist command to WAL: {}", error.getMessage(), error);
                    promise.tryFail(error);
                });

        return promise.future();
    }

    private Future<LeaderAppendDecision> prepareAndPersistLeaderAppend(RaftCommand command) {
        if (state != State.LEADER) {
            return Future.succeededFuture(LeaderAppendDecision.rejected(
                    new IllegalStateException("Not the leader. Current state: " + state)));
        }

        if (log.size() >= logHardLimit) {
            logger.warn("Raft log at capacity ({}/{}), rejecting command", log.size(), logHardLimit);
            return Future.succeededFuture(LeaderAppendDecision.rejected(
                    new IllegalStateException(
                            "Raft log at capacity (" + log.size() + "/" + logHardLimit
                                    + "). Wait for snapshot.")));
        }

        LogEntry entry = new LogEntry(currentTerm, lastLogIndex() + 1, command);
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
        pendingCommands.put(entry.getIndex(), commandPromise);
        logger.info("Command submitted at index {} term {}", entry.getIndex(), entry.getTerm());

        for (String peer : clusterNodes) {
            if (!peer.equals(nodeId)) sendAppendEntries(peer, false);
        }

        updateCommitIndex();
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
        return storage.map(s -> {
            ByteString serialized = serialize(entry.getCommand());
            LogEntryData entryData = new LogEntryData(entry.getIndex(), entry.getTerm(), serialized.toByteArray());

            return toFuture(s.appendEntries(List.of(entryData)))
                .compose(v -> toFuture(s.sync()));  // Durability barrier
        }).orElseGet(Future::succeededFuture);  // Volatile mode
    }

    // ... Getters ...
    public boolean isRunning() {
        return running;
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
     * <p>Listeners are invoked on the Vert.x event loop context, making them safe
     * for use with Vert.x Futures and Promises. This enables reactive test patterns
     * instead of Thread.sleep polling loops.
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
        // Already in target state
        if (state == targetState) {
            return Future.succeededFuture(targetState);
        }

        Promise<State> promise = Promise.promise();

        // Register listener
        java.util.function.Consumer<State> listener = newState -> {
            if (newState == targetState && !promise.future().isComplete()) {
                promise.complete(targetState);
            }
        };
        addStateChangeListener(listener);

        // Set timeout
        long timerId = setTimer(timeoutMs, id -> {
            if (!promise.future().isComplete()) {
                removeStateChangeListener(listener);
                promise.fail("Timed out waiting for state " + targetState + " after " + timeoutMs + "ms (current: " + state + ")");
            }
        });

        // Clean up on completion
        promise.future().onComplete(ar -> {
            removeStateChangeListener(listener);
            runtime.cancelTimer(timerId);
        });

        return promise.future();
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
        if (electionTimerId != -1)
            runtime.cancelTimer(electionTimerId);
        if (heartbeatTimerId != -1)
            runtime.cancelTimer(heartbeatTimerId);
        if (snapshotTimerId != -1)
            runtime.cancelTimer(snapshotTimerId);
    }

    private void resetElectionTimer() {
        if (electionTimerId != -1) {
            runtime.cancelTimer(electionTimerId);
        }

        long timeout = electionTimeoutMs + (long) (Math.random() * electionTimeoutMs);

        electionTimerId = setTimer(timeout, id -> startElection());
    }

    private void startElection() {
        transitionSequencer.submit(
                        "start-election",
                        RaftTransitionSequencer.FailurePolicy.FENCE,
                        this::prepareAndPersistElection,
                        this::applyElection)
                .onFailure(error -> logger.error(
                        "Failed to start election because metadata durability is uncertain: {}",
                        error.getMessage(), error));
    }

    private Future<ElectionDecision> prepareAndPersistElection() {
        if (!running || state == State.LEADER) {
            return Future.succeededFuture(new ElectionDecision(false, currentTerm));
        }

        long electionTerm = currentTerm + 1;
        logger.info("Preparing election for node {} at term {}", nodeId, electionTerm);
        return persistMetadata(electionTerm, Optional.of(nodeId))
                .map(ignored -> new ElectionDecision(true, electionTerm));
    }

    private Void applyElection(ElectionDecision decision) {
        if (!decision.start() || !running) return null;

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

    private record ElectionDecision(boolean start, long term) {}

    private void requestVotes() {
        long term = currentTerm;
        long lastLogIdx = lastLogIndex();
        long lastLogTrm = lastLogIdx > 0 && hasLogEntry(lastLogIdx)
                ? log.get(toArrayIndex(lastLogIdx)).getTerm()
                : snapshotLastTerm;

        AtomicLong voteCount = new AtomicLong(1); // Self vote

        if (clusterNodes.size() == 1) {
            becomeLeader();
            return;
        }

        for (String peerId : clusterNodes) {
            if (!peerId.equals(nodeId)) {
                VoteRequest request = VoteRequest.newBuilder()
                        .setTerm(term)
                        .setCandidateId(nodeId)
                        .setLastLogIndex(lastLogIdx)
                        .setLastLogTerm(lastLogTrm)
                        .build();

                // Using transport (Wait for Future integration)
                transport.sendVoteRequest(peerId, request)
                        .onSuccess(response -> runOnContext(v -> handleVoteResponse(response, term, voteCount)))
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

    private void handleVoteResponse(VoteResponse response, long electionTerm, AtomicLong voteCount) {
        if (state != State.CANDIDATE || currentTerm != electionTerm)
            return;

        if (response.getTerm() > currentTerm) {
            stepDown(response.getTerm());
            return;
        }

        if (response.getVoteGranted()) {
            long votes = voteCount.incrementAndGet();
            if (votes > clusterNodes.size() / 2) {
                becomeLeader();
            }
        }
    }

    private void becomeLeader() {
        if (state != State.CANDIDATE)
            return;

        state = State.LEADER;
        leadershipGeneration++;
        currentLeaderId = nodeId;
        MDC.put("raftRole", "LEADER");
        MDC.put("raftTerm", String.valueOf(currentTerm));
        logger.info("Node {} became LEADER for term {}", nodeId, currentTerm);
        notifyStateChangeListeners(State.LEADER);

        if (electionTimerId != -1)
            runtime.cancelTimer(electionTimerId);

        initializeLeaderState();
        startHeartbeats();
        sendHeartbeats(); // Immediate
        startSnapshotScheduler();
    }

    private void initializeLeaderState() {
        long nextIndexValue = lastLogIndex() + 1;
        for (String peer : clusterNodes) {
            if (!peer.equals(nodeId)) {
                nextIndex.put(peer, nextIndexValue);
                matchIndex.put(peer, 0L);
            }
        }
    }

    private void startHeartbeats() {
        heartbeatTimerId = setPeriodic(heartbeatIntervalMs, id -> sendHeartbeats());
    }

    private void sendHeartbeats() {
        if (state != State.LEADER)
            return;

        for (String peer : clusterNodes) {
            if (!peer.equals(nodeId)) {
                sendAppendEntries(peer, true);
            }
        }
    }

    private void stepDown(long newTerm) {
        stepDown(newTerm, true)
                .onFailure(err -> {
                    logger.error("Failed to persist step-down metadata for term {}: {}", newTerm, err.getMessage(), err);
                });
    }

    private Future<Void> stepDown(long newTerm, boolean persistMetadataRequired) {
        RaftTransitionSequencer.FailurePolicy failurePolicy = persistMetadataRequired
                ? RaftTransitionSequencer.FailurePolicy.FENCE
                : RaftTransitionSequencer.FailurePolicy.CONTINUE;
        return transitionSequencer.submit(
                "step-down:" + newTerm,
                failurePolicy,
                () -> prepareAndPersistStepDown(newTerm, persistMetadataRequired),
                this::applyStepDown);
    }

    private Future<StepDownDecision> prepareAndPersistStepDown(
            long newTerm, boolean persistMetadataRequired) {
        if (newTerm <= currentTerm) {
            return Future.succeededFuture(new StepDownDecision(false, currentTerm));
        }
        if (!persistMetadataRequired) {
            return Future.succeededFuture(new StepDownDecision(true, newTerm));
        }
        return persistMetadata(newTerm, Optional.empty())
                .map(ignored -> new StepDownDecision(true, newTerm));
    }

    private Void applyStepDown(StepDownDecision decision) {
        if (decision.apply()) {
            applyDurableHigherTerm(decision.term(), null);
        }
        return null;
    }

    private record StepDownDecision(boolean apply, long term) {}

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
        // Ensure we are on the Vert.x Context
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
        return transitionSequencer.submit(
                "request-vote:" + request.getCandidateId() + ":" + request.getTerm(),
                RaftTransitionSequencer.FailurePolicy.FENCE,
                () -> prepareAndPersistVote(request),
                this::applyVoteDecision);
    }

    private Future<VoteDecision> prepareAndPersistVote(VoteRequest request) {
        long requestedTerm = request.getTerm();
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
                .setVoteGranted(decision.granted())
                .build();
    }

    private void applyDurableHigherTerm(long newTerm, String durableVote) {
        currentTerm = newTerm;
        votedFor = durableVote;
        state = State.FOLLOWER;
        currentLeaderId = null;
        MDC.put("raftRole", "FOLLOWER");
        MDC.put("raftTerm", String.valueOf(currentTerm));
        notifyStateChangeListeners(State.FOLLOWER);
        failPendingCommands(new IllegalStateException(
                "Leadership lost before command commit; outcome may be unknown"));
        cancelTimers();
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
        if (logger.isDebugEnabled()) {
            logger.debug("Persisting raft metadata: term={}, votedForPresent={}, votedFor={}",
                    term, votedForCandidate.isPresent(), votedForCandidate.orElse("<none>"));
        }
        return storage.map(s -> toFuture(s.updateMetadata(term, votedForCandidate)))
            .orElseGet(Future::succeededFuture);  // Volatile mode
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
        transitionSequencer.submit(
                        "append-entries:" + request.getLeaderId() + ":" + request.getTerm(),
                        RaftTransitionSequencer.FailurePolicy.FENCE,
                        () -> prepareAndPersistFollowerAppend(request),
                        this::applyFollowerAppend)
                .onComplete(result -> {
                    if (result.failed()) {
                        Throwable error = result.cause();
                        logger.error("AppendEntries failed during durable transition: {}",
                                error.getMessage(), error);
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
        return response.future();
    }

    private Future<FollowerAppendDecision> prepareAndPersistFollowerAppend(
            AppendEntriesRequest request) {
        if (request.getTerm() < currentTerm) {
            logger.debug("Rejecting AppendEntries: stale term {} < {}", request.getTerm(), currentTerm);
            return Future.succeededFuture(FollowerAppendDecision.stale(request));
        }

        boolean higherTerm = request.getTerm() > currentTerm;
        Future<Void> termPersistence = higherTerm
                ? persistMetadata(request.getTerm(), Optional.empty())
                : Future.succeededFuture();

        if (!hasLogEntry(request.getPrevLogIndex()) ||
                log.get(toArrayIndex(request.getPrevLogIndex())).getTerm() != request.getPrevLogTerm()) {
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
            for (dev.mars.qraft.controller.raft.grpc.LogEntry entryProto : request.getEntriesList()) {
                RaftCommand command = deserialize(entryProto.getData());
                LogEntry newEntry = new LogEntry(entryProto.getTerm(), currentIndex, command);
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

        // AppendPlan uses one-based positions. Exclude Qraft's snapshot sentinel and
        // translate the request into coordinates relative to the compacted prefix.
        List<LogEntryData> currentEntryData = log.stream().skip(1)
                .map(entry -> new LogEntryData(entry.getIndex(), entry.getTerm(),
                        serialize(entry.getCommand()).toByteArray()))
                .toList();
        long relativeStartIndex = startIndex - snapshotLastIndex;
        AppendPlan appendPlan = AppendPlan.from(relativeStartIndex, incomingEntryData, currentEntryData);
        Long truncateFromIndex = appendPlan.truncateFromIndex() == null
                ? null
                : appendPlan.truncateFromIndex() + snapshotLastIndex;
        Set<Long> indicesToAppend = appendPlan.entriesToAppend().stream()
                .map(LogEntryData::index)
                .collect(java.util.stream.Collectors.toSet());
        List<LogEntry> entriesToPersist = incomingEntries.stream()
                .filter(entry -> indicesToAppend.contains(entry.getIndex()))
                .toList();

        FollowerAppendDecision decision = FollowerAppendDecision.accepted(
                request, higherTerm, truncateFromIndex, entriesToPersist);
        return termPersistence
                .compose(ignored -> persistAppendEntries(truncateFromIndex, entriesToPersist))
                .map(decision);
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
            if (heartbeatTimerId != -1) {
                runtime.cancelTimer(heartbeatTimerId);
                heartbeatTimerId = -1;
            }
        }
        currentLeaderId = request.getLeaderId();
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
        }
        for (LogEntry entry : decision.entriesToPersist()) {
            if (!hasLogEntry(entry.getIndex())) log.add(entry);
        }

        if (request.getLeaderCommit() > commitIndex) {
            commitIndex = Math.min(request.getLeaderCommit(), lastLogIndex());
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
                .setMatchIndex(lastLogIndex())
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
        if (storage.isEmpty()) {
            return Future.succeededFuture();  // Volatile mode
        }
        
        if (entries.isEmpty() && truncateFromIndex == null) {
            return Future.succeededFuture();  // Nothing to persist (heartbeat)
        }

        RaftStorage s = storage.get();
        Future<Void> f = Future.succeededFuture();

        // Truncate if needed
        if (truncateFromIndex != null) {
            f = f.compose(v -> toFuture(s.truncateSuffix(truncateFromIndex)));
        }

        // Append entries
        if (!entries.isEmpty()) {
            List<LogEntryData> entryDataList = entries.stream()
                .map(e -> new LogEntryData(e.getIndex(), e.getTerm(), 
                                           serialize(e.getCommand()).toByteArray()))
                .toList();
            f = f.compose(v -> toFuture(s.appendEntries(entryDataList)));
        }

        // Sync for durability
        return f.compose(v -> toFuture(s.sync()));
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
                .setPrevLogIndex(prevLogIndex)
                .setPrevLogTerm(prevLogTerm)
                .setLeaderCommit(commitIndex);

        long lastIdx = lastLogIndex();
        boolean includeEntries = !heartbeat || nextIdx <= lastIdx;
        if (includeEntries) {
            for (long i = nextIdx; i <= lastIdx; i++) {
                if (hasLogEntry(i)) {
                    LogEntry entry = log.get(toArrayIndex(i));
                    builder.addEntries(dev.mars.qraft.controller.raft.grpc.LogEntry.newBuilder()
                            .setTerm(entry.getTerm())
                            .setIndex(entry.getIndex())
                            .setData(serialize(entry.getCommand()))
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
                .onFailure(error -> logger.error(
                        "Failed to process AppendEntries response from {}: {}",
                        peerId, error.getMessage(), error));
    }

    private Future<AppendResponseDecision> prepareAppendEntriesResponse(
            String peerId, AppendEntriesResponse response,
            long originatingTerm, long originatingGeneration) {
        if (response.getTerm() > currentTerm) {
            return persistMetadata(response.getTerm(), Optional.empty())
                    .map(ignored -> new AppendResponseDecision(
                            peerId, response, originatingTerm, originatingGeneration, true));
        }
        return Future.succeededFuture(new AppendResponseDecision(
                peerId, response, originatingTerm, originatingGeneration, false));
    }

    private Void applyAppendEntriesResponse(AppendResponseDecision decision) {
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

        if (unavailablePeers.remove(decision.peerId())) {
            logger.info("Raft peer {} is reachable again", decision.peerId());
        }
        if (response.getSuccess()) {
            matchIndex.put(decision.peerId(), response.getMatchIndex());
            nextIndex.put(decision.peerId(), response.getMatchIndex() + 1);
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

        List<Long> indices = new ArrayList<>(matchIndex.values());
        indices.add(lastLogIndex()); // Leader's match index
        Collections.sort(indices);
        long N = indices.get(indices.size() / 2); // Majority index

        if (N > commitIndex && hasLogEntry(N) && log.get(toArrayIndex(N)).getTerm() == currentTerm) {
            commitIndex = N;
            applyLog();
        }
    }

    private void applyLog() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            if (!hasLogEntry(lastApplied)) {
                // Entry already compacted by snapshot - skip
                continue;
            }
            LogEntry entry = log.get(toArrayIndex(lastApplied));
            RaftCommandResult<?> result = null;
            Exception exception = null;

            try {
                if (entry.getCommand() != null) {
                    result = stateMachine.apply(entry.getCommand());
                }
                stateMachine.setLastAppliedIndex(lastApplied);
            } catch (Exception e) {
                logger.error("Failed to apply command at index {}: {}", lastApplied, e.getMessage());
                logger.debug("Stack trace for command apply failure at index {}", lastApplied, e);
                exception = e;
            }

            // Complete future if this node is leader
            Promise<RaftCommandResult<?>> promise = pendingCommands.remove(lastApplied);
            if (promise != null) {
                if (exception != null) {
                    promise.fail(exception);
                } else {
                    promise.complete(result);
                }
            }
        }
    }

    // ========== SNAPSHOT SCHEDULING ==========

    /**
     * Starts periodic snapshot eligibility checks (leader only).
     * The check fires at the configured interval and triggers a snapshot
     * when (lastApplied - snapshotLastIndex) exceeds the threshold.
     */
    private void startSnapshotScheduler() {
        if (!snapshotEnabled) {
            return;
        }
        if (snapshotTimerId != -1) {
            runtime.cancelTimer(snapshotTimerId);
        }
        snapshotTimerId = setPeriodic(snapshotCheckIntervalMs, id -> checkAndTakeSnapshot());
        logger.info("Snapshot scheduler started: threshold={}, checkInterval={}ms",
                snapshotThreshold, snapshotCheckIntervalMs);
    }

    /**
     * Checks whether a snapshot is needed and takes one if the threshold is reached.
     * Only the leader takes snapshots to avoid redundant work.
     */
    private void checkAndTakeSnapshot() {
        if (state != State.LEADER || !snapshotEnabled) {
            return;
        }

        long entriesSinceSnapshot = lastApplied - snapshotLastIndex;
        if (entriesSinceSnapshot < snapshotThreshold) {
            logger.debug("Snapshot check: {} entries since last snapshot (threshold: {})",
                    entriesSinceSnapshot, snapshotThreshold);
            return;
        }

        logger.info("Snapshot threshold reached: {} entries since last snapshot, triggering snapshot",
                entriesSinceSnapshot);
        takeSnapshot();
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
        if (storage.isEmpty()) {
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
            snapshot = new SnapshotData(
                    stateMachine.takeSnapshot(), snapshotIndex, snapshotTerm);
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
            publication = toFuture(snapshotStore.orElseThrow().saveAtomically(snapshot))
                    .map(ignored -> captured)
                    .recover(error -> Future.succeededFuture(
                            LocalSnapshotDecision.failedBeforePublication(error)));
        } catch (RuntimeException error) {
            publication = Future.succeededFuture(
                    LocalSnapshotDecision.failedBeforePublication(error));
        }

        return publication.compose(decision -> {
            if (decision.failure() != null) return Future.succeededFuture(decision);
            return toFuture(storage.orElseThrow().truncatePrefix(snapshotIndex)).map(decision);
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
        // Prevent concurrent snapshot installs to the same follower
        if (installSnapshotInProgress.contains(target)) {
            logger.debug("InstallSnapshot already in progress for {}, skipping", target);
            return;
        }
        if (storage.isEmpty()) {
            logger.warn("Cannot send InstallSnapshot: no storage configured");
            return;
        }

        installSnapshotInProgress.add(target);
        logger.info("Sending InstallSnapshot to lagging follower {} (nextIndex={}, snapshotLastIndex={})",
                target, nextIndex.getOrDefault(target, 1L), snapshotLastIndex);

        toFuture(snapshotStore.orElseThrow().loadLatest())
                .onSuccess(snapshotOpt -> {
                    if (snapshotOpt.isEmpty()) {
                        logger.warn("No snapshot available to send to {}", target);
                        installSnapshotInProgress.remove(target);
                        return;
                    }
                    SnapshotData snapshot = snapshotOpt.get();
                    byte[] data = snapshot.data();
                    int totalChunks = Math.max(1, (int) Math.ceil((double) data.length / SNAPSHOT_CHUNK_SIZE));

                    logger.info("Sending snapshot to {}: {} bytes in {} chunk(s), lastIncludedIndex={}, lastIncludedTerm={}",
                            target, data.length, totalChunks, snapshot.lastIncludedIndex(), snapshot.lastIncludedTerm());

                    sendSnapshotChunk(target, snapshot, data, 0, totalChunks);
                })
                .onFailure(err -> {
                    logger.error("Failed to load snapshot for InstallSnapshot to {}: {}", target, err.getMessage());
                    installSnapshotInProgress.remove(target);
                });
    }

    /**
     * Sends a single snapshot chunk sequentially. On success, sends the next chunk
     * or completes the install if this was the last chunk.
     */
    private void sendSnapshotChunk(String target, SnapshotData snapshot, byte[] data,
                                    int chunkIndex, int totalChunks) {
        if (state != State.LEADER) {
            logger.debug("No longer leader, aborting InstallSnapshot to {}", target);
            installSnapshotInProgress.remove(target);
            return;
        }

        int offset = chunkIndex * SNAPSHOT_CHUNK_SIZE;
        int length = Math.min(SNAPSHOT_CHUNK_SIZE, data.length - offset);
        boolean isLast = (chunkIndex == totalChunks - 1);

        InstallSnapshotRequest request = InstallSnapshotRequest.newBuilder()
                .setTerm(currentTerm)
                .setLeaderId(nodeId)
                .setLastIncludedIndex(snapshot.lastIncludedIndex())
                .setLastIncludedTerm(snapshot.lastIncludedTerm())
                .setChunkIndex(chunkIndex)
                .setTotalChunks(totalChunks)
                .setData(com.google.protobuf.ByteString.copyFrom(data, offset, length))
                .setDone(isLast)
                .build();

        installSnapshotSent.add(1);

        transport.sendInstallSnapshot(target, request)
                .onSuccess(response -> runOnContext(v -> {
                    if (response.getTerm() > currentTerm) {
                        stepDown(response.getTerm());
                        installSnapshotInProgress.remove(target);
                        return;
                    }

                    if (!response.getSuccess()) {
                        logger.warn("InstallSnapshot chunk {}/{} rejected by {}, retrying from chunk {}",
                                chunkIndex + 1, totalChunks, target, response.getNextChunkIndex());
                        // Retry from the chunk the follower expects
                        sendSnapshotChunk(target, snapshot, data, response.getNextChunkIndex(), totalChunks);
                        return;
                    }

                    if (isLast) {
                        // Snapshot fully installed — update follower tracking
                        long snapIdx = snapshot.lastIncludedIndex();
                        nextIndex.put(target, snapIdx + 1);
                        matchIndex.put(target, snapIdx);
                        installSnapshotInProgress.remove(target);
                        logger.info("InstallSnapshot to {} complete: nextIndex={}, matchIndex={}",
                                target, snapIdx + 1, snapIdx);
                        updateCommitIndex();
                    } else {
                        // Send next chunk
                        sendSnapshotChunk(target, snapshot, data, chunkIndex + 1, totalChunks);
                    }
                }))
                .onFailure(err -> {
                    logger.error("Failed to send InstallSnapshot chunk {}/{} to {}: {}",
                            chunkIndex + 1, totalChunks, target, err.getMessage());
                    installSnapshotInProgress.remove(target);
                });

        rpcCounter.add(1, Attributes.of(
                AttributeKey.stringKey("source"), nodeId,
                AttributeKey.stringKey("target"), target,
                AttributeKey.stringKey("type"), "install_snapshot"
        ));
    }

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
        transitionSequencer.submit(
                        "install-snapshot:" + request.getLeaderId() + ":" + request.getTerm()
                                + ":" + request.getChunkIndex(),
                        RaftTransitionSequencer.FailurePolicy.FENCE,
                        () -> prepareAndPersistInstalledSnapshot(request),
                        this::applyInstalledSnapshot)
                .onComplete(result -> {
                    if (result.succeeded()) {
                        promise.tryComplete(result.result());
                    } else {
                        Throwable error = result.cause();
                        logger.error("InstallSnapshot durable transition failed: {}",
                                error.getMessage(), error);
                        promise.tryComplete(InstallSnapshotResponse.newBuilder()
                                .setTerm(currentTerm)
                                .setSuccess(false)
                                .setNextChunkIndex(0)
                                .build());
                    }
                });
        return promise.future();
    }

    private Future<InstalledSnapshotPlan> prepareAndPersistInstalledSnapshot(
            InstallSnapshotRequest request) {
        installSnapshotReceived.add(1);
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
        return termPersistence.compose(ignored -> persistInstalledSnapshot(plan));
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
                return InstalledSnapshotPlan.rejected(
                        request, higherTerm, clearAssemblers, true, 0);
            }
            assembler = null;
        }
        if (assembler == null) {
            if (chunkIndex != 0) {
                return InstalledSnapshotPlan.rejected(
                        request, higherTerm, clearAssemblers, true, 0);
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
        return InstalledSnapshotPlan.completed(
                request, higherTerm, clearAssemblers, snapshot);
    }

    private Future<InstalledSnapshotPlan> persistInstalledSnapshot(InstalledSnapshotPlan plan) {
        if (plan.snapshot() == null) return Future.succeededFuture(plan);

        Future<Void> publication;
        try {
            publication = snapshotStore
                    .map(store -> toFuture(store.saveAtomically(plan.snapshot())))
                    .orElseGet(Future::succeededFuture);
        } catch (RuntimeException error) {
            return Future.succeededFuture(plan.publicationFailed(error));
        }

        return publication
                .map(ignored -> plan)
                .recover(error -> Future.succeededFuture(plan.publicationFailed(error)))
                .compose(published -> {
                    if (published.failure() != null) return Future.succeededFuture(published);
                    return storage
                            .map(store -> toFuture(store.truncatePrefix(
                                    published.snapshot().lastIncludedIndex())).map(published))
                            .orElseGet(() -> Future.succeededFuture(published));
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
            failPendingCommands(new IllegalStateException(
                    "Leadership lost before command commit; outcome may be unknown"));
            if (heartbeatTimerId != -1) {
                runtime.cancelTimer(heartbeatTimerId);
                heartbeatTimerId = -1;
            }
        }
        if (plan.clearAssemblers()) pendingInstalls.clear();
        currentLeaderId = request.getLeaderId();
        resetElectionTimer();

        if (plan.removeAssembler()) pendingInstalls.remove(request.getLeaderId());
        if (plan.assemblerToStore() != null) {
            pendingInstalls.put(request.getLeaderId(), plan.assemblerToStore());
        }
        if (plan.failure() != null) {
            logger.error("Failed to publish installed snapshot: {}",
                    plan.failure().getMessage(), plan.failure());
            return installSnapshotResponse(false, 0);
        }
        if (plan.snapshot() == null) {
            return installSnapshotResponse(plan.success(), plan.nextChunkIndex());
        }

        SnapshotData snapshot = plan.snapshot();
        long lastIncludedIndex = snapshot.lastIncludedIndex();
        long lastIncludedTerm = snapshot.lastIncludedTerm();

        List<LogEntry> retainedSuffix = List.of();
        if (hasLogEntry(lastIncludedIndex)
                && log.get(toArrayIndex(lastIncludedIndex)).getTerm() == lastIncludedTerm) {
            int suffixStart = toArrayIndex(lastIncludedIndex) + 1;
            retainedSuffix = new ArrayList<>(log.subList(suffixStart, log.size()));
        }

        stateMachine.restoreSnapshot(snapshot.data());
        stateMachine.setLastAppliedIndex(lastIncludedIndex);
        log.clear();
        log.add(new LogEntry(lastIncludedTerm, lastIncludedIndex, null));
        log.addAll(retainedSuffix);
        snapshotLastIndex = lastIncludedIndex;
        snapshotLastTerm = lastIncludedTerm;
        lastApplied = lastIncludedIndex;
        commitIndex = Math.max(commitIndex, lastIncludedIndex);

        logger.info("Snapshot installed: snapshotLastIndex={}, snapshotLastTerm={}, commitIndex={}",
                snapshotLastIndex, snapshotLastTerm, commitIndex);
        return installSnapshotResponse(true, request.getTotalChunks());
    }

    private InstallSnapshotResponse installSnapshotResponse(boolean success, int nextChunkIndex) {
        return InstallSnapshotResponse.newBuilder()
                .setTerm(currentTerm)
                .setSuccess(success)
                .setNextChunkIndex(nextChunkIndex)
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
            boolean success,
            int nextChunkIndex,
            Throwable failure) {

        private static InstalledSnapshotPlan rejectedWithoutStateChange(
                InstallSnapshotRequest request) {
            return new InstalledSnapshotPlan(request, false, false, false, false,
                    null, null, false, 0, null);
        }

        private static InstalledSnapshotPlan rejected(
                InstallSnapshotRequest request, boolean higherTerm,
                boolean clearAssemblers, boolean removeAssembler, int nextChunkIndex) {
            return new InstalledSnapshotPlan(request, true, higherTerm, clearAssemblers,
                    removeAssembler, null, null, false, nextChunkIndex, null);
        }

        private static InstalledSnapshotPlan duplicate(
                InstallSnapshotRequest request, boolean higherTerm,
                boolean clearAssemblers, int nextChunkIndex) {
            return new InstalledSnapshotPlan(request, true, higherTerm, clearAssemblers,
                    true, null, null, true, nextChunkIndex, null);
        }

        private static InstalledSnapshotPlan acceptedChunk(
                InstallSnapshotRequest request, boolean higherTerm,
                boolean clearAssemblers, SnapshotChunkAssembler assembler) {
            return new InstalledSnapshotPlan(request, true, higherTerm, clearAssemblers,
                    false, assembler, null, true, assembler.getNextExpectedChunk(), null);
        }

        private static InstalledSnapshotPlan completed(
                InstallSnapshotRequest request, boolean higherTerm,
                boolean clearAssemblers, SnapshotData snapshot) {
            return new InstalledSnapshotPlan(request, true, higherTerm, clearAssemblers,
                    true, null, snapshot, true, request.getTotalChunks(), null);
        }

        private InstalledSnapshotPlan publicationFailed(Throwable error) {
            return new InstalledSnapshotPlan(request, termAccepted, higherTerm, clearAssemblers,
                    true, null, snapshot, false, 0, error);
        }
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

    private Future<Void> closeDurableStorage() {
        Throwable failure = null;
        try {
            if (snapshotStore.isPresent()) snapshotStore.get().close();
        } catch (Throwable error) {
            failure = error;
        }
        try {
            if (storage.isPresent()) storage.get().close();
        } catch (Throwable error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        return failure == null ? Future.succeededFuture() : Future.failedFuture(failure);
    }

    private static <T> Future<T> toFuture(CompletableFuture<T> future) {
        return Future.fromCompletionStage(future);
    }

    private ByteString serialize(RaftCommand cmd) {
        return ByteString.copyFrom(commandCodec.serialize(cmd));
    }

    private void runOnContext(java.util.function.Consumer<Void> action) {
        runtime.runOnContext(ignored -> withLoggingContext(() -> action.accept(null)));
    }

    private long setTimer(long delayMs, java.util.function.Consumer<Long> action) {
        return runtime.setTimer(delayMs, id -> withLoggingContext(() -> action.accept(id)));
    }

    private long setPeriodic(long periodMs, java.util.function.Consumer<Long> action) {
        return runtime.setPeriodic(periodMs, id -> withLoggingContext(() -> action.accept(id)));
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

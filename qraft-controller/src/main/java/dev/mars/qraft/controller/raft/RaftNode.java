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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
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
    private final RaftTimerScheduler timerScheduler;

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
        private long electionTimeoutMs = 5000;
        private long heartbeatIntervalMs = 1000;
        private Boolean snapshotEnabled;       // null = derive from mode
        private long snapshotThreshold = 10000;
        private long snapshotCheckIntervalMs = 60000;
        private long logHardLimit = 100000;
        private RaftTimerScheduler timerScheduler;
        private int transitionQueueCapacity = 1024;

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
            if (transport == null) throw new IllegalStateException("transport is required");
            if (stateMachine == null) throw new IllegalStateException("stateMachine is required");
                if (commandCodec == null) throw new IllegalStateException("commandCodec is required");
            if (mode == null) throw new IllegalStateException("mode is required");

            boolean snap = (snapshotEnabled != null) ? snapshotEnabled : mode.isDurable();
            return new RaftNode(runtime, nodeId, clusterNodes, transport, stateMachine,
                    commandCodec, mode, electionTimeoutMs, heartbeatIntervalMs, snap,
                    snapshotThreshold, snapshotCheckIntervalMs, logHardLimit, timerScheduler,
                    transitionQueueCapacity);
        }
    }

    // ========== CONSTRUCTOR (private) ==========

    private RaftNode(JavaRuntime runtime, String nodeId, Set<String> clusterNodes, RaftTransport transport,
            RaftLogApplicator stateMachine, CommandCodec<RaftCommand> commandCodec,
            RaftNodeMode mode, long electionTimeoutMs, long heartbeatIntervalMs,
            boolean snapshotEnabled, long snapshotThreshold, long snapshotCheckIntervalMs,
            long logHardLimit, RaftTimerScheduler configuredTimerScheduler,
            int transitionQueueCapacity) {
        this.runtime = runtime;
        this.nodeId = nodeId;
        this.clusterNodes = new HashSet<>(clusterNodes);
        this.transport = transport;
        this.stateMachine = stateMachine;
        this.commandCodec = commandCodec;
        this.storage = requireNonNull(mode, "mode").storage();
        this.snapshotStore = mode.snapshots();
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
            recoverFromStorage().onComplete(result ->
                    runOnContext(v -> finishStart(result, completion)));
        } catch (Throwable error) {
            finishOwnedAsyncOperation();
            completion.tryFail(error);
        }
    }

    private void finishStart(
            dev.mars.qraft.controller.runtime.AsyncResult<Void> recovery,
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
                resetElectionTimer();
                running = true;
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
            rollbackFailedStart(completion, startupFailure);
        }
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
        if (storage.isEmpty()) {
            logger.info("No storage configured, running in volatile mode");
            return Future.succeededFuture();
        }

        RaftStorage store = storage.get();
        SnapshotStore snapshots = snapshotStore.orElseThrow();
        logger.info("Recovering Raft state from storage...");

        Future<Optional<SnapshotData>> snapshotLoad =
                composeOnStateLoop(toFuture(store.loadMetadata()), meta -> {
                this.currentTerm = meta.currentTerm();
                this.votedFor = meta.votedFor().orElse(null);
                logger.info("Recovered metadata: term={}, votedFor={}", currentTerm, votedFor);

                // Try to load snapshot
                return toFuture(snapshots.loadLatest());
            });
        Future<List<LogEntryData>> replay = composeOnStateLoop(snapshotLoad, snapshotOpt -> {
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
            });
        Future<Void> recovered = composeOnStateLoop(replay, entries -> {
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
                            log.add(new LogEntry(entry.term(), entry.index(), command));
                            replayedCount++;
                            expectedIndex++;
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
                    long expectedIndex = 1;
                    for (LogEntryData entry : entries) {
                        if (entry.index() != expectedIndex) {
                            return Future.failedFuture(new IllegalStateException(
                                    "Non-contiguous WAL replay: expected index " + expectedIndex
                                            + " but found " + entry.index()));
                        }
                        RaftCommand command = deserialize(ByteString.copyFrom(entry.payload()));
                        log.add(new LogEntry(entry.term(), entry.index(), command));
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

        Future.all(transitionSequencer.drain(), drainOwnedAsyncOperations()).onComplete(drainResult -> {
            state = State.FOLLOWER;
            currentLeaderId = null;
            failPendingCommands(new IllegalStateException(
                    "Node stopped before command commitment; outcome may be unknown"));
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
        transitionSequencer.assertActiveTransition();
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

    public boolean isFenced() {
        return transitionSequencer.isFenced();
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
            timerScheduler.cancelTimer(timerId);
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
        cancelElectionTimer();
        cancelHeartbeatTimer();
        cancelSnapshotTimer();
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

        long timeout = electionTimeoutMs + (long) (Math.random() * electionTimeoutMs);
        long timerGeneration = electionTimerGeneration;

        electionTimerId = setTimer(timeout, id -> onElectionTimer(id, timerGeneration));
    }

    private void onElectionTimer(long timerId, long timerGeneration) {
        if (timerId != electionTimerId || timerGeneration != electionTimerGeneration) return;
        electionTimerId = -1;
        startElection(timerGeneration);
    }

    private void startElection(long timerGeneration) {
        transitionSequencer.submit(
                        "start-election",
                        RaftTransitionSequencer.FailurePolicy.FENCE,
                        () -> prepareAndPersistElection(timerGeneration),
                        this::applyElection)
                .onFailure(error -> logger.error(
                        "Failed to start election because metadata durability is uncertain: {}",
                        error.getMessage(), error))
                .onFailure(error -> {
                    if (error instanceof RaftTransitionSequencer.QueueFullException
                            && running && state != State.LEADER
                            && timerGeneration == electionTimerGeneration) {
                        resetElectionTimer();
                    }
                });
    }

    private Future<ElectionDecision> prepareAndPersistElection(long timerGeneration) {
        if (!running || state == State.LEADER
                || timerGeneration != electionTimerGeneration) {
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
                        .onSuccess(response -> sequenceVoteResponse(response, term, voteCount))
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
            VoteResponse response, long electionTerm, AtomicLong voteCount) {
        transitionSequencer.submit(
                        "vote-response:" + electionTerm + ":" + response.getTerm(),
                        RaftTransitionSequencer.FailurePolicy.FENCE,
                        () -> prepareVoteResponse(response),
                        decision -> applyVoteResponse(decision, electionTerm, voteCount))
                .onFailure(error -> logger.error(
                        "Failed to process vote response for election term {}: {}",
                        electionTerm, error.getMessage(), error));
    }

    private Future<VoteResponseDecision> prepareVoteResponse(VoteResponse response) {
        if (response.getTerm() <= currentTerm) {
            return Future.succeededFuture(new VoteResponseDecision(response, false));
        }
        return persistMetadata(response.getTerm(), Optional.empty())
                .map(ignored -> new VoteResponseDecision(response, true));
    }

    private Void applyVoteResponse(
            VoteResponseDecision decision, long electionTerm, AtomicLong voteCount) {
        VoteResponse response = decision.response();
        if (decision.higherTerm()) {
            applyDurableHigherTerm(response.getTerm(), null);
            return null;
        }
        if (state != State.CANDIDATE || currentTerm != electionTerm) {
            return null;
        }
        if (response.getVoteGranted()) {
            long votes = voteCount.incrementAndGet();
            if (votes > clusterNodes.size() / 2) {
                becomeLeader();
            }
        }
        return null;
    }

    private record VoteResponseDecision(VoteResponse response, boolean higherTerm) {}

    private void becomeLeader() {
        if (state != State.CANDIDATE)
            return;

        outboundSnapshotTransfers.clear();
        state = State.LEADER;
        leadershipGeneration++;
        currentLeaderId = nodeId;
        MDC.put("raftRole", "LEADER");
        MDC.put("raftTerm", String.valueOf(currentTerm));
        logger.info("Node {} became LEADER for term {}", nodeId, currentTerm);
        notifyStateChangeListeners(State.LEADER);

        cancelElectionTimer();

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
            sendHeartbeats();
        }
        return null;
    }

    private record HeartbeatTimerDecision(
            long timerGeneration, long term, long leaderGeneration) {}

    private void sendHeartbeats() {
        if (state != State.LEADER)
            return;

        for (String peer : clusterNodes) {
            if (!peer.equals(nodeId)) {
                sendAppendEntries(peer, true);
            }
        }
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
        transitionSequencer.assertActiveTransition();
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
        Future<FollowerAppendResult> transition = transitionSequencer.submit(
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
        return composeOnStateLoop(termPersistence,
                        ignored -> persistAppendEntries(truncateFromIndex, entriesToPersist))
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
                });
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
            cancelSnapshotTimer();
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
        transitionSequencer.assertActiveTransition();
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
        cancelSnapshotTimer();
        long timerGeneration = snapshotTimerGeneration;
        long term = currentTerm;
        long leaderGeneration = leadershipGeneration;
        snapshotTimerId = setPeriodic(snapshotCheckIntervalMs,
                id -> onSnapshotTimer(id, timerGeneration, term, leaderGeneration));
        logger.info("Snapshot scheduler started: threshold={}, checkInterval={}ms",
                snapshotThreshold, snapshotCheckIntervalMs);
    }

    /**
     * Checks whether a snapshot is needed and takes one if the threshold is reached.
     * Only the leader takes snapshots to avoid redundant work.
     */
    private void onSnapshotTimer(
            long timerId, long timerGeneration, long term, long leaderGeneration) {
        if (timerId != snapshotTimerId
                || timerGeneration != snapshotTimerGeneration
                || snapshotTimerTransitionPending) {
            return;
        }
        snapshotTimerTransitionPending = true;
        transitionSequencer.submit(
                        "snapshot-timer:" + term + ":" + leaderGeneration,
                        RaftTransitionSequencer.FailurePolicy.FENCE,
                        () -> prepareScheduledSnapshot(timerGeneration, term, leaderGeneration),
                        this::applyLocalSnapshot)
                .onComplete(result -> {
                    snapshotTimerTransitionPending = false;
                    if (result.failed()) {
                        logger.error("Scheduled snapshot failed: {}",
                                result.cause().getMessage(), result.cause());
                    } else if (result.result().failure() != null) {
                        logger.error("Scheduled snapshot failed: {}",
                                result.result().failure().getMessage(), result.result().failure());
                    }
                });
    }

    private Future<LocalSnapshotDecision> prepareScheduledSnapshot(
            long timerGeneration, long term, long leaderGeneration) {
        if (!snapshotEnabled
                || timerGeneration != snapshotTimerGeneration
                || !isCurrentLeadership(term, leaderGeneration)) {
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
        transitionSequencer.assertActiveTransition();
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
        if (storage.isEmpty()) {
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
            load = toFuture(snapshotStore.orElseThrow().loadLatest());
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
        OutboundSnapshotResponseDecision decision = new OutboundSnapshotResponseDecision(
                transfer, snapshot, data, chunkIndex, totalChunks, last, response,
                response.getTerm() > currentTerm);
        if (!decision.higherTerm()) return Future.succeededFuture(decision);
        return persistMetadata(response.getTerm(), Optional.empty()).map(decision);
    }

    private Void applyOutboundSnapshotResponse(OutboundSnapshotResponseDecision decision) {
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

        if (!response.getSuccess()) {
            int retryChunk = response.getNextChunkIndex();
            if (retryChunk < 0 || retryChunk >= decision.totalChunks()) {
                logger.warn("InstallSnapshot response from {} requested invalid chunk {} of {}",
                        transfer.target(), retryChunk, decision.totalChunks());
                removeOutboundSnapshotTransfer(transfer);
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
        Future<InstallSnapshotResponse> transition = transitionSequencer.submit(
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
        boolean retainSuffix = hasLogEntry(snapshot.lastIncludedIndex())
                && log.get(toArrayIndex(snapshot.lastIncludedIndex())).getTerm()
                        == snapshot.lastIncludedTerm();
        return InstalledSnapshotPlan.completed(
                request, higherTerm, clearAssemblers, snapshot, retainSuffix);
    }

    private Future<InstalledSnapshotPlan> persistInstalledSnapshot(InstalledSnapshotPlan plan) {
        transitionSequencer.assertActiveTransition();
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
                            .map(store -> {
                                long boundary = published.snapshot().lastIncludedIndex();
                                Future<Void> durability = published.retainSuffix()
                                        ? Future.succeededFuture()
                                        : toFuture(store.truncateSuffix(boundary + 1))
                                                .compose(ignored -> toFuture(store.sync()));
                                return durability
                                        .compose(ignored -> toFuture(store.truncatePrefix(boundary)))
                                        .map(published);
                            })
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
            cancelHeartbeatTimer();
            cancelSnapshotTimer();
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
        if (plan.retainSuffix()) {
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
            boolean retainSuffix,
            boolean success,
            int nextChunkIndex,
            Throwable failure) {

        private static InstalledSnapshotPlan rejectedWithoutStateChange(
                InstallSnapshotRequest request) {
            return new InstalledSnapshotPlan(request, false, false, false, false,
                    null, null, false, false, 0, null);
        }

        private static InstalledSnapshotPlan rejected(
                InstallSnapshotRequest request, boolean higherTerm,
                boolean clearAssemblers, boolean removeAssembler, int nextChunkIndex) {
            return new InstalledSnapshotPlan(request, true, higherTerm, clearAssemblers,
                    removeAssembler, null, null, false, false, nextChunkIndex, null);
        }

        private static InstalledSnapshotPlan duplicate(
                InstallSnapshotRequest request, boolean higherTerm,
                boolean clearAssemblers, int nextChunkIndex) {
            return new InstalledSnapshotPlan(request, true, higherTerm, clearAssemblers,
                    true, null, null, false, true, nextChunkIndex, null);
        }

        private static InstalledSnapshotPlan acceptedChunk(
                InstallSnapshotRequest request, boolean higherTerm,
                boolean clearAssemblers, SnapshotChunkAssembler assembler) {
            return new InstalledSnapshotPlan(request, true, higherTerm, clearAssemblers,
                    false, assembler, null, false, true, assembler.getNextExpectedChunk(), null);
        }

        private static InstalledSnapshotPlan completed(
                InstallSnapshotRequest request, boolean higherTerm,
                boolean clearAssemblers, SnapshotData snapshot, boolean retainSuffix) {
            return new InstalledSnapshotPlan(request, true, higherTerm, clearAssemblers,
                    true, null, snapshot, retainSuffix, true, request.getTotalChunks(), null);
        }

        private InstalledSnapshotPlan publicationFailed(Throwable error) {
            return new InstalledSnapshotPlan(request, termAccepted, higherTerm, clearAssemblers,
                    true, null, snapshot, retainSuffix, false, 0, error);
        }
    }

    private static boolean isAmbiguousLeaderAppendFailure(Throwable error) {
        return !isKnownPrewriteRejection(error);
    }

    private static boolean isAmbiguousFollowerAppendFailure(
            Throwable error, Long truncateFromIndex, int entriesToPersist) {
        if (!isKnownPrewriteRejection(error) || truncateFromIndex != null) {
            return true;
        }
        Throwable cause = unwrapCompletionFailure(error);
        if (cause.getMessage().startsWith("Insufficient disk space:")) {
            // FileRaftStorage performs this check per large record. In a batch,
            // earlier records may already have been written before a later check fails.
            return entriesToPersist != 1;
        }
        // Payload sizes are validated for the complete batch before any record is written.
        return false;
    }

    private static boolean isKnownPrewriteRejection(Throwable error) {
        Throwable cause = unwrapCompletionFailure(error);
        return cause instanceof dev.mars.raftlog.storage.FileRaftStorage.StorageException
                && cause.getCause() == null
                && cause.getMessage() != null
                && (cause.getMessage().startsWith("Payload too large:")
                    || cause.getMessage().startsWith("Insufficient disk space:"));
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
        return runtime.executeBlocking(() -> {
            Throwable failure = null;
            try {
                transport.stop();
            } catch (Throwable error) {
                failure = error;
            }

            Object snapshots = snapshotStore.orElse(null);
            Object wal = storage.orElse(null);
            if (snapshots != null) {
                try {
                    ((SnapshotStore) snapshots).close();
                } catch (Throwable error) {
                    failure = combineFailures(failure, error);
                }
            }
            if (wal != null && wal != snapshots) {
                try {
                    ((RaftStorage) wal).close();
                } catch (Throwable error) {
                    failure = combineFailures(failure, error);
                }
            }

            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IllegalStateException(failure);
            return null;
        });
    }

    private static Throwable combineFailures(Throwable first, Throwable second) {
        if (first == null) return second;
        if (second != null && second != first) first.addSuppressed(second);
        return first;
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

    private <T> void onStateLoopComplete(
            Future<T> future,
            java.util.function.Consumer<dev.mars.qraft.controller.runtime.AsyncResult<T>> action) {
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

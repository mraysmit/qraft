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

package dev.mars.qraft.server.recovery;

import dev.mars.qraft.common.async.BlockingTaskExecutor;
import dev.mars.qraft.common.async.RaftStateLoop;
import dev.mars.qraft.common.concurrent.Completions;
import dev.mars.qraft.raft.ConfigurationCommand;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftConfiguration;
import dev.mars.qraft.raft.RaftMessage;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.raft.RaftTransport;
import dev.mars.qraft.raft.api.SnapshotStore.SnapshotData;
import dev.mars.qraft.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;
import dev.mars.qraft.raft.storage.RaftStorageFactory;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import dev.mars.raftlog.storage.RaftStorage.PersistentMeta;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * What a copy of a stopped data directory holds, and whether ordinary startup would accept it (Step 7 contract,
 * step 3). It reads the copy's storage for the report, then starts a real {@link RaftNode} on the copy with the
 * directory's own server ID, so the checks are exactly those of a server's start, and stops it again before any
 * election. The node has no peers: its transport refuses every send, and its election timeout lies far beyond
 * the examination. A directory with no Raft state at all is refused without a node, since a node would bootstrap
 * it.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-22
 * @version 1.0
 */
record RecoveryExamination(String serverId, PersistentMeta metadata, long lastLogIndex, long lastLogTerm,
                           long snapshotIndex, long snapshotTerm, long compactedThrough,
                           Optional<RaftConfiguration> configuration,
                           DirectoryReport.ConfigurationSource configurationSource,
                           Optional<String> startupRefusal, Set<String> retainedServerIds) {
    /** The name the examining node runs under; the directory's own name plays no part in its checks. */
    private static final String NODE_NAME = "offline-recovery";
    private static final long ELECTION_TIMEOUT_MS = TimeUnit.HOURS.toMillis(1);
    private static final long HEARTBEAT_INTERVAL_MS = TimeUnit.SECONDS.toMillis(1);
    private static final long WAIT_SECONDS = 60;
    private static final ProtobufRaftCommandCodec CODEC = new ProtobufRaftCommandCodec();

    /** Examines {@code copy}, a copy of a directory whose server ID is {@code serverId}. */
    static RecoveryExamination examine(Path copy, String serverId) throws RecoveryRefusedException {
        Optional<StoredState> stored = readStorage(copy);
        if (stored.isPresent() && stored.get().entries().isEmpty() && stored.get().snapshot().isEmpty()) {
            throw new RecoveryRefusedException(copy + " holds no Raft state; there is nothing to recover");
        }
        NodeCheck check = startAndStop(copy, serverId);
        if (stored.isEmpty()) {
            return new RecoveryExamination(serverId, new PersistentMeta(0, Optional.empty()), 0, 0, 0, 0, 0,
                    Optional.empty(), DirectoryReport.ConfigurationSource.NONE,
                    Optional.of(check.refusal().orElse("its storage cannot be read")), Set.of());
        }
        return fromStorage(serverId, stored.get(), check);
    }

    private static RecoveryExamination fromStorage(String serverId, StoredState stored, NodeCheck check) {
        Set<String> retained = new HashSet<>(stored.retainedServerIds());
        check.configuration().ifPresent(found -> found.servers().forEach(server -> retained.add(server.serverId())));
        DirectoryReport.ConfigurationSource source = DirectoryReport.ConfigurationSource.NONE;
        if (check.refusal().isEmpty() && stored.walHoldsConfiguration()) {
            source = DirectoryReport.ConfigurationSource.WAL;
        } else if (check.refusal().isEmpty() && stored.snapshot().isPresent()) {
            source = DirectoryReport.ConfigurationSource.SNAPSHOT;
        }
        long snapshotIndex = stored.snapshot().map(SnapshotData::lastIncludedIndex).orElse(0L);
        long snapshotTerm = stored.snapshot().map(SnapshotData::lastIncludedTerm).orElse(0L);
        long lastIndex = stored.entries().isEmpty() ? snapshotIndex : stored.entries().getLast().index();
        long lastTerm = stored.entries().isEmpty() ? snapshotTerm : stored.entries().getLast().term();
        Optional<RaftConfiguration> configuration =
                check.refusal().isPresent() ? Optional.empty() : check.configuration();
        return new RecoveryExamination(serverId, stored.metadata(), lastIndex, lastTerm, snapshotIndex,
                snapshotTerm, stored.compactedThrough(), configuration, source, check.refusal(),
                Set.copyOf(retained));
    }

    /** What the node's start found: the configuration in force, or why ordinary startup refuses the copy. */
    private record NodeCheck(Optional<RaftConfiguration> configuration, Optional<String> refusal) {
        static NodeCheck refused(Throwable failure) {
            return new NodeCheck(Optional.empty(), Optional.of(describe(failure)));
        }
    }

    private static NodeCheck startAndStop(Path copy, String serverId) {
        RaftStateLoop loop = RaftStateLoop.create();
        BlockingTaskExecutor tasks = new BlockingTaskExecutor("qraft-recovery", 1, loop);
        try {
            return runNode(copy, serverId, loop, tasks);
        } finally {
            try {
                await(shutdown(tasks, loop));
            } catch (ExecutionException stalled) {
                throw new IllegalStateException("The examination's executors did not stop", stalled.getCause());
            }
        }
    }

    private static NodeCheck runNode(Path copy, String serverId, RaftStateLoop loop, BlockingTaskExecutor tasks) {
        RaftNode node;
        try {
            node = RaftNode.builder()
                    .stateLoop(loop).blockingTasks(tasks)
                    .nodeId(NODE_NAME).serverId(serverId).clusterNodes(Set.of(NODE_NAME))
                    .transport(new OfflineTransport()).stateMachine(new QraftStateStore()).commandCodec(CODEC)
                    .mode(durable(copy)).snapshotEnabled(false)
                    .electionTimeout(ELECTION_TIMEOUT_MS).heartbeatInterval(HEARTBEAT_INTERVAL_MS)
                    .build();
        } catch (ExecutionException storageFailure) {
            return NodeCheck.refused(storageFailure.getCause());
        }
        NodeCheck check;
        try {
            await(node.start());
            check = new NodeCheck(node.getConfiguration(), Optional.empty());
        } catch (ExecutionException failed) {
            check = NodeCheck.refused(failed.getCause());
        }
        try {
            await(node.stop());
        } catch (ExecutionException stopFailure) {
            if (check.refusal().isEmpty()) check = NodeCheck.refused(stopFailure.getCause());
        }
        return check;
    }

    /** What the copy's storage holds, read directly, for the report; the node's own start decides acceptance. */
    private record StoredState(PersistentMeta metadata, List<LogEntryData> entries, Optional<SnapshotData> snapshot,
                               long compactedThrough, boolean walHoldsConfiguration, Set<String> retainedServerIds) { }

    /** Reads the copy's storage, or empty if it cannot be read; the node's start then reports why. */
    private static Optional<StoredState> readStorage(Path copy) {
        try {
            return Optional.of(readStorageOrFail(copy));
        } catch (ExecutionException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static StoredState readStorageOrFail(Path copy) throws ExecutionException {
        RaftStorageFactory.DurableStorage storage = await(RaftStorageFactory.createDurable(copy, true));
        try {
            PersistentMeta metadata = await(storage.wal().loadMetadata());
            List<LogEntryData> entries = await(storage.wal().replayLog());
            Optional<SnapshotData> snapshot = await(storage.snapshots().loadLatest());
            long compactedThrough = await(storage.wal().compactionBoundary());
            boolean walHoldsConfiguration = false;
            Set<String> retained = new HashSet<>();
            for (LogEntryData entry : entries) {
                RaftCommand command;
                try {
                    command = CODEC.deserialize(entry.payload());
                } catch (RuntimeException undecodable) {
                    continue; // the node's own start reports an entry it cannot decode
                }
                if (command instanceof ConfigurationCommand recorded) {
                    walHoldsConfiguration = true;
                    recorded.configuration().servers().forEach(server -> retained.add(server.serverId()));
                }
            }
            return new StoredState(metadata, entries, snapshot, compactedThrough, walHoldsConfiguration, retained);
        } finally {
            await(storage.snapshots().closeAsync());
            await(storage.wal().closeAsync());
        }
    }

    private static RaftNodeMode durable(Path copy) throws ExecutionException {
        RaftStorageFactory.DurableStorage storage = await(RaftStorageFactory.createDurable(copy, true));
        return RaftNodeMode.durable(storage.wal(), storage.snapshots());
    }

    /** The messages of {@code failure} and its causes, so a wrapped reason, such as a corrupt WAL, stays visible. */
    static String describe(Throwable failure) {
        Set<String> messages = new LinkedHashSet<>();
        for (Throwable cause = Completions.unwrap(failure); cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) messages.add(cause.getMessage());
        }
        return messages.isEmpty() ? String.valueOf(failure) : String.join(": ", messages);
    }

    private static CompletableFuture<Void> shutdown(BlockingTaskExecutor tasks, RaftStateLoop loop) {
        return tasks.shutdown().handle((ignored, failure) -> null).thenCompose(ignored -> loop.shutdown());
    }

    /** Waits for {@code stage}; the bound only stops a stalled storage operation holding the command forever. */
    static <T> T await(CompletionStage<T> stage) throws ExecutionException {
        try {
            return stage.toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExecutionException("Interrupted while examining a data directory", interrupted);
        } catch (TimeoutException stalled) {
            throw new ExecutionException("A storage operation did not finish within " + WAIT_SECONDS + " s",
                    stalled);
        }
    }

    /** The examining node's transport: it has no peers, so every send is refused. */
    private static final class OfflineTransport implements RaftTransport {
        @Override public void start(Consumer<RaftMessage> messageHandler) {
            // Nothing listens: no server can reach a node that runs only for the examination.
        }

        @Override public void stop() {
            // Nothing was opened.
        }

        @Override
        public CompletableFuture<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return noPeer(targetId);
        }

        @Override
        public CompletableFuture<AppendEntriesResponse> sendAppendEntries(String targetId,
                                                                         AppendEntriesRequest request) {
            return noPeer(targetId);
        }

        @Override
        public CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(String targetId,
                                                                             InstallSnapshotRequest request) {
            return noPeer(targetId);
        }

        private static <T> CompletableFuture<T> noPeer(String targetId) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Offline recovery contacts no server, so nothing is sent to " + targetId));
        }
    }
}

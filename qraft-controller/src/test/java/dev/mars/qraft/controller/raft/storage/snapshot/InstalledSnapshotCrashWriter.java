/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.mars.qraft.controller.raft.storage.snapshot;

import com.google.protobuf.ByteString;
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
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.raftlog.storage.RaftStorage;
import dev.mars.raftlog.storage.RaftStorageConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Child-process fixture for real installed-snapshot and shutdown-drain recovery.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-15
 * @version 1.0
 */
public final class InstalledSnapshotCrashWriter {
    public static final int HALT_EXIT_CODE = 93;
    public static final String AFTER_INSTALLED_SNAPSHOT_PUBLICATION =
            "AFTER_INSTALLED_SNAPSHOT_PUBLICATION";
    public static final String DURING_SHUTDOWN_AFTER_PREFIX_COMPACTION =
            "DURING_SHUTDOWN_AFTER_PREFIX_COMPACTION";
    public static final String AFTER_DIVERGENT_SUFFIX_INSTALL =
            "AFTER_DIVERGENT_SUFFIX_INSTALL";

    private InstalledSnapshotCrashWriter() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("expected: <directory> <checkpoint>");
        }

        Path directory = Path.of(args[0]);
        String checkpoint = args[1];
        FileRaftStorage realWal = new FileRaftStorage(RaftStorageConfig.builder()
                .dataDir(directory)
                .syncEnabled(true)
                .build());
        realWal.open(directory).get(5, TimeUnit.SECONDS);

        CompactionGateStorage gatedWal = DURING_SHUTDOWN_AFTER_PREFIX_COMPACTION.equals(checkpoint)
                ? new CompactionGateStorage(realWal)
                : null;
        RaftStorage wal = gatedWal == null ? realWal : gatedWal;
        FileSnapshotStore snapshots = new FileSnapshotStore(reached -> {
            if (AFTER_INSTALLED_SNAPSHOT_PUBLICATION.equals(checkpoint)
                    && reached == FileSnapshotStore.PersistenceCheckpoint.AFTER_DIRECTORY_FORCE) {
                Runtime.getRuntime().halt(HALT_EXIT_CODE);
            }
        });
        snapshots.open(directory).get(5, TimeUnit.SECONDS);

        JavaRuntime runtime = JavaRuntime.create();
        RaftNode node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("follower-1")
                .clusterNodes(Set.of("follower-1", "leader-1"))
                .transport(new NoOpTransport())
                .stateMachine(new QraftStateStore())
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(wal, snapshots))
                .snapshotEnabled(false)
                .electionTimeout(60_000)
                .heartbeatInterval(60_000)
                .build();
        await(node.start());

        Future<InstallSnapshotResponse> installation = node.handleInstallSnapshot(
                InstallSnapshotRequest.newBuilder()
                        .setTerm(3)
                        .setLeaderId("leader-1")
                        .setLastIncludedIndex(3)
                        .setLastIncludedTerm(AFTER_DIVERGENT_SUFFIX_INSTALL.equals(checkpoint)
                                ? 99 : 2)
                        .setChunkIndex(0)
                        .setTotalChunks(1)
                        .setData(ByteString.copyFrom(snapshotBytes()))
                        .setDone(true)
                        .build());

        if (gatedWal != null) {
            gatedWal.awaitCompaction();
            Future<Void> stop = node.stop();
            InstallSnapshotResponse rejected = await(node.handleInstallSnapshot(
                    InstallSnapshotRequest.newBuilder()
                            .setTerm(3)
                            .setLeaderId("leader-1")
                            .setLastIncludedIndex(4)
                            .setLastIncludedTerm(2)
                            .setChunkIndex(0)
                            .setTotalChunks(1)
                            .setData(ByteString.copyFrom(snapshotBytes()))
                            .setDone(true)
                            .build()));
            if (rejected.getSuccess() || stop.isComplete() || installation.isComplete()) {
                throw new AssertionError("shutdown did not remain in drain behind installed snapshot");
            }
            Runtime.getRuntime().halt(HALT_EXIT_CODE);
        }

        InstallSnapshotResponse installed = await(installation);
        if (AFTER_DIVERGENT_SUFFIX_INSTALL.equals(checkpoint) && installed.getSuccess()) {
            Runtime.getRuntime().halt(HALT_EXIT_CODE);
        }
        throw new AssertionError("checkpoint was not reached: " + checkpoint);
    }

    private static byte[] snapshotBytes() {
        QraftStateStore state = new QraftStateStore();
        state.apply(put("key-1", "one"));
        state.apply(put("key-2", "two"));
        state.apply(put("key-3", "three"));
        state.setLastAppliedIndex(3);
        return state.takeSnapshot();
    }

    private static DistributedStateRaftCommand put(String key, String value) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, value));
    }

    private static <T> T await(Future<T> future) {
        return future.timeout(5, TimeUnit.SECONDS).toCompletionStage().toCompletableFuture().join();
    }

    private static final class CompactionGateStorage implements RaftStorage {
        private final FileRaftStorage delegate;
        private final CompletableFuture<Void> compactionReached = new CompletableFuture<>();
        private final CompletableFuture<Void> neverRelease = new CompletableFuture<>();

        private CompactionGateStorage(FileRaftStorage delegate) {
            this.delegate = delegate;
        }

        void awaitCompaction() throws Exception {
            compactionReached.get(5, TimeUnit.SECONDS);
        }

        @Override public CompletableFuture<Void> open(Path dataDir) { return delegate.open(dataDir); }
        @Override public CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor) { return delegate.updateMetadata(term, votedFor); }
        @Override public CompletableFuture<PersistentMeta> loadMetadata() { return delegate.loadMetadata(); }
        @Override public CompletableFuture<Void> appendEntries(List<LogEntryData> entries) { return delegate.appendEntries(entries); }
        @Override public CompletableFuture<Void> truncateSuffix(long fromIndex) { return delegate.truncateSuffix(fromIndex); }
        @Override public CompletableFuture<Void> truncatePrefix(long toIndex) {
            return delegate.truncatePrefix(toIndex).thenCompose(ignored -> {
                compactionReached.complete(null);
                return neverRelease;
            });
        }
        @Override public CompletableFuture<Void> sync() { return delegate.sync(); }
        @Override public CompletableFuture<List<LogEntryData>> replayLog() { return delegate.replayLog(); }
        @Override public void close() { delegate.close(); }
        @Override public CompletableFuture<Void> closeAsync() { return delegate.closeAsync(); }
    }

    private static final class NoOpTransport implements RaftTransport {
        @Override public void start(Consumer<RaftMessage> messageHandler) { }
        @Override public void stop() { }
        @Override public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return Future.failedFuture("unexpected vote request");
        }
        @Override public Future<AppendEntriesResponse> sendAppendEntries(
                String targetId, AppendEntriesRequest request) {
            return Future.failedFuture("unexpected append request");
        }
        @Override public Future<InstallSnapshotResponse> sendInstallSnapshot(
                String targetId, InstallSnapshotRequest request) {
            return Future.failedFuture("unexpected snapshot request");
        }
    }
}

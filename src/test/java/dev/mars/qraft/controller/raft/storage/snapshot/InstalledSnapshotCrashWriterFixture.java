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

package dev.mars.qraft.controller.raft.storage.snapshot;

import com.google.protobuf.ByteString;
import dev.mars.qraft.controller.raft.ManualRaftClusterFixture;
import dev.mars.qraft.controller.raft.PeerlessTransportFixture;
import dev.mars.qraft.controller.raft.RaftNode;
import dev.mars.qraft.controller.raft.RaftNodeMode;
import dev.mars.qraft.controller.raft.SnapshotEnvelope;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
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

import static dev.mars.qraft.controller.raft.RaftAwaitHelper.await;

/**
 * Test helper executable launched in a separate JVM by
 * {@link dev.mars.qraft.controller.raft.RaftNodeInstalledSnapshotRealRecoveryTest}
 * to prepare crashes during snapshot installation or while shutdown waits for installation.
 *
 * <p>Starts a Raft node backed by real storage, installs a snapshot, and halts the JVM
 * at the checkpoint selected by the calling test. Halting skips normal cleanup so the
 * test can reopen the storage and assert recovery from the persisted snapshot and WAL.
 *
 * <p>This class belongs in the test sources because it supplies a subprocess for the
 * test. Its {@code main} method prepares the crash; the calling test checks recovery.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-15
 * @version 1.0
 */
public final class InstalledSnapshotCrashWriterFixture {
    public static final int HALT_EXIT_CODE = 93;
    public static final String AFTER_INSTALLED_SNAPSHOT_PUBLICATION =
            "AFTER_INSTALLED_SNAPSHOT_PUBLICATION";
    public static final String DURING_SHUTDOWN_AFTER_PREFIX_COMPACTION =
            "DURING_SHUTDOWN_AFTER_PREFIX_COMPACTION";
    public static final String AFTER_DIVERGENT_SUFFIX_INSTALL =
            "AFTER_DIVERGENT_SUFFIX_INSTALL";
    /** Halts once a snapshot whose boundary term conflicts with the WAL is durable, before the WAL is trimmed. */
    public static final String AFTER_DIVERGENT_SNAPSHOT_PUBLICATION =
            "AFTER_DIVERGENT_SNAPSHOT_PUBLICATION";
    /** The follower's cluster; the WAL the test seeds begins with its bootstrap configuration. */
    public static final Set<String> MEMBERS = Set.of("follower-1", "leader-1");
    /** The installed snapshot's boundary: the seeded WAL holds key-3 there, in term 2. */
    private static final long SNAPSHOT_INDEX = 4;

    private InstalledSnapshotCrashWriterFixture() {
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
        realWal.open(directory).get(10, TimeUnit.SECONDS);

        CompactionGateStorageFixture gatedWal = DURING_SHUTDOWN_AFTER_PREFIX_COMPACTION.equals(checkpoint)
                ? new CompactionGateStorageFixture(realWal)
                : null;
        RaftStorage wal = gatedWal == null ? realWal : gatedWal;
        FileSnapshotStore snapshots = new FileSnapshotStore(reached -> {
            if ((AFTER_INSTALLED_SNAPSHOT_PUBLICATION.equals(checkpoint)
                    || AFTER_DIVERGENT_SNAPSHOT_PUBLICATION.equals(checkpoint))
                    && reached == FileSnapshotStore.PersistenceCheckpoint.AFTER_DIRECTORY_FORCE) {
                Runtime.getRuntime().halt(HALT_EXIT_CODE);
            }
        });
        snapshots.open(directory).get(10, TimeUnit.SECONDS);

        JavaRuntime runtime = JavaRuntime.create();
        RaftNode node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("follower-1")
                .serverId(ManualRaftClusterFixture.serverIdOf("follower-1"))
                .clusterNodes(MEMBERS)
                .transport(new PeerlessTransportFixture())
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
                        .setLastIncludedIndex(SNAPSHOT_INDEX)
                        .setLastIncludedTerm(AFTER_DIVERGENT_SUFFIX_INSTALL.equals(checkpoint)
                                || AFTER_DIVERGENT_SNAPSHOT_PUBLICATION.equals(checkpoint) ? 99 : 2)
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
                            .setLastIncludedIndex(SNAPSHOT_INDEX + 1)
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
        state.setLastAppliedIndex(SNAPSHOT_INDEX);
        return SnapshotEnvelope.wrap(ManualRaftClusterFixture.configurationOf(MEMBERS), state.takeSnapshot());
    }

    private static DistributedStateRaftCommand put(String key, String value) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, value));
    }

    /** Test storage fixture that holds prefix-compaction completion so the crash subprocess can halt during shutdown. */
    private static final class CompactionGateStorageFixture implements RaftStorage {
        private final FileRaftStorage delegate;
        private final CompletableFuture<Void> compactionReached = new CompletableFuture<>();
        private final CompletableFuture<Void> neverRelease = new CompletableFuture<>();

        private CompactionGateStorageFixture(FileRaftStorage delegate) {
            this.delegate = delegate;
        }

        void awaitCompaction() throws Exception {
            compactionReached.get(10, TimeUnit.SECONDS);
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
}

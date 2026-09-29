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

import com.google.protobuf.ByteString;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.controller.raft.storage.RaftStorageFactory;
import dev.mars.qraft.controller.raft.storage.snapshot.FileSnapshotStore;
import dev.mars.qraft.controller.raft.storage.snapshot.InstalledSnapshotCrashWriter;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.controller.testsupport.RemediationTest;
import dev.mars.qraft.controller.testsupport.RemediationTestExtension;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.raft.api.SnapshotStore;
import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.raftlog.storage.RaftStorage;
import dev.mars.raftlog.storage.RaftStorageConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.controller.raft.RaftAwait.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash-recovery tests for {@link RaftNode} after installed-snapshot checkpoints, using a separate
 * JVM crash writer with real {@link FileRaftStorage} and {@link FileSnapshotStore}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-14
 * @version 1.0
 */
@RemediationTest(phase = "6-installed-recovery", scenarioPrefix = "RAFT-INSTALLED-RECOVERY")
class RaftNodeInstalledSnapshotRealRecoveryTest {
    private static final ProtobufRaftCommandCodec CODEC = new ProtobufRaftCommandCodec();
    /** The follower's cluster, as the crash writer's follower and every restart of it know it. */
    private static final Set<String> MEMBERS = InstalledSnapshotCrashWriter.MEMBERS;

    @TempDir
    Path directory;

    private JavaRuntime runtime;
    private RaftNode node;

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) await(node.stop());
        if (runtime != null) {
            runtime.shutdown().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void restartDuringInstalledSnapshotPublicationUsesSnapshotAndUntrimmedWal() throws Exception {
        verifyRecovery(InstalledSnapshotCrashWriter.AFTER_INSTALLED_SNAPSHOT_PUBLICATION,
                List.of(1L, 2L, 3L, 4L, 5L), 2, true);
    }

    @Test
    void restartWhileShutdownDrainsCompactedInstallationUsesExactSuffix() throws Exception {
        verifyRecovery(InstalledSnapshotCrashWriter.DURING_SHUTDOWN_AFTER_PREFIX_COMPACTION,
                List.of(5L), 2, true);
    }

    @Test
    void divergentSuffixIsAbsentFromWalAndRecoveryAfterInstallation() throws Exception {
        verifyRecovery(InstalledSnapshotCrashWriter.AFTER_DIVERGENT_SUFFIX_INSTALL,
                List.of(), 99, false);
    }

    /**
     * A follower publishes an installed snapshot before it truncates the WAL suffix that conflicts
     * with it. The child process halts in that window; the follower restarts in its real cluster.
     * The WAL entry at the snapshot boundary, and everything after it, belong to the history the
     * snapshot replaced: recovery drops them, finishes the compaction, and a leader continues from
     * the boundary.
     */
    @Test
    void crashAfterPublishingAConflictingSnapshotDropsTheReplacedHistory() throws Exception {
        seedWal();
        RemediationTestExtension.logExpectedFailure(
                InstalledSnapshotCrashWriter.AFTER_DIVERGENT_SNAPSHOT_PUBLICATION, "ProcessHalt",
                "fixture halts after publishing a conflicting installed snapshot");
        ProcessResult crash = runCrashWriter(InstalledSnapshotCrashWriter.AFTER_DIVERGENT_SNAPSHOT_PUBLICATION);
        assertEquals(InstalledSnapshotCrashWriter.HALT_EXIT_CODE, crash.exitCode(), crash.output());
        assertDurableState(4, 99, List.of(1L, 2L, 3L, 4L, 5L));

        QraftStateStore state = new QraftStateStore();
        node = follower(state);
        await(node.start());

        RaftStatus recovered = await(node.status());
        assertEquals(4, recovered.snapshotLastIndex());
        assertEquals(4, recovered.lastLogIndex(), "no WAL entry of the replaced history is in the log");
        assertEquals(4, recovered.lastApplied());
        assertEquals("three", state.getMetadata("key-3"));
        assertNull(state.getMetadata("key-4"));

        AppendEntriesResponse appended = await(node.handleAppendEntriesRequest(appendAfterBoundary()));
        assertTrue(appended.getSuccess(), appended.toString());
        awaitApplied(5);
        assertEquals("after-recovery", state.getMetadata("key-5"));

        await(node.stop());
        node = null;
        assertDurableState(4, 99, List.of(5L));
    }

    /**
     * Recovery's own compaction removes the suffix, syncs, then removes the covered prefix. A crash
     * between the two leaves the conflicting boundary entry without a suffix; the next recovery
     * finds the same conflict and completes the compaction.
     */
    @Test
    void recoveryCompletesACompactionThatWasItselfInterrupted() throws Exception {
        try (FileRaftStorage wal = wal()) {
            wal.open(directory).get(10, TimeUnit.SECONDS);
            wal.updateMetadata(3, Optional.of("follower-1")).get(10, TimeUnit.SECONDS);
            wal.appendEntries(List.of(
                    ManualRaftCluster.bootstrapEntry(MEMBERS),
                    entry(2, 1, "key-1", "one"),
                    entry(3, 1, "key-2", "two"),
                    entry(4, 2, "key-3", "three"))).get(10, TimeUnit.SECONDS);
            wal.sync().get(10, TimeUnit.SECONDS);
        }
        saveConflictingSnapshot();

        QraftStateStore state = new QraftStateStore();
        node = follower(state);
        await(node.start());

        assertEquals(4, await(node.status()).lastLogIndex());
        assertEquals("leader-three", state.getMetadata("key-3"));
        await(node.stop());
        node = null;
        assertDurableState(4, 99, List.of());
    }

    /**
     * A storage failure while recovery removes the replaced history must stop the node from
     * starting, never let it serve a half-compacted log. The next start completes the compaction.
     */
    @Test
    void aFailedCompactionFailsStartupAndTheNextStartCompletesIt() throws Exception {
        seedWal();
        saveConflictingSnapshot();
        RaftStorageFactory.DurableStorage durable = await(
                RaftStorageFactory.createDurable(directory, true));
        runtime = JavaRuntime.create();
        node = RaftNode.builder()
                .runtime(runtime).nodeId("follower-1").serverId(ManualRaftCluster.serverIdOf("follower-1"))
                .clusterNodes(MEMBERS)
                .transport(new PeerlessTransport()).stateMachine(new QraftStateStore()).commandCodec(CODEC)
                .mode(RaftNodeMode.durable(new FailingSyncStorage(durable.wal()), durable.snapshots()))
                .snapshotEnabled(false).electionTimeout(60_000).heartbeatInterval(60_000)
                .build();
        RemediationTestExtension.logExpectedFailure(
                "RECOVERY_COMPACTION_SYNC", "IOException", "fixture fails the WAL sync during recovery");

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> node.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));

        assertEquals("Simulated recovery sync failure", rootMessage(failure), failure.toString());
        assertFalse(node.isRunning());
        await(node.stop());
        node = null;
        runtime.shutdown().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        runtime = null;
        assertDurableState(4, 99, List.of(1L, 2L, 3L, 4L));

        QraftStateStore state = new QraftStateStore();
        node = follower(state);
        await(node.start());

        assertEquals(4, await(node.status()).lastLogIndex());
        await(node.stop());
        node = null;
        assertDurableState(4, 99, List.of());
    }

    /**
     * A server added to a running cluster starts with an empty WAL and no configuration, so it does not
     * bootstrap: it waits for its leader, which here brings it up to date with a snapshot.
     */
    @Test
    void emptyWalFollowerCanInstallSnapshotBeyondItsLastIndex() throws Exception {
        RaftStorageFactory.DurableStorage durable = await(
                RaftStorageFactory.createDurable(directory, true));
        runtime = JavaRuntime.create();
        QraftStateStore state = new QraftStateStore();
        Set<String> members = Set.of("leader", "empty-follower", "peer");
        node = RaftNode.builder()
                .runtime(runtime).nodeId("empty-follower").serverId(ManualRaftCluster.serverIdOf("empty-follower"))
                .clusterNodes(members)
                .transport(new PeerlessTransport()).stateMachine(state).commandCodec(CODEC)
                .mode(RaftNodeMode.durable(durable.wal(), durable.snapshots()))
                .snapshotEnabled(false).electionTimeout(60_000).heartbeatInterval(60_000)
                .build();
        await(node.start());
        assertEquals(0, node.getLastLogIndex(), "a server with no configuration does not bootstrap");
        QraftStateStore source = new QraftStateStore();
        source.apply(new DistributedStateRaftCommand(
                DistributedStateCommand.put("installed", "snapshot")));

        InstallSnapshotResponse response = await(node.handleInstallSnapshot(
                InstallSnapshotRequest.newBuilder()
                        .setTerm(1).setLeaderId("leader")
                        .setLastIncludedIndex(6).setLastIncludedTerm(1)
                        .setChunkIndex(0).setTotalChunks(1).setDone(true)
                        .setData(ByteString.copyFrom(ManualRaftCluster.snapshotOf(members, source.takeSnapshot())))
                        .build()));

        assertTrue(response.getSuccess(), response.toString());
        assertEquals(6, node.getSnapshotLastIndex());
        assertEquals("snapshot", state.getMetadata("installed"));
        assertEquals(Optional.of(ManualRaftCluster.configurationOf(members)), node.getConfiguration(),
                "the installed snapshot brings the cluster configuration with it");
    }

    /**
     * Seeds the WAL, halts the crash writer at {@code checkpoint}, checks what is durable, and restarts the
     * follower in its cluster. The snapshot is at index 4; the WAL entry after it, key-4 at index 5, is kept
     * unless the snapshot's term conflicts with the WAL. A restarted follower of a two-member cluster treats
     * only its snapshot as committed, so a kept entry is proved recovered by being in the log, matching its
     * leader's previous entry, and being applied once the leader commits it.
     */
    private void verifyRecovery(String checkpoint, List<Long> expectedWalIndexes,
                                long expectedSnapshotTerm, boolean expectFourthEntry) throws Exception {
        seedWal();
        RemediationTestExtension.logExpectedFailure(
                checkpoint, "ProcessHalt", "fixture halts an active installed-snapshot transition");
        ProcessResult crash = runCrashWriter(checkpoint);
        assertEquals(InstalledSnapshotCrashWriter.HALT_EXIT_CODE, crash.exitCode(), crash.output());

        try (FileSnapshotStore snapshots = new FileSnapshotStore()) {
            snapshots.open(directory).get(10, TimeUnit.SECONDS);
            SnapshotStore.SnapshotData snapshot = snapshots.loadLatest()
                    .get(10, TimeUnit.SECONDS).orElseThrow();
            assertEquals(4, snapshot.lastIncludedIndex());
            assertEquals(expectedSnapshotTerm, snapshot.lastIncludedTerm());
        }

        try (FileRaftStorage wal = wal()) {
            wal.open(directory).get(10, TimeUnit.SECONDS);
            assertEquals(new RaftStorage.PersistentMeta(3, Optional.of("follower-1")),
                    wal.loadMetadata().get(10, TimeUnit.SECONDS));
            assertEquals(expectedWalIndexes, wal.replayLog().get(10, TimeUnit.SECONDS)
                    .stream().map(RaftStorage.LogEntryData::index).toList());
        }

        QraftStateStore state = new QraftStateStore();
        node = follower(state);
        await(node.start());

        assertTrue(node.isRunning());
        assertEquals(3, node.getCurrentTerm());
        assertEquals(4, node.getSnapshotLastIndex());
        assertEquals(4, node.getLastApplied(), "a restarted follower treats only its snapshot as committed");
        assertEquals(expectFourthEntry ? 5 : 4, node.getLastLogIndex());
        assertEquals("one", state.getMetadata("key-1"));
        assertEquals("two", state.getMetadata("key-2"));
        assertEquals("three", state.getMetadata("key-3"));
        assertNull(state.getMetadata("key-4"), "key-4 is not applied before its leader commits it");
        if (expectFourthEntry) {
            AppendEntriesResponse committed = await(node.handleAppendEntriesRequest(commitRecoveredEntry()));
            assertTrue(committed.getSuccess(), "the recovered entry matches its leader's: " + committed);
            awaitApplied(5);
            assertEquals("four", state.getMetadata("key-4"));
        }
        assertFalse(Files.exists(directory.resolve("snapshot.dat.tmp")));
    }

    private void saveConflictingSnapshot() throws Exception {
        QraftStateStore installed = new QraftStateStore();
        installed.apply(new DistributedStateRaftCommand(
                DistributedStateCommand.put("key-3", "leader-three")));
        try (FileSnapshotStore snapshots = new FileSnapshotStore()) {
            snapshots.open(directory).get(10, TimeUnit.SECONDS);
            snapshots.saveAtomically(new SnapshotStore.SnapshotData(
                            ManualRaftCluster.snapshotOf(MEMBERS, installed.takeSnapshot()), 4, 99))
                    .get(10, TimeUnit.SECONDS);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        return String.valueOf(root.getMessage());
    }

    /** Delegates to the real WAL except that {@code sync} fails. */
    private record FailingSyncStorage(RaftStorage delegate) implements RaftStorage {
        @Override public java.util.concurrent.CompletableFuture<Void> open(Path dataDir) { return delegate.open(dataDir); }
        @Override public java.util.concurrent.CompletableFuture<Void> updateMetadata(long term, Optional<String> votedFor) {
            return delegate.updateMetadata(term, votedFor);
        }
        @Override public java.util.concurrent.CompletableFuture<PersistentMeta> loadMetadata() { return delegate.loadMetadata(); }
        @Override public java.util.concurrent.CompletableFuture<Void> appendEntries(List<LogEntryData> entries) {
            return delegate.appendEntries(entries);
        }
        @Override public java.util.concurrent.CompletableFuture<Void> truncateSuffix(long fromIndex) {
            return delegate.truncateSuffix(fromIndex);
        }
        @Override public java.util.concurrent.CompletableFuture<Void> truncatePrefix(long toIndex) {
            return delegate.truncatePrefix(toIndex);
        }
        @Override public java.util.concurrent.CompletableFuture<Void> sync() {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new java.io.IOException("Simulated recovery sync failure"));
        }
        @Override public java.util.concurrent.CompletableFuture<List<LogEntryData>> replayLog() { return delegate.replayLog(); }
        @Override public void close() { delegate.close(); }
        @Override public java.util.concurrent.CompletableFuture<Void> closeAsync() { return delegate.closeAsync(); }
    }

    /** The follower the crash writer ran, restarted in the same two-member cluster. */
    private RaftNode follower(QraftStateStore state) {
        RaftStorageFactory.DurableStorage durable = await(
                RaftStorageFactory.createDurable(directory, true));
        runtime = JavaRuntime.create();
        return RaftNode.builder()
                .runtime(runtime)
                .nodeId("follower-1")
                .serverId(ManualRaftCluster.serverIdOf("follower-1"))
                .clusterNodes(MEMBERS)
                .transport(new PeerlessTransport())
                .stateMachine(state)
                .commandCodec(CODEC)
                .mode(RaftNodeMode.durable(durable.wal(), durable.snapshots()))
                .snapshotEnabled(false)
                .electionTimeout(60_000)
                .heartbeatInterval(60_000)
                .build();
    }

    /** A leader of the next term appending index 5 directly after the conflicting snapshot boundary. */
    private static AppendEntriesRequest appendAfterBoundary() {
        return AppendEntriesRequest.newBuilder()
                .setTerm(4).setLeaderId("leader-1")
                .setPrevLogIndex(4).setPrevLogTerm(99).setLeaderCommit(5)
                .addEntries(dev.mars.qraft.controller.raft.grpc.LogEntry.newBuilder()
                        .setTerm(4).setIndex(5)
                        .setData(ByteString.copyFrom(entry(5, 4, "key-5", "after-recovery").payload())))
                .build();
    }

    /** The term-3 leader's heartbeat whose previous entry is key-4 at index 5, committing through it. */
    private static AppendEntriesRequest commitRecoveredEntry() {
        return AppendEntriesRequest.newBuilder()
                .setTerm(3).setLeaderId("leader-1")
                .setPrevLogIndex(5).setPrevLogTerm(2).setLeaderCommit(5)
                .build();
    }

    private void awaitApplied(long index) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (await(node.status()).lastApplied() < index && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(index, await(node.status()).lastApplied());
    }

    private void assertDurableState(long snapshotIndex, long snapshotTerm, List<Long> walIndexes)
            throws Exception {
        try (FileSnapshotStore snapshots = new FileSnapshotStore()) {
            snapshots.open(directory).get(10, TimeUnit.SECONDS);
            SnapshotStore.SnapshotData snapshot = snapshots.loadLatest()
                    .get(10, TimeUnit.SECONDS).orElseThrow();
            assertEquals(snapshotIndex, snapshot.lastIncludedIndex());
            assertEquals(snapshotTerm, snapshot.lastIncludedTerm());
        }
        try (FileRaftStorage wal = wal()) {
            wal.open(directory).get(10, TimeUnit.SECONDS);
            assertEquals(walIndexes, wal.replayLog().get(10, TimeUnit.SECONDS).stream()
                    .map(RaftStorage.LogEntryData::index).toList());
        }
    }

    /** The follower's WAL: its bootstrap configuration, then key-1 to key-4 at indexes 2 to 5. */
    private void seedWal() throws Exception {
        try (FileRaftStorage wal = wal()) {
            wal.open(directory).get(10, TimeUnit.SECONDS);
            wal.updateMetadata(3, Optional.of("follower-1")).get(10, TimeUnit.SECONDS);
            wal.appendEntries(List.of(
                    ManualRaftCluster.bootstrapEntry(MEMBERS),
                    entry(2, 1, "key-1", "one"),
                    entry(3, 1, "key-2", "two"),
                    entry(4, 2, "key-3", "three"),
                    entry(5, 2, "key-4", "four"))).get(10, TimeUnit.SECONDS);
            wal.sync().get(10, TimeUnit.SECONDS);
        }
    }

    private ProcessResult runCrashWriter(String checkpoint) throws Exception {
        String executable = System.getProperty("os.name", "").startsWith("Windows")
                ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classPath = System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
        Path outputFile = directory.resolve("installed-snapshot-crash-writer.log");
        Process process = new ProcessBuilder(
                java.toString(), "-cp", classPath,
                InstalledSnapshotCrashWriter.class.getName(),
                directory.toString(), checkpoint)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
        boolean exited = process.waitFor(15, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("installed-snapshot crash writer did not reach "
                    + checkpoint + ":\n" + Files.readString(outputFile, StandardCharsets.UTF_8));
        }
        return new ProcessResult(process.exitValue(),
                Files.readString(outputFile, StandardCharsets.UTF_8));
    }

    private FileRaftStorage wal() {
        return new FileRaftStorage(RaftStorageConfig.builder()
                .dataDir(directory)
                .syncEnabled(true)
                .build());
    }

    private static RaftStorage.LogEntryData entry(
            long index, long term, String key, String value) {
        DistributedStateRaftCommand command =
                new DistributedStateRaftCommand(DistributedStateCommand.put(key, value));
        return new RaftStorage.LogEntryData(index, term, CODEC.serialize(command));
    }

    private record ProcessResult(int exitCode, String output) { }
}

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
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.raft.storage.RaftStorageFactory;
import dev.mars.qraft.controller.raft.storage.snapshot.FileSnapshotStore;
import dev.mars.qraft.controller.raft.storage.snapshot.InstalledSnapshotCrashWriter;
import dev.mars.qraft.controller.runtime.Future;
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
import java.util.function.Consumer;

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
                List.of(1L, 2L, 3L, 4L), 2, 4, true);
    }

    @Test
    void restartWhileShutdownDrainsCompactedInstallationUsesExactSuffix() throws Exception {
        verifyRecovery(InstalledSnapshotCrashWriter.DURING_SHUTDOWN_AFTER_PREFIX_COMPACTION,
                List.of(4L), 2, 4, true);
    }

    @Test
    void divergentSuffixIsAbsentFromWalAndRecoveryAfterInstallation() throws Exception {
        verifyRecovery(InstalledSnapshotCrashWriter.AFTER_DIVERGENT_SUFFIX_INSTALL,
                List.of(), 99, 3, false);
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
        assertDurableState(3, 99, List.of(1L, 2L, 3L, 4L));

        QraftStateStore state = new QraftStateStore();
        node = follower(state);
        await(node.start());

        RaftStatus recovered = await(node.status());
        assertEquals(3, recovered.snapshotLastIndex());
        assertEquals(3, recovered.lastLogIndex(), "no WAL entry of the replaced history is in the log");
        assertEquals(3, recovered.lastApplied());
        assertEquals("three", state.getMetadata("key-3"));
        assertNull(state.getMetadata("key-4"));

        AppendEntriesResponse appended = await(node.handleAppendEntriesRequest(appendAfterBoundary()));
        assertTrue(appended.getSuccess(), appended.toString());
        awaitApplied(4);
        assertEquals("after-recovery", state.getMetadata("key-5"));

        await(node.stop());
        node = null;
        assertDurableState(3, 99, List.of(4L));
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
                    entry(1, 1, "key-1", "one"),
                    entry(2, 1, "key-2", "two"),
                    entry(3, 2, "key-3", "three"))).get(10, TimeUnit.SECONDS);
            wal.sync().get(10, TimeUnit.SECONDS);
        }
        saveConflictingSnapshot();

        QraftStateStore state = new QraftStateStore();
        node = follower(state);
        await(node.start());

        assertEquals(3, await(node.status()).lastLogIndex());
        assertEquals("leader-three", state.getMetadata("key-3"));
        await(node.stop());
        node = null;
        assertDurableState(3, 99, List.of());
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
                .runtime(runtime).nodeId("follower-1").clusterNodes(Set.of("follower-1", "leader-1"))
                .transport(new NoOpTransport()).stateMachine(new QraftStateStore()).commandCodec(CODEC)
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
        assertDurableState(3, 99, List.of(1L, 2L, 3L));

        QraftStateStore state = new QraftStateStore();
        node = follower(state);
        await(node.start());

        assertEquals(3, await(node.status()).lastLogIndex());
        await(node.stop());
        node = null;
        assertDurableState(3, 99, List.of());
    }

    @Test
    void emptyWalFollowerCanInstallSnapshotBeyondItsLastIndex() throws Exception {
        RaftStorageFactory.DurableStorage durable = await(
                RaftStorageFactory.createDurable(directory, true));
        runtime = JavaRuntime.create();
        QraftStateStore state = new QraftStateStore();
        node = RaftNode.builder()
                .runtime(runtime).nodeId("empty-follower")
                .clusterNodes(Set.of("leader", "empty-follower", "peer"))
                .transport(new NoOpTransport()).stateMachine(state).commandCodec(CODEC)
                .mode(RaftNodeMode.durable(durable.wal(), durable.snapshots()))
                .snapshotEnabled(false).electionTimeout(60_000).heartbeatInterval(60_000)
                .build();
        await(node.start());
        QraftStateStore source = new QraftStateStore();
        source.apply(new DistributedStateRaftCommand(
                DistributedStateCommand.put("installed", "snapshot")));

        InstallSnapshotResponse response = await(node.handleInstallSnapshot(
                InstallSnapshotRequest.newBuilder()
                        .setTerm(1).setLeaderId("leader")
                        .setLastIncludedIndex(6).setLastIncludedTerm(1)
                        .setChunkIndex(0).setTotalChunks(1).setDone(true)
                        .setData(ByteString.copyFrom(source.takeSnapshot()))
                        .build()));

        assertTrue(response.getSuccess(), response.toString());
        assertEquals(6, node.getSnapshotLastIndex());
        assertEquals("snapshot", state.getMetadata("installed"));
    }

    private void verifyRecovery(String checkpoint, List<Long> expectedWalIndexes,
                                long expectedSnapshotTerm, long expectedLastApplied,
                                boolean expectFourthEntry) throws Exception {
        seedWal();
        RemediationTestExtension.logExpectedFailure(
                checkpoint, "ProcessHalt", "fixture halts an active installed-snapshot transition");
        ProcessResult crash = runCrashWriter(checkpoint);
        assertEquals(InstalledSnapshotCrashWriter.HALT_EXIT_CODE, crash.exitCode(), crash.output());

        try (FileSnapshotStore snapshots = new FileSnapshotStore()) {
            snapshots.open(directory).get(10, TimeUnit.SECONDS);
            SnapshotStore.SnapshotData snapshot = snapshots.loadLatest()
                    .get(10, TimeUnit.SECONDS).orElseThrow();
            assertEquals(3, snapshot.lastIncludedIndex());
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
        node = singleNode(state);
        await(node.start());

        assertTrue(node.isRunning());
        assertEquals(3, node.getCurrentTerm());
        assertEquals(3, node.getSnapshotLastIndex());
        assertEquals(expectedLastApplied, node.getLastApplied());
        assertEquals("one", state.getMetadata("key-1"));
        assertEquals("two", state.getMetadata("key-2"));
        assertEquals("three", state.getMetadata("key-3"));
        assertEquals(expectFourthEntry ? "four" : null, state.getMetadata("key-4"));
        assertFalse(Files.exists(directory.resolve("snapshot.dat.tmp")));
    }

    private void saveConflictingSnapshot() throws Exception {
        QraftStateStore installed = new QraftStateStore();
        installed.apply(new DistributedStateRaftCommand(
                DistributedStateCommand.put("key-3", "leader-three")));
        try (FileSnapshotStore snapshots = new FileSnapshotStore()) {
            snapshots.open(directory).get(10, TimeUnit.SECONDS);
            snapshots.saveAtomically(new SnapshotStore.SnapshotData(installed.takeSnapshot(), 3, 99))
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
                .clusterNodes(Set.of("follower-1", "leader-1"))
                .transport(new NoOpTransport())
                .stateMachine(state)
                .commandCodec(CODEC)
                .mode(RaftNodeMode.durable(durable.wal(), durable.snapshots()))
                .snapshotEnabled(false)
                .electionTimeout(60_000)
                .heartbeatInterval(60_000)
                .build();
    }

    /** A leader of the next term appending index 4 directly after the conflicting snapshot boundary. */
    private static AppendEntriesRequest appendAfterBoundary() {
        return AppendEntriesRequest.newBuilder()
                .setTerm(4).setLeaderId("leader-1")
                .setPrevLogIndex(3).setPrevLogTerm(99).setLeaderCommit(4)
                .addEntries(dev.mars.qraft.controller.raft.grpc.LogEntry.newBuilder()
                        .setTerm(4).setIndex(4)
                        .setData(ByteString.copyFrom(entry(4, 4, "key-5", "after-recovery").payload())))
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

    private RaftNode singleNode(QraftStateStore state) {
        RaftStorageFactory.DurableStorage durable = await(
                RaftStorageFactory.createDurable(directory, true));
        runtime = JavaRuntime.create();
        return RaftNode.builder()
                .runtime(runtime)
                .nodeId("follower-1")
                .clusterNodes(Set.of("follower-1"))
                .transport(new NoOpTransport())
                .stateMachine(state)
                .commandCodec(CODEC)
                .mode(RaftNodeMode.durable(durable.wal(), durable.snapshots()))
                .snapshotEnabled(false)
                .electionTimeout(60_000)
                .heartbeatInterval(60_000)
                .build();
    }

    private void seedWal() throws Exception {
        try (FileRaftStorage wal = wal()) {
            wal.open(directory).get(10, TimeUnit.SECONDS);
            wal.updateMetadata(3, Optional.of("follower-1")).get(10, TimeUnit.SECONDS);
            wal.appendEntries(List.of(
                    entry(1, 1, "key-1", "one"),
                    entry(2, 1, "key-2", "two"),
                    entry(3, 2, "key-3", "three"),
                    entry(4, 2, "key-4", "four"))).get(10, TimeUnit.SECONDS);
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

    private static <T> T await(Future<T> future) {
        return future.timeout(10, TimeUnit.SECONDS).toCompletionStage().toCompletableFuture().join();
    }

    private record ProcessResult(int exitCode, String output) { }

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

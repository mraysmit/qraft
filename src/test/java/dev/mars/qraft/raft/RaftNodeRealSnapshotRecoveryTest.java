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

import dev.mars.qraft.raft.storage.RaftStorageFactory;
import dev.mars.qraft.raft.storage.snapshot.FileSnapshotStore;
import dev.mars.qraft.raft.storage.snapshot.SnapshotStoreCrashWriterFixture;
import dev.mars.qraft.common.async.JavaRuntime;
import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import dev.mars.qraft.raft.api.SnapshotStore;
import dev.mars.qraft.testing.fault.IntentionalErrorFixture;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import dev.mars.qraft.testing.fault.SubprocessOutputAuditHelper;
import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.raftlog.storage.RaftStorage;
import dev.mars.raftlog.storage.RaftStorageConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.RaftAwaitHelper.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash-recovery tests for {@link RaftNode} at each snapshot publication and compaction checkpoint,
 * using a separate-JVM crash writer with real {@link FileRaftStorage} and {@link
 * FileSnapshotStore}.
 *
 * <p>The {@code firstSnapshot} tests cover a node that has no published snapshot yet, at every point where
 * a kill can land while it publishes its first. The node restarts by itself at every one. Between the
 * creation of the temporary file and its publication it restarts from its log, and keeps the unpublished
 * file aside.
 *
 * <p>The last tests cover the state that recovery must refuse: a log compacted further than the published
 * snapshot reaches, which means a published snapshot is missing or has been replaced by an older one.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-14
 * @version 1.0
 */
class RaftNodeRealSnapshotRecoveryTest {
    private static final ProtobufRaftCommandCodec CODEC = new ProtobufRaftCommandCodec();
    private static final Set<String> MEMBERS = Set.of("node-1");

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
    void restartBeforeTemporaryCreationKeepsPublishedSnapshot() throws Exception {
        verifyRecovery("BEFORE_TEMPORARY_CREATE", 3, List.of(1L, 2L, 3L, 4L, 5L));
    }

    @Test
    void restartAfterTemporaryWriteKeepsPublishedSnapshotAndDiscardsTemporary() throws Exception {
        verifyRecovery("AFTER_TEMPORARY_WRITE", 3, List.of(1L, 2L, 3L, 4L, 5L));
    }

    @Test
    void restartAfterTemporaryForceKeepsPublishedSnapshotAndDiscardsTemporary() throws Exception {
        verifyRecovery("AFTER_TEMPORARY_FORCE", 3, List.of(1L, 2L, 3L, 4L, 5L));
    }

    @Test
    void firstSnapshotCrashBeforeTemporaryCreationRestartsFromTheWholeLog() throws Exception {
        verifyFirstSnapshotRecovery("BEFORE_TEMPORARY_CREATE", 0, List.of(1L, 2L, 3L, 4L, 5L));
    }

    @Test
    void firstSnapshotCrashAfterTemporaryWriteRestartsFromTheLogAndKeepsTheFile() throws Exception {
        verifyInterruptedFirstSnapshotIsSetAside("AFTER_TEMPORARY_WRITE");
    }

    @Test
    void firstSnapshotCrashAfterTemporaryForceRestartsFromTheLogAndKeepsTheFile() throws Exception {
        verifyInterruptedFirstSnapshotIsSetAside("AFTER_TEMPORARY_FORCE");
    }

    @Test
    void firstSnapshotCrashAfterAtomicPublicationRestartsFromTheSnapshot() throws Exception {
        verifyFirstSnapshotRecovery("AFTER_ATOMIC_PUBLICATION", 4, List.of(1L, 2L, 3L, 4L, 5L));
    }

    @Test
    void firstSnapshotCrashAfterDirectoryForceRestartsFromTheSnapshot() throws Exception {
        verifyFirstSnapshotRecovery("AFTER_DIRECTORY_FORCE", 4, List.of(1L, 2L, 3L, 4L, 5L));
    }

    @Test
    void firstSnapshotCrashBeforeCompactionRestartsFromTheSnapshotWithTheUntrimmedLog() throws Exception {
        verifyFirstSnapshotRecovery(SnapshotStoreCrashWriterFixture.AFTER_PUBLICATION_BEFORE_COMPACTION,
                4, List.of(1L, 2L, 3L, 4L, 5L));
    }

    @Test
    void firstSnapshotCrashAfterCompactionRestartsFromTheSnapshotAndTheLogSuffix() throws Exception {
        verifyFirstSnapshotRecovery(SnapshotStoreCrashWriterFixture.AFTER_PREFIX_COMPACTION, 4, List.of(5L));
    }

    @Test
    void restartAfterAtomicPublicationUsesNewSnapshotWithUntrimmedWal() throws Exception {
        verifyRecovery("AFTER_ATOMIC_PUBLICATION", 4, List.of(1L, 2L, 3L, 4L, 5L));
    }

    @Test
    void restartAfterPublicationBeforeCompactionUsesNewSnapshotWithUntrimmedWal() throws Exception {
        verifyRecovery(SnapshotStoreCrashWriterFixture.AFTER_PUBLICATION_BEFORE_COMPACTION,
                4, List.of(1L, 2L, 3L, 4L, 5L));
    }

    @Test
    void restartAfterPrefixCompactionUsesNewSnapshotAndWalSuffix() throws Exception {
        verifyRecovery(SnapshotStoreCrashWriterFixture.AFTER_PREFIX_COMPACTION, 4, List.of(5L));
    }

    @Test
    void aCompactedLogWithNoSnapshotRefusesToRecoverWhileEntriesAreLeft() throws Exception {
        seedStorage();
        compactWalThrough(3);
        Files.delete(directory.resolve("snapshot.dat"));

        assertRecoveryRefused(3, 0);
    }

    @Test
    void aCompactedLogWithNoSnapshotRefusesToRecoverWhenNoEntriesAreLeft() throws Exception {
        seedStorage();
        compactWalThrough(5);
        Files.delete(directory.resolve("snapshot.dat"));

        assertRecoveryRefused(5, 0);
    }

    /**
     * A server of a larger cluster does not write a first entry by itself, so nothing else would stop it:
     * without the check it starts empty and keeps its vote, with everything it had acknowledged gone.
     */
    @Test
    void aServerOfALargerClusterWithACompactedLogAndNoSnapshotRefusesToRecover() throws Exception {
        seedStorage();
        compactWalThrough(5);
        Files.delete(directory.resolve("snapshot.dat"));

        assertRecoveryRefused(Set.of("node-1", "node-2"), 5, 0);
    }

    @Test
    void aCompactedLogWithOnlyAnUnpublishedSnapshotRefusesToRecoverAndKeepsTheFile() throws Exception {
        seedStorage();
        compactWalThrough(5);
        Files.move(directory.resolve("snapshot.dat"), directory.resolve("snapshot.dat.tmp"));
        byte[] unpublished = Files.readAllBytes(directory.resolve("snapshot.dat.tmp"));

        assertRecoveryRefused(5, 0);

        assertFalse(Files.exists(directory.resolve("snapshot.dat.tmp")));
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                unpublished, Files.readAllBytes(directory.resolve("snapshot.dat.interrupted")));
    }

    @Test
    void aSnapshotOlderThanTheLogsCompactionRefusesToRecoverWhileEntriesAreLeft() throws Exception {
        seedStorage();
        compactWalThrough(4);

        assertRecoveryRefused(4, 3);
    }

    @Test
    void aSnapshotOlderThanTheLogsCompactionRefusesToRecoverWhenNoEntriesAreLeft() throws Exception {
        seedStorage();
        compactWalThrough(5);

        assertRecoveryRefused(5, 3);
    }

    private void verifyRecovery(
            String checkpoint,
            long expectedSnapshotIndex,
            List<Long> expectedWalIndexes) throws Exception {
        SnapshotStore.SnapshotData replacement = seedStorage();
        ProcessResult crash = runCrashWriter(checkpoint, replacement);
        assertEquals(SnapshotStoreCrashWriterFixture.HALT_EXIT_CODE, crash.exitCode(), crash.output());

        try (FileSnapshotStore reopened = new FileSnapshotStore()) {
            reopened.open(directory).get(10, TimeUnit.SECONDS);
            SnapshotStore.SnapshotData latest = reopened.loadLatest()
                    .get(10, TimeUnit.SECONDS).orElseThrow();
            assertEquals(expectedSnapshotIndex, latest.lastIncludedIndex());
            assertEquals(expectedSnapshotIndex == 3 ? 1 : 2, latest.lastIncludedTerm());
        }
        assertFalse(Files.exists(directory.resolve("snapshot.dat.tmp")),
                "recovery must remove a non-authoritative temporary snapshot");
        assertFalse(Files.exists(directory.resolve("snapshot.dat.interrupted")),
                "a stale temporary file beside a published snapshot is removed, not kept");

        assertWalHolds(expectedWalIndexes);
        startNodeAndAssertWholeState(expectedSnapshotIndex);
    }

    /**
     * Seeds a WAL with no snapshot, halts the crash writer at {@code checkpoint} while it publishes the node's
     * first snapshot, and restarts the node on what the crash left.
     */
    private void verifyFirstSnapshotRecovery(
            String checkpoint,
            long expectedSnapshotIndex,
            List<Long> expectedWalIndexes) throws Exception {
        seedWal();
        ProcessResult crash = runCrashWriter(checkpoint, replacementSnapshot());
        assertEquals(SnapshotStoreCrashWriterFixture.HALT_EXIT_CODE, crash.exitCode(), crash.output());

        assertFalse(Files.exists(directory.resolve("snapshot.dat.tmp")),
                "no temporary file is left at " + checkpoint);
        assertEquals(expectedSnapshotIndex > 0, Files.exists(directory.resolve("snapshot.dat")),
                "whether the first snapshot is published at " + checkpoint);
        assertWalHolds(expectedWalIndexes);
        startNodeAndAssertWholeState(expectedSnapshotIndex);
    }

    /**
     * Halts the crash writer at {@code checkpoint}, after the first snapshot's temporary file exists and before
     * it is published. The log is compacted only after a snapshot is published, so it still holds every entry:
     * the node starts from it alone, with its whole state, and keeps the unpublished file aside, unchanged.
     */
    private void verifyInterruptedFirstSnapshotIsSetAside(String checkpoint) throws Exception {
        seedWal();
        ProcessResult crash = runCrashWriter(checkpoint, replacementSnapshot());
        assertEquals(SnapshotStoreCrashWriterFixture.HALT_EXIT_CODE, crash.exitCode(), crash.output());

        Path temporary = directory.resolve("snapshot.dat.tmp");
        assertTrue(Files.exists(temporary), "the crash at " + checkpoint + " leaves the temporary file");
        assertFalse(Files.exists(directory.resolve("snapshot.dat")));
        byte[] unpublished = Files.readAllBytes(temporary);
        assertWalHolds(List.of(1L, 2L, 3L, 4L, 5L));

        startNodeAndAssertWholeState(0);

        assertFalse(Files.exists(temporary), "start-up sets the unpublished snapshot aside");
        assertFalse(Files.exists(directory.resolve("snapshot.dat")), "and never publishes it");
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                unpublished, Files.readAllBytes(directory.resolve("snapshot.dat.interrupted")));
    }

    /** Compacts the seeded WAL through {@code index}, as a snapshot at that index would have. */
    private void compactWalThrough(long index) throws Exception {
        try (FileRaftStorage wal = wal()) {
            wal.open(directory).get(10, TimeUnit.SECONDS);
            wal.truncatePrefix(index).get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Starts the node on the storage directory and checks that recovery refuses, naming how far the log is
     * compacted and how far the published snapshot reaches. The node is then fenced, not running.
     */
    private void assertRecoveryRefused(long compactedThrough, long snapshotIndex) throws Exception {
        assertRecoveryRefused(MEMBERS, compactedThrough, snapshotIndex);
    }

    private void assertRecoveryRefused(Set<String> members, long compactedThrough, long snapshotIndex)
            throws Exception {
        IntentionalErrorsHelper.expect(IntentionalErrorFixture.RAFT_RECOVERY_SNAPSHOT_MISSING);
        node = durableNode(new QraftStateStore(), members);

        CompletionException failure = assertThrows(CompletionException.class, () -> await(node.start()));

        assertTrue(messageChain(failure).contains("its log is compacted through index " + compactedThrough
                + " but its published snapshot reaches only index " + snapshotIndex), messageChain(failure));
        assertFalse(node.isRunning());
        assertTrue(node.isFenced(), "a node that refused to recover reports itself fenced");
    }

    private void assertWalHolds(List<Long> expectedWalIndexes) throws Exception {
        try (FileRaftStorage reopened = wal()) {
            reopened.open(directory).get(10, TimeUnit.SECONDS);
            assertEquals(new RaftStorage.PersistentMeta(3, Optional.of("node-1")),
                    reopened.loadMetadata().get(10, TimeUnit.SECONDS));
            List<RaftStorage.LogEntryData> entries = reopened.replayLog().get(10, TimeUnit.SECONDS);
            assertEquals(expectedWalIndexes,
                    entries.stream().map(RaftStorage.LogEntryData::index).toList());
        }
    }

    /** Starts the node on the storage directory and checks that it holds everything the log ever held. */
    private void startNodeAndAssertWholeState(long expectedSnapshotIndex) throws Exception {
        QraftStateStore state = new QraftStateStore();
        node = durableNode(state, MEMBERS);
        await(node.start());

        assertTrue(node.isRunning());
        assertEquals(3, node.getCurrentTerm());
        assertEquals(expectedSnapshotIndex, node.getSnapshotLastIndex());
        assertEquals(5, node.getLastApplied());
        assertEquals("one", state.getMetadata("key-1"));
        assertEquals("two", state.getMetadata("key-2"));
        assertEquals("three", state.getMetadata("key-3"));
        assertEquals("four", state.getMetadata("key-4"));
    }

    /** The node on the storage directory, opened as the server opens it, and not yet started. */
    private RaftNode durableNode(QraftStateStore state, Set<String> members) {
        RaftStorageFactory.DurableStorage durable = await(
                RaftStorageFactory.createDurable(directory, true));
        runtime = JavaRuntime.create();
        return RaftNode.builder()
                .runtime(runtime)
                .nodeId("node-1")
                .serverId(ManualRaftClusterFixture.serverIdOf("node-1"))
                .clusterNodes(members)
                .transport(new PeerlessTransportFixture())
                .stateMachine(state)
                .commandCodec(CODEC)
                .mode(RaftNodeMode.durable(durable.wal(), durable.snapshots()))
                .snapshotEnabled(false)
                .electionTimeout(10_000)
                .heartbeatInterval(10_000)
                .build();
    }

    private SnapshotStore.SnapshotData seedStorage() throws Exception {
        QraftStateStore snapshotState = new QraftStateStore();
        snapshotState.apply(put("key-1", "one"));
        snapshotState.apply(put("key-2", "two"));
        SnapshotStore.SnapshotData published = new SnapshotStore.SnapshotData(
                ManualRaftClusterFixture.snapshotOf(MEMBERS, snapshotState.takeSnapshot()), 3, 1);
        try (FileSnapshotStore snapshots = new FileSnapshotStore()) {
            snapshots.open(directory).get(10, TimeUnit.SECONDS);
            snapshots.saveAtomically(published).get(10, TimeUnit.SECONDS);
        }

        SnapshotStore.SnapshotData replacement = replacementSnapshot();
        seedWal();
        return replacement;
    }

    private void seedWal() throws Exception {
        try (FileRaftStorage wal = wal()) {
            wal.open(directory).get(10, TimeUnit.SECONDS);
            wal.updateMetadata(3, Optional.of("node-1")).get(10, TimeUnit.SECONDS);
            wal.appendEntries(List.of(
                    ManualRaftClusterFixture.bootstrapEntry(MEMBERS),
                    entry(2, 1, "key-1", "one"),
                    entry(3, 1, "key-2", "two"),
                    entry(4, 2, "key-3", "three"),
                    entry(5, 2, "key-4", "four"))).get(10, TimeUnit.SECONDS);
            wal.sync().get(10, TimeUnit.SECONDS);
        }
    }

    private static SnapshotStore.SnapshotData replacementSnapshot() {
        QraftStateStore state = new QraftStateStore();
        state.apply(put("key-1", "one"));
        state.apply(put("key-2", "two"));
        state.apply(put("key-3", "three"));
        return new SnapshotStore.SnapshotData(
                ManualRaftClusterFixture.snapshotOf(MEMBERS, state.takeSnapshot()), 4, 2);
    }

    private ProcessResult runCrashWriter(
            String checkpoint, SnapshotStore.SnapshotData replacement) throws Exception {
        String executable = System.getProperty("os.name", "").startsWith("Windows")
                ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classPath = System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
        Path outputFile = directory.resolve("snapshot-crash-writer.log");
        Process process = new ProcessBuilder(
                java.toString(),
                "-cp", classPath,
                SnapshotStoreCrashWriterFixture.class.getName(),
                directory.toString(),
                checkpoint,
                Base64.getEncoder().encodeToString(replacement.data()),
                Integer.toString(replacement.formatVersion()),
                Long.toString(replacement.lastIncludedIndex()),
                Long.toString(replacement.lastIncludedTerm()))
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
        boolean exited = process.waitFor(10, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("snapshot crash writer did not reach " + checkpoint + ":\n"
                    + Files.readString(outputFile, StandardCharsets.UTF_8));
        }
        String output = Files.readString(outputFile, StandardCharsets.UTF_8);
        SubprocessOutputAuditHelper.requireNoErrors("snapshot-crash-writer", output);
        return new ProcessResult(process.exitValue(), output);
    }

    private FileRaftStorage wal() {
        return new FileRaftStorage(RaftStorageConfig.builder()
                .dataDir(directory)
                .syncEnabled(true)
                .build());
    }

    private static DistributedStateRaftCommand put(String key, String value) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, value));
    }

    private static RaftStorage.LogEntryData entry(
            long index, long term, String key, String value) {
        return new RaftStorage.LogEntryData(index, term, CODEC.serialize(put(key, value)));
    }

    private static String messageChain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
    }

    private record ProcessResult(int exitCode, String output) {
    }
}

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
    void restartWithUnpublishedFirstSnapshotFencesAndPreservesTemporary() throws Exception {
        seedWal();
        SnapshotStore.SnapshotData replacement = replacementSnapshot();
        String checkpoint = "AFTER_TEMPORARY_FORCE";
        ProcessResult crash = runCrashWriter(checkpoint, replacement);
        assertEquals(SnapshotStoreCrashWriterFixture.HALT_EXIT_CODE, crash.exitCode(), crash.output());

        assertTrue(Files.exists(directory.resolve("snapshot.dat.tmp")));
        assertFalse(Files.exists(directory.resolve("snapshot.dat")));
        byte[] evidence = Files.readAllBytes(directory.resolve("snapshot.dat.tmp"));
        CompletionException failure = assertThrows(CompletionException.class,
                () -> await(RaftStorageFactory.createDurable(directory, true)));
        assertTrue(failure.getCause().getMessage().contains("unpublished first snapshot"));
        assertTrue(Files.exists(directory.resolve("snapshot.dat.tmp")));
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                evidence, Files.readAllBytes(directory.resolve("snapshot.dat.tmp")));
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

        try (FileRaftStorage reopened = wal()) {
            reopened.open(directory).get(10, TimeUnit.SECONDS);
            assertEquals(new RaftStorage.PersistentMeta(3, Optional.of("node-1")),
                    reopened.loadMetadata().get(10, TimeUnit.SECONDS));
            List<RaftStorage.LogEntryData> entries = reopened.replayLog().get(10, TimeUnit.SECONDS);
            assertEquals(expectedWalIndexes,
                    entries.stream().map(RaftStorage.LogEntryData::index).toList());
        }

        RaftStorageFactory.DurableStorage durable = await(
                RaftStorageFactory.createDurable(directory, true));
        runtime = JavaRuntime.create();
        QraftStateStore state = new QraftStateStore();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("node-1")
                .serverId(ManualRaftClusterFixture.serverIdOf("node-1"))
                .clusterNodes(MEMBERS)
                .transport(new PeerlessTransportFixture())
                .stateMachine(state)
                .commandCodec(CODEC)
                .mode(RaftNodeMode.durable(durable.wal(), durable.snapshots()))
                .snapshotEnabled(false)
                .electionTimeout(10_000)
                .heartbeatInterval(10_000)
                .build();
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

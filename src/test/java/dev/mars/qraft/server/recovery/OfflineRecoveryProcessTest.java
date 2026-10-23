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

import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.ConfigurationCommand;
import dev.mars.qraft.raft.InMemoryTransportSimulatorFixture;
import dev.mars.qraft.raft.ManualRaftClusterFixture;
import dev.mars.qraft.raft.RaftConfiguration;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.raft.storage.DirectoryLockProcessFixture;
import dev.mars.qraft.raft.storage.RaftStorageFactory;
import dev.mars.qraft.raft.storage.ServerIdentity;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.testing.fault.SubprocessOutputAuditHelper;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static dev.mars.qraft.raft.ManualRaftClusterFixture.serverIdOf;
import static dev.mars.qraft.server.recovery.SurvivorDirectoryFixture.CODEC;
import static dev.mars.qraft.server.recovery.SurvivorDirectoryFixture.MEMBERS;
import static dev.mars.qraft.server.recovery.SurvivorDirectoryFixture.digest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link OfflineRecovery} across separate JVMs on real storage (membership Step 7 exit gate):
 * <ul>
 *   <li>a JVM halted at each durable step before publication leaves no output and an unchanged source, and the
 *       next run publishes a valid seed;</li>
 *   <li>a JVM halted after publishing, before the claim records it, leaves the output, and the next run records
 *       the publication without changing it;</li>
 *   <li>a run halted after completing is refused on the next run, with nothing changed;</li>
 *   <li>a source whose lock another process holds is refused by {@code recover} and {@code inspect};</li>
 *   <li>a node and its server identity, started on the source as ordinary startup does, act on neither the claim
 *       nor the staging directory a crash left behind.</li>
 * </ul>
 * Each halted JVM's complete output is audited for errors.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-23
 * @version 1.0
 */
class OfflineRecoveryProcessTest {
    private static final String ADDRESS = "host-a:9090";
    private static final long PROCESS_SECONDS = 60;

    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(value = RecoveryCheckpoint.class, names = {"CLAIM_CREATED", "SOURCE_COPIED", "EXAMINED",
            "RECOVERY_ENTRY_SYNCED", "METADATA_UPDATED", "SERVER_ID_REPLACED", "CLAIM_PREPARED"})
    void aCrashBeforePublicationLeavesNoOutputAndTheNextRunCompletes(RecoveryCheckpoint checkpoint) throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));
        Map<String, String> before = digest(source);

        crashAt(source, checkpoint);

        assertFalse(Files.exists(output()), "nothing is published before the rename");
        assertEquals(before, digest(source), "a crash never changes the source");
        assertTrue(RecoveryClaim.read(source).isPresent(), "the claim made before copying survives the crash");

        RecoveryResult result = OfflineRecovery.recover(plan(source));

        assertEquals(RecoveryResult.Outcome.RECOVERED, result.outcome());
        assertPublished(result);
        assertEquals(before, digest(source));
    }

    @Test
    void aCrashAfterPublishingIsRecordedByTheNextRunWithoutChange() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));

        crashAt(source, RecoveryCheckpoint.PUBLISHED);

        RecoveryClaim prepared = RecoveryClaim.read(source).orElseThrow();
        assertFalse(prepared.published());
        String published = prepared.newServerId().orElseThrow();
        assertEquals(published, Files.readString(output().resolve("server-id")));
        Map<String, String> output = digest(output());

        RecoveryResult result = OfflineRecovery.recover(plan(source));

        assertEquals(new RecoveryResult(published, 4, 2, output(), RecoveryResult.Outcome.PUBLICATION_RECORDED),
                result);
        assertEquals(output, digest(output()), "recording the publication leaves the output as it was");
        assertTrue(RecoveryClaim.read(source).orElseThrow().published());
        assertPublished(result);
    }

    @Test
    void aCompletedRunHaltedBeforeReturningIsRefusedOnTheNextRun() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));
        crashAt(source, RecoveryCheckpoint.CLAIM_PUBLISHED);
        Map<String, String> output = digest(output());
        String claim = Files.readString(RecoveryClaim.fileOf(source));

        RecoveryRefusedException refused = assertThrows(RecoveryRefusedException.class,
                () -> OfflineRecovery.recover(plan(source)));

        assertTrue(refused.getMessage().contains("already recovered"), refused.getMessage());
        assertEquals(output, digest(output()));
        assertEquals(claim, Files.readString(RecoveryClaim.fileOf(source)));
    }

    @Test
    void aSourceLockedByAnotherProcessIsRefused(TestInfo test) throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));
        Path ownerLog = directory.resolve("lock-owner.log");
        Process owner = new ProcessBuilder(java(), "-cp", classPath(), DirectoryLockProcessFixture.class.getName(),
                source.toString(), "owner",
                getClass().getSimpleName() + "#" + test.getTestMethod().orElseThrow().getName())
                .redirectErrorStream(true).redirectOutput(ownerLog.toFile()).start();
        try {
            awaitOutput(owner, ownerLog, "LOCKED ");
            Map<String, String> before = digest(source);

            RecoveryRefusedException recovering = assertThrows(RecoveryRefusedException.class,
                    () -> OfflineRecovery.recover(plan(source)));
            RecoveryRefusedException inspecting = assertThrows(RecoveryRefusedException.class,
                    () -> OfflineRecovery.inspect(source));

            assertTrue(recovering.getMessage().contains("is locked by another process"), recovering.getMessage());
            assertTrue(inspecting.getMessage().contains("is locked by another process"), inspecting.getMessage());
            assertFalse(Files.exists(RecoveryClaim.fileOf(source)), "a locked source is never claimed");
            assertFalse(Files.exists(output()));
            assertEquals(before, digest(source));
        } finally {
            if (owner.isAlive()) {
                owner.getOutputStream().write(System.lineSeparator().getBytes(StandardCharsets.UTF_8));
                owner.getOutputStream().flush();
            }
            boolean exited = owner.waitFor(PROCESS_SECONDS, TimeUnit.SECONDS);
            if (!exited) owner.destroyForcibly();
            assertTrue(exited, "the lock owner closes once asked");
        }
        assertEquals(0, owner.exitValue(), Files.readString(ownerLog, StandardCharsets.UTF_8));
        SubprocessOutputAuditHelper.requireNoErrors("lock-owner", Files.readString(ownerLog, StandardCharsets.UTF_8));
    }

    @Test
    void ordinaryStartupNeverActsOnAClaimOrAStagingDirectory() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));
        crashAt(source, RecoveryCheckpoint.SERVER_ID_REPLACED);
        Path staging = output().resolveSibling("." + output().getFileName() + ".qraft-recovering");
        assertTrue(Files.isDirectory(staging), "the crash left the staging directory");
        Map<String, String> stagingBefore = digest(staging);
        String claim = Files.readString(RecoveryClaim.fileOf(source));

        String identity = ServerIdentity.loadOrCreate(source);
        InMemoryTransportSimulatorFixture.clearAllTransports();
        RaftExecutorsFixture runtime = RaftExecutorsFixture.create();
        try (ManualRaftClusterFixture cluster = new ManualRaftClusterFixture(runtime)) {
            RaftStorageFactory.DurableStorage storage = await(RaftStorageFactory.createDurable(source, true));
            RaftNode node = cluster.add(cluster.unconfiguredBuilder("a", MEMBERS,
                            new InMemoryTransportSimulatorFixture("a"), new QraftStateStore(),
                            RaftNodeMode.durable(storage.wal(), storage.snapshots()))
                    .serverId(identity).snapshotEnabled(false));

            await(node.start());

            assertEquals(serverIdOf("a"), identity, "the source keeps its own server ID");
            assertEquals(Optional.of(ManualRaftClusterFixture.configurationOf(MEMBERS)), node.getConfiguration(),
                    "the source is still a member of its old configuration, not the recovered one");
            assertEquals(3, node.getLastLogIndex(), "no recovery entry reaches the source");
        } finally {
            runtime.close().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
        }
        assertEquals(stagingBefore, digest(staging), "startup leaves the staging directory as it was");
        assertEquals(claim, Files.readString(RecoveryClaim.fileOf(source)), "startup leaves the claim as it was");
        assertFalse(Files.exists(output()));
    }

    /** Runs a recovery of {@code source} in a separate JVM that halts at {@code checkpoint}, and audits it. */
    private void crashAt(Path source, RecoveryCheckpoint checkpoint) throws Exception {
        Path log = directory.resolve("crash-" + checkpoint + ".log");
        Process process = new ProcessBuilder(java(), "-cp", classPath(), RecoveryCrashWriterFixture.class.getName(),
                source.toString(), serverIdOf("a"), output().toString(), "a", ADDRESS, checkpoint.name())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean exited = process.waitFor(PROCESS_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("the recovery did not reach " + checkpoint + ":\n"
                    + Files.readString(log, StandardCharsets.UTF_8));
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);
        assertEquals(RecoveryCrashWriterFixture.HALT_EXIT_CODE, process.exitValue(), output);
        SubprocessOutputAuditHelper.requireNoErrors("recovery-crash-writer", output);
    }

    /** The output is a valid seed for {@code result}, the claim records it, and no staging directory remains. */
    private void assertPublished(RecoveryResult result) throws Exception {
        assertEquals(result.newServerId(), Files.readString(output().resolve("server-id")));
        List<LogEntryData> log = replay(output());
        assertEquals(4, log.size());
        LogEntryData recovery = log.getLast();
        assertEquals(result.recoveryIndex(), recovery.index());
        assertEquals(result.recoveryTerm(), recovery.term());
        assertEquals(new ConfigurationCommand(new RaftConfiguration(List.of(
                        new RaftConfiguration.Server(result.newServerId(), "a", ADDRESS, true)))),
                CODEC.deserialize(recovery.payload()));
        RecoveryClaim claim = RecoveryClaim.read(directory.resolve("survivors").resolve("a")).orElseThrow();
        assertTrue(claim.published());
        assertEquals(Optional.of(result.newServerId()), claim.newServerId());
        try (Stream<Path> entries = Files.list(output().getParent())) {
            assertEquals(List.of(output()), entries.toList(), "no staging directory remains");
        }
    }

    private Path output() {
        return directory.resolve("recovered").resolve("a");
    }

    private RecoveryPlan plan(Path source) {
        return new RecoveryPlan(source, serverIdOf("a"), output(), "a", ADDRESS);
    }

    private static List<LogEntryData> replay(Path directory) throws Exception {
        RaftStorageFactory.DurableStorage storage = await(RaftStorageFactory.createDurable(directory, true));
        try {
            return await(storage.wal().replayLog());
        } finally {
            await(storage.snapshots().closeAsync());
            await(storage.wal().closeAsync());
        }
    }

    /** Waits until {@code process} has written {@code marker} to {@code log}; the bound only diagnoses a hang. */
    private static void awaitOutput(Process process, Path log, String marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_SECONDS);
        while (System.nanoTime() < deadline) {
            if (Files.exists(log) && Files.readString(log, StandardCharsets.UTF_8).contains(marker)) return;
            if (!process.isAlive()) break;
            Thread.sleep(20);
        }
        throw new AssertionError("the process did not print " + marker + ":\n"
                + (Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : ""));
    }

    private static String java() {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static String classPath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }
}

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
import dev.mars.qraft.raft.RaftConfiguration.Server;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.raft.storage.RaftStorageFactory;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import dev.mars.raftlog.storage.RaftStorage;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static dev.mars.qraft.raft.ManualRaftClusterFixture.serverIdOf;
import static dev.mars.qraft.server.recovery.SurvivorDirectoryFixture.CODEC;
import static dev.mars.qraft.server.recovery.SurvivorDirectoryFixture.MEMBERS;
import static dev.mars.qraft.server.recovery.SurvivorDirectoryFixture.digest;
import static dev.mars.qraft.server.recovery.SurvivorDirectoryFixture.put;
import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_RECOVERY_AMBIGUOUS_CORRUPTION;
import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_STATE_WITHOUT_CONFIGURATION;
import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.WAL_AMBIGUOUS_CORRUPTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link OfflineRecovery} on real storage in this JVM (membership Step 7 contract):
 * <ul>
 *   <li>recovery publishes the seed's directory: the source's history, then one configuration entry making the
 *       seed the sole voter under a new server ID, in a term above any the source holds; the source is unchanged,
 *       a claim lies beside it, and no staging directory remains;</li>
 *   <li>a real node started on that directory commits its whole log as sole voter;</li>
 *   <li>{@code inspect} reports a stopped directory, including why ordinary startup would refuse it, without
 *       changing it;</li>
 *   <li>each refusal the contract lists leaves the source unchanged and publishes nothing.</li>
 * </ul>
 * Separate-JVM locking and the crash boundaries are tested elsewhere.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-22
 * @version 1.0
 */
class OfflineRecoveryTest {
    private static final String ADDRESS = "host-a:9090";

    @TempDir
    Path directory;

    @Test
    void recoveryPublishesTheSeedUnderANewServerIdAndLeavesTheSourceUnchanged() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));
        // Read through RaftLog before the digest: opening storage is not part of what is compared.
        List<LogEntryData> sourceLog = replay(source);
        Map<String, String> before = digest(source);
        Path output = output();

        RecoveryResult result = OfflineRecovery.recover(plan(source, output));

        assertEquals(RecoveryResult.Outcome.RECOVERED, result.outcome());
        assertEquals(output, result.output());
        assertEquals(result.newServerId(), UUID.fromString(result.newServerId()).toString(), "a canonical UUID");
        assertNotEquals(serverIdOf("a"), result.newServerId());
        assertEquals(result.newServerId(), Files.readString(output.resolve("server-id")));
        assertEquals(4, result.recoveryIndex(), "the entry follows the source's last");
        assertEquals(2, result.recoveryTerm(), "one above the source's term and last log term");

        List<LogEntryData> recoveredLog = replay(output);
        assertEquals(sourceLog.size() + 1, recoveredLog.size());
        for (int i = 0; i < sourceLog.size(); i++) assertEntryEquals(sourceLog.get(i), recoveredLog.get(i));
        LogEntryData recovery = recoveredLog.getLast();
        assertEquals(4, recovery.index());
        assertEquals(2, recovery.term());
        assertEquals(new ConfigurationCommand(new RaftConfiguration(List.of(
                        new Server(result.newServerId(), "a", ADDRESS, true)))),
                CODEC.deserialize(recovery.payload()));
        assertEquals(new RaftStorage.PersistentMeta(2, Optional.empty()), metadata(output));

        assertEquals(before, digest(source), "the source is never changed");
        assertTrue(Files.isRegularFile(claim(source)), "a claim lies beside the source");
        assertEquals(List.of(output), siblings(output), "no staging directory remains");
    }

    @Test
    void theRecoveredDirectoryStartsAsTheSoleVoterAndCommitsItsWholeLog() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));
        Path output = output();
        RecoveryResult result = OfflineRecovery.recover(plan(source, output));
        InMemoryTransportSimulatorFixture.clearAllTransports();
        RaftExecutorsFixture runtime = RaftExecutorsFixture.create();
        try (ManualRaftClusterFixture cluster = new ManualRaftClusterFixture(runtime)) {
            QraftStateStore store = new QraftStateStore();
            RaftStorageFactory.DurableStorage storage = await(RaftStorageFactory.createDurable(output, true));
            RaftNode seed = cluster.add(cluster.unconfiguredBuilder("a", MEMBERS,
                            new InMemoryTransportSimulatorFixture("a"), store,
                            RaftNodeMode.durable(storage.wal(), storage.snapshots()))
                    .serverId(result.newServerId()).snapshotEnabled(false));

            await(seed.start());

            assertEquals(result.recoveryIndex(), seed.getCommitIndex());
            assertEquals("v1", store.getMetadata("k1"));
            assertEquals("v2", store.getMetadata("k2"));
            cluster.elect(seed);
            assertEquals(result.recoveryTerm() + 1, seed.getCurrentTerm());
        } finally {
            runtime.close().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
        }
    }

    @Test
    void inspectReportsAStoppedDirectoryWithoutChangingIt() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("a"));
        Map<String, String> before = digest(source);

        DirectoryReport report = OfflineRecovery.inspect(source);

        assertEquals(new DirectoryReport(serverIdOf("a"), 1, Optional.of("a"), 3, 1, 0, 0, 0,
                Optional.of(ManualRaftClusterFixture.configurationOf(MEMBERS)),
                DirectoryReport.ConfigurationSource.WAL, Optional.empty()), report);
        assertEquals(before, digest(source));
    }

    @Test
    void inspectReportsWhyOrdinaryStartupWouldRefuseADirectory() throws Exception {
        // inspect runs the ordinary recovery checks on a copy, and those report this refusal.
        IntentionalErrorsHelper.expect(RAFT_STATE_WITHOUT_CONFIGURATION);
        Path source = directory.resolve("a");
        SurvivorDirectoryFixture.write(source, serverIdOf("a"), 1, Optional.empty(), List.of(put(1, 1, "k", "v")));
        Map<String, String> before = digest(source);

        DirectoryReport report = OfflineRecovery.inspect(source);

        assertEquals(Optional.empty(), report.configuration());
        assertEquals(DirectoryReport.ConfigurationSource.NONE, report.configurationSource());
        assertTrue(report.startupRefusal().orElseThrow().contains("no cluster configuration"),
                report.startupRefusal().orElseThrow());
        assertEquals(before, digest(source));
    }

    @Test
    void aSourceServerIdThatDoesNotMatchIsRefused() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));
        assertRefusedBeforeClaim(source, new RecoveryPlan(source, serverIdOf("b"), output(), "a", ADDRESS),
                "does not match");
    }

    @Test
    void aSourceWithoutAServerIdIsRefused() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));
        Files.delete(source.resolve("server-id"));
        assertRefusedBeforeClaim(source, plan(source, output()), "has no server-id");
    }

    @Test
    void anUnreadableServerIdIsRefused() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));
        Files.writeString(source.resolve("server-id"), "not-a-server-id");
        assertRefusedBeforeClaim(source, plan(source, output()), "cannot be read");
    }

    @Test
    void aLockedSourceIsRefused() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));
        RaftStorageFactory.DurableStorage running = await(RaftStorageFactory.createDurable(source, true));
        try {
            assertRefusedBeforeClaim(source, plan(source, output()), "is locked");
        } finally {
            await(running.snapshots().closeAsync());
            await(running.wal().closeAsync());
        }
    }

    @Test
    void anExistingOutputIsRefused() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));
        Path output = Files.createDirectories(output());
        assertRefusedBeforeClaim(source, plan(source, output), "already exists");
        assertEquals(List.of(), siblings(output.resolve("child")), "the existing output is left as it was");
    }

    @Test
    void anOutputThatOverlapsTheSourceIsRefused() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));
        for (Path output : List.of(source, source.resolve("nested"), source.getParent())) {
            assertRefusedBeforeClaim(source, plan(source, output), "overlap");
        }
    }

    @Test
    void aRemovedSourceIsRefused() throws Exception {
        Path source = directory.resolve("survivors").resolve("a");
        SurvivorDirectoryFixture.write(source, serverIdOf("a"), 1, Optional.of("a"), List.of(
                ManualRaftClusterFixture.bootstrapEntry(MEMBERS), put(2, 1, "k1", "v1"),
                SurvivorDirectoryFixture.configuration(3, 1, Set.of("b", "c"))));
        assertRefused(source, plan(source, output()), "is not a member");
    }

    @Test
    void aSourceThatOrdinaryStartupWouldRefuseIsRefused() throws Exception {
        // The copy is opened with the ordinary recovery checks, and those report this refusal.
        IntentionalErrorsHelper.expect(RAFT_STATE_WITHOUT_CONFIGURATION);
        Path source = directory.resolve("survivors").resolve("a");
        SurvivorDirectoryFixture.write(source, serverIdOf("a"), 1, Optional.empty(), List.of(put(1, 1, "k", "v")));
        assertRefused(source, plan(source, output()), "no cluster configuration");
    }

    @Test
    void aCorruptSourceIsRefusedAndKeptAsEvidence() throws Exception {
        // The copy's damaged interior record is reported by RaftLog and by the ordinary recovery checks.
        IntentionalErrorsHelper.expect(WAL_AMBIGUOUS_CORRUPTION);
        IntentionalErrorsHelper.expect(RAFT_RECOVERY_AMBIGUOUS_CORRUPTION);
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));
        Path log = source.resolve("raft.log");
        byte[] bytes = Files.readAllBytes(log);
        bytes[bytes.length / 2] ^= 0x01;
        Files.write(log, bytes);
        assertRefused(source, plan(source, output()), "corrupt");
    }

    @Test
    void aSecondRecoveryFromTheSameSourceToAnotherOutputIsRefused() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));
        OfflineRecovery.recover(plan(source, output()));
        Path other = directory.resolve("elsewhere").resolve("a");

        RecoveryRefusedException refused = assertThrows(RecoveryRefusedException.class,
                () -> OfflineRecovery.recover(plan(source, other)));

        assertTrue(refused.getMessage().contains("already claimed"), refused.getMessage());
        assertFalse(Files.exists(other), "one source never seeds two clusters");
    }

    @Test
    void repeatingACompletedRecoveryIsRefusedAndChangesNothing() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));
        Path output = output();
        OfflineRecovery.recover(plan(source, output));
        Map<String, String> published = digest(output);
        String claim = Files.readString(claim(source));
        Map<String, String> sourceBefore = digest(source);

        RecoveryRefusedException refused = assertThrows(RecoveryRefusedException.class,
                () -> OfflineRecovery.recover(plan(source, output)));

        assertTrue(refused.getMessage().contains("already recovered"), refused.getMessage());
        assertEquals(published, digest(output));
        assertEquals(claim, Files.readString(claim(source)));
        assertEquals(sourceBefore, digest(source));
    }

    private Path output() {
        return directory.resolve("recovered").resolve("a");
    }

    private static RecoveryPlan plan(Path source, Path output) {
        return new RecoveryPlan(source, serverIdOf("a"), output, "a", ADDRESS);
    }

    private static Path claim(Path source) {
        return source.resolveSibling(source.getFileName() + ".qraft-recovery.json");
    }

    /** Refused by a check that reads the source and changes nothing, so no claim is made and nothing staged. */
    private static void assertRefusedBeforeClaim(Path source, RecoveryPlan plan, String reason) throws Exception {
        assertRefused(source, plan, reason);
        assertFalse(Files.exists(claim(source)), "a plan refused before copying makes no claim");
    }

    /** Refused with {@code reason}: the source is unchanged, and no output or staging directory is left. */
    private static void assertRefused(Path source, RecoveryPlan plan, String reason) throws Exception {
        Map<String, String> before = digest(source);
        boolean outputExisted = Files.exists(plan.output());
        Map<String, String> outputBefore = digest(plan.output());

        RecoveryRefusedException refused = assertThrows(RecoveryRefusedException.class,
                () -> OfflineRecovery.recover(plan));

        assertTrue(refused.getMessage().contains(reason), refused.getMessage());
        assertEquals(before, digest(source), "a refusal never changes the source");
        assertEquals(outputExisted, Files.exists(plan.output()));
        assertEquals(outputBefore, digest(plan.output()));
        if (!plan.output().startsWith(source) && !source.startsWith(plan.output())) {
            List<Path> expected = outputExisted ? List.of(plan.output()) : List.of();
            assertEquals(expected, siblings(plan.output()), "no staging directory is left beside the output");
        }
    }

    /** The entries beside {@code path} in its parent directory, or none if the parent does not exist. */
    private static List<Path> siblings(Path path) throws Exception {
        if (!Files.isDirectory(path.getParent())) return List.of();
        try (Stream<Path> entries = Files.list(path.getParent())) {
            return entries.sorted().toList();
        }
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

    private static RaftStorage.PersistentMeta metadata(Path directory) throws Exception {
        RaftStorageFactory.DurableStorage storage = await(RaftStorageFactory.createDurable(directory, true));
        try {
            return await(storage.wal().loadMetadata());
        } finally {
            await(storage.snapshots().closeAsync());
            await(storage.wal().closeAsync());
        }
    }

    private static void assertEntryEquals(LogEntryData expected, LogEntryData actual) {
        assertEquals(expected.index(), actual.index());
        assertEquals(expected.term(), actual.term());
        assertTrue(java.util.Arrays.equals(expected.payload(), actual.payload()), "entry " + expected.index());
    }
}

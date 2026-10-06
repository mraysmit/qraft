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
import dev.mars.qraft.controller.raft.RaftConfiguration.Server;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.LogEntry;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.ConfigurationCommand;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.controller.state.RaftCommand;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.testing.fault.IntentionalErrors;
import dev.mars.qraft.controller.raft.storage.RaftStorageFactory;
import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import dev.mars.raftlog.storage.RaftStorageConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static dev.mars.qraft.controller.raft.ManualRaftCluster.await;
import static dev.mars.qraft.testing.fault.IntentionalError.COMMITTED_ENTRY_REPLACEMENT;
import static dev.mars.qraft.testing.fault.IntentionalError.RAFT_STATE_WITHOUT_CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how a {@link RaftNode} holds its configuration, the servers it replicates to:
 * <ul>
 *   <li>a node with no Raft state is bootstrapped with its initial configuration, written at index 1 in term 0 and
 *       treated as committed, and a sole member bootstraps itself;</li>
 *   <li>a node with Raft state but no configuration, which is data from before configurations were recorded,
 *       refuses to start;</li>
 *   <li>the latest configuration in the log is in force, committed or not, and truncating it reverts to the one
 *       before;</li>
 *   <li>recovery restores the configuration from the WAL, or from the snapshot once the WAL entry is compacted,
 *       and an installed snapshot brings its configuration;</li>
 *   <li>configuration entries never reach the state machine.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
class RaftNodeConfigurationTest {
    private static final ProtobufRaftCommandCodec CODEC = new ProtobufRaftCommandCodec();
    private static final Server F = new Server("id-f", "f", "f:9080", true);
    private static final Server L = new Server("id-l", "l", "l:9080", true);
    private static final Server M = new Server("id-m", "m", "m:9080", true);
    private static final RaftConfiguration FL = new RaftConfiguration(List.of(F, L));
    private static final RaftConfiguration FLM = new RaftConfiguration(List.of(F, L, M));

    @TempDir
    Path directory;

    private JavaRuntime runtime;
    private ManualRaftCluster cluster;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        cluster = new ManualRaftCluster(runtime);
        InMemoryTransportSimulator.clearAllTransports();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            cluster.close();
        } finally {
            runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulator.clearAllTransports();
        }
    }

    @Test
    void aNodeWithNoStateIsBootstrappedAtIndexOneInTermZeroAsCommitted() throws Exception {
        QraftStateStore store = new QraftStateStore();
        RaftNode follower = follower(store, RaftNodeMode.volatileMode(), FL);

        await(follower.start());

        assertEquals(Optional.of(FL), follower.getConfiguration());
        assertEquals(1, follower.getLastLogIndex());
        assertEquals(0, follower.getCurrentTerm(), "bootstrapping spends no term");
        assertEquals(1, follower.getCommitIndex(), "every server that holds index 1 holds this same entry");
        assertEquals(1, follower.getLastApplied());
        assertFalse(await(follower.status()).fenced(), "the entry never reached the state machine");
        assertEquals(1, store.getLastAppliedIndex());
    }

    @Test
    void aSoleMemberBootstrapsItself() throws Exception {
        RaftNode sole = cluster.add(cluster.unconfiguredBuilder("f", Set.of("f"), new InMemoryTransportSimulator("f"),
                new QraftStateStore(), RaftNodeMode.volatileMode()).serverId("id-f"));

        await(sole.start());

        assertEquals(Optional.of(new RaftConfiguration(List.of(new Server("id-f", "f", "f", true)))),
                sole.getConfiguration());
    }

    @Test
    void aNodeWithManyMembersAndNoInitialConfigurationHasNone() throws Exception {
        RaftNode node = cluster.add(cluster.unconfiguredBuilder("f", Set.of("f", "l"), new InMemoryTransportSimulator("f"),
                new QraftStateStore(), RaftNodeMode.volatileMode()).serverId("id-f"));

        await(node.start());

        assertEquals(Optional.empty(), node.getConfiguration());
        assertEquals(0, node.getLastLogIndex());
    }

    @Test
    void stateFromBeforeConfigurationsWereRecordedRefusesToStart() throws Exception {
        IntentionalErrors.expect(RAFT_STATE_WITHOUT_CONFIGURATION, 1);
        TestRaftStorage storage = openStorage();
        storage.appendEntries(List.of(new LogEntryData(1, 1, CODEC.serialize(put("k"))))).get(10, TimeUnit.SECONDS);
        RaftNode node = cluster.add(cluster.unconfiguredBuilder("f", Set.of("f"), new InMemoryTransportSimulator("f"),
                new QraftStateStore(), RaftNodeMode.durable(storage, storage)).serverId("id-f"));

        ExecutionException refused = assertThrows(ExecutionException.class, () -> await(node.start()));

        assertInstanceOf(IllegalStateException.class, refused.getCause());
        assertTrue(refused.getCause().getMessage().contains("holds Raft state but no cluster configuration"),
                refused.getCause().getMessage());
    }

    @Test
    void theLatestConfigurationInTheLogIsInForceAndTruncatingItRevertsToTheOneBefore() throws Exception {
        RaftNode follower = follower(new QraftStateStore(), RaftNodeMode.volatileMode(), FL);
        await(follower.start());

        assertTrue(await(follower.handleAppendEntriesRequest(append(1, 1, 0, entry(2, 1, new ConfigurationCommand(FLM)))))
                .getSuccess());
        assertEquals(Optional.of(FLM), follower.getConfiguration(), "in force before it is committed");

        assertTrue(await(follower.handleAppendEntriesRequest(append(2, 1, 0, entry(2, 2, put("k"))))).getSuccess());
        assertEquals(Optional.of(FL), follower.getConfiguration(), "the entry was truncated, so FL is in force again");
    }

    @Test
    void aRunningNodeIsBootstrappedOnlyWhileItHoldsNoStateAndOnlyWithItself() throws Exception {
        RaftNode fresh = cluster.add(cluster.unconfiguredBuilder("f", Set.of("f", "l"),
                new InMemoryTransportSimulator("f"), new QraftStateStore(), RaftNodeMode.volatileMode()).serverId("id-f"));
        await(fresh.start());

        ExecutionException withoutItself = assertThrows(ExecutionException.class,
                () -> await(fresh.bootstrap(new RaftConfiguration(List.of(L, M)))));
        assertInstanceOf(IllegalArgumentException.class, withoutItself.getCause());
        assertEquals(Optional.empty(), fresh.getConfiguration());

        await(fresh.bootstrap(FL));
        assertEquals(Optional.of(FL), fresh.getConfiguration());
        assertEquals(1, fresh.getCommitIndex());

        ExecutionException again = assertThrows(ExecutionException.class, () -> await(fresh.bootstrap(FLM)));
        assertInstanceOf(IllegalStateException.class, again.getCause());
        assertTrue(again.getCause().getMessage().contains("already holds Raft state"), again.getCause().getMessage());
        assertEquals(Optional.of(FL), fresh.getConfiguration(), "the first configuration stands");
        assertEquals(1, fresh.getLastLogIndex());
    }

    @Test
    void indexOneMustHoldABootstrapConfiguration() throws Exception {
        RaftNode joining = cluster.add(cluster.unconfiguredBuilder("f", Set.of("f", "l"),
                new InMemoryTransportSimulator("f"), new QraftStateStore(), RaftNodeMode.volatileMode()).serverId("id-f"));
        await(joining.start());

        for (LogEntry notABootstrap : List.of(entry(1, 1, put("k")), entry(1, 1, new ConfigurationCommand(FL)))) {
            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> await(joining.handleAppendEntriesRequest(append(1, 0, 0, notABootstrap))));
            assertInstanceOf(IllegalArgumentException.class, refused.getCause());
            assertEquals("Log index 1 must hold the bootstrap configuration, in term 0",
                    refused.getCause().getMessage());
            assertEquals(0, joining.getLastLogIndex(), "nothing is written");
        }

        assertTrue(await(joining.handleAppendEntriesRequest(append(1, 0, 0, entry(1, 0, new ConfigurationCommand(FL)))))
                .getSuccess(), "a leader's real first entry is accepted");
        assertEquals(Optional.of(FL), joining.getConfiguration());
    }

    @Test
    void anAppendThatWouldReplaceACommittedEntryIsRefusedAndChangesNothing() throws Exception {
        IntentionalErrors.expect(COMMITTED_ENTRY_REPLACEMENT, 1);
        RaftNode follower = follower(new QraftStateStore(), RaftNodeMode.volatileMode(), FL);
        await(follower.start());

        // Only another term-0 configuration can reach index 1 (see indexOneMustHoldABootstrapConfiguration),
        // and one in the same term matches rather than conflicts, so the bootstrap entry cannot be replaced.
        assertThrows(ExecutionException.class,
                () -> await(follower.handleAppendEntriesRequest(append(1, 0, 0, entry(1, 1, put("k"))))));
        assertEquals(Optional.of(FL), follower.getConfiguration(), "the committed configuration is kept");
        assertEquals(1, follower.getLastLogIndex());

        assertTrue(await(follower.handleAppendEntriesRequest(append(1, 1, 0, entry(2, 1, put("a"))).toBuilder()
                .setLeaderCommit(2).build())).getSuccess());
        assertEquals(2, follower.getCommitIndex());
        ExecutionException commandReplaced = assertThrows(ExecutionException.class,
                () -> await(follower.handleAppendEntriesRequest(append(2, 1, 0, entry(2, 2, put("b"))))),
                "a committed command cannot be replaced either");
        assertInstanceOf(IllegalStateException.class, commandReplaced.getCause());
        assertTrue(commandReplaced.getCause().getMessage().contains("committed entry at index 2"),
                commandReplaced.getCause().getMessage());
        assertEquals(2, follower.getLastLogIndex());
        assertEquals(2, follower.getLastApplied());
    }

    @Test
    void recoveryRestoresTheConfigurationFromTheWal() throws Exception {
        RaftNode first = follower(new QraftStateStore(), durable(), FL);
        await(first.start());
        await(first.stop());

        RaftNode restarted = cluster.add(cluster.unconfiguredBuilder("f", Set.of("f", "l"), new InMemoryTransportSimulator("f"),
                new QraftStateStore(), durable()).serverId("id-f"));
        await(restarted.start());

        assertEquals(Optional.of(FL), restarted.getConfiguration());
        assertEquals(1, restarted.getCommitIndex());
    }

    @Test
    void recoveryRestoresTheConfigurationFromTheSnapshotOnceTheWalEntryIsCompacted() throws Exception {
        RaftConfiguration sole = new RaftConfiguration(List.of(F));
        RaftNode first = cluster.add(cluster.unconfiguredBuilder("f", Set.of("f"), new InMemoryTransportSimulator("f"),
                        new QraftStateStore(), durable())
                .serverId("id-f").initialConfiguration(sole)
                .snapshotEnabled(true).snapshotThreshold(3).snapshotCheckInterval(300));
        await(first.start());
        cluster.elect(first);
        for (int i = 0; i < 5; i++) await(first.submitCommand(put("k" + i)));
        cluster.timers(first).firePeriodic(300);
        awaitTrue(() -> first.getSnapshotLastIndex() >= 3, "the node compacts its log");
        assertEquals(Optional.of(sole), first.getConfiguration(), "compaction keeps the configuration in force");
        await(first.stop());
        try (FileRaftStorage wal = new FileRaftStorage(RaftStorageConfig.builder().dataDir(directory).build())) {
            wal.open(directory).get(10, TimeUnit.SECONDS);
            assertTrue(wal.replayLog().get(10, TimeUnit.SECONDS).stream().noneMatch(entry -> entry.index() == 1),
                    "the configuration entry is gone from the WAL");
        }

        RaftNode restarted = cluster.add(cluster.unconfiguredBuilder("f", Set.of("f"), new InMemoryTransportSimulator("f"),
                new QraftStateStore(), durable()).serverId("id-f"));
        await(restarted.start());

        assertEquals(Optional.of(sole), restarted.getConfiguration());
    }

    @Test
    void anInstalledSnapshotBringsItsConfiguration() throws Exception {
        RaftNode follower = cluster.add(cluster.unconfiguredBuilder("f", Set.of("f", "l"), new InMemoryTransportSimulator("f"),
                new QraftStateStore(), RaftNodeMode.volatileMode()).serverId("id-f"));
        await(follower.start());
        byte[] data = SnapshotEnvelope.wrap(FLM, new QraftStateStore().takeSnapshot());

        assertTrue(await(follower.handleInstallSnapshot(InstallSnapshotRequest.newBuilder()
                .setTerm(1).setLeaderId("l").setLeaderServerId("id-l").setLastIncludedIndex(4).setLastIncludedTerm(1)
                .setChunkIndex(0).setTotalChunks(1).setData(ByteString.copyFrom(data)).setDone(true).build()))
                .getSuccess());

        assertEquals(Optional.of(FLM), follower.getConfiguration());
    }

    private RaftNode follower(QraftStateStore store, RaftNodeMode mode, RaftConfiguration configuration) {
        return cluster.add(cluster.unconfiguredBuilder("f", Set.of("f", "l"), new InMemoryTransportSimulator("f"), store, mode)
                .serverId("id-f").initialConfiguration(configuration));
    }

    private static AppendEntriesRequest append(long term, long prevIndex, long prevTerm, LogEntry entry) {
        return AppendEntriesRequest.newBuilder().setTerm(term).setLeaderId("l").setLeaderServerId("id-l")
                .setPrevLogIndex(prevIndex).setPrevLogTerm(prevTerm).setLeaderCommit(0).addEntries(entry).build();
    }

    private static LogEntry entry(long index, long term, RaftCommand command) {
        return LogEntry.newBuilder().setIndex(index).setTerm(term)
                .setData(ByteString.copyFrom(CODEC.serialize(command))).build();
    }

    private static RaftCommand put(String key) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, "v"));
    }

    /** Real WAL and snapshot storage in this test's directory, opened afresh for each node that uses it. */
    private RaftNodeMode durable() throws Exception {
        RaftStorageFactory.DurableStorage storage = await(RaftStorageFactory.createDurable(directory, true));
        return RaftNodeMode.durable(storage.wal(), storage.snapshots());
    }

    private static TestRaftStorage openStorage() throws Exception {
        TestRaftStorage storage = new TestRaftStorage();
        storage.open(null).get(10, TimeUnit.SECONDS);
        return storage;
    }

    /** Bounds a wait for the state loop; the bound only diagnoses a hang. */
    private static void awaitTrue(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), description);
    }
}

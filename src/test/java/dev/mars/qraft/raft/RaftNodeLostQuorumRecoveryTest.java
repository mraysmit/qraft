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

import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.RaftConfiguration.Server;
import dev.mars.qraft.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.raft.storage.RaftStorageFactory;
import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.HEARTBEAT_MS;
import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static dev.mars.qraft.raft.ManualRaftClusterFixture.serverIdOf;
import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixes how a {@link RaftNode} treats a directory rebuilt by lost-quorum recovery (membership Step 7, decision 8)
 * before the offline command exists: the stopped survivor's history, copied, followed by one configuration entry
 * that makes it the sole voter under a new server ID. {@link #seedFrom} builds that directory as the command will.
 * <ul>
 *   <li>a running leader refuses that configuration as a change, yet servers joining the seed accept it, by
 *       replication and inside an installed snapshot;</li>
 *   <li>the seed's first start commits its whole log, the tail no quorum committed included, and it elects
 *       itself;</li>
 *   <li>a returning old majority can serve its own clients, which is why quarantine is mandatory, but it neither
 *       changes the recovered cluster nor counts a reply from it.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-22
 * @version 1.0
 */
class RaftNodeLostQuorumRecoveryTest {
    private static final ProtobufRaftCommandCodec CODEC = new ProtobufRaftCommandCodec();
    private static final Set<String> MEMBERS = Set.of("a", "b", "c");
    private static final String SEED_ID = "recovered-a";
    private static final RaftConfiguration RECOVERED =
            new RaftConfiguration(List.of(new Server(SEED_ID, "a", "a", true)));

    @TempDir
    Path directory;

    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private final Map<String, QraftStateStore> stores = new HashMap<>();
    private RaftNode a;
    private RaftNode b;
    private RaftNode c;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryTransportSimulatorFixture.clearAllTransports();
        runtime = RaftExecutorsFixture.create();
        cluster = new ManualRaftClusterFixture(runtime);
        a = member("a", new QraftStateStore());
        b = member("b", new QraftStateStore());
        c = member("c", new QraftStateStore());
        ManualRaftClusterFixture.startAll(a, b, c);
        cluster.elect(a);
        await(a.submitCommand(put("before-loss", "committed")));
        cluster.heartbeatUntil(a, () -> stores.values().stream()
                        .allMatch(store -> "committed".equals(store.getMetadata("before-loss"))),
                "every member holds the committed write");
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (cluster != null) cluster.close();
        } finally {
            if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
        }
    }

    @Test
    void aRunningLeaderRefusesTheRecoveryConfigurationAsAChange() throws Exception {
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> await(a.proposeConfiguration(RECOVERED)));

        assertInstanceOf(IllegalArgumentException.class, refused.getCause());
        assertTrue(refused.getCause().getMessage().contains("one server at a time"), refused.getCause().getMessage());
        assertEquals(Optional.of(ManualRaftClusterFixture.configurationOf(MEMBERS)), a.getConfiguration());
    }

    @Test
    void theSeedsFirstStartCommitsItsWholeLogAndItElectsItself() throws Exception {
        RecoveryEntryHelper recovery = seedFrom(directory.resolve("a"), loseTheMajority());
        QraftStateStore store = new QraftStateStore();
        RaftNode seed = seed(store, false);

        await(seed.start());

        assertEquals(Optional.of(RECOVERED), seed.getConfiguration());
        assertEquals(recovery.term(), seed.getCurrentTerm());
        assertEquals(recovery.index(), seed.getCommitIndex(), "as sole voter it commits every entry it holds");
        assertEquals(recovery.index(), seed.getLastApplied());
        assertEquals("uncommitted", store.getMetadata("seed-only"), "the tail no quorum committed is now committed");
        assertEquals("committed", store.getMetadata("before-loss"));

        cluster.elect(seed);
        assertEquals(recovery.term() + 1, seed.getCurrentTerm());
        await(seed.submitCommand(put("after-recovery", "committed")));
        assertEquals("committed", store.getMetadata("after-recovery"));
        assertFalse(seed.isFenced());
    }

    @Test
    void aServerJoiningTheSeedTakesTheRecoveryEntryByReplication() throws Exception {
        seedFrom(directory.resolve("a"), loseTheMajority());
        RaftNode seed = seed(new QraftStateStore(), false);
        await(seed.start());
        cluster.elect(seed);
        QraftStateStore joinedStore = new QraftStateStore();

        RaftNode joined = join("d", joinedStore, seed);

        assertEquals(0, joined.getSnapshotLastIndex(), "every entry arrived by AppendEntries");
        assertRecoveredConfiguration(joined);
        assertEquals("uncommitted", joinedStore.getMetadata("seed-only"));
        assertEquals("committed", joinedStore.getMetadata("before-loss"));
        assertFalse(joined.isFenced());
    }

    @Test
    void aServerJoiningTheSeedTakesTheRecoveryEntryInsideAnInstalledSnapshot() throws Exception {
        RecoveryEntryHelper recovery = seedFrom(directory.resolve("a"), loseTheMajority());
        RaftNode seed = seed(new QraftStateStore(), true);
        await(seed.start());
        cluster.elect(seed);
        for (int i = 0; i < 4; i++) await(seed.submitCommand(put("k" + i, "v")));
        cluster.timers(seed).firePeriodic(300);
        awaitTrue(() -> seed.getSnapshotLastIndex() > recovery.index(),
                "the seed compacts the recovery entry into its snapshot");
        QraftStateStore joinedStore = new QraftStateStore();

        RaftNode joined = join("e", joinedStore, seed);

        assertTrue(joined.getSnapshotLastIndex() > recovery.index(),
                "the recovery entry arrived inside the installed snapshot");
        assertRecoveredConfiguration(joined);
        assertEquals("uncommitted", joinedStore.getMetadata("seed-only"));
        assertEquals("v", joinedStore.getMetadata("k3"));
        assertFalse(joined.isFenced());
    }

    @Test
    void aReturningOldMajorityNeitherChangesTheRecoveredClusterNorCountsItsReplies() throws Exception {
        seedFrom(directory.resolve("a"), loseTheMajority());
        QraftStateStore seedStore = new QraftStateStore();
        RaftNode seed = seed(seedStore, false);
        await(seed.start());
        cluster.elect(seed);
        QraftStateStore joinedStore = new QraftStateStore();
        RaftNode joined = join("d", joinedStore, seed);
        long seedTerm = seed.getCurrentTerm();
        long seedCommit = seed.getCommitIndex();
        Optional<RaftConfiguration> recovered = seed.getConfiguration();

        // Quarantine is broken on purpose: b and c restart on their old directories, a majority of the old
        // configuration. b campaigns while c is still down, so its term climbs above the seed's and the
        // seed's refusal below is by server ID, not by term.
        QraftStateStore oldCStore = new QraftStateStore();
        RaftNode oldB = member("b", new QraftStateStore());
        await(oldB.start());
        for (int campaign = 0; campaign < 3; campaign++) {
            long before = oldB.getCurrentTerm();
            cluster.timers(oldB).fireElectionTimeout();
            // A durable campaign persists its term on the WAL thread, which settle does not drain, and only then
            // sends its votes and arms the next election timeout.
            awaitTrue(() -> oldB.getCurrentTerm() > before && cluster.timers(oldB).oneShotCount() > 0,
                    "b's campaign persists its term and arms its next election timeout");
            cluster.settle();
        }
        assertTrue(oldB.getCurrentTerm() > seedTerm, "the old server campaigns above the recovered term");
        RaftNode oldC = member("c", oldCStore);
        await(oldC.start());
        cluster.elect(oldB);
        await(oldB.submitCommand(put("old-cluster", "diverged")));
        cluster.heartbeatUntil(oldB, () -> "diverged".equals(oldCStore.getMetadata("old-cluster")),
                "the old majority still serves its own writes");

        assertTrue(seed.isLeader(), "the recovered leader ignores the old campaigns");
        assertEquals(seedTerm, seed.getCurrentTerm(), "a higher-term append meant for a's old server ID is refused");
        assertEquals(recovered, seed.getConfiguration());
        assertEquals(seedCommit, seed.getCommitIndex());
        assertNull(seedStore.getMetadata("old-cluster"));
        assertNull(joinedStore.getMetadata("old-cluster"));

        // A wiped server that rejoined refuses the same traffic when it reaches it under an old member's name.
        AppendEntriesResponse meantForOldC = await(joined.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                .setTerm(oldB.getCurrentTerm()).setLeaderId("b").setLeaderServerId(serverIdOf("b"))
                .setTargetServerId(serverIdOf("c")).setPrevLogIndex(0).setPrevLogTerm(0).setLeaderCommit(0)
                .build()));
        assertFalse(meantForOldC.getSuccess());
        assertEquals(seedTerm, joined.getCurrentTerm());
        assertEquals("a", joined.getLeaderId());

        // With c gone again, a is b's only other voter. The seed answers under its new server ID, so the old
        // configuration counts none of its replies and b cannot commit.
        await(oldC.stop());
        long oldCommit = oldB.getCommitIndex();
        CompletableFuture<?> needsA = oldB.submitCommand(put("needs-a", "never"));
        for (int round = 0; round < 5; round++) heartbeat(oldB);
        assertFalse(needsA.isDone(), "no reply from the recovered cluster completes an old-cluster write");
        assertEquals(oldCommit, oldB.getCommitIndex());
        assertNull(seedStore.getMetadata("needs-a"));

        await(seed.submitCommand(put("after-isolation", "committed")));
        cluster.heartbeatUntil(seed, () -> "committed".equals(joinedStore.getMetadata("after-isolation")),
                "the recovered cluster keeps replicating");
        // Teardown stops the seed before the old leader, which may still be sending to a.
        IntentionalErrorsHelper.expectPeerShutdownDuringCleanup("a");
    }

    /**
     * Stops b and c, the majority, then leaves on a a write that no other server holds, and stops a: its directory
     * is the survivor's. Returns the last index a's log holds.
     */
    private long loseTheMajority() throws Exception {
        // a replicates the tail toward both lost servers, b and c; each send fails once they are gone.
        IntentionalErrorsHelper.expect(RAFT_PEER_UNREACHABLE);
        await(b.stop());
        await(c.stop());
        long before = a.getLastLogIndex();
        a.submitCommand(put("seed-only", "uncommitted"));
        // The leader adds the entry once its WAL append completes, off the state loop that settle drains.
        awaitTrue(() -> a.getLastLogIndex() > before, "a appends the tail to its log");
        cluster.settle();
        long tail = a.getLastLogIndex();
        assertTrue(a.getCommitIndex() < tail, "no quorum remains to commit the tail");
        await(a.stop());
        return tail;
    }

    /**
     * Builds the seed's directory as the recovery command will (Step 7 contract, steps 2, 4 and 5): a byte copy of
     * the stopped {@code source}, then one configuration entry at the next index, in a term above any the source
     * holds, naming the seed its sole voter, and the persistent term moved to that term with no vote. The new
     * server ID, the command's step 6, is the one {@link #seed} gives the builder.
     */
    private RecoveryEntryHelper seedFrom(Path source, long sourceLastIndex) throws Exception {
        Path seed = directory.resolve("seed");
        copyDirectory(source, seed);
        RaftStorageFactory.DurableStorage storage = await(RaftStorageFactory.createDurable(seed, true));
        try {
            List<LogEntryData> entries = await(storage.wal().replayLog());
            LogEntryData last = entries.get(entries.size() - 1);
            assertEquals(sourceLastIndex, last.index(), "the copy holds the survivor's whole log");
            long term = Math.max(await(storage.wal().loadMetadata()).currentTerm(), last.term()) + 1;
            long index = last.index() + 1;
            await(storage.wal().appendEntries(List.of(new LogEntryData(index, term,
                    CODEC.serialize(new ConfigurationCommand(RECOVERED))))));
            await(storage.wal().sync());
            await(storage.wal().updateMetadata(term, Optional.empty()));
            return new RecoveryEntryHelper(index, term);
        } finally {
            await(storage.snapshots().closeAsync());
            await(storage.wal().closeAsync());
        }
    }

    /** The seed on its rebuilt directory, under its old name and server JSON but its new server ID. */
    private RaftNode seed(QraftStateStore store, boolean snapshots) throws Exception {
        RaftNode.Builder builder = cluster.unconfiguredBuilder("a", MEMBERS, new InMemoryTransportSimulatorFixture("a"),
                store, durable(directory.resolve("seed"))).serverId(SEED_ID);
        return cluster.add(snapshots
                ? builder.snapshotEnabled(true).snapshotThreshold(3).snapshotCheckInterval(300)
                : builder.snapshotEnabled(false));
    }

    /**
     * Starts an empty server, as a wiped one returns, and has {@code leader} admit it. The simulator addresses
     * servers by name, so it takes a name no old member used; the same-name case is the one the rejoined server's
     * refusal in {@link #aReturningOldMajorityNeitherChangesTheRecoveredClusterNorCountsItsReplies} covers.
     */
    private RaftNode join(String name, QraftStateStore store, RaftNode leader) throws Exception {
        String serverId = "rejoined-" + name;
        RaftNode joining = cluster.add(cluster.unconfiguredBuilder(name, Set.of("a", name),
                new InMemoryTransportSimulatorFixture(name), store, RaftNodeMode.volatileMode()).serverId(serverId));
        await(joining.start());
        assertEquals(JoinResult.JOINED, await(leader.admit(new Server(serverId, name, name, false))));
        cluster.heartbeatUntil(leader, () -> joining.getCommitIndex() == leader.getCommitIndex()
                        && joining.getConfiguration().equals(leader.getConfiguration()),
                name + " catches up with the seed");
        return joining;
    }

    /** A member of the original cluster on its own durable directory, which survives its node being stopped. */
    private RaftNode member(String name, QraftStateStore store) throws Exception {
        stores.putIfAbsent(name, store);
        return cluster.add(cluster.builder(name, MEMBERS, new InMemoryTransportSimulatorFixture(name), store,
                durable(directory.resolve(name))).snapshotEnabled(false));
    }

    private static void assertRecoveredConfiguration(RaftNode node) {
        RaftConfiguration configuration = node.getConfiguration().orElseThrow();
        assertTrue(configuration.isVoter(SEED_ID), "the seed is the voter under its new server ID");
        for (String old : MEMBERS) {
            assertTrue(configuration.server(serverIdOf(old)).isEmpty(), "no old server ID remains: " + old);
        }
    }

    private void heartbeat(RaftNode leader) throws Exception {
        cluster.timers(leader).firePeriodic(HEARTBEAT_MS);
        cluster.settle();
    }

    /** Real WAL and snapshot storage in {@code path}, opened afresh for each node that uses it. */
    private static RaftNodeMode durable(Path path) throws Exception {
        Files.createDirectories(path);
        RaftStorageFactory.DurableStorage storage = await(RaftStorageFactory.createDurable(path, true));
        return RaftNodeMode.durable(storage.wal(), storage.snapshots());
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path copy = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(copy);
                else Files.copy(path, copy);
            }
        }
    }

    private static DistributedStateRaftCommand put(String key, String value) {
        return new DistributedStateRaftCommand(DistributedStateCommand.put(key, value));
    }

    /** Bounds a wait for the state loop; the bound only diagnoses a hang. */
    private static void awaitTrue(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), description);
    }

    /** Test helper recording where {@link #seedFrom} wrote the recovery entry. */
    private record RecoveryEntryHelper(long index, long term) { }
}

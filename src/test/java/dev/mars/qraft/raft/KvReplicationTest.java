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
import dev.mars.qraft.raft.storage.RaftStorageFactory;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.kv.KvCommand;
import dev.mars.qraft.state.kv.KvEntry;
import dev.mars.qraft.state.kv.KvScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests key/value replication (key/value list, Phase 1, decision 2): what the leader writes, every server holds at
 * the same creation and modification indexes, which are the entries' Raft indexes; and a durable server that
 * recovers from its snapshot and log holds them at the same indexes again.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class KvReplicationTest {
    private static final KvScope SCOPE = new KvScope("acme", "prod");
    private static final Set<String> MEMBERS = Set.of("kv1", "kv2", "kv3");

    @TempDir
    Path tempDir;

    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;

    @BeforeEach
    void setUp() {
        runtime = RaftExecutorsFixture.create();
        cluster = new ManualRaftClusterFixture(runtime);
        InMemoryTransportSimulatorFixture.clearAllTransports();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            cluster.close();
        } finally {
            runtime.close().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void everyServerHoldsWhatTheLeaderWroteAtTheSameIndexes() throws Exception {
        QraftStateStore[] stores = {new QraftStateStore(), new QraftStateStore(), new QraftStateStore()};
        RaftNode[] nodes = new RaftNode[3];
        List<String> ids = List.of("kv1", "kv2", "kv3");
        for (int i = 0; i < 3; i++) {
            nodes[i] = cluster.add(cluster.builder(ids.get(i), MEMBERS,
                    new InMemoryTransportSimulatorFixture(ids.get(i)), stores[i], RaftNodeMode.volatileMode()));
        }
        ManualRaftClusterFixture.startAll(nodes);
        RaftNode leader = cluster.elect(nodes[0]);

        assertInstanceOf(RaftCommandResult.Success.class,
                await(leader.submitCommand(KvCommand.set(SCOPE, "app/a", bytes("1"), 5, null))));
        assertInstanceOf(RaftCommandResult.Success.class,
                await(leader.submitCommand(KvCommand.set(SCOPE, "app/a", bytes("2"), 5, null))));
        KvEntry written = stores[0].getKv().get(SCOPE, "app/a").orElseThrow();
        assertEquals(written.createIndex() + 1, written.modifyIndex(), "two consecutive entries");
        cluster.heartbeatUntil(leader, () -> List.of(stores).stream()
                .allMatch(store -> store.getKv().get(SCOPE, "app/a").map(written::equals).orElse(false)),
                "every server holds the entry at the leader's indexes");
    }

    @Test
    void aServerThatRecoversFromItsSnapshotAndLogHoldsTheSameIndexes() throws Exception {
        Path storage = tempDir.resolve("kv1");
        QraftStateStore original = new QraftStateStore();
        RaftNode first = durable(original, storage);
        await(first.start());
        cluster.elect(first);
        await(first.submitCommand(KvCommand.set(SCOPE, "snapshotted", bytes("s"), 0, null)));
        await(first.takeSnapshot());
        await(first.submitCommand(KvCommand.set(SCOPE, "logged", bytes("l"), 0, null)));
        List<KvEntry> before = original.getKv().list(SCOPE, "");
        long lastIndex = original.getKv().lastIndex();
        await(first.stop());

        QraftStateStore recovered = new QraftStateStore();
        RaftNode restarted = durable(recovered, storage);
        await(restarted.start());

        assertEquals(before, recovered.getKv().list(SCOPE, ""));
        assertEquals(lastIndex, recovered.getKv().lastIndex());
    }

    private RaftNode durable(QraftStateStore store, Path storage) throws Exception {
        RaftStorageFactory.DurableStorage durable = RaftStorageFactory.createDurable(storage, true)
                .get(10, TimeUnit.SECONDS);
        return cluster.add(cluster.builder("kv1", Set.of("kv1"), new InMemoryTransportSimulatorFixture("kv1"), store,
                RaftNodeMode.durable(durable.wal(), durable.snapshots())));
    }
}

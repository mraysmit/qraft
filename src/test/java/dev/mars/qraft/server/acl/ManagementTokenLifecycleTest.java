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

package dev.mars.qraft.server.acl;

import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.InMemoryTransportSimulatorFixture;
import dev.mars.qraft.raft.ManualRaftClusterFixture;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.raft.storage.RaftStorageFactory;
import dev.mars.qraft.server.health.ManualExpiryTimeHelper;
import dev.mars.qraft.state.AclCommand;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.acl.AclRefusal;
import dev.mars.qraft.state.acl.AclToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the management token's life on real storage across a three-server cluster (ACL list, Phase 3's exit, and
 * the revocation order of proposal 12): the first leader installs the token from its file through the bootstrap; a
 * restarted cluster keeps it; once every server's file holds a new secret, the next leader installs the new token
 * beside the old; the old token can then be deleted, while deleting the new one, now the only token holding
 * {@code acl:write} without an expiry, is refused as a lock-out; and after the next restart the deleted token does not
 * return, because no file holds its secret.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
class ManagementTokenLifecycleTest {
    private static final List<String> NAMES = List.of("a", "b", "c");
    private static final String FIRST_SECRET = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    private static final String SECOND_SECRET = "3f2504e1-4f89-41d3-9a0c-0305e82c3301";

    @TempDir
    Path tempDir;

    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private final ManualExpiryTimeHelper time = new ManualExpiryTimeHelper(Instant.parse("2026-11-05T09:00:00Z"));
    private final List<ManagementTokenInstaller> installers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        runtime = RaftExecutorsFixture.create();
        cluster = new ManualRaftClusterFixture(runtime);
        InMemoryTransportSimulatorFixture.clearAllTransports();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            installers.forEach(ManagementTokenInstaller::close);
            cluster.close();
        } finally {
            runtime.close().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
        }
    }

    @Test
    void theTokenIsKeptAcrossARestartReplacedThroughTheFilesAndTheOldOneRevoked() throws Exception {
        Map<String, QraftStateStore> first = start(FIRST_SECRET);
        RaftNode leader = cluster.elect(node("a"));
        cluster.heartbeatUntil(leader, () -> first.values().stream()
                .allMatch(store -> store.getAcl().tokenBySecret(FIRST_SECRET).isPresent()),
                "every server holds the token the first leader installed");
        AclToken original = first.get("a").getAcl().tokenBySecret(FIRST_SECRET).orElseThrow();
        assertTrue(original.installedFromFile());
        assertEquals(1, first.get("b").getAcl().bootstrapIndex(), "installed through the bootstrap");
        stopAll();

        // Every server's file now holds a new secret, and a restart changes leadership.
        Map<String, QraftStateStore> second = start(SECOND_SECRET);
        RaftNode next = cluster.elect(node("b"));
        cluster.heartbeatUntil(next, () -> second.values().stream().allMatch(store ->
                        store.getAcl().tokenBySecret(FIRST_SECRET).isPresent()
                                && store.getAcl().tokenBySecret(SECOND_SECRET).isPresent()),
                "the restarted cluster keeps the first token, and its new leader installs the second");
        assertEquals(1, second.get("c").getAcl().bootstrapIndex(), "the second is created, not bootstrapped");

        RaftCommandResult<?> deleteOld = await(next.submitCommand(AclCommand.deleteToken(original.accessorId())));
        AclToken replacement = second.get("b").getAcl().tokenBySecret(SECOND_SECRET).orElseThrow();
        RaftCommandResult<?> deleteNew = await(next.submitCommand(AclCommand.deleteToken(replacement.accessorId())));

        assertInstanceOf(RaftCommandResult.Success.class, deleteOld, "the new token holds acl:write");
        RaftCommandResult.Refused<?> lockout = assertInstanceOf(RaftCommandResult.Refused.class, deleteNew);
        assertEquals(AclRefusal.ACL_LOCKOUT.code(), lockout.code(), "the last holder of acl:write cannot go");
        cluster.heartbeatUntil(next, () -> second.values().stream()
                .allMatch(store -> store.getAcl().tokenBySecret(FIRST_SECRET).isEmpty()), "the old token is gone");
        stopAll();

        Map<String, QraftStateStore> third = start(SECOND_SECRET);
        RaftNode last = cluster.elect(node("c"));
        await(last.submitCommand(AclCommand.deleteExpiredTokens(Instant.EPOCH, List.of())));
        cluster.heartbeatUntil(last, () -> third.values().stream()
                .allMatch(store -> store.getAcl().tokenBySecret(SECOND_SECRET).isPresent()),
                "every server has applied the log");
        assertTrue(third.values().stream().allMatch(store -> store.getAcl().tokenBySecret(FIRST_SECRET).isEmpty()),
                "no file holds the old secret, so it does not return");
    }

    private final Map<String, RaftNode> nodes = new LinkedHashMap<>();

    private Map<String, QraftStateStore> start(String secret) throws Exception {
        Map<String, QraftStateStore> stores = new LinkedHashMap<>();
        nodes.clear();
        for (String name : NAMES) {
            QraftStateStore store = new QraftStateStore();
            stores.put(name, store);
            RaftStorageFactory.DurableStorage storage = RaftStorageFactory.createDurable(tempDir.resolve(name), true)
                    .get(10, TimeUnit.SECONDS);
            RaftNode node = cluster.add(cluster.builder(name, java.util.Set.copyOf(NAMES),
                    new InMemoryTransportSimulatorFixture(name), store,
                    RaftNodeMode.durable(storage.wal(), storage.snapshots())).snapshotEnabled(false));
            nodes.put(name, node);
            installers.add(ManagementTokenInstaller.attach(node, store.getAcl(), secret, node::submitCommand, time,
                    time, Duration.ofSeconds(1)));
        }
        ManualRaftClusterFixture.startAll(nodes.values().toArray(RaftNode[]::new));
        return stores;
    }

    private RaftNode node(String name) {
        return nodes.get(name);
    }

    private void stopAll() throws Exception {
        installers.forEach(ManagementTokenInstaller::close);
        installers.clear();
        for (RaftNode node : nodes.values()) await(node.stop());
        InMemoryTransportSimulatorFixture.clearAllTransports();
    }
}

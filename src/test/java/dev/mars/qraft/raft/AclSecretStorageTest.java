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
import dev.mars.qraft.state.AclCommand;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.acl.AclBuiltins;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests on real storage that a token's secret never reaches the disk (ACL list, proposal 3): a server that bootstraps
 * and creates tokens, takes a snapshot, and creates another holds in its WAL and its snapshot the hash of each secret
 * and none of the secrets, and after a restart still finds each token by its secret.
 *
 * <p>The search reads every file the server's storage wrote. It finds each hash, which proves that it reads the
 * entries and the snapshot as written; neither store compresses them.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
class AclSecretStorageTest {
    private static final Instant CREATED = Instant.parse("2026-11-04T09:00:00Z");
    private static final String BOOTSTRAP_SECRET = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    private static final String SNAPSHOTTED_SECRET = "3f2504e1-4f89-41d3-9a0c-0305e82c3301";
    private static final String LOGGED_SECRET = "3f2504e2-4f89-41d3-9a0c-0305e82c3301";

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

    @Test
    void theWalAndTheSnapshotHoldEachSecretsHashAndNoSecret() throws Exception {
        Path storage = tempDir.resolve("acl-server");
        RaftNode original = durableNode(new QraftStateStore(), storage);
        await(original.start());
        cluster.elect(original);
        await(original.submitCommand(AclCommand.bootstrap(token("6ba7b810-9dad-41d1-80b4-00c04fd430c8",
                BOOTSTRAP_SECRET, AclBuiltins.GLOBAL_MANAGEMENT_ID), 0)));
        await(original.submitCommand(AclCommand.createToken(token("6ba7b811-9dad-41d1-80b4-00c04fd430c8",
                SNAPSHOTTED_SECRET))));
        await(original.takeSnapshot());
        await(original.submitCommand(AclCommand.createToken(token("6ba7b812-9dad-41d1-80b4-00c04fd430c8",
                LOGGED_SECRET))));
        await(original.stop());

        List<Path> files = filesUnder(storage);
        for (String secret : List.of(BOOTSTRAP_SECRET, SNAPSHOTTED_SECRET, LOGGED_SECRET)) {
            assertEquals(List.of(), filesHolding(files, secret), "files holding the secret " + secret);
            assertTrue(!filesHolding(files, AclSecrets.hash(secret)).isEmpty(),
                    "the search finds the hash of " + secret + " in " + files);
        }

        QraftStateStore recovered = new QraftStateStore();
        RaftNode restarted = durableNode(recovered, storage);
        await(restarted.start());

        for (String secret : List.of(BOOTSTRAP_SECRET, SNAPSHOTTED_SECRET, LOGGED_SECRET)) {
            assertTrue(recovered.getAcl().tokenBySecret(secret).isPresent(), "a token is found by " + secret);
        }
        assertEquals(1, recovered.getAcl().bootstrapIndex());
    }

    private RaftNode durableNode(QraftStateStore store, Path storage) throws Exception {
        RaftStorageFactory.DurableStorage durable = RaftStorageFactory.createDurable(storage, true)
                .get(10, TimeUnit.SECONDS);
        return cluster.add(cluster.builder("acl-server", Set.of("acl-server"),
                new InMemoryTransportSimulatorFixture("acl-server"), store,
                RaftNodeMode.durable(durable.wal(), durable.snapshots())));
    }

    private static AclToken token(String accessor, String secret, String... policies) {
        return new AclToken(accessor, AclSecrets.hash(secret), "", List.of(policies), List.of(), List.of(), List.of(),
                CREATED, null, false);
    }

    private static List<Path> filesUnder(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static List<Path> filesHolding(List<Path> files, String text) throws IOException {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        List<Path> holding = new ArrayList<>();
        for (Path file : files) {
            if (contains(Files.readAllBytes(file), needle)) holding.add(file);
        }
        return holding;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) return true;
        }
        return false;
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future) throws Exception {
        return ManualRaftClusterFixture.await(future);
    }
}

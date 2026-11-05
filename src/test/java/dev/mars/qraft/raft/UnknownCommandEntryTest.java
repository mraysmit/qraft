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

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.raft.grpc.RaftCommandMessage;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.testing.fault.IntentionalErrorFixture;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a server meeting a log entry whose command it does not know, as a newer server writes it during a rolling
 * upgrade, stops rather than skipping it: a follower refuses the append and writes nothing, and a server whose WAL
 * holds such an entry fails its recovery and leaves the WAL as it was. Before 2026-11-05 such an entry was read as a
 * no-op, so an older server would have gone on without the change every newer replica applied (ACL list, Phase 1's
 * open point, decided by the owner on 2026-11-05).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
class UnknownCommandEntryTest {
    private static final Set<String> MEMBERS = Set.of("node1", "leader");

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
    void aFollowerRefusesAnAppendHoldingACommandItDoesNotKnowAndWritesNothing() throws Exception {
        TestRaftStorageFixture storage = storageHolding(List.of(ManualRaftClusterFixture.bootstrapEntry(MEMBERS)));
        QraftStateStore state = new QraftStateStore();
        RaftNode follower = node(storage, state);
        ManualRaftClusterFixture.await(follower.start());
        String before = new String(state.takeSnapshot(), java.nio.charset.StandardCharsets.UTF_8);

        ExecutionException refused = assertThrows(ExecutionException.class, () -> ManualRaftClusterFixture.await(
                follower.handleAppendEntriesRequest(AppendEntriesRequest.newBuilder()
                        .setTerm(1).setLeaderId("leader").setPrevLogIndex(1).setPrevLogTerm(0)
                        .addEntries(dev.mars.qraft.raft.grpc.LogEntry.newBuilder().setTerm(1)
                                .setData(ByteString.copyFrom(fromANewerServer())))
                        .build())));

        IllegalArgumentException failure = assertInstanceOf(IllegalArgumentException.class, refused.getCause());
        assertEquals("Invalid command payload at log index 2", failure.getMessage());
        assertTrue(failure.getCause().getMessage().contains("field 99"), failure.getCause().getMessage());
        assertEquals(List.of(1L), storage.getLog().stream().map(LogEntryData::index).toList(), "nothing is written");
        assertEquals(1, follower.getLastLogIndex());
        assertEquals(before, new String(state.takeSnapshot(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void aServerWhoseWalHoldsACommandItDoesNotKnowFailsItsRecoveryAndLeavesTheWal() throws Exception {
        // The WAL's second entry is one a newer server wrote; this server cannot apply it, so it must not start. The
        // refusal is logged by both recovery log sites, as RaftNodeRealStorageRecoveryTest sees for a corrupt WAL.
        IntentionalErrorsHelper.expect(IntentionalErrorFixture.RAFT_RECOVERY_UNKNOWN_COMMAND, 2);
        LogEntryData unknown = new LogEntryData(2, 1, fromANewerServer());
        TestRaftStorageFixture storage = storageHolding(List.of(ManualRaftClusterFixture.bootstrapEntry(MEMBERS), unknown));
        RaftNode restarted = node(storage, new QraftStateStore());

        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> ManualRaftClusterFixture.await(restarted.start()));

        Throwable cause = failed.getCause();
        while (cause != null && !(cause instanceof IllegalArgumentException)) cause = cause.getCause();
        assertInstanceOf(IllegalArgumentException.class, cause, failed.toString());
        assertTrue(cause.getMessage().contains("field 99"), cause.getMessage());
        assertEquals(List.of(1L, 2L), storage.getLog().stream().map(LogEntryData::index).toList(),
                "the WAL is evidence, never rewritten by recovery");
    }

    private RaftNode node(TestRaftStorageFixture storage, QraftStateStore state) {
        return cluster.add(cluster.unconfiguredBuilder("node1", MEMBERS, new InMemoryTransportSimulatorFixture("node1"),
                state, RaftNodeMode.durable(storage, storage)).serverId("id-node1"));
    }

    private TestRaftStorageFixture storageHolding(List<LogEntryData> entries) throws Exception {
        TestRaftStorageFixture storage = new TestRaftStorageFixture();
        storage.open(tempDir).get(10, TimeUnit.SECONDS);
        storage.appendEntries(entries).get(10, TimeUnit.SECONDS);
        return storage;
    }

    /** An entry as a newer server writes it: a command in a field this server does not know. */
    private static byte[] fromANewerServer() {
        return RaftCommandMessage.newBuilder().setUnknownFields(UnknownFieldSet.newBuilder().addField(99,
                UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFromUtf8("a future command"))
                        .build()).build()).build().toByteArray();
    }
}

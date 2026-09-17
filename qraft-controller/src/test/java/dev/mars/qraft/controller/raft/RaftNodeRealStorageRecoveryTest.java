/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.mars.qraft.controller.raft;

import com.google.protobuf.ByteString;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.raft.storage.RaftStorageFactory;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.controller.testsupport.RemediationTest;
import dev.mars.qraft.controller.testsupport.RemediationTestExtension;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RemediationTest(phase = "6-real-storage", scenarioPrefix = "RAFT-REAL-RECOVERY")
class RaftNodeRealStorageRecoveryTest {
    private static final ProtobufRaftCommandCodec CODEC = new ProtobufRaftCommandCodec();

    @TempDir
    Path directory;

    private JavaRuntime runtime;
    private RaftNode node;

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) await(node.stop());
        if (runtime != null) {
            runtime.shutdown().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void restartAfterSuffixTruncationRecoversTheRetainedPrefix() throws Exception {
        verifyRecovery(RealStorageCrashWriter.Checkpoint.AFTER_TRUNCATE,
                List.of(1L, 2L), List.of(1L, 1L), 2, false);
    }

    @Test
    void restartAfterReplacementAppendRecoversTheWrittenReplacement() throws Exception {
        verifyRecovery(RealStorageCrashWriter.Checkpoint.AFTER_APPEND,
                List.of(1L, 2L, 3L), List.of(1L, 1L, 2L), 3, true);
    }

    @Test
    void restartAfterSyncBeforeResponseRecoversTheDurableReplacement() throws Exception {
        verifyRecovery(RealStorageCrashWriter.Checkpoint.AFTER_SYNC,
                List.of(1L, 2L, 3L), List.of(1L, 1L, 2L), 3, true);
    }

    @Test
    void higherTermAppendCompletesThroughTheRealWalExecutor() throws Exception {
        RaftStorageFactory.DurableStorage durable = await(
                RaftStorageFactory.createDurable(directory, true));
        durable.wal().updateMetadata(1, Optional.empty()).get(5, TimeUnit.SECONDS);
        runtime = JavaRuntime.create();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("node-1")
                .clusterNodes(Set.of("node-1", "leader-1"))
                .transport(new NoOpTransport())
                .stateMachine(new QraftStateStore())
                .commandCodec(CODEC)
                .mode(RaftNodeMode.durable(durable.wal(), durable.snapshots()))
                .snapshotEnabled(false)
                .electionTimeout(60_000)
                .heartbeatInterval(60_000)
                .build();
        await(node.start());

        AppendEntriesResponse response = await(node.handleAppendEntriesRequest(
                AppendEntriesRequest.newBuilder()
                        .setTerm(2)
                        .setLeaderId("leader-1")
                        .setPrevLogIndex(0)
                        .setPrevLogTerm(0)
                        .addEntries(dev.mars.qraft.controller.raft.grpc.LogEntry.newBuilder()
                                .setTerm(2)
                                .setData(ByteString.copyFrom(encodePut("real-wal", "ok")))
                                .build())
                        .build()));

        assertTrue(response.getSuccess());
        assertEquals(2, node.getCurrentTerm());
        assertEquals(1, node.getLastLogIndex());
        assertFalse(node.isFenced());
    }

    private void verifyRecovery(
            RealStorageCrashWriter.Checkpoint checkpoint,
            List<Long> expectedIndexes,
            List<Long> expectedTerms,
            long expectedLastApplied,
            boolean expectReplacement) throws Exception {
        seedWal();
        byte[] replacementPayload = encodePut("replacement", "new");
        RemediationTestExtension.logExpectedFailure(
                checkpoint.name(), "ProcessHalt", "fixture halts without closing the WAL");
        ProcessResult crash = runCrashWriter(checkpoint, replacementPayload);
        assertEquals(RealStorageCrashWriter.HALT_EXIT_CODE, crash.exitCode(), crash.output());

        try (FileRaftStorage reopened = storage()) {
            reopened.open(directory).get(5, TimeUnit.SECONDS);
            assertEquals(new RaftStorage.PersistentMeta(2, Optional.of("node-1")),
                    reopened.loadMetadata().get(5, TimeUnit.SECONDS));
            List<RaftStorage.LogEntryData> recovered = reopened.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(expectedIndexes, recovered.stream().map(RaftStorage.LogEntryData::index).toList());
            assertEquals(expectedTerms, recovered.stream().map(RaftStorage.LogEntryData::term).toList());
        }

        RaftStorageFactory.DurableStorage durable = await(
                RaftStorageFactory.createDurable(directory, true));
        runtime = JavaRuntime.create();
        QraftStateStore state = new QraftStateStore();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("node-1")
                .clusterNodes(Set.of("node-1"))
                .transport(new NoOpTransport())
                .stateMachine(state)
                .commandCodec(CODEC)
                .mode(RaftNodeMode.durable(durable.wal(), durable.snapshots()))
                .snapshotEnabled(false)
                .electionTimeout(10_000)
                .heartbeatInterval(10_000)
                .build();

        await(node.start());

        assertTrue(node.isRunning());
        assertEquals(2, node.getCurrentTerm());
        assertEquals(0, node.getSnapshotLastIndex());
        assertEquals(expectedLastApplied, node.getLastApplied());
        assertEquals("one", state.getMetadata("retained-1"));
        assertEquals("two", state.getMetadata("retained-2"));
        assertEquals(expectReplacement, "new".equals(state.getMetadata("replacement")));
        assertFalse("old".equals(state.getMetadata("obsolete")),
                "the truncated suffix must never be resurrected");
    }

    private void seedWal() throws Exception {
        try (FileRaftStorage wal = storage()) {
            wal.open(directory).get(5, TimeUnit.SECONDS);
            wal.updateMetadata(2, Optional.of("node-1")).get(5, TimeUnit.SECONDS);
            wal.appendEntries(List.of(
                    entry(1, 1, "retained-1", "one"),
                    entry(2, 1, "retained-2", "two"),
                    entry(3, 1, "obsolete", "old"))).get(5, TimeUnit.SECONDS);
            wal.sync().get(5, TimeUnit.SECONDS);
        }
    }

    private ProcessResult runCrashWriter(
            RealStorageCrashWriter.Checkpoint checkpoint,
            byte[] replacementPayload) throws Exception {
        String executable = System.getProperty("os.name", "").startsWith("Windows")
                ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classPath = System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
        Path outputFile = directory.resolve("crash-writer.log");
        Process process = new ProcessBuilder(
                java.toString(),
                "-cp", classPath,
                RealStorageCrashWriter.class.getName(),
                directory.toString(),
                checkpoint.name(),
                Base64.getEncoder().encodeToString(replacementPayload))
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
        boolean exited = process.waitFor(10, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("crash writer did not reach " + checkpoint + ":\n"
                    + Files.readString(outputFile, StandardCharsets.UTF_8));
        }
        String output = Files.readString(outputFile, StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    private FileRaftStorage storage() {
        return new FileRaftStorage(RaftStorageConfig.builder()
                .dataDir(directory)
                .syncEnabled(true)
                .build());
    }

    private static RaftStorage.LogEntryData entry(
            long index, long term, String key, String value) {
        return new RaftStorage.LogEntryData(index, term, encodePut(key, value));
    }

    private static byte[] encodePut(String key, String value) {
        return CODEC.serialize(new DistributedStateRaftCommand(
                DistributedStateCommand.put(key, value)));
    }

    private static <T> T await(Future<T> future) {
        return future.timeout(5, TimeUnit.SECONDS).toCompletionStage().toCompletableFuture().join();
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private static final class NoOpTransport implements RaftTransport {
        @Override public void start(Consumer<RaftMessage> messageHandler) { }
        @Override public void stop() { }
        @Override public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
            return Future.failedFuture("unexpected vote request");
        }
        @Override public Future<AppendEntriesResponse> sendAppendEntries(
                String targetId, AppendEntriesRequest request) {
            return Future.failedFuture("unexpected append request");
        }
        @Override public Future<InstallSnapshotResponse> sendInstallSnapshot(
                String targetId, InstallSnapshotRequest request) {
            return Future.failedFuture("unexpected snapshot request");
        }
    }
}

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

package dev.mars.qraft.controller.raft.storage;

import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.raftlog.storage.RaftStorage;
import dev.mars.raftlog.storage.RaftStorageConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.zip.CRC32C;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Qraft's integration contract for the external WAL implementation it ships.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-12
 * @version 1.0
 */
class RaftLogStorageIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void metadataConflictReplacementAndCompactionSurviveReopen() throws Exception {
        try (FileRaftStorage wal = storage(directory)) {
            wal.open(directory).join();
            wal.updateMetadata(7, Optional.of("node-2")).join();
            wal.appendEntries(List.of(entry(1, 1, "one"), entry(2, 1, "two"),
                    entry(3, 1, "obsolete"))).join();
            wal.sync().join();
            wal.truncateSuffix(3).join();
            wal.appendEntries(List.of(entry(3, 2, "replacement"))).join();
            wal.sync().join();
            wal.truncatePrefix(1).join();
        }

        try (FileRaftStorage reopened = storage(directory)) {
            reopened.open(directory).join();
            assertEquals(new RaftStorage.PersistentMeta(7, Optional.of("node-2")),
                    reopened.loadMetadata().join());
            List<RaftStorage.LogEntryData> replayed = reopened.replayLog().join();
            assertEquals(List.of(2L, 3L), replayed.stream().map(RaftStorage.LogEntryData::index).toList());
            assertEquals(List.of(1L, 2L), replayed.stream().map(RaftStorage.LogEntryData::term).toList());
            assertArrayEquals("replacement".getBytes(UTF_8), replayed.getLast().payload());
        }
    }

    @Test
    void secondWriterCannotOpenTheSameDirectory() {
        try (FileRaftStorage owner = storage(directory); FileRaftStorage contender = storage(directory)) {
            owner.open(directory).join();
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> contender.open(directory).join());
            assertInstanceOf(FileRaftStorage.StorageException.class, failure.getCause());
        }
    }

    @Test
    void completeRecordCorruptionFailsReplayInsteadOfDiscardingAcknowledgedData() throws Exception {
        try (FileRaftStorage wal = storage(directory)) {
            wal.open(directory).join();
            wal.appendEntries(List.of(entry(1, 1, "durable"))).join();
            wal.sync().join();
        }

        Path log = directory.resolve("raft.log");
        byte[] bytes = Files.readAllBytes(log);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(log, bytes);

        try (FileRaftStorage reopened = storage(directory)) {
            reopened.open(directory).join();
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> reopened.replayLog().join());
            assertInstanceOf(FileRaftStorage.CorruptLogException.class, failure.getCause());
        }
    }

    @Test
    void disablingDurabilityIsRejectedAtConfigurationBoundary() {
        assertThrows(IllegalArgumentException.class,
                () -> RaftStorageConfig.builder().dataDir(directory).syncEnabled(false).build());
    }

    @Test
    void readsTheLegacyQraftWalAndMetadataFormat() throws Exception {
        Files.write(directory.resolve("meta.dat"), legacyMetadata(9, "old-node"));
        Files.write(directory.resolve("raft.log"), concatenate(
                legacyRecord((byte) 2, 1, 1, "one".getBytes(UTF_8)),
                legacyRecord((byte) 2, 2, 1, "obsolete".getBytes(UTF_8)),
                legacyRecord((byte) 1, 2, 0, new byte[0]),
                legacyRecord((byte) 2, 2, 2, "replacement".getBytes(UTF_8))));

        try (FileRaftStorage wal = storage(directory)) {
            wal.open(directory).join();
            assertEquals(new RaftStorage.PersistentMeta(9, Optional.of("old-node")),
                    wal.loadMetadata().join());
            List<RaftStorage.LogEntryData> entries = wal.replayLog().join();
            assertEquals(List.of(1L, 2L), entries.stream().map(RaftStorage.LogEntryData::index).toList());
            assertEquals(List.of(1L, 2L), entries.stream().map(RaftStorage.LogEntryData::term).toList());
            assertArrayEquals("replacement".getBytes(UTF_8), entries.getLast().payload());
        }
    }

    private static FileRaftStorage storage(Path directory) {
        return new FileRaftStorage(RaftStorageConfig.builder()
                .dataDir(directory)
                .syncEnabled(true)
                .build());
    }

    private static RaftStorage.LogEntryData entry(long index, long term, String payload) {
        return new RaftStorage.LogEntryData(index, term, payload.getBytes(UTF_8));
    }

    private static byte[] legacyMetadata(long term, String candidate) {
        byte[] vote = candidate.getBytes(UTF_8);
        ByteBuffer bytes = ByteBuffer.allocate(Long.BYTES + Integer.BYTES + vote.length + Integer.BYTES);
        bytes.putLong(term).putInt(vote.length).put(vote);
        appendCrc(bytes);
        return bytes.array();
    }

    private static byte[] legacyRecord(byte type, long index, long term, byte[] payload) {
        ByteBuffer bytes = ByteBuffer.allocate(4 + 2 + 1 + 8 + 8 + 4 + payload.length + 4);
        bytes.putInt(0x52414654).putShort((short) 1).put(type)
                .putLong(index).putLong(term).putInt(payload.length).put(payload);
        appendCrc(bytes);
        return bytes.array();
    }

    private static void appendCrc(ByteBuffer bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes.array(), 0, bytes.position());
        bytes.putInt((int) crc.getValue());
    }

    private static byte[] concatenate(byte[]... parts) {
        int size = java.util.Arrays.stream(parts).mapToInt(part -> part.length).sum();
        ByteBuffer result = ByteBuffer.allocate(size);
        for (byte[] part : parts) result.put(part);
        return result.array();
    }
}

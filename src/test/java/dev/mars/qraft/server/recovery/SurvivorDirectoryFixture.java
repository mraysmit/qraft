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

import dev.mars.qraft.raft.ConfigurationCommand;
import dev.mars.qraft.raft.ManualRaftClusterFixture;
import dev.mars.qraft.raft.storage.RaftStorageFactory;
import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.serverIdOf;

/**
 * Test fixture that writes a stopped server's data directory through the real WAL and snapshot storage, as the
 * source of an offline recovery: its {@code server-id} file, its log, and its persistent term and vote. It also
 * digests a directory, so a test can prove recovery left a source byte for byte as it was.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-22
 * @version 1.0
 */
public final class SurvivorDirectoryFixture {
    public static final Set<String> MEMBERS = Set.of("a", "b", "c");
    public static final ProtobufRaftCommandCodec CODEC = new ProtobufRaftCommandCodec();

    private SurvivorDirectoryFixture() { }

    /**
     * Member a of {a, b, c} after its peers were lost: the bootstrap entry, two writes in term 1 (k1 and k2), and
     * term 1 with its vote for itself.
     */
    public static Path survivor(Path directory) throws Exception {
        write(directory, serverIdOf("a"), 1, Optional.of("a"), List.of(
                ManualRaftClusterFixture.bootstrapEntry(MEMBERS), put(2, 1, "k1", "v1"), put(3, 1, "k2", "v2")));
        return directory;
    }

    /** Writes {@code entries} and the metadata through real storage, and {@code serverId} unless it is null. */
    public static void write(Path directory, String serverId, long term, Optional<String> vote,
                      List<LogEntryData> entries) throws Exception {
        Files.createDirectories(directory);
        if (serverId != null) Files.writeString(directory.resolve("server-id"), serverId);
        RaftStorageFactory.DurableStorage storage = RaftStorageFactory.createDurable(directory, true)
                .get(10, TimeUnit.SECONDS);
        try {
            if (!entries.isEmpty()) storage.wal().appendEntries(entries).get(10, TimeUnit.SECONDS);
            storage.wal().sync().get(10, TimeUnit.SECONDS);
            storage.wal().updateMetadata(term, vote).get(10, TimeUnit.SECONDS);
        } finally {
            storage.snapshots().closeAsync().get(10, TimeUnit.SECONDS);
            storage.wal().closeAsync().get(10, TimeUnit.SECONDS);
        }
    }

    public static LogEntryData put(long index, long term, String key, String value) {
        return new LogEntryData(index, term,
                CODEC.serialize(new DistributedStateRaftCommand(DistributedStateCommand.put(key, value))));
    }

    /** A configuration entry making {@code members} the voters, with the server IDs the cluster fixture gives. */
    public static LogEntryData configuration(long index, long term, Set<String> members) {
        return new LogEntryData(index, term,
                CODEC.serialize(new ConfigurationCommand(ManualRaftClusterFixture.configurationOf(members))));
    }

    /**
     * The SHA-256 of every file under {@code directory}, by relative path; empty if it does not exist. RaftLog's
     * {@code raft.lock} holds no data and, on Windows, cannot be read through another handle while a lock is
     * held, so only its size is recorded.
     */
    public static Map<String, String> digest(Path directory) throws IOException {
        Map<String, String> digests = new TreeMap<>();
        if (!Files.exists(directory)) return digests;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (!Files.isRegularFile(path)) continue;
                String relative = directory.relativize(path).toString();
                digests.put(relative, path.getFileName().toString().equals("raft.lock")
                        ? "size " + Files.size(path)
                        : sha256(Files.readAllBytes(path)));
            }
        }
        return digests;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }
}

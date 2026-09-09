package dev.mars.qraft.controller.raft.storage;

import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FileRaftStorageSnapshotTest {

    @TempDir
    Path tempDir;

    private JavaRuntime runtime;
    private RaftStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        runtime = JavaRuntime.create();
        storage = await(RaftStorageFactory.create(runtime, "file", tempDir, false));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (storage != null) await(storage.close());
        if (runtime != null) await(runtime.close());
    }

    @Test
    void snapshotRoundTripAndPrefixTruncation() throws Exception {
        assertTrue(await(storage.loadSnapshot()).isEmpty());
        await(storage.appendEntries(List.of(
                new RaftStorage.LogEntryData(1, 1, "a".getBytes()),
                new RaftStorage.LogEntryData(2, 1, "b".getBytes()),
                new RaftStorage.LogEntryData(3, 2, "c".getBytes()))));

        byte[] data = "state".getBytes();
        await(storage.saveSnapshot(data, 2, 1));
        RaftStorage.SnapshotData snapshot = await(storage.loadSnapshot()).orElseThrow();
        assertArrayEquals(data, snapshot.data());
        assertEquals(2, snapshot.lastIncludedIndex());
        assertEquals(1, snapshot.lastIncludedTerm());

        await(storage.truncatePrefix(2));
        assertEquals(List.of(3L), await(storage.replayLog()).stream()
                .map(RaftStorage.LogEntryData::index).toList());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}

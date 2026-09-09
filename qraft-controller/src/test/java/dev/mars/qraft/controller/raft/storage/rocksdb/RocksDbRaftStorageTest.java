package dev.mars.qraft.controller.raft.storage.rocksdb;

import dev.mars.qraft.controller.raft.storage.RaftStorage;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.runtime.WorkerExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbRaftStorageTest {

    @TempDir
    Path tempDir;

    private JavaRuntime runtime;
    private WorkerExecutor executor;
    private RocksDbRaftStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        runtime = JavaRuntime.create();
        executor = runtime.createSharedWorkerExecutor("rocks-test", 1);
        storage = new RocksDbRaftStorage(runtime, executor, tempDir, false);
        await(storage.open());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (storage != null) await(storage.close());
        if (executor != null) executor.close();
        if (runtime != null) await(runtime.close());
    }

    @Test
    void persistsMetadataLogAndSnapshot() throws Exception {
        assertEquals(new RaftStorage.PersistentMeta(0, Optional.empty()), await(storage.loadMetadata()));

        await(storage.updateMetadata(7, Optional.of("node-2")));
        assertEquals(new RaftStorage.PersistentMeta(7, Optional.of("node-2")), await(storage.loadMetadata()));
        await(storage.updateMetadata(8, Optional.empty()));
        assertEquals(new RaftStorage.PersistentMeta(8, Optional.empty()), await(storage.loadMetadata()));

        await(storage.appendEntries(List.of(
                new RaftStorage.LogEntryData(1, 3, "one".getBytes()),
                new RaftStorage.LogEntryData(2, 3, null),
                new RaftStorage.LogEntryData(3, 4, "three".getBytes()))));
        await(storage.appendEntries(List.of()));
        await(storage.sync());

        List<RaftStorage.LogEntryData> entries = await(storage.replayLog());
        assertEquals(List.of(1L, 2L, 3L), entries.stream().map(RaftStorage.LogEntryData::index).toList());
        assertArrayEquals(new byte[0], entries.get(1).payload());

        await(storage.truncateSuffix(3));
        assertEquals(List.of(1L, 2L), await(storage.replayLog()).stream()
                .map(RaftStorage.LogEntryData::index).toList());

        byte[] snapshot = "snapshot".getBytes();
        await(storage.saveSnapshot(snapshot, 2, 3));
        RaftStorage.SnapshotData loaded = await(storage.loadSnapshot()).orElseThrow();
        assertArrayEquals(snapshot, loaded.data());
        assertEquals(2, loaded.lastIncludedIndex());
        assertEquals(3, loaded.lastIncludedTerm());

        await(storage.truncatePrefix(1));
        assertEquals(List.of(2L), await(storage.replayLog()).stream()
                .map(RaftStorage.LogEntryData::index).toList());
    }

    @Test
    void supportsConstructorPathAndReopen() throws Exception {
        await(storage.updateMetadata(8, Optional.of("node-8")));
        await(storage.close());
        storage = new RocksDbRaftStorage(runtime, executor);
        ExecutionException missingPath = assertThrows(ExecutionException.class,
                () -> await(storage.open()));
        assertInstanceOf(IllegalStateException.class, missingPath.getCause());
        assertTrue(missingPath.getCause().getMessage().contains("dataDir"));

        await(storage.open(tempDir));
        assertEquals(8, await(storage.loadMetadata()).currentTerm());
        await(storage.close());
        await(storage.close());
    }

    private static <T> T await(dev.mars.qraft.controller.runtime.Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}

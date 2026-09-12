package dev.mars.qraft.controller.raft.storage.snapshot;

import dev.mars.qraft.raft.api.SnapshotStore.SnapshotData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSnapshotStoreTest {

    @TempDir
    Path directory;

    @Test
    void snapshotSurvivesCloseAndReopen() throws Exception {
        byte[] state = "catalog-state".getBytes(StandardCharsets.UTF_8);

        try (FileSnapshotStore store = new FileSnapshotStore()) {
            store.open(directory).get(10, TimeUnit.SECONDS);
            assertTrue(store.loadLatest().get(10, TimeUnit.SECONDS).isEmpty());
            store.saveAtomically(new SnapshotData(state, 42, 7)).get(10, TimeUnit.SECONDS);
        }

        try (FileSnapshotStore reopened = new FileSnapshotStore()) {
            reopened.open(directory).get(10, TimeUnit.SECONDS);
            SnapshotData snapshot = reopened.loadLatest().get(10, TimeUnit.SECONDS).orElseThrow();
            assertArrayEquals(state, snapshot.data());
            assertEquals(42, snapshot.lastIncludedIndex());
            assertEquals(7, snapshot.lastIncludedTerm());
        }
    }
}

package dev.mars.qraft.controller.raft.storage.snapshot;

import dev.mars.qraft.raft.api.SnapshotStore.SnapshotData;
import dev.mars.qraft.raft.api.SnapshotStore.PublicationOutcome;
import dev.mars.qraft.raft.api.SnapshotStore.SnapshotPublicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void failureBeforeAtomicMoveReportsNotPublished() throws Exception {
        try (FileSnapshotStore store = new FileSnapshotStore(checkpoint -> {
            if (checkpoint == FileSnapshotStore.PersistenceCheckpoint.AFTER_TEMPORARY_FORCE) {
                throw new IllegalStateException("fail before move");
            }
        })) {
            store.open(directory).get(10, TimeUnit.SECONDS);

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> store.saveAtomically(snapshot()).get(10, TimeUnit.SECONDS));
            SnapshotPublicationException publicationFailure = assertInstanceOf(
                    SnapshotPublicationException.class, failure.getCause());

            assertEquals(PublicationOutcome.NOT_PUBLISHED, publicationFailure.outcome());
        }
    }

    @Test
    void failureAfterAtomicMoveReportsPublicationMayHaveOccurred() throws Exception {
        try (FileSnapshotStore store = new FileSnapshotStore(checkpoint -> {
            if (checkpoint == FileSnapshotStore.PersistenceCheckpoint.AFTER_ATOMIC_PUBLICATION) {
                throw new IllegalStateException("fail after move");
            }
        })) {
            store.open(directory).get(10, TimeUnit.SECONDS);

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> store.saveAtomically(snapshot()).get(10, TimeUnit.SECONDS));
            SnapshotPublicationException publicationFailure = assertInstanceOf(
                    SnapshotPublicationException.class, failure.getCause());

            assertEquals(PublicationOutcome.PUBLICATION_MAY_HAVE_OCCURRED,
                    publicationFailure.outcome());
            assertTrue(store.loadLatest().get(10, TimeUnit.SECONDS).isPresent());
        }
    }

    @Test
    void unpublishedFirstSnapshotTemporaryFileFencesOpenAndIsPreserved() throws Exception {
        Path temporary = directory.resolve("snapshot.dat.tmp");
        byte[] evidence = "incomplete-first-snapshot".getBytes(StandardCharsets.UTF_8);
        Files.write(temporary, evidence);

        try (FileSnapshotStore store = new FileSnapshotStore()) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> store.open(directory).get(10, TimeUnit.SECONDS));
            String diagnostic = failure.getCause().getMessage();
            assertTrue(diagnostic.contains(temporary.toString()), diagnostic);
            assertTrue(diagnostic.contains("unpublished first snapshot"), diagnostic);
        }
        assertArrayEquals(evidence, Files.readAllBytes(temporary),
                "startup fencing must preserve the temporary file for diagnosis");
    }

    private static SnapshotData snapshot() {
        return new SnapshotData("state".getBytes(StandardCharsets.UTF_8), 1, 1);
    }
}

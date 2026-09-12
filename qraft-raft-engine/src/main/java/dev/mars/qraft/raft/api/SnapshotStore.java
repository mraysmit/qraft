package dev.mars.qraft.raft.api;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Durable storage for application-owned Raft snapshots.
 *
 * <p>The WAL does not own application snapshots. A caller must successfully
 * publish a snapshot through this interface before compacting the covered WAL
 * prefix.</p>
 */
public interface SnapshotStore extends AutoCloseable {

    int CURRENT_FORMAT_VERSION = 1;

    CompletableFuture<Void> open(Path directory);

    CompletableFuture<Void> saveAtomically(SnapshotData snapshot);

    CompletableFuture<Optional<SnapshotData>> loadLatest();

    @Override
    void close();

    /** Complete application snapshot and its Raft boundary. */
    record SnapshotData(byte[] data, long lastIncludedIndex, long lastIncludedTerm, int formatVersion) {
        public SnapshotData(byte[] data, long lastIncludedIndex, long lastIncludedTerm) {
            this(data, lastIncludedIndex, lastIncludedTerm, CURRENT_FORMAT_VERSION);
        }

        public SnapshotData {
            Objects.requireNonNull(data, "data");
            if (data.length == 0) throw new IllegalArgumentException("snapshot data must not be empty");
            if (lastIncludedIndex < 0) throw new IllegalArgumentException("lastIncludedIndex must be non-negative");
            if (lastIncludedTerm < 0) throw new IllegalArgumentException("lastIncludedTerm must be non-negative");
            if (formatVersion < 1) throw new IllegalArgumentException("formatVersion must be positive");
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }
}

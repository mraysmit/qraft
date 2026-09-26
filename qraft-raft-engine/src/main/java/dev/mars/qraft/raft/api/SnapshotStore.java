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
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-12
 * @version 1.0
 */
public interface SnapshotStore extends AutoCloseable {

    int CURRENT_FORMAT_VERSION = 1;

    CompletableFuture<Void> open(Path directory);

    /**
     * Publishes a replacement snapshot atomically.
     *
     * <p>Failures must complete with {@link SnapshotPublicationException} so callers can
     * distinguish a snapshot that is definitely unpublished from one whose publication
     * may have occurred.</p>
     */
    CompletableFuture<Void> saveAtomically(SnapshotData snapshot);

    CompletableFuture<Optional<SnapshotData>> loadLatest();

    /**
     * Closes the store and completes after its resources have been released.
     * Implementations with synchronous close semantics may use this default.
     */
    default CompletableFuture<Void> closeAsync() {
        try {
            close();
            return CompletableFuture.completedFuture(null);
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    void close();

    /** Whether a failed atomic save can have made the replacement snapshot visible. */
    enum PublicationOutcome {
        NOT_PUBLISHED,
        PUBLICATION_MAY_HAVE_OCCURRED
    }

    /**
     * Failure from {@link #saveAtomically(SnapshotData)} with an explicit
     * publication outcome for callers that must decide whether continuing is safe.
     */
    final class SnapshotPublicationException extends RuntimeException {
        private final PublicationOutcome outcome;

        public SnapshotPublicationException(
                PublicationOutcome outcome, String message, Throwable cause) {
            super(message, cause);
            this.outcome = Objects.requireNonNull(outcome, "outcome");
        }

        public PublicationOutcome outcome() {
            return outcome;
        }
    }

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

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

import dev.mars.qraft.raft.api.SnapshotStore.PublicationOutcome;
import dev.mars.qraft.raft.api.SnapshotStore.SnapshotData;
import dev.mars.qraft.raft.api.SnapshotStore.SnapshotPublicationException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the parts of {@link SnapshotStore} that the interface itself defines: the default {@code closeAsync}
 * closes the store once and reports a failed close through the returned future rather than by throwing, and a
 * {@link SnapshotPublicationException} requires and reports its publication outcome.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-28
 * @version 1.0
 */
class SnapshotStoreContractTest {

    @Test
    void closeAsyncClosesTheStoreOnceAndCompletes() {
        ClosingStore store = new ClosingStore(null);

        CompletableFuture<Void> closed = store.closeAsync();

        assertTrue(closed.isDone() && !closed.isCompletedExceptionally(), "a clean close completes normally");
        assertNull(closed.join());
        assertEquals(1, store.closes.get());
    }

    @Test
    void aFailedCloseFailsTheReturnedFutureInsteadOfThrowing() {
        IllegalStateException failure = new IllegalStateException("disk gone");
        ClosingStore store = new ClosingStore(failure);

        CompletableFuture<Void> closed = store.closeAsync();

        assertEquals(1, store.closes.get());
        CompletionException error = assertThrows(CompletionException.class, closed::join);
        assertSame(failure, error.getCause(), "the close failure itself is reported");
    }

    @Test
    void anErrorFromCloseAlsoFailsTheReturnedFuture() {
        AssertionError failure = new AssertionError("fatal");
        ClosingStore store = new ClosingStore(failure);

        CompletionException error = assertThrows(CompletionException.class, () -> store.closeAsync().join());

        assertSame(failure, error.getCause());
    }

    @Test
    void aPublicationFailureReportsItsOutcomeMessageAndCause() {
        Throwable cause = new RuntimeException("rename failed");

        for (PublicationOutcome outcome : PublicationOutcome.values()) {
            SnapshotPublicationException error = new SnapshotPublicationException(outcome, "publish failed", cause);

            assertEquals(outcome, error.outcome());
            assertEquals("publish failed", error.getMessage());
            assertSame(cause, error.getCause());
        }
    }

    @Test
    void aPublicationFailureRequiresAnOutcome() {
        NullPointerException missing = assertThrows(NullPointerException.class,
                () -> new SnapshotPublicationException(null, "publish failed", null));

        assertEquals("outcome", missing.getMessage());
    }

    /** A store whose only behaviour is counting closes, optionally throwing {@code failure} from each. */
    private static final class ClosingStore implements SnapshotStore {
        private final Throwable failure;
        private final AtomicInteger closes = new AtomicInteger();

        private ClosingStore(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }

        @Override
        public CompletableFuture<Void> open(Path directory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> saveAtomically(SnapshotData snapshot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Optional<SnapshotData>> loadLatest() {
            throw new UnsupportedOperationException();
        }
    }
}

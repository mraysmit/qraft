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

package dev.mars.qraft.raft;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests how {@link SharedDockerClusterFixture} treats a failed build of the runtime image. The Docker daemon's
 * cache can name a layer whose snapshot it has already removed and fail the build; that failure, and only that
 * one, is tried again once. Any other failure, and the same one twice, reach the test unchanged.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
class SharedDockerClusterImageBuildTest {
    /** The daemon's failure as Jenkins build 35 reported it, shortened. */
    private static final String MISSING_SNAPSHOT = "Docker image build failed with exit code: 1; its last output was:\n"
            + "#13 ERROR: failed to prepare extraction snapshot \"extract-947068684-C-AJ sha256:23cb95\": parent"
            + " snapshot sha256:e465d1 does not exist: not found\n"
            + "failed to solve: failed to prepare extraction snapshot";

    private final AtomicInteger attempts = new AtomicInteger();

    @Test
    void aBuildThatSucceedsIsRunOnce() {
        assertDoesNotThrow(() -> SharedDockerClusterFixture.buildImageRetryingAMissingSnapshot(attempts::incrementAndGet));

        assertEquals(1, attempts.get());
    }

    @Test
    void aMissingSnapshotIsTriedAgainOnceAndTheSecondAttemptStands() {
        assertDoesNotThrow(() -> SharedDockerClusterFixture.buildImageRetryingAMissingSnapshot(() -> {
            if (attempts.incrementAndGet() == 1) throw new RuntimeException(MISSING_SNAPSHOT);
        }));

        assertEquals(2, attempts.get());
    }

    @Test
    void aMissingSnapshotTwiceIsThrownWithTheFirstFailureKept() {
        RuntimeException first = new RuntimeException(MISSING_SNAPSHOT);
        RuntimeException second = new RuntimeException(MISSING_SNAPSHOT + "\nagain");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> SharedDockerClusterFixture.buildImageRetryingAMissingSnapshot(() -> {
                    throw attempts.incrementAndGet() == 1 ? first : second;
                }));

        assertEquals(2, attempts.get(), "tried again once and no more");
        assertSame(second, thrown, "the failure that remains is the last one");
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(first, thrown.getSuppressed()[0], "and the first is not lost");
    }

    @Test
    void anyOtherBuildFailureIsThrownAtOnceAndNotTriedAgain() {
        RuntimeException other = new RuntimeException("Docker image build failed with exit code: 1;"
                + " its last output was:\npull access denied for sapmachine, repository does not exist");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> SharedDockerClusterFixture.buildImageRetryingAMissingSnapshot(() -> {
                    attempts.incrementAndGet();
                    throw other;
                }));

        assertEquals(1, attempts.get());
        assertSame(other, thrown);
    }

    @Test
    void aMessageThatOnlyMentionsASnapshotIsNotTheMissingSnapshotFailure() {
        RuntimeException other = new RuntimeException("Docker image build failed with exit code: 1;"
                + " its last output was:\nfailed to prepare extraction snapshot: no space left on device");

        assertThrows(RuntimeException.class,
                () -> SharedDockerClusterFixture.buildImageRetryingAMissingSnapshot(() -> {
                    attempts.incrementAndGet();
                    throw other;
                }));

        assertEquals(1, attempts.get(), "only a parent snapshot that does not exist is retried");
    }
}

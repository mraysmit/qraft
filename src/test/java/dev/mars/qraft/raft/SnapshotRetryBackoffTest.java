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

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link SnapshotRetryBackoff}: the delay before a leader tries again to send a snapshot to a peer grows
 * with each consecutive failure to that peer, up to a cap, and starts over after a success. Time is injected, so
 * every boundary is exact.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
class SnapshotRetryBackoffTest {
    private static final long MILLIS = TimeUnit.MILLISECONDS.toNanos(1);

    private final AtomicLong now = new AtomicLong();
    private final SnapshotRetryBackoff backoff = new SnapshotRetryBackoff(250 * MILLIS, 5_000 * MILLIS, now::get);

    @Test
    void aPeerThatHasNotFailedIsNotInBackoff() {
        assertFalse(backoff.inBackoff("peer-1"));
    }

    @Test
    void theFirstFailureBacksOffForTheBaseDelayAndNotOneNanosecondLonger() {
        assertEquals(250 * MILLIS, backoff.failed("peer-1"));

        assertTrue(backoff.inBackoff("peer-1"));
        now.addAndGet(250 * MILLIS - 1);
        assertTrue(backoff.inBackoff("peer-1"), "one nanosecond before the delay has passed");
        now.addAndGet(1);
        assertFalse(backoff.inBackoff("peer-1"), "exactly when the delay has passed");
    }

    @Test
    void eachConsecutiveFailureDoublesTheDelayUpToTheCap() {
        long[] expectedMillis = {250, 500, 1_000, 2_000, 4_000, 5_000, 5_000, 5_000};
        for (int failure = 0; failure < expectedMillis.length; failure++) {
            assertEquals(expectedMillis[failure] * MILLIS, backoff.failed("peer-1"), "failure " + (failure + 1));
        }
    }

    @Test
    void theDelayOfALaterFailureIsMeasuredFromThatFailure() {
        backoff.failed("peer-1");
        now.addAndGet(250 * MILLIS);
        assertFalse(backoff.inBackoff("peer-1"));

        backoff.failed("peer-1");
        now.addAndGet(500 * MILLIS - 1);
        assertTrue(backoff.inBackoff("peer-1"), "the second delay is 500 ms from the second failure");
        now.addAndGet(1);
        assertFalse(backoff.inBackoff("peer-1"));
    }

    @Test
    void aSuccessStartsTheDelayAgainFromTheBase() {
        backoff.failed("peer-1");
        backoff.failed("peer-1");
        backoff.failed("peer-1");

        backoff.succeeded("peer-1");

        assertFalse(backoff.inBackoff("peer-1"), "a delivered snapshot ends the backoff");
        assertEquals(250 * MILLIS, backoff.failed("peer-1"), "and the next failure starts from the base delay");
    }

    @Test
    void theFailuresOfOnePeerDoNotDelayAnother() {
        backoff.failed("peer-1");
        backoff.failed("peer-1");

        assertFalse(backoff.inBackoff("peer-2"));
        assertEquals(250 * MILLIS, backoff.failed("peer-2"));
        backoff.succeeded("peer-2");
        assertTrue(backoff.inBackoff("peer-1"), "a success for peer-2 leaves peer-1 in backoff");
    }

    @Test
    void aPeerNoLongerConfiguredIsForgottenSoANewPeerUnderItsNameStartsFromTheBase() {
        backoff.failed("peer-1");
        backoff.failed("peer-1");
        backoff.failed("peer-2");

        backoff.retainOnly(Set.of("peer-2"));

        assertFalse(backoff.inBackoff("peer-1"), "a removed peer's backoff is forgotten");
        assertEquals(250 * MILLIS, backoff.failed("peer-1"), "a new peer under that name starts from the base delay");
        assertTrue(backoff.inBackoff("peer-2"), "a peer still configured keeps its backoff");
    }

    @Test
    void clearingForgetsEveryPeerSoANewLeadershipStartsFresh() {
        backoff.failed("peer-1");
        backoff.failed("peer-1");
        backoff.failed("peer-2");

        backoff.clear();

        assertFalse(backoff.inBackoff("peer-1"));
        assertFalse(backoff.inBackoff("peer-2"));
        assertEquals(250 * MILLIS, backoff.failed("peer-1"));
    }

    @Test
    void anEndlessRunOfFailuresStaysAtTheCap() {
        long last = 0;
        for (int failure = 0; failure < 100_000; failure++) {
            last = backoff.failed("peer-1");
        }
        assertEquals(5_000 * MILLIS, last);
    }

    @Test
    void theBoundsAndTheClockAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> new SnapshotRetryBackoff(0, 5_000 * MILLIS, now::get));
        assertThrows(IllegalArgumentException.class,
                () -> new SnapshotRetryBackoff(250 * MILLIS, 249 * MILLIS, now::get));
        assertThrows(NullPointerException.class, () -> new SnapshotRetryBackoff(250 * MILLIS, 5_000 * MILLIS, null));
    }
}

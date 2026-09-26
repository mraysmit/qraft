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

package dev.mars.qraft.agent.health;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HealthPublisher} sequencing, renewal, coalescing, retry identity, stale recovery, and
 * stop ordering, driven by {@link ManualTime} and a scripted {@link ObservationClient}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
class HealthPublisherTest {
    private static final Instant START = Instant.parse("2026-09-26T12:00:00Z");
    private static final HttpCheck HTTP = new HttpCheck("web", "http", java.net.URI.create("http://127.0.0.1/health"),
            Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(30), true);
    private static final TtlCheck TTL = new TtlCheck("web", "app", Duration.ofSeconds(20), false);

    private final ManualTime time = new ManualTime(START);
    private final ScriptedClient client = new ScriptedClient();
    private final HealthPublisher publisher = new HealthPublisher(client, time, time, attempt -> 1_000L << attempt);

    @Test
    void firstResultPublishesAClockSeededObservationOfTheCheck() {
        publisher.onResult(HTTP, result(CheckStatus.WARNING, "HTTP 429"));

        assertEquals(List.of(new CheckObservation("web", "http", CheckStatus.WARNING, START.toEpochMilli(), START,
                Duration.ofSeconds(30), true, "HTTP 429")), client.sent());
    }

    @Test
    void unchangedResultsArePublishedOnlyWhenARenewalIsDue() {
        publisher.onResult(HTTP, result(CheckStatus.PASSING, "HTTP 200"));
        client.accept(0);

        time.advance(Duration.ofSeconds(10));
        publisher.onResult(HTTP, result(CheckStatus.PASSING, "HTTP 200"));
        assertEquals(1, client.sent().size(), "an unchanged result before half the TTL is not republished");

        time.advance(Duration.ofSeconds(5));
        publisher.onResult(HTTP, result(CheckStatus.PASSING, "HTTP 200"));
        assertEquals(2, client.sent().size(), "half the TTL after acceptance a renewal is due");
        CheckObservation renewal = client.sent().get(1);
        assertEquals(START.plusSeconds(15).toEpochMilli(), renewal.sequenceNumber());
        assertEquals(START.plusSeconds(15), renewal.observedAt());
        client.accept(1);

        time.advance(Duration.ofSeconds(1));
        publisher.onResult(HTTP, result(CheckStatus.CRITICAL, "HTTP 503"));
        assertEquals(3, client.sent().size(), "a changed status is published immediately");
        assertEquals(CheckStatus.CRITICAL, client.sent().get(2).status());
    }

    @Test
    void resultsArrivingDuringAPublicationAreCoalescedToTheLatest() {
        publisher.onResult(HTTP, result(CheckStatus.PASSING, "HTTP 200"));
        publisher.onResult(HTTP, result(CheckStatus.WARNING, "HTTP 429"));
        publisher.onResult(HTTP, result(CheckStatus.CRITICAL, "HTTP 503"));
        assertEquals(1, client.sent().size(), "one publication per check is in flight at a time");

        client.accept(0);

        assertEquals(2, client.sent().size());
        CheckObservation latest = client.sent().get(1);
        assertEquals(CheckStatus.CRITICAL, latest.status());
        assertTrue(latest.sequenceNumber() > client.sent().getFirst().sequenceNumber());
    }

    @Test
    void retryableFailuresResendTheSameObservationAfterBackoff() {
        publisher.onResult(HTTP, result(CheckStatus.PASSING, "HTTP 200"));
        CheckObservation original = client.sent().getFirst();

        client.retryable(0);
        time.advance(Duration.ofMillis(999));
        assertEquals(1, client.sent().size());
        time.advance(Duration.ofMillis(1));
        assertEquals(original, client.sent().get(1), "a retry keeps the observation's idempotent identity");

        client.retryable(1);
        time.advance(Duration.ofMillis(1_999));
        assertEquals(2, client.sent().size(), "the second retry waits for the next backoff step");
        publisher.onResult(HTTP, result(CheckStatus.PASSING, "HTTP 200"));
        assertEquals(original, client.sent().get(2), "an identical new result resends the unconfirmed observation");
        client.accept(2);
        assertEquals(0, time.pendingTasks(), "acceptance cancels the pending retry");
    }

    @Test
    void aChangedResultSupersedesAnUnconfirmedObservation() {
        publisher.onResult(HTTP, result(CheckStatus.PASSING, "HTTP 200"));
        client.retryable(0);

        publisher.onResult(HTTP, result(CheckStatus.CRITICAL, "HTTP 503"));

        assertEquals(2, client.sent().size());
        assertEquals(CheckStatus.CRITICAL, client.sent().get(1).status());
        assertNotEquals(client.sent().get(0).sequenceNumber(), client.sent().get(1).sequenceNumber());
        assertEquals(0, time.pendingTasks(), "the superseded retry is cancelled");
    }

    @Test
    void aStaleAnswerRaisesTheSequenceFloorAndRepublishes() {
        publisher.onResult(HTTP, result(CheckStatus.PASSING, "HTTP 200"));
        long serverSequence = START.toEpochMilli() + 5_000;

        client.stale(0, serverSequence);

        assertEquals(2, client.sent().size());
        CheckObservation republished = client.sent().get(1);
        assertEquals(serverSequence + 1, republished.sequenceNumber());
        assertEquals(CheckStatus.PASSING, republished.status());
        client.accept(1);
        publisher.onResult(HTTP, result(CheckStatus.WARNING, "HTTP 429"));
        assertEquals(serverSequence + 2, client.sent().get(2).sequenceNumber());
    }

    @Test
    void anUnregisteredServiceIsRetriedAndAnInvalidObservationIsDropped() {
        publisher.onResult(HTTP, result(CheckStatus.PASSING, "HTTP 200"));
        client.reject(0, "service_not_found");
        time.advance(Duration.ofSeconds(1));
        assertEquals(client.sent().get(0), client.sent().get(1), "registration may still be converging");

        client.reject(1, "invalid_observation");
        assertEquals(0, time.pendingTasks(), "a semantic rejection is not retried unchanged");
        time.advance(Duration.ofMinutes(1));
        assertEquals(2, client.sent().size());
    }

    @Test
    void checksPublishIndependently() {
        publisher.onResult(HTTP, result(CheckStatus.PASSING, "HTTP 200"));
        publisher.onResult(TTL, result(CheckStatus.WARNING, "degraded"));

        assertEquals(2, client.sent().size(), "an in-flight publication does not block another check");
        assertEquals("app", client.sent().get(1).checkId());
        assertEquals(false, client.sent().get(1).required());
        assertEquals(Duration.ofSeconds(20), client.sent().get(1).ttl());
    }

    @Test
    void stopRefusesNewWorkAndCompletesWhenInFlightPublicationsFinish() {
        publisher.onResult(HTTP, result(CheckStatus.PASSING, "HTTP 200"));
        publisher.onResult(TTL, result(CheckStatus.PASSING, "ok"));
        client.retryable(1);
        assertEquals(1, time.pendingTasks());

        CompletableFuture<Void> stopped = publisher.stop();

        assertEquals(0, time.pendingTasks(), "scheduled retries are cancelled");
        assertFalse(stopped.isDone(), "stop waits for the in-flight publication");
        publisher.onResult(HTTP, result(CheckStatus.CRITICAL, "HTTP 503"));
        client.stale(0, START.toEpochMilli() + 10);
        assertTrue(stopped.isDone());
        assertEquals(2, client.sent().size(), "nothing is published after stop");
        assertTrue(publisher.stop().isDone());
    }

    private CheckResult result(CheckStatus status, String output) {
        return new CheckResult(status, output, time.instant());
    }

    /** Records observations and lets the test decide each outcome. */
    private static final class ScriptedClient implements ObservationClient {
        private final List<CheckObservation> sent = new CopyOnWriteArrayList<>();
        private final List<CompletableFuture<ObservationOutcome>> replies = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<ObservationOutcome> observe(CheckObservation observation) {
            CompletableFuture<ObservationOutcome> reply = new CompletableFuture<>();
            sent.add(observation);
            replies.add(reply);
            return reply;
        }

        List<CheckObservation> sent() {
            return sent;
        }

        void accept(int index) {
            CheckObservation observation = sent.get(index);
            replies.get(index).complete(new ObservationOutcome.Accepted(observation.sequenceNumber(),
                    observation.observedAt().plus(observation.ttl())));
        }

        void retryable(int index) {
            replies.get(index).complete(new ObservationOutcome.Retryable("transport_error", "refused", null));
        }

        void stale(int index, long currentSequenceNumber) {
            replies.get(index).complete(new ObservationOutcome.Stale(currentSequenceNumber));
        }

        void reject(int index, String code) {
            replies.get(index).complete(new ObservationOutcome.Rejected(code, code, null));
        }
    }
}

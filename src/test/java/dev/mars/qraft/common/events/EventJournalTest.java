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

package dev.mars.qraft.common.events;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the bounded local journal (events list, Phase 2; architecture sections 8 and 11): strict order, the count bound
 * and the history gap it leaves, paginated replay with a filter, other-boot and invalid requests, subscribers that
 * replay and then follow, each with its own queue, a slow one ended by overflow while the others and the publisher go
 * on, a failing consumer ended alone, and cancellation and closing.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class EventJournalTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID BOOT = UUID.fromString("0f2e8c6a-3b1d-4e5f-9a7b-2c4d6e8f0a1b");
    private EventJournal journal;

    private record Tick(int n) implements QraftEvent {
        @Override
        public String type() {
            return n % 2 == 0 ? "test.even" : "test.odd";
        }
    }

    /** A consumer that records what it receives and how its subscription ended. */
    private static class Recording implements EventConsumer {
        final List<Long> sequences = new CopyOnWriteArrayList<>();
        final CompletableFuture<SubscriptionEnd> end = new CompletableFuture<>();
        final CountDownLatch received;

        Recording(int expected) {
            received = new CountDownLatch(expected);
        }

        @Override
        public void accept(EventEnvelope event) throws Exception {
            sequences.add(event.id().sequence());
            received.countDown();
        }

        @Override
        public void ended(SubscriptionEnd ended) {
            end.complete(ended);
        }
    }

    @AfterEach
    void close() {
        if (journal != null) journal.close();
    }

    private void publish(int count) {
        for (int i = 1; i <= count; i++) journal.publishSystem(EventSeverity.INFO, 1, new Tick(i));
    }

    private static void awaitCount(Recording recording, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (recording.sequences.size() < count && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(count, recording.sequences.size());
    }

    private static List<Long> sequences(EventPage page) {
        return page.events().stream().map(event -> event.id().sequence()).toList();
    }

    @Test
    void eventsAreKeptInStrictOrderWithinTheirBoundAndAnOlderCursorIsAGap() {
        journal = new EventJournal("server1", BOOT, CLOCK, 3, 16);
        publish(5);

        EventPage gap = journal.recent(EventCursor.start(BOOT), EventFilter.all(), 10);
        assertEquals(EventPage.Status.HISTORY_GAP, gap.status(), "events 1 and 2 were overwritten");
        assertEquals(new EventCursor(BOOT, 2), gap.next(), "the cursor before the oldest retained event");
        EventPage retained = journal.recent(gap.next(), EventFilter.all(), 10);
        assertEquals(List.of(3L, 4L, 5L), sequences(retained));
        assertEquals(EventPage.Status.COMPLETE, retained.status());
        assertEquals(new EventCursor(BOOT, 5), retained.next());
    }

    @Test
    void pagesReplayWithAFilterAndOtherBootsAndInvalidRequestsAreAnsweredAsSuch() {
        journal = new EventJournal("server1", BOOT, CLOCK, 100, 16);
        publish(6);

        EventPage first = journal.recent(EventCursor.start(BOOT), EventFilter.all(), 4);
        assertEquals(List.of(1L, 2L, 3L, 4L), sequences(first));
        assertEquals(EventPage.Status.MORE, first.status());
        EventPage second = journal.recent(first.next(), EventFilter.all(), 4);
        assertEquals(List.of(5L, 6L), sequences(second));
        assertEquals(EventPage.Status.COMPLETE, second.status());

        EventFilter even = new EventFilter(Set.of(), Set.of("test.even"), Optional.empty(), Optional.empty());
        assertEquals(List.of(2L, 4L, 6L), sequences(journal.recent(EventCursor.start(BOOT), even, 10)));
        assertEquals(EventPage.Status.OTHER_BOOT,
                journal.recent(EventCursor.start(UUID.randomUUID()), EventFilter.all(), 10).status());
        assertEquals(EventPage.Status.INVALID, journal.recent(EventCursor.start(BOOT), EventFilter.all(), 0).status());
        assertEquals(EventPage.Status.INVALID, journal.recent(new EventCursor(BOOT, 99), EventFilter.all(), 10).status(),
                "a cursor past the last event names nothing this boot raised");
    }

    @Test
    void aSubscriberReplaysWhatIsRetainedAndThenFollows() throws Exception {
        journal = new EventJournal("server1", BOOT, CLOCK, 100, 16);
        publish(3);
        Recording recording = new Recording(5);

        EventSubscription subscription = journal.subscribe(EventCursor.after(new EventId("server1", BOOT, 1)),
                EventFilter.all(), recording);
        publish(3);

        assertTrue(recording.received.await(10, TimeUnit.SECONDS));
        assertEquals(List.of(2L, 3L, 4L, 5L, 6L), recording.sequences);
        assertTrue(subscription.active());
        subscription.close();
        assertEquals(SubscriptionEnd.Reason.CANCELLED, recording.end.get(10, TimeUnit.SECONDS).reason());
        assertFalse(subscription.active());
        publish(1);
        assertEquals(5, recording.sequences.size(), "nothing after cancellation");
    }

    @Test
    void aSlowSubscriberOverflowsAloneAndThePublisherNeverWaits() throws Exception {
        journal = new EventJournal("server1", BOOT, CLOCK, 1_000, 16);
        CountDownLatch release = new CountDownLatch(1);
        Recording slow = new Recording(1) {
            @Override
            public void accept(EventEnvelope event) throws Exception {
                super.accept(event);
                release.await();
            }
        };
        Recording fast = new Recording(18);
        journal.subscribe(EventCursor.start(BOOT), EventFilter.all(), slow);
        journal.subscribe(EventCursor.start(BOOT), EventFilter.all(), fast);

        publish(1);
        assertTrue(slow.received.await(10, TimeUnit.SECONDS), "the slow subscriber holds the first event");
        // A burst no larger than a queue: the fast subscriber, which has taken event 1, has room for all of it, and the
        // slow one's queue fills with events 2 to 17 while its consumer is held.
        long started = System.nanoTime();
        publish(16);
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5, "publication did not wait");
        awaitCount(fast, 17);
        assertFalse(slow.end.isDone(), "a full queue is not yet an overflow");
        publish(1);
        release.countDown();

        SubscriptionEnd overflow = slow.end.get(10, TimeUnit.SECONDS);
        assertEquals(SubscriptionEnd.Reason.OVERFLOW, overflow.reason(), "event 18 found its queue full");
        assertEquals(new EventCursor(BOOT, 17), overflow.resumeAfter(), "it resumes after what it received");
        for (int i = 0; i < slow.sequences.size(); i++) assertEquals(i + 1L, slow.sequences.get(i), "no gap before");
        assertEquals(17, slow.sequences.size(), "it took what it had queued before ending");
        assertTrue(fast.received.await(10, TimeUnit.SECONDS));
        assertEquals(18, fast.sequences.size(), "the other subscriber received every event");
    }

    @Test
    void aFailingConsumerEndsOnlyItsOwnSubscriptionAndClosingEndsTheRest() throws Exception {
        journal = new EventJournal("server1", BOOT, CLOCK, 100, 16);
        Recording failing = new Recording(1) {
            @Override
            public void accept(EventEnvelope event) {
                received.countDown();
                throw new IllegalStateException("the consumer cannot take events");
            }
        };
        Recording healthy = new Recording(3);
        journal.subscribe(EventCursor.start(BOOT), EventFilter.all(), failing);
        journal.subscribe(EventCursor.start(BOOT), EventFilter.all(), healthy);

        publish(3);
        assertEquals(SubscriptionEnd.Reason.FAILED, failing.end.get(10, TimeUnit.SECONDS).reason());
        assertTrue(healthy.received.await(10, TimeUnit.SECONDS));
        assertEquals(List.of(1L, 2L, 3L), healthy.sequences);

        journal.close();
        assertEquals(SubscriptionEnd.Reason.CLOSED, healthy.end.get(10, TimeUnit.SECONDS).reason());
        Recording late = new Recording(0);
        assertFalse(journal.subscribe(EventCursor.start(BOOT), EventFilter.all(), late).active());
        assertEquals(SubscriptionEnd.Reason.CLOSED, late.end.get(10, TimeUnit.SECONDS).reason());
    }
}

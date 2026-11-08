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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * One node's bounded event journal (events list, Phase 2; architecture sections 8 and 11):
 *
 * <ul>
 *   <li>events are numbered in strict order within this boot, and the newest {@code capacity} are kept;</li>
 *   <li>{@link #recent} pages through what is kept after a cursor, and answers a cursor older than what is kept as a
 *       history gap, with the cursor to resume from, one of another boot as such, and anything else invalid;</li>
 *   <li>a subscriber replays what is kept after its cursor and then follows, through its own queue of
 *       {@code subscriberQueue} events, drained by its own virtual thread. One that falls a whole queue behind is ended
 *       by overflow once it has taken what it had queued, with the cursor after the last event it received, so it can
 *       resume without a gap; one whose consumer throws is ended alone; publication never waits for any of them.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class EventJournal implements EventSource, AutoCloseable {
    /** How many events a journal keeps by default (decision 2). */
    public static final int DEFAULT_CAPACITY = 4096;
    /** How many events a subscriber may fall behind by default (decision 3). */
    public static final int DEFAULT_SUBSCRIBER_QUEUE = 1024;

    private static final Logger LOG = LoggerFactory.getLogger(EventJournal.class);
    private static final long POLL_MILLIS = 100;
    private static final AtomicLong SUBSCRIBERS = new AtomicLong();

    private final String nodeId;
    private final UUID bootId;
    private final Clock clock;
    private final int capacity;
    private final int subscriberQueue;
    private final ArrayDeque<EventEnvelope> retained = new ArrayDeque<>();
    private final List<Subscriber> subscribers = new ArrayList<>();
    private long sequence;
    private boolean closed;

    public EventJournal(String nodeId, UUID bootId, Clock clock, int capacity, int subscriberQueue) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.bootId = Objects.requireNonNull(bootId, "bootId");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive: " + capacity);
        if (subscriberQueue < 1) throw new IllegalArgumentException("subscriberQueue must be positive: " + subscriberQueue);
        this.capacity = capacity;
        this.subscriberQueue = subscriberQueue;
    }

    /** A journal for {@code nodeId}'s boot that starts now, with the default bounds. */
    public static EventJournal forBoot(String nodeId, Clock clock) {
        return new EventJournal(nodeId, UUID.randomUUID(), clock, DEFAULT_CAPACITY, DEFAULT_SUBSCRIBER_QUEUE);
    }

    public UUID bootId() {
        return bootId;
    }

    /** Publishes a system event of this node. */
    public EventEnvelope publishSystem(EventSeverity severity, long term, QraftEvent event) {
        return publish(id -> EventEnvelope.system(id, clock.instant(), severity, term, event));
    }

    /** Publishes a domain event, the {@code ordinal}th of the entry committed at {@code index}. */
    public EventEnvelope publishDomain(long term, long index, int ordinal, String tenant, String namespace,
                                       QraftEvent event) {
        return publish(id -> EventEnvelope.domain(id, clock.instant(), term, index, ordinal, tenant, namespace, event));
    }

    /**
     * Gives {@code envelope} the next ID, keeps it, and offers it to every subscriber; never waits for one. After
     * {@link #close()} the envelope is built and answered, but neither kept nor delivered.
     */
    public synchronized EventEnvelope publish(Function<EventId, EventEnvelope> envelope) {
        EventId id = new EventId(nodeId, bootId, ++sequence);
        EventEnvelope built = Objects.requireNonNull(envelope.apply(id), "envelope");
        if (!built.id().equals(id)) throw new IllegalArgumentException("an envelope keeps the ID it was given: " + id);
        if (closed) return built;
        retained.addLast(built);
        if (retained.size() > capacity) retained.removeFirst();
        for (Subscriber subscriber : List.copyOf(subscribers)) subscriber.offer(built);
        return built;
    }

    @Override
    public synchronized EventPage recent(EventCursor after, EventFilter filter, int limit) {
        if (after == null || filter == null || limit < 1) {
            return new EventPage(List.of(), after == null ? EventCursor.start(bootId) : after, EventPage.Status.INVALID);
        }
        if (!after.bootId().equals(bootId)) return new EventPage(List.of(), after, EventPage.Status.OTHER_BOOT);
        if (after.sequence() > sequence) return new EventPage(List.of(), after, EventPage.Status.INVALID);
        long oldest = oldestKept();
        if (after.sequence() + 1 < oldest) {
            return new EventPage(List.of(), new EventCursor(bootId, oldest - 1), EventPage.Status.HISTORY_GAP);
        }
        List<EventEnvelope> page = new ArrayList<>();
        long next = after.sequence();
        for (EventEnvelope event : retained) {
            if (event.id().sequence() <= after.sequence()) continue;
            if (page.size() == limit) break;
            next = event.id().sequence();
            if (filter.matches(event)) page.add(event);
        }
        return new EventPage(page, new EventCursor(bootId, next),
                next < sequence ? EventPage.Status.MORE : EventPage.Status.COMPLETE);
    }

    @Override
    public EventSubscription subscribe(EventCursor after, EventFilter filter, EventConsumer consumer) {
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(consumer, "consumer");
        Subscriber subscriber = new Subscriber(filter, consumer, after.sequence());
        SubscriptionEnd refused = null;
        synchronized (this) {
            if (closed) {
                refused = new SubscriptionEnd(SubscriptionEnd.Reason.CLOSED, after);
            } else if (!after.bootId().equals(bootId)) {
                refused = new SubscriptionEnd(SubscriptionEnd.Reason.OTHER_BOOT, after);
            } else if (after.sequence() > sequence) {
                throw new IllegalArgumentException("the cursor names no event of this boot: " + after);
            } else if (after.sequence() + 1 < oldestKept()) {
                refused = new SubscriptionEnd(SubscriptionEnd.Reason.HISTORY_GAP, new EventCursor(bootId, oldestKept() - 1));
            } else {
                for (EventEnvelope event : retained) {
                    if (event.id().sequence() > after.sequence() && !subscriber.offer(event)) break;
                }
                subscribers.add(subscriber);
                subscriber.start();
            }
        }
        if (refused != null) {
            subscriber.ending = refused.reason();
            subscriber.tell(refused);
        }
        return subscriber;
    }

    /** Ends every subscription as closed; the journal keeps nothing more. */
    @Override
    public void close() {
        List<Subscriber> open;
        synchronized (this) {
            if (closed) return;
            closed = true;
            open = List.copyOf(subscribers);
        }
        open.forEach(subscriber -> subscriber.end(SubscriptionEnd.Reason.CLOSED));
    }

    private long oldestKept() {
        return retained.isEmpty() ? sequence + 1 : retained.peekFirst().id().sequence();
    }

    private synchronized void remove(Subscriber subscriber) {
        subscribers.remove(subscriber);
    }

    /** One subscription: its queue, its thread, and why it ends. */
    private final class Subscriber implements EventSubscription {
        private final EventFilter filter;
        private final EventConsumer consumer;
        private final ArrayBlockingQueue<EventEnvelope> queue = new ArrayBlockingQueue<>(subscriberQueue);
        private volatile SubscriptionEnd.Reason ending;
        private volatile long delivered;

        Subscriber(EventFilter filter, EventConsumer consumer, long after) {
            this.filter = filter;
            this.consumer = consumer;
            this.delivered = after;
        }

        /** Queues {@code event} if it is wanted; false once this subscription is ending. Called under the journal's lock. */
        boolean offer(EventEnvelope event) {
            if (ending != null) return false;
            if (!filter.matches(event)) return true;
            if (queue.offer(event)) return true;
            end(SubscriptionEnd.Reason.OVERFLOW);
            return false;
        }

        void start() {
            Thread.ofVirtual().name("qraft-event-subscriber-" + SUBSCRIBERS.incrementAndGet()).start(this::run);
        }

        void end(SubscriptionEnd.Reason reason) {
            if (ending == null) ending = reason;
        }

        private void run() {
            try {
                while (true) {
                    SubscriptionEnd.Reason reason = ending;
                    if (reason == SubscriptionEnd.Reason.CANCELLED || reason == SubscriptionEnd.Reason.CLOSED) break;
                    EventEnvelope event = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                    if (event == null) {
                        if (ending == SubscriptionEnd.Reason.OVERFLOW) break;
                        continue;
                    }
                    try {
                        consumer.accept(event);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        end(SubscriptionEnd.Reason.CLOSED);
                        break;
                    } catch (Exception failure) {
                        LOG.warn("An event subscriber failed on {} and its subscription was ended: {}", event.id(),
                                failure.toString());
                        ending = SubscriptionEnd.Reason.FAILED;
                        break;
                    }
                    delivered = event.id().sequence();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                end(SubscriptionEnd.Reason.CLOSED);
            } finally {
                remove(this);
                tell(new SubscriptionEnd(ending == null ? SubscriptionEnd.Reason.CLOSED : ending,
                        new EventCursor(bootId, delivered)));
            }
        }

        void tell(SubscriptionEnd end) {
            try {
                consumer.ended(end);
            } catch (RuntimeException failure) {
                LOG.warn("An event subscriber failed when told its subscription ended: {}", failure.toString());
            }
        }

        @Override
        public boolean active() {
            return ending == null;
        }

        @Override
        public void close() {
            end(SubscriptionEnd.Reason.CANCELLED);
        }
    }
}

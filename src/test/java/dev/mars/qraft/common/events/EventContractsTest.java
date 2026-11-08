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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the event contracts (events list, Phase 1; architecture sections 5 and 8): a domain event's envelope carries
 * its committed index and replicated identity, and any other's carries neither; local IDs are ordered within one boot
 * and not across boots; a replicated identity is the same on every node; cursors compare within a boot; and filters
 * match by category, type and scope.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class EventContractsTest {
    private static final Instant AT = Instant.parse("2026-11-08T12:00:00Z");
    private static final UUID BOOT = UUID.fromString("5a1f6d3e-0b8c-4f3a-9e21-7c4d2b1a0f99");

    private record Registered(String service) implements QraftEvent {
        @Override
        public String type() {
            return "service.registered";
        }
    }

    private record LeadershipGained() implements QraftEvent {
        @Override
        public String type() {
            return "raft.leadership_gained";
        }
    }

    private static EventId id(long sequence) {
        return new EventId("server1", BOOT, sequence);
    }

    @Test
    void aDomainEnvelopeCarriesItsCommittedIndexAndReplicatedIdentityAndOthersCarryNeither() {
        EventEnvelope domain = EventEnvelope.domain(id(1), AT, 3, 42, 0, "acme", "prod", new Registered("web"));
        assertEquals(OptionalLong.of(42), domain.committedIndex());
        assertEquals(Optional.of(new ReplicatedEventId(42, 0, "service.registered")), domain.replicatedId());
        assertEquals(Optional.of("acme"), domain.tenant());

        EventEnvelope system = EventEnvelope.system(id(2), AT, EventSeverity.INFO, 3, new LeadershipGained());
        assertEquals(OptionalLong.empty(), system.committedIndex());
        assertEquals(Optional.empty(), system.replicatedId());

        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(id(3), AT, EventCategory.DOMAIN,
                EventSeverity.INFO, 3, OptionalLong.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), new Registered("web")), "a domain event has a committed index");
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(id(3), AT, EventCategory.SYSTEM,
                EventSeverity.INFO, 3, OptionalLong.of(7), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), new LeadershipGained()), "a system event is not replicated");
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(id(3), AT, EventCategory.DOMAIN,
                EventSeverity.INFO, 3, OptionalLong.of(42), Optional.of(new ReplicatedEventId(41, 0,
                "service.registered")), Optional.empty(), Optional.empty(), Optional.empty(), new Registered("web")),
                "the replicated identity is the committed index's");
        assertThrows(IllegalArgumentException.class, () -> EventEnvelope.system(id(3), AT, EventSeverity.INFO, -1,
                new LeadershipGained()), "a term is never negative");
    }

    @Test
    void localIdsAreOrderedWithinOneBootAndNotAcrossBoots() {
        assertTrue(id(1).compareTo(id(2)) < 0);
        assertTrue(id(2).sameBoot(id(1)));
        EventId otherBoot = new EventId("server1", UUID.randomUUID(), 1);
        assertFalse(id(1).sameBoot(otherBoot));
        assertThrows(IllegalArgumentException.class, () -> id(1).compareTo(otherBoot),
                "the sequence starts again in another boot, so the two are not ordered");
        assertThrows(IllegalArgumentException.class, () -> new EventId("server1", BOOT, 0), "a sequence starts at 1");
    }

    @Test
    void aReplicatedIdentityIsTheSameOnEveryNode() {
        ReplicatedEventId onServer1 = EventEnvelope.domain(id(9), AT, 3, 42, 1, null, null, new Registered("web"))
                .replicatedId().orElseThrow();
        ReplicatedEventId onServer2 = EventEnvelope.domain(new EventId("server2", UUID.randomUUID(), 4), AT.plusSeconds(1),
                3, 42, 1, null, null, new Registered("web")).replicatedId().orElseThrow();
        assertEquals(onServer1, onServer2, "the index, the ordinal and the type, and no local field");
        assertEquals("42:1:service.registered", onServer1.toString());
        assertThrows(IllegalArgumentException.class, () -> new ReplicatedEventId(0, 0, "x"));
        assertThrows(IllegalArgumentException.class, () -> new ReplicatedEventId(1, -1, "x"));
    }

    @Test
    void aCursorPrecedesTheEventsAfterItInItsBoot() {
        EventCursor start = EventCursor.start(BOOT);
        assertTrue(start.precedes(id(1)));
        EventCursor afterTwo = EventCursor.after(id(2));
        assertFalse(afterTwo.precedes(id(2)));
        assertTrue(afterTwo.precedes(id(3)));
        assertFalse(afterTwo.precedes(new EventId("server1", UUID.randomUUID(), 3)), "not in another boot");
        assertThrows(IllegalArgumentException.class, () -> new EventCursor(BOOT, -1));
    }

    @Test
    void aFilterMatchesByCategoryTypeAndScope() {
        EventEnvelope acme = EventEnvelope.domain(id(1), AT, 3, 42, 0, "acme", "prod", new Registered("web"));
        EventEnvelope system = EventEnvelope.system(id(2), AT, EventSeverity.INFO, 3, new LeadershipGained());

        assertTrue(EventFilter.all().matches(acme) && EventFilter.all().matches(system));
        EventFilter domainOnly = new EventFilter(Set.of(EventCategory.DOMAIN), Set.of(), Optional.empty(),
                Optional.empty());
        assertTrue(domainOnly.matches(acme));
        assertFalse(domainOnly.matches(system));
        EventFilter type = new EventFilter(Set.of(), Set.of("raft.leadership_gained"), Optional.empty(),
                Optional.empty());
        assertTrue(type.matches(system));
        assertFalse(type.matches(acme));
        EventFilter scope = new EventFilter(Set.of(), Set.of(), Optional.of("acme"), Optional.of("prod"));
        assertTrue(scope.matches(acme));
        assertFalse(scope.matches(system), "an event without a scope is not in one");
        assertFalse(new EventFilter(Set.of(), Set.of(), Optional.of("beta"), Optional.empty()).matches(acme));
    }
}

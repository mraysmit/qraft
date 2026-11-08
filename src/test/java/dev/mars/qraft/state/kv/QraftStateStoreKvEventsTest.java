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

package dev.mars.qraft.state.kv;

import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.events.DomainEvent;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.state.NodeCommand;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.session.Session;
import dev.mars.qraft.state.session.SessionBehavior;
import dev.mars.qraft.state.session.SessionCommand;
import dev.mars.qraft.state.session.SessionEvents;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the key/value, lock and session events (event query and watch list, Phase 1, decision 1): a key set, created or
 * updated, a key deleted, a tree deleted, a lock acquired and released, and a session created and destroyed with its
 * reason, each in its scope and carrying the key but never the value; a write that changed nothing produces none.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class QraftStateStoreKvEventsTest {
    private static final Instant T0 = Instant.parse("2026-11-08T12:00:00Z");
    private static final KvScope ACME = new KvScope("default", "default");
    private final QraftStateStore store = new QraftStateStore();
    private long index;

    private List<DomainEvent> apply(RaftCommand command) {
        store.apply(command);
        store.setLastAppliedIndex(++index);
        return store.drainDomainEvents();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static DomainEvent in(Object event) {
        return new DomainEvent("default", "default", (dev.mars.qraft.common.events.QraftEvent) event);
    }

    @Test
    void keyChangesProduceTheirEventsWithoutTheirValuesAndNoChangeProducesNone() {
        store.recordDomainEvents(true);

        assertEquals(List.of(in(new KvEvents.KeySet("app/config", true))),
                apply(KvCommand.set(ACME, "app/config", bytes("secret-value"), 0, null)));
        assertEquals(List.of(in(new KvEvents.KeySet("app/config", false))),
                apply(KvCommand.set(ACME, "app/config", bytes("other"), 0, null)));
        assertEquals(List.of(), apply(KvCommand.set(ACME, "app/config", bytes("x"), 0, 1L)), "a failed compare-and-set");
        assertTrue(store.drainDomainEvents().isEmpty());
        assertEquals(List.of(in(new KvEvents.KeyDeleted("app/config"))), apply(KvCommand.delete(ACME, "app/config", null)));
        assertEquals(List.of(), apply(KvCommand.delete(ACME, "app/config", null)), "deleting an absent key changes nothing");
        apply(KvCommand.set(ACME, "tree/a", bytes("1"), 0, null));
        assertEquals(List.of(in(new KvEvents.TreeDeleted("tree/"))), apply(KvCommand.deleteTree(ACME, "tree/")));
        assertEquals(List.of(), apply(KvCommand.deleteTree(ACME, "tree/")), "an empty tree changes nothing");
        assertTrue(apply(KvCommand.set(ACME, "v", bytes("secret-value"), 0, null)).toString()
                .indexOf("secret-value") < 0, "no event carries a value");
    }

    @Test
    void locksAndSessionsProduceTheirEvents() {
        apply(NodeCommand.register(Node.of("host-a", "10.0.0.1", null, null, Map.of()), T0));
        store.recordDomainEvents(true);

        assertEquals(List.of(new DomainEvent(null, null, new SessionEvents.SessionCreated("s1", "host-a"))),
                apply(SessionCommand.create(new Session("s1", "host-a", "lock", SessionBehavior.RELEASE, Duration.ZERO,
                        Duration.ZERO, 0, T0))));
        assertEquals(List.of(in(new KvEvents.LockAcquired("leader", "s1"))),
                apply(KvCommand.lock(ACME, "leader", bytes("me"), 0, "s1", T0)));
        assertEquals(List.of(in(new KvEvents.LockReleased("leader", "s1"))),
                apply(KvCommand.unlock(ACME, "leader", bytes("me"), 0, "s1")));
        assertEquals(List.of(), apply(KvCommand.unlock(ACME, "leader", bytes("me"), 0, "s1")), "nothing held");
        assertEquals(List.of(new DomainEvent(null, null, new SessionEvents.SessionDestroyed("s1", "destroyed"))),
                apply(SessionCommand.destroy("s1", T0)));
        assertEquals(List.of(), apply(SessionCommand.destroy("s1", T0)), "a session that is gone");
    }
}

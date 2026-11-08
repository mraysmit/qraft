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

package dev.mars.qraft.state.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.qraft.common.Node;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.state.NodeCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.kv.KvCommand;
import dev.mars.qraft.state.kv.KvEntry;
import dev.mars.qraft.state.kv.KvScope;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests replicated sessions and key locks (sessions list, Phase 1), on Consul's: a session is created and destroyed at
 * Raft indexes; a key is acquired by a live session, kept by it, released only by it, and a new holder increments
 * {@code LockIndex}; invalidation releases or deletes the session's keys by its behaviour and starts their lock delay,
 * which refuses an acquisition stamped before it ends; a node's removal or its becoming unreachable invalidates its
 * sessions; the snapshot carries sessions and lock delays; and the commands round-trip through the codec.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class QraftStateStoreSessionTest {
    private static final KvScope SCOPE = KvScope.DEFAULT;
    private static final Instant T0 = Instant.parse("2026-11-08T12:00:00Z");
    private long index;

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private RaftCommandResult<?> apply(QraftStateStore store, RaftCommand command) {
        RaftCommandResult<?> result = store.apply(command);
        store.setLastAppliedIndex(++index);
        return result;
    }

    private static Session session(String id, String node, SessionBehavior behavior, Duration lockDelay) {
        return new Session(id, node, "lock-" + id, behavior, Duration.ofSeconds(30), lockDelay, 0, T0);
    }

    private QraftStateStore storeWithNode(String node) {
        QraftStateStore store = new QraftStateStore();
        apply(store, NodeCommand.register(Node.of(node, "10.0.0.1", null, null, java.util.Map.of()), T0));
        return store;
    }

    @Test
    void aSessionIsCreatedAtItsIndexAndAKeyIsLockedByIt() {
        QraftStateStore store = storeWithNode("host-a");
        assertEquals(new RaftCommandResult.Success<>(true),
                apply(store, SessionCommand.create(session("s1", "host-a", SessionBehavior.RELEASE, Duration.ZERO))));
        assertEquals(index, store.getSessions().get("s1").orElseThrow().createIndex());
        apply(store, SessionCommand.create(session("s2", "host-a", SessionBehavior.RELEASE, Duration.ZERO)));

        assertEquals(new RaftCommandResult.Success<>(true), apply(store, KvCommand.lock(SCOPE, "leader", bytes("a"), 0,
                "s1", T0)));
        KvEntry held = store.getKv().get(SCOPE, "leader").orElseThrow();
        assertEquals("s1", held.session());
        assertEquals(1, held.lockIndex());
        assertEquals(new RaftCommandResult.Success<>(false), apply(store, KvCommand.lock(SCOPE, "leader", bytes("b"), 0,
                "s2", T0)), "another session cannot take a held key");
        assertEquals(new RaftCommandResult.Success<>(true), apply(store, KvCommand.lock(SCOPE, "leader", bytes("a2"), 0,
                "s1", T0)), "its holder may write it again");
        assertEquals(1, store.getKv().get(SCOPE, "leader").orElseThrow().lockIndex(), "without a new holder");

        assertEquals(new RaftCommandResult.Success<>(false),
                apply(store, KvCommand.unlock(SCOPE, "leader", bytes("x"), 0, "s2")), "only its holder releases it");
        assertEquals(new RaftCommandResult.Success<>(true),
                apply(store, KvCommand.unlock(SCOPE, "leader", bytes("free"), 0, "s1")));
        KvEntry released = store.getKv().get(SCOPE, "leader").orElseThrow();
        assertNull(released.session());
        assertArrayEquals(bytes("free"), released.value());

        assertEquals(new RaftCommandResult.Success<>(true), apply(store, KvCommand.lock(SCOPE, "leader", bytes("b"), 0,
                "s2", T0)));
        assertEquals(2, store.getKv().get(SCOPE, "leader").orElseThrow().lockIndex(), "a new holder");
    }

    @Test
    void aSessionThatDoesNotExistCannotLock() {
        QraftStateStore store = storeWithNode("host-a");

        RaftCommandResult<?> refused = apply(store, KvCommand.lock(SCOPE, "k", bytes("v"), 0, "absent", T0));

        assertInstanceOf(RaftCommandResult.Refused.class, refused);
        assertEquals("invalid_session", ((RaftCommandResult.Refused<?>) refused).code());
        assertEquals(Optional.empty(), store.getKv().get(SCOPE, "k"));
    }

    @Test
    void aSessionBoundToANodeThatIsNotRegisteredCannotBeCreated() {
        QraftStateStore store = new QraftStateStore();

        RaftCommandResult<?> refused = apply(store, SessionCommand.create(
                session("s1", "nowhere", SessionBehavior.RELEASE, Duration.ZERO)));

        assertInstanceOf(RaftCommandResult.Refused.class, refused);
        assertEquals("node_not_found", ((RaftCommandResult.Refused<?>) refused).code());
    }

    @Test
    void destroyingASessionReleasesItsKeysAndTheLockDelayRefusesAnEarlyAcquisition() {
        QraftStateStore store = storeWithNode("host-a");
        apply(store, SessionCommand.create(session("s1", "host-a", SessionBehavior.RELEASE, Duration.ofSeconds(15))));
        apply(store, SessionCommand.create(session("s2", "host-a", SessionBehavior.RELEASE, Duration.ZERO)));
        apply(store, KvCommand.lock(SCOPE, "leader", bytes("a"), 0, "s1", T0));

        assertEquals(new RaftCommandResult.Success<>(true), apply(store, SessionCommand.destroy("s1", T0)));

        assertEquals(Optional.empty(), store.getSessions().get("s1"));
        KvEntry released = store.getKv().get(SCOPE, "leader").orElseThrow();
        assertNull(released.session(), "released, its value kept");
        assertArrayEquals(bytes("a"), released.value());
        assertEquals(new RaftCommandResult.Success<>(false), apply(store, KvCommand.lock(SCOPE, "leader", bytes("b"), 0,
                "s2", T0.plusSeconds(14))), "within the lock delay");
        assertEquals(new RaftCommandResult.Success<>(true), apply(store, KvCommand.lock(SCOPE, "leader", bytes("b"), 0,
                "s2", T0.plusSeconds(15))), "once it has passed");
    }

    @Test
    void aDeleteBehaviourDeletesTheSessionsKeys() {
        QraftStateStore store = storeWithNode("host-a");
        apply(store, SessionCommand.create(session("s1", "host-a", SessionBehavior.DELETE, Duration.ZERO)));
        apply(store, KvCommand.lock(SCOPE, "ephemeral", bytes("a"), 0, "s1", T0));
        apply(store, KvCommand.set(SCOPE, "kept", bytes("b"), 0, null));

        apply(store, SessionCommand.destroy("s1", T0));

        assertEquals(Optional.empty(), store.getKv().get(SCOPE, "ephemeral"));
        assertTrue(store.getKv().get(SCOPE, "kept").isPresent());
    }

    @Test
    void aNodesRemovalOrItsBecomingUnreachableInvalidatesItsSessions() {
        QraftStateStore store = storeWithNode("host-a");
        apply(store, NodeCommand.register(Node.of("host-b", "10.0.0.2", null, null, java.util.Map.of()), T0));
        apply(store, SessionCommand.create(session("on-a", "host-a", SessionBehavior.RELEASE, Duration.ZERO)));
        apply(store, SessionCommand.create(session("on-b", "host-b", SessionBehavior.RELEASE, Duration.ZERO)));
        apply(store, KvCommand.lock(SCOPE, "held-by-a", bytes("a"), 0, "on-a", T0));

        apply(store, NodeCommand.deregister("host-a"));
        assertEquals(Optional.empty(), store.getSessions().get("on-a"));
        assertNull(store.getKv().get(SCOPE, "held-by-a").orElseThrow().session());

        Instant lastContact = QraftStateStore.lastContact(store.findNode("host-b").orElseThrow());
        apply(store, NodeCommand.expire("host-b", lastContact, false, T0.plusSeconds(90)));
        assertEquals(Optional.empty(), store.getSessions().get("on-b"), "an unreachable node's sessions are invalid");
    }

    @Test
    void theSnapshotCarriesSessionsAndLockDelaysAndOneFromBeforeRestoresNone() throws Exception {
        QraftStateStore store = storeWithNode("host-a");
        apply(store, SessionCommand.create(session("s1", "host-a", SessionBehavior.RELEASE, Duration.ofSeconds(15))));
        apply(store, SessionCommand.create(session("s2", "host-a", SessionBehavior.RELEASE, Duration.ZERO)));
        apply(store, KvCommand.lock(SCOPE, "leader", bytes("a"), 0, "s1", T0));
        apply(store, KvCommand.lock(SCOPE, "other", bytes("o"), 0, "s2", T0));
        apply(store, SessionCommand.destroy("s1", T0));

        QraftStateStore restored = new QraftStateStore();
        restored.restoreSnapshot(store.takeSnapshot());
        assertEquals(store.getSessions().list(), restored.getSessions().list());
        assertEquals("s2", restored.getKv().get(SCOPE, "other").orElseThrow().session());
        assertEquals(new RaftCommandResult.Success<>(false), restored.apply(KvCommand.lock(SCOPE, "leader", bytes("b"),
                0, "s2", T0.plusSeconds(1))), "the lock delay is restored with the snapshot");

        ObjectNode older = (ObjectNode) new ObjectMapper().readTree(store.takeSnapshot());
        older.remove("sessions");
        QraftStateStore fromOlder = new QraftStateStore();
        fromOlder.restoreSnapshot(new ObjectMapper().writeValueAsBytes(older));
        assertEquals(List.of(), fromOlder.getSessions().list());
    }

    @Test
    void theCommandsRoundTripThroughTheCodec() {
        ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();
        for (RaftCommand command : List.of(
                SessionCommand.create(session("s1", "host-a", SessionBehavior.DELETE, Duration.ofSeconds(15))),
                SessionCommand.create(new Session("s2", "host-b", "", SessionBehavior.RELEASE, Duration.ZERO,
                        Duration.ZERO, 0, T0)),
                SessionCommand.destroy("s1", T0),
                KvCommand.lock(SCOPE, "k", new byte[] {0, 1}, 3, "s1", T0),
                KvCommand.unlock(new KvScope("acme", "prod"), "k", new byte[0], 0, "s1"))) {
            assertEquals(command, codec.deserialize(codec.serialize(command)), command.toString());
        }
    }
}

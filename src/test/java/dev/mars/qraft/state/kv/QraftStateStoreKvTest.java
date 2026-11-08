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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests key/value in the state store (key/value list, Phase 1): a command applies at its entry's Raft index, which
 * the store takes as the last applied index plus one (decision 2); a refused compare-and-set answers {@code false};
 * the snapshot carries the entries, and one written before key/value restores none; and every command round-trips
 * through the protobuf codec.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class QraftStateStoreKvTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final KvScope ACME = new KvScope("acme", "prod");

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Applies {@code command} as {@code RaftNode} does: the entry, then its index. */
    private static RaftCommandResult<?> applyAt(QraftStateStore store, long index, RaftCommand command) {
        RaftCommandResult<?> result = store.apply(command);
        store.setLastAppliedIndex(index);
        return result;
    }

    @Test
    void aCommandAppliesAtItsEntrysRaftIndex() {
        QraftStateStore store = new QraftStateStore();
        store.setLastAppliedIndex(10);

        assertEquals(new RaftCommandResult.Success<>(true),
                applyAt(store, 11, KvCommand.set(ACME, "app/a", bytes("1"), 3, null)));
        KvEntry entry = store.getKv().get(ACME, "app/a").orElseThrow();
        assertEquals(11, entry.createIndex());
        assertEquals(11, entry.modifyIndex());
        assertEquals(3, entry.flags());
        assertEquals(11, store.getKv().lastIndex());

        assertEquals(new RaftCommandResult.Success<>(false),
                applyAt(store, 12, KvCommand.set(ACME, "app/a", bytes("2"), 0, 5L)), "a stale index is refused");
        assertEquals(new RaftCommandResult.Success<>(true),
                applyAt(store, 13, KvCommand.set(ACME, "app/a", bytes("2"), 0, 11L)));
        assertEquals(13, store.getKv().get(ACME, "app/a").orElseThrow().modifyIndex());

        assertEquals(new RaftCommandResult.Success<>(true), applyAt(store, 14, KvCommand.deleteTree(ACME, "app/")));
        assertEquals(Optional.empty(), store.getKv().get(ACME, "app/a"));
        assertEquals(new RaftCommandResult.Success<>(true), applyAt(store, 15, KvCommand.delete(ACME, "gone", 9L)),
                "an absent key is deleted already, whatever the index");
    }

    @Test
    void theSnapshotCarriesTheEntriesAndOneFromBeforeKeyValueRestoresNone() throws Exception {
        QraftStateStore store = new QraftStateStore();
        store.setLastAppliedIndex(4);
        applyAt(store, 5, KvCommand.set(ACME, "a", new byte[] {0, 1, (byte) 0xfe}, 9, null));

        QraftStateStore restored = new QraftStateStore();
        restored.restoreSnapshot(store.takeSnapshot());
        assertEquals(store.getKv().list(ACME, ""), restored.getKv().list(ACME, ""));
        assertArrayEquals(new byte[] {0, 1, (byte) 0xfe}, restored.getKv().get(ACME, "a").orElseThrow().value());
        assertEquals(5, restored.getKv().lastIndex());

        ObjectNode older = (ObjectNode) JSON.readTree(store.takeSnapshot());
        older.remove("kv");
        QraftStateStore fromOlder = new QraftStateStore();
        fromOlder.restoreSnapshot(JSON.writeValueAsBytes(older));
        assertEquals(List.of(), fromOlder.getKv().list(ACME, ""));
        assertEquals(0, fromOlder.getKv().lastIndex());
    }

    @Test
    void aResetEmptiesTheKeyValueState() {
        QraftStateStore store = new QraftStateStore();
        applyAt(store, 1, KvCommand.set(ACME, "a", bytes("1"), 0, null));

        store.reset();

        assertEquals(List.of(), store.getKv().list(ACME, ""));
        assertEquals(0, store.getKv().lastIndex());
    }

    @Test
    void theFieldNumbersOfTheWalFormatDoNotChange() {
        // Written to every WAL that holds a key/value command; renumbering them would decode those entries as others.
        assertEquals(6, dev.mars.qraft.raft.grpc.RaftCommandMessage.KV_COMMAND_FIELD_NUMBER);
        assertEquals(List.of(1, 2, 3, 4, 5), List.of(dev.mars.qraft.raft.grpc.KvCommandProto.TENANT_FIELD_NUMBER,
                dev.mars.qraft.raft.grpc.KvCommandProto.NAMESPACE_FIELD_NUMBER,
                dev.mars.qraft.raft.grpc.KvCommandProto.SET_FIELD_NUMBER,
                dev.mars.qraft.raft.grpc.KvCommandProto.DELETE_FIELD_NUMBER,
                dev.mars.qraft.raft.grpc.KvCommandProto.DELETE_TREE_PREFIX_FIELD_NUMBER));
        assertEquals(List.of(1, 2, 3, 4), List.of(dev.mars.qraft.raft.grpc.KvSetProto.KEY_FIELD_NUMBER,
                dev.mars.qraft.raft.grpc.KvSetProto.VALUE_FIELD_NUMBER,
                dev.mars.qraft.raft.grpc.KvSetProto.FLAGS_FIELD_NUMBER,
                dev.mars.qraft.raft.grpc.KvSetProto.CAS_FIELD_NUMBER));
        assertEquals(List.of(1, 2), List.of(dev.mars.qraft.raft.grpc.KvDeleteProto.KEY_FIELD_NUMBER,
                dev.mars.qraft.raft.grpc.KvDeleteProto.CAS_FIELD_NUMBER));
    }

    @Test
    void everyCommandRoundTripsThroughTheProtobufCodec() {
        ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();
        for (KvCommand command : List.of(
                KvCommand.set(ACME, "a/b", new byte[] {0, 7, (byte) 0x80}, -1L, null),
                KvCommand.set(KvScope.DEFAULT, "x", new byte[0], 0, 0L),
                KvCommand.set(KvScope.DEFAULT, "x", bytes("v"), 12, 99L),
                KvCommand.delete(ACME, "a/b", null),
                KvCommand.delete(ACME, "a/b", 4L),
                KvCommand.deleteTree(ACME, ""),
                KvCommand.deleteTree(KvScope.DEFAULT, "config/"))) {
            assertEquals(command, codec.deserialize(codec.serialize(command)), command.toString());
        }
    }
}

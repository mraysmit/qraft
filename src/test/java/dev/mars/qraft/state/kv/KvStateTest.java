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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the replicated key/value state (key/value list, Phase 1), on Consul's KV store: set and delete with their
 * compare-and-set forms, delete-tree, creation and modification indexes, flags, scoping by tenant and namespace,
 * listing in key order, keys up to a separator, and the snapshot.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class KvStateTest {
    private static final KvScope DEFAULT = KvScope.DEFAULT;
    private static final KvScope ACME = new KvScope("acme", "prod");
    private static final OptionalLong ANY = OptionalLong.empty();

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aSetCreatesAnEntryAtItsIndexAndAnotherModifiesIt() {
        KvState kv = new KvState();

        assertTrue(kv.set(DEFAULT, "config/db", bytes("one"), 0, ANY, 5));
        KvEntry created = kv.get(DEFAULT, "config/db").orElseThrow();
        assertEquals(5, created.createIndex());
        assertEquals(5, created.modifyIndex());
        assertEquals(0, created.flags());
        assertArrayEquals(bytes("one"), created.value());

        assertTrue(kv.set(DEFAULT, "config/db", bytes("two"), 42, ANY, 9));
        KvEntry modified = kv.get(DEFAULT, "config/db").orElseThrow();
        assertEquals(5, modified.createIndex(), "the creation index stays");
        assertEquals(9, modified.modifyIndex());
        assertEquals(42, modified.flags());
        assertArrayEquals(bytes("two"), modified.value());
        assertEquals(9, kv.lastIndex());
    }

    @Test
    void compareAndSetWritesOnlyAtTheEntrysModifyIndexAndZeroOnlyWhenAbsent() {
        KvState kv = new KvState();

        assertTrue(kv.set(DEFAULT, "lock", bytes("a"), 0, OptionalLong.of(0), 3), "cas 0 creates an absent key");
        assertFalse(kv.set(DEFAULT, "lock", bytes("b"), 0, OptionalLong.of(0), 4), "cas 0 refuses an existing key");
        assertFalse(kv.set(DEFAULT, "lock", bytes("b"), 0, OptionalLong.of(2), 5), "a stale index is refused");
        assertArrayEquals(bytes("a"), kv.get(DEFAULT, "lock").orElseThrow().value());
        assertEquals(3, kv.lastIndex(), "a refused write changes nothing, not even the index");

        assertTrue(kv.set(DEFAULT, "lock", bytes("c"), 0, OptionalLong.of(3), 6));
        assertEquals(6, kv.get(DEFAULT, "lock").orElseThrow().modifyIndex());
    }

    @Test
    void aDeleteRemovesAndSucceedsForAnAbsentKeyAndItsCompareFormChecksTheIndex() {
        KvState kv = new KvState();
        kv.set(DEFAULT, "a", bytes("1"), 0, ANY, 2);

        assertFalse(kv.delete(DEFAULT, "a", OptionalLong.of(1), 3), "a stale index keeps the entry");
        assertTrue(kv.get(DEFAULT, "a").isPresent());
        assertTrue(kv.delete(DEFAULT, "a", OptionalLong.of(2), 4));
        assertEquals(Optional.empty(), kv.get(DEFAULT, "a"));
        assertEquals(4, kv.lastIndex());
        assertTrue(kv.delete(DEFAULT, "absent", ANY, 5), "deleting an absent key succeeds, as in Consul");
        assertTrue(kv.delete(DEFAULT, "absent", OptionalLong.of(7), 5),
                "whatever index a compare-and-delete gives, as Consul's kvsDeleteCASTxn answers");
        assertEquals(4, kv.lastIndex(), "and changes nothing");
    }

    @Test
    void aDeleteTreeRemovesThePrefixOnly() {
        KvState kv = new KvState();
        kv.set(DEFAULT, "app/a", bytes("1"), 0, ANY, 2);
        kv.set(DEFAULT, "app/b/c", bytes("2"), 0, ANY, 3);
        kv.set(DEFAULT, "apple", bytes("3"), 0, ANY, 4);

        kv.deleteTree(DEFAULT, "app/", 5);

        assertEquals(List.of("apple"), kv.list(DEFAULT, "").stream().map(KvEntry::key).toList());
        assertEquals(5, kv.lastIndex());
    }

    @Test
    void eachScopeHoldsItsOwnKeys() {
        KvState kv = new KvState();
        kv.set(DEFAULT, "shared", bytes("default"), 0, ANY, 2);
        kv.set(ACME, "shared", bytes("acme"), 0, ANY, 3);

        assertArrayEquals(bytes("default"), kv.get(DEFAULT, "shared").orElseThrow().value());
        assertArrayEquals(bytes("acme"), kv.get(ACME, "shared").orElseThrow().value());
        kv.deleteTree(ACME, "", 4);
        assertTrue(kv.get(DEFAULT, "shared").isPresent(), "a tree delete stays in its scope");
        assertEquals(List.of(), kv.list(ACME, ""));
    }

    @Test
    void listingIsInKeyOrderUnderThePrefix() {
        KvState kv = new KvState();
        for (String key : List.of("b/2", "a", "b/10", "b/1", "c")) kv.set(DEFAULT, key, bytes(key), 0, ANY, 2);

        assertEquals(List.of("b/1", "b/10", "b/2"), kv.list(DEFAULT, "b/").stream().map(KvEntry::key).toList());
        assertEquals(List.of("a", "b/1", "b/10", "b/2", "c"),
                kv.list(DEFAULT, "").stream().map(KvEntry::key).toList());
    }

    @Test
    void keysStopAtTheFirstSeparatorAfterThePrefixAsInConsul() {
        KvState kv = new KvState();
        for (String key : List.of("web/config/a", "web/config/b", "web/name", "worker/x", "w"))
            kv.set(DEFAULT, key, bytes(key), 0, ANY, 2);

        assertEquals(List.of("w", "web/", "worker/"), kv.keys(DEFAULT, "", "/"));
        assertEquals(List.of("web/config/", "web/name"), kv.keys(DEFAULT, "web/", "/"));
        assertEquals(List.of("web/config/a", "web/config/b", "web/name"), kv.keys(DEFAULT, "web/", null));
    }

    @Test
    void aKeyMustBeNamedAndNotStartWithASlash() {
        KvState kv = new KvState();

        assertThrows(IllegalArgumentException.class, () -> kv.set(DEFAULT, "", bytes("x"), 0, ANY, 2));
        assertThrows(IllegalArgumentException.class, () -> kv.set(DEFAULT, "/a", bytes("x"), 0, ANY, 2));
        assertThrows(IllegalArgumentException.class, () -> kv.delete(DEFAULT, "", ANY, 2));
    }

    @Test
    void theSnapshotRestoresEveryEntryAndTheLastIndex() {
        KvState kv = new KvState();
        kv.set(DEFAULT, "a", bytes("1"), 7, ANY, 2);
        kv.set(ACME, "b", new byte[] {0, (byte) 0xff, 10}, 0, ANY, 3);

        KvState restored = new KvState();
        restored.restore(kv.snapshot());

        assertEquals(kv.list(DEFAULT, ""), restored.list(DEFAULT, ""));
        assertEquals(kv.list(ACME, ""), restored.list(ACME, ""));
        assertEquals(3, restored.lastIndex());
        restored.restore(null);
        assertEquals(List.of(), restored.list(DEFAULT, ""), "a snapshot from before key/value restores none");
        assertEquals(0, restored.lastIndex());
    }
}

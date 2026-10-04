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

package dev.mars.qraft.raft.api;

import dev.mars.qraft.raft.api.SnapshotStore.SnapshotData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link SnapshotData}: it refuses missing or empty data, a negative index or term, and a format version
 * below one, accepting each bound itself; it defaults to the current format version; and it copies its bytes
 * both in and out, so neither the caller's array nor a returned array can change the snapshot.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-28
 * @version 1.0
 */
class SnapshotDataTest {
    private static final byte[] BYTES = {1, 2, 3};

    @Test
    void invalidContentIsRefusedWithTheReason() {
        assertEquals("data", assertThrows(NullPointerException.class,
                () -> new SnapshotData(null, 1, 1)).getMessage());
        assertEquals("snapshot data must not be empty", assertThrows(IllegalArgumentException.class,
                () -> new SnapshotData(new byte[0], 1, 1)).getMessage());
        assertEquals("lastIncludedIndex must be non-negative", assertThrows(IllegalArgumentException.class,
                () -> new SnapshotData(BYTES, -1, 1)).getMessage());
        assertEquals("lastIncludedTerm must be non-negative", assertThrows(IllegalArgumentException.class,
                () -> new SnapshotData(BYTES, 1, -1)).getMessage());
        for (int version : new int[] {0, -1}) {
            assertEquals("formatVersion must be positive", assertThrows(IllegalArgumentException.class,
                    () -> new SnapshotData(BYTES, 1, 1, version)).getMessage());
        }
    }

    @Test
    void theLowestValidValuesAreAccepted() {
        SnapshotData lowest = new SnapshotData(new byte[] {0}, 0, 0, 1);

        assertEquals(0, lowest.lastIncludedIndex());
        assertEquals(0, lowest.lastIncludedTerm());
        assertEquals(1, lowest.formatVersion());
        assertArrayEquals(new byte[] {0}, lowest.data());
    }

    @Test
    void theThreeArgumentFormUsesTheCurrentFormatVersion() {
        assertEquals(SnapshotStore.CURRENT_FORMAT_VERSION, new SnapshotData(BYTES, 7, 3).formatVersion());
    }

    @Test
    void theBytesAreCopiedInAndOut() {
        byte[] source = BYTES.clone();
        SnapshotData snapshot = new SnapshotData(source, 7, 3);

        source[0] = 99;
        assertArrayEquals(BYTES, snapshot.data(), "changing the caller's array does not change the snapshot");

        byte[] returned = snapshot.data();
        returned[1] = 99;
        assertArrayEquals(BYTES, snapshot.data(), "changing a returned array does not change the snapshot");
        assertNotSame(snapshot.data(), snapshot.data(), "each read returns its own copy");
    }
}

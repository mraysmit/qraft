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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the storage status (observability list, Phase 3, decision 6): the bytes every file under the storage directory
 * holds, the log's and the snapshot's together, and the age of the published snapshot, or -1 with none; a directory
 * that does not exist holds nothing.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class StorageStatusTest {
    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

    @TempDir
    Path storage;

    @Test
    void theStorageHoldsEveryFilesBytesAndTheSnapshotIsAsOldAsItsFile() throws Exception {
        StorageStatus status = new StorageStatus(storage, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(0, status.bytes());
        assertEquals(-1, status.snapshotAgeSeconds());

        Files.write(storage.resolve("segment-1.log"), new byte[100]);
        Files.write(Files.createDirectories(storage.resolve("meta")).resolve("state"), new byte[50]);
        Path snapshot = Files.write(storage.resolve(StorageStatus.SNAPSHOT_FILE), new byte[25]);
        Files.setLastModifiedTime(snapshot, FileTime.from(NOW.minusSeconds(30)));

        assertEquals(175, status.bytes());
        assertEquals(30, status.snapshotAgeSeconds());
        assertEquals(0, new StorageStatus(storage.resolve("absent"), Clock.systemUTC()).bytes());
    }
}

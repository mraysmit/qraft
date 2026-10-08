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

import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.raftlog.storage.RaftStorage;
import dev.mars.raftlog.storage.RaftStorageConfig;

import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/**
 * Test helper executable launched in a separate JVM by {@link RaftNodeRealStorageRecoveryTest}
 * to prepare real write-ahead log (WAL) crash scenarios.
 *
 * <p>Truncates the WAL from the replacement index, appends the replacement there in term 2,
 * and syncs, halting the JVM at the checkpoint selected by the calling test. Halting skips
 * normal cleanup so the test can reopen the storage and assert recovery after a crash.
 *
 * <p>This class belongs in the test sources because it supplies a subprocess for the
 * test. Its {@code main} method prepares the crash; the calling test checks recovery.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-14
 * @version 1.1
 */
public final class RealStorageCrashWriterFixture {
    static final int HALT_EXIT_CODE = 91;

    private RealStorageCrashWriterFixture() {
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "expected: <directory> <checkpoint> <replacement-payload-base64> <replacement-index>");
        }

        Path directory = Path.of(args[0]);
        Checkpoint checkpoint = Checkpoint.valueOf(args[1]);
        byte[] replacementPayload = Base64.getDecoder().decode(args[2]);
        long replacementIndex = Long.parseLong(args[3]);
        FileRaftStorage wal = new FileRaftStorage(RaftStorageConfig.builder()
                .dataDir(directory)
                .syncEnabled(true)
                .build());

        wal.open(directory).join();
        wal.replayLog().join();
        wal.truncateSuffix(replacementIndex).join();
        haltAt(checkpoint, Checkpoint.AFTER_TRUNCATE);

        wal.appendEntries(List.of(new RaftStorage.LogEntryData(replacementIndex, 2, replacementPayload))).join();
        haltAt(checkpoint, Checkpoint.AFTER_APPEND);

        wal.sync().join();
        haltAt(checkpoint, Checkpoint.AFTER_SYNC);
        throw new AssertionError("unknown checkpoint: " + checkpoint);
    }

    private static void haltAt(Checkpoint selected, Checkpoint reached) {
        if (selected == reached) Runtime.getRuntime().halt(HALT_EXIT_CODE);
    }

    enum Checkpoint {
        AFTER_TRUNCATE,
        AFTER_APPEND,
        AFTER_SYNC
    }
}

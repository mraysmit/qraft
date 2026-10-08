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

package dev.mars.qraft.raft.storage.snapshot;

import dev.mars.qraft.raft.api.SnapshotStore;
import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.raftlog.storage.RaftStorageConfig;

import java.nio.file.Path;
import java.util.Base64;

/**
 * Test helper executable launched in a separate JVM by
 * {@link dev.mars.qraft.raft.RaftNodeRealSnapshotRecoveryTest}
 * to prepare crashes during snapshot persistence and write-ahead log (WAL) compaction.
 *
 * <p>Publishes the supplied snapshot bytes unchanged, including their configuration
 * envelope, and halts the JVM at the selected persistence checkpoint. For
 * {@link #AFTER_PREFIX_COMPACTION}, it first compacts the WAL up to the snapshot's last
 * included index. Halting skips normal cleanup so the test can assert crash recovery.
 *
 * <p>This class belongs in the test sources because it supplies a subprocess for the
 * test. Its {@code main} method prepares the crash; the calling test checks recovery.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-14
 * @version 1.1
 */
public final class SnapshotStoreCrashWriterFixture {
    public static final int HALT_EXIT_CODE = 92;
    public static final String AFTER_PUBLICATION_BEFORE_COMPACTION =
            "AFTER_PUBLICATION_BEFORE_COMPACTION";
    public static final String AFTER_PREFIX_COMPACTION = "AFTER_PREFIX_COMPACTION";

    private SnapshotStoreCrashWriterFixture() {
    }

    public static void main(String[] args) {
        if (args.length != 6) {
            throw new IllegalArgumentException("expected: <directory> <checkpoint> <snapshot-data-base64> "
                    + "<format-version> <last-included-index> <last-included-term>");
        }

        Path directory = Path.of(args[0]);
        String selected = args[1];
        byte[] snapshotData = Base64.getDecoder().decode(args[2]);
        int formatVersion = Integer.parseInt(args[3]);
        long lastIncludedIndex = Long.parseLong(args[4]);
        long lastIncludedTerm = Long.parseLong(args[5]);
        FileSnapshotStore snapshots = new FileSnapshotStore(
                checkpoint -> haltAt(selected, checkpoint.name()));
        snapshots.open(directory).join();
        snapshots.saveAtomically(new SnapshotStore.SnapshotData(
                snapshotData, lastIncludedIndex, lastIncludedTerm, formatVersion)).join();

        if (AFTER_PUBLICATION_BEFORE_COMPACTION.equals(selected)) {
            Runtime.getRuntime().halt(HALT_EXIT_CODE);
        }
        if (AFTER_PREFIX_COMPACTION.equals(selected)) {
            FileRaftStorage wal = new FileRaftStorage(RaftStorageConfig.builder()
                    .dataDir(directory)
                    .syncEnabled(true)
                    .build());
            wal.open(directory).join();
            wal.truncatePrefix(lastIncludedIndex).join();
            Runtime.getRuntime().halt(HALT_EXIT_CODE);
        }
        throw new AssertionError("checkpoint was not reached: " + selected);
    }

    private static void haltAt(String selected, String reached) {
        if (selected.equals(reached)) Runtime.getRuntime().halt(HALT_EXIT_CODE);
    }
}

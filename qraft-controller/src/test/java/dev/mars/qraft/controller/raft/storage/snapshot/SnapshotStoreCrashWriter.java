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

package dev.mars.qraft.controller.raft.storage.snapshot;

import dev.mars.qraft.raft.api.SnapshotStore;
import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.raftlog.storage.RaftStorageConfig;

import java.nio.file.Path;
import java.util.Base64;

/**
 * Child-process fixture that halts at an observable snapshot/WAL boundary. It publishes the snapshot bytes it is
 * given as they are, so a caller passes them as a node stores them, configuration envelope included, and then,
 * for {@link #AFTER_PREFIX_COMPACTION}, compacts the WAL up to the snapshot's last included index.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-14
 * @version 1.1
 */
public final class SnapshotStoreCrashWriter {
    public static final int HALT_EXIT_CODE = 92;
    public static final String AFTER_PUBLICATION_BEFORE_COMPACTION =
            "AFTER_PUBLICATION_BEFORE_COMPACTION";
    public static final String AFTER_PREFIX_COMPACTION = "AFTER_PREFIX_COMPACTION";

    private SnapshotStoreCrashWriter() {
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

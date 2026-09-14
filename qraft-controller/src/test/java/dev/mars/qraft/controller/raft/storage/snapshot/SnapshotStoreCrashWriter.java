/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.mars.qraft.controller.raft.storage.snapshot;

import dev.mars.qraft.raft.api.SnapshotStore;
import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.raftlog.storage.RaftStorageConfig;

import java.nio.file.Path;
import java.util.Base64;

/** Child-process fixture that halts at an observable snapshot/WAL boundary. */
public final class SnapshotStoreCrashWriter {
    public static final int HALT_EXIT_CODE = 92;
    public static final String AFTER_PUBLICATION_BEFORE_COMPACTION =
            "AFTER_PUBLICATION_BEFORE_COMPACTION";
    public static final String AFTER_PREFIX_COMPACTION = "AFTER_PREFIX_COMPACTION";

    private SnapshotStoreCrashWriter() {
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "expected: <directory> <checkpoint> <snapshot-data-base64> <format-version>");
        }

        Path directory = Path.of(args[0]);
        String selected = args[1];
        byte[] snapshotData = Base64.getDecoder().decode(args[2]);
        int formatVersion = Integer.parseInt(args[3]);
        FileSnapshotStore snapshots = new FileSnapshotStore(
                checkpoint -> haltAt(selected, checkpoint.name()));
        snapshots.open(directory).join();
        snapshots.saveAtomically(new SnapshotStore.SnapshotData(
                snapshotData, 3, 2, formatVersion)).join();

        if (AFTER_PUBLICATION_BEFORE_COMPACTION.equals(selected)) {
            Runtime.getRuntime().halt(HALT_EXIT_CODE);
        }
        if (AFTER_PREFIX_COMPACTION.equals(selected)) {
            FileRaftStorage wal = new FileRaftStorage(RaftStorageConfig.builder()
                    .dataDir(directory)
                    .syncEnabled(true)
                    .build());
            wal.open(directory).join();
            wal.truncatePrefix(3).join();
            Runtime.getRuntime().halt(HALT_EXIT_CODE);
        }
        throw new AssertionError("checkpoint was not reached: " + selected);
    }

    private static void haltAt(String selected, String reached) {
        if (selected.equals(reached)) Runtime.getRuntime().halt(HALT_EXIT_CODE);
    }
}

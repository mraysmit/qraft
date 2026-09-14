/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.mars.qraft.controller.raft;

import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.raftlog.storage.RaftStorage;
import dev.mars.raftlog.storage.RaftStorageConfig;

import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/** Child-process fixture that halts at an observable real-WAL call boundary. */
public final class RealStorageCrashWriter {
    static final int HALT_EXIT_CODE = 91;

    private RealStorageCrashWriter() {
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            throw new IllegalArgumentException("expected: <directory> <checkpoint> <replacement-payload-base64>");
        }

        Path directory = Path.of(args[0]);
        Checkpoint checkpoint = Checkpoint.valueOf(args[1]);
        byte[] replacementPayload = Base64.getDecoder().decode(args[2]);
        FileRaftStorage wal = new FileRaftStorage(RaftStorageConfig.builder()
                .dataDir(directory)
                .syncEnabled(true)
                .build());

        wal.open(directory).join();
        wal.truncateSuffix(3).join();
        haltAt(checkpoint, Checkpoint.AFTER_TRUNCATE);

        wal.appendEntries(List.of(new RaftStorage.LogEntryData(3, 2, replacementPayload))).join();
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

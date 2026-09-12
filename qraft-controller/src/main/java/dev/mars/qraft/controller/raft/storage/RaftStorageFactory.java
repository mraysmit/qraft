/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.mars.qraft.controller.raft.storage;

import dev.mars.qraft.controller.raft.storage.snapshot.FileSnapshotStore;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.raft.api.SnapshotStore;

import java.nio.file.Path;

/** Opens the persistence components used by a durable Qraft node. */
public final class RaftStorageFactory {

    private RaftStorageFactory() {
    }

    /** Persistence dependencies separated according to their ownership. */
    public record DurableStorage(dev.mars.raftlog.storage.RaftStorage wal,
                                 SnapshotStore snapshots) {
        public DurableStorage {
            if (wal == null) throw new NullPointerException("wal");
            if (snapshots == null) throw new NullPointerException("snapshots");
        }
    }

    /**
     * Opens the external WAL and Qraft's application snapshot store.
     * If either open fails, both resources are closed before failure is returned.
     */
    public static Future<DurableStorage> createDurable(Path storagePath, boolean fsync) {
        var config = dev.mars.raftlog.storage.RaftStorageConfig.builder()
                .dataDir(storagePath)
                .syncEnabled(fsync)
                .build();
        dev.mars.raftlog.storage.RaftStorage wal =
                new dev.mars.raftlog.storage.FileRaftStorage(config);
        SnapshotStore snapshots = new FileSnapshotStore();

        return Future.fromCompletionStage(wal.open(storagePath))
                .compose(ignored -> Future.fromCompletionStage(snapshots.open(storagePath)))
                .map(ignored -> new DurableStorage(wal, snapshots))
                .recover(error -> {
                    try {
                        snapshots.close();
                    } catch (Exception closeError) {
                        error.addSuppressed(closeError);
                    }
                    try {
                        wal.close();
                    } catch (Exception closeError) {
                        error.addSuppressed(closeError);
                    }
                    return Future.failedFuture(error);
                });
    }
}

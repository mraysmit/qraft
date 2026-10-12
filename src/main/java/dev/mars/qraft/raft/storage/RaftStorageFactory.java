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

package dev.mars.qraft.raft.storage;

import dev.mars.qraft.raft.storage.snapshot.FileSnapshotStore;
import dev.mars.qraft.common.concurrent.Completions;
import dev.mars.qraft.raft.api.SnapshotStore;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Opens the persistence components used by a durable Qraft node.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
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
    public static CompletableFuture<DurableStorage> createDurable(Path storagePath, boolean fsync) {
        var config = dev.mars.raftlog.storage.RaftStorageConfig.builder()
                .dataDir(storagePath)
                .syncEnabled(fsync)
                .build();
        dev.mars.raftlog.storage.RaftStorage wal =
                new dev.mars.raftlog.storage.FileRaftStorage(config);
        SnapshotStore snapshots = new FileSnapshotStore();

        // The result is failed directly, so that its callbacks see the opening failure and not a wrapper.
        CompletableFuture<DurableStorage> result = new CompletableFuture<>();
        wal.open(storagePath)
                .thenCompose(ignored -> snapshots.open(storagePath))
                .whenComplete((ignored, error) -> {
                    if (error == null) {
                        result.complete(new DurableStorage(wal, snapshots));
                        return;
                    }
                    Throwable openingFailure = Completions.unwrap(error);
                    closeFailure(snapshots::closeAsync).thenCompose(snapshotFailure ->
                            closeFailure(wal::closeAsync).thenAccept(walFailure -> {
                                addSuppressed(openingFailure, snapshotFailure);
                                addSuppressed(openingFailure, walFailure);
                            })).whenComplete((closed, unexpected) -> {
                                addSuppressed(openingFailure, unexpected);
                                result.completeExceptionally(openingFailure);
                            });
                });
        return result;
    }

    /** Completes with the failure of {@code close}, or with {@code null} if it succeeds; never fails. */
    private static CompletableFuture<Throwable> closeFailure(Supplier<? extends CompletionStage<?>> close) {
        try {
            return close.get().handle((ignored, error) -> Completions.unwrap(error)).toCompletableFuture();
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(error);
        }
    }

    private static void addSuppressed(Throwable primary, Throwable secondary) {
        if (secondary != null && secondary != primary) primary.addSuppressed(secondary);
    }
}

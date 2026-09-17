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

import dev.mars.qraft.raft.api.SnapshotStore;
import dev.mars.qraft.raft.api.SnapshotStore.SnapshotData;
import dev.mars.raftlog.storage.RaftStorage;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Capability-guarded persistence boundary for a Raft node.
 *
 * <p>Reads and lifecycle operations are available directly. Every mutation
 * requires the opaque ownership capability of the exact transition that
 * initiated it, including mutations started by asynchronous continuations.</p>
 */
final class RaftPersistence {
    private final Optional<RaftStorage> wal;
    private final Optional<SnapshotStore> snapshots;

    RaftPersistence(Optional<RaftStorage> wal, Optional<SnapshotStore> snapshots) {
        this.wal = wal;
        this.snapshots = snapshots;
    }

    boolean isDurable() {
        return wal.isPresent();
    }

    CompletableFuture<RaftStorage.PersistentMeta> loadMetadata() {
        return wal.orElseThrow().loadMetadata();
    }

    CompletableFuture<List<LogEntryData>> replayLog() {
        return wal.orElseThrow().replayLog();
    }

    CompletableFuture<Optional<SnapshotData>> loadLatestSnapshot() {
        return snapshots.orElseThrow().loadLatest();
    }

    CompletableFuture<Void> updateMetadata(
            RaftTransitionSequencer.Ownership ownership,
            long term, Optional<String> votedFor) {
        ownership.assertActive();
        return wal.orElseThrow().updateMetadata(term, votedFor);
    }

    CompletableFuture<Void> appendEntries(
            RaftTransitionSequencer.Ownership ownership, List<LogEntryData> entries) {
        ownership.assertActive();
        return wal.orElseThrow().appendEntries(entries);
    }

    CompletableFuture<Void> truncateSuffix(
            RaftTransitionSequencer.Ownership ownership, long fromIndex) {
        ownership.assertActive();
        return wal.orElseThrow().truncateSuffix(fromIndex);
    }

    CompletableFuture<Void> truncatePrefix(
            RaftTransitionSequencer.Ownership ownership, long throughIndex) {
        ownership.assertActive();
        return wal.orElseThrow().truncatePrefix(throughIndex);
    }

    CompletableFuture<Void> sync(RaftTransitionSequencer.Ownership ownership) {
        ownership.assertActive();
        return wal.orElseThrow().sync();
    }

    CompletableFuture<Void> saveSnapshot(
            RaftTransitionSequencer.Ownership ownership, SnapshotData snapshot) {
        ownership.assertActive();
        return snapshots.orElseThrow().saveAtomically(snapshot);
    }

    SnapshotStore snapshotStoreForClose() {
        return snapshots.orElse(null);
    }

    RaftStorage walForClose() {
        return wal.orElse(null);
    }

    boolean sharesCloseTarget() {
        return wal.orElse(null) == snapshots.orElse(null);
    }
}

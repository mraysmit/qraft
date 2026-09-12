package dev.mars.qraft.controller.raft;

import dev.mars.raftlog.storage.RaftStorage;
import dev.mars.qraft.raft.api.SnapshotStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Purpose-built, non-durable test fixture for the two persistence contracts used
 * by {@link RaftNode}. Production code must use the real file implementations.
 */
final class TestRaftStorage implements RaftStorage, SnapshotStore {
    private long currentTerm;
    private Optional<String> votedFor = Optional.empty();
    private final List<LogEntryData> log = new ArrayList<>();
    private SnapshotData snapshot;
    private boolean opened;
    private boolean closed;
    private boolean failOnSync;
    private boolean failOnAppend;
    private boolean failOnMetadataUpdate;

    @Override
    public CompletableFuture<Void> open(Path dataDir) {
        if (closed) return failed(new IllegalStateException("Storage has been closed"));
        opened = true;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> updateMetadata(long term, Optional<String> candidate) {
        if (!isOpen()) return failed(new IllegalStateException("Storage not open"));
        if (failOnMetadataUpdate) return failed(new IOException("Simulated metadata update failure"));
        currentTerm = term;
        votedFor = candidate;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<PersistentMeta> loadMetadata() {
        if (closed) return failed(new IllegalStateException("Storage has been closed"));
        return CompletableFuture.completedFuture(new PersistentMeta(currentTerm, votedFor));
    }

    @Override
    public CompletableFuture<Void> appendEntries(List<LogEntryData> entries) {
        if (!isOpen()) return failed(new IllegalStateException("Storage not open"));
        if (failOnAppend) return failed(new IOException("Simulated append failure"));
        log.addAll(entries);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> truncateSuffix(long fromIndex) {
        if (!isOpen()) return failed(new IllegalStateException("Storage not open"));
        log.removeIf(entry -> entry.index() >= fromIndex);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> truncatePrefix(long toIndex) {
        if (!isOpen()) return failed(new IllegalStateException("Storage not open"));
        log.removeIf(entry -> entry.index() <= toIndex);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sync() {
        if (!isOpen()) return failed(new IllegalStateException("Storage not open"));
        if (failOnSync) return failed(new IOException("Simulated sync failure"));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<LogEntryData>> replayLog() {
        if (closed) return failed(new IllegalStateException("Storage has been closed"));
        return CompletableFuture.completedFuture(new ArrayList<>(log));
    }

    @Override
    public CompletableFuture<Void> saveAtomically(SnapshotData value) {
        if (!isOpen()) return failed(new IllegalStateException("Storage not open"));
        snapshot = new SnapshotData(value.data(), value.lastIncludedIndex(),
                value.lastIncludedTerm(), value.formatVersion());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Optional<SnapshotData>> loadLatest() {
        if (closed) return failed(new IllegalStateException("Storage has been closed"));
        return CompletableFuture.completedFuture(Optional.ofNullable(snapshot));
    }

    @Override
    public void close() {
        opened = false;
        closed = true;
    }

    void setFailOnSync(boolean fail) { failOnSync = fail; }
    void setFailOnAppend(boolean fail) { failOnAppend = fail; }
    void setFailOnMetadataUpdate(boolean fail) { failOnMetadataUpdate = fail; }
    List<LogEntryData> getLog() { return Collections.unmodifiableList(log); }
    long getCurrentTerm() { return currentTerm; }
    Optional<String> getVotedFor() { return votedFor; }
    boolean isOpen() { return opened && !closed; }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        return CompletableFuture.failedFuture(error);
    }
}

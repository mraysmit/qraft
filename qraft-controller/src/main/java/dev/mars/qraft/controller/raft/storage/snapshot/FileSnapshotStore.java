package dev.mars.qraft.controller.raft.storage.snapshot;

import dev.mars.qraft.raft.api.SnapshotStore;
import dev.mars.qraft.raft.api.SnapshotStore.PublicationOutcome;
import dev.mars.qraft.raft.api.SnapshotStore.SnapshotPublicationException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

import static java.util.Objects.requireNonNull;

/** Atomic, checksummed file storage for application-owned Raft snapshots. */
public final class FileSnapshotStore implements SnapshotStore {

    private static final int MAGIC = 0x5152534E; // QRSN
    private static final short FILE_VERSION = 1;
    private static final int NEW_HEADER_SIZE = Integer.BYTES + Short.BYTES
            + Long.BYTES + Long.BYTES + Integer.BYTES;
    private static final int LEGACY_HEADER_SIZE = Long.BYTES + Long.BYTES + Integer.BYTES;
    private static final int CRC_SIZE = Integer.BYTES;
    private static final String SNAPSHOT_FILE = "snapshot.dat";
    private static final String TEMP_FILE = "snapshot.dat.tmp";

    private final PersistenceObserver persistenceObserver;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("qraft-snapshot-store").factory());
    private volatile Path directory;
    private volatile boolean opened;
    private volatile boolean closed;
    private CompletableFuture<Void> closeFuture;

    public FileSnapshotStore() {
        this(checkpoint -> { });
    }

    FileSnapshotStore(PersistenceObserver persistenceObserver) {
        this.persistenceObserver = requireNonNull(persistenceObserver, "persistenceObserver");
    }

    @Override
    public CompletableFuture<Void> open(Path directory) {
        return run(() -> {
            if (opened) return;
            if (closed) throw new IllegalStateException("Snapshot store is closed");
            this.directory = directory.toAbsolutePath().normalize();
            Files.createDirectories(this.directory);
            Path temporary = this.directory.resolve(TEMP_FILE);
            if (Files.exists(temporary)) {
                Path published = this.directory.resolve(SNAPSHOT_FILE);
                if (!Files.exists(published)) {
                    throw new IOException("Refusing startup because unpublished first snapshot "
                            + temporary + " has no published " + published
                            + "; preserve the file for diagnosis and restore this node from its peers");
                }
                Files.delete(temporary);
            }
            opened = true;
        });
    }

    @Override
    public CompletableFuture<Void> saveAtomically(SnapshotData snapshot) {
        return CompletableFuture.runAsync(() -> saveWithPublicationOutcome(snapshot), executor);
    }

    private void saveWithPublicationOutcome(SnapshotData snapshot) {
        PublicationOutcome outcome = PublicationOutcome.NOT_PUBLISHED;
        try {
            requireOpen();
            byte[] encoded = encode(snapshot);
            Path temporary = directory.resolve(TEMP_FILE);
            Path published = directory.resolve(SNAPSHOT_FILE);
            persistenceObserver.reached(PersistenceCheckpoint.BEFORE_TEMPORARY_CREATE);
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                writeFully(channel, ByteBuffer.wrap(encoded));
                persistenceObserver.reached(PersistenceCheckpoint.AFTER_TEMPORARY_WRITE);
                channel.force(true);
                persistenceObserver.reached(PersistenceCheckpoint.AFTER_TEMPORARY_FORCE);
            }
            outcome = PublicationOutcome.PUBLICATION_MAY_HAVE_OCCURRED;
            Files.move(temporary, published,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            persistenceObserver.reached(PersistenceCheckpoint.AFTER_ATOMIC_PUBLICATION);
            forceDirectory(directory);
            persistenceObserver.reached(PersistenceCheckpoint.AFTER_DIRECTORY_FORCE);
        } catch (IOException | RuntimeException error) {
            if (error instanceof SnapshotPublicationException publicationFailure) {
                throw publicationFailure;
            }
            throw new SnapshotPublicationException(
                    outcome, "Failed to publish snapshot atomically", error);
        }
    }

    @Override
    public CompletableFuture<Optional<SnapshotData>> loadLatest() {
        return supply(() -> {
            requireOpen();
            Path published = directory.resolve(SNAPSHOT_FILE);
            try {
                return Optional.of(decode(Files.readAllBytes(published)));
            } catch (NoSuchFileException missing) {
                return Optional.empty();
            }
        });
    }

    @Override
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closeFuture != null) return closeFuture;
        closed = true;
        opened = false;
        closeFuture = new CompletableFuture<>();
        CompletableFuture<Void> completion = closeFuture;
        try {
            executor.shutdown();
            Thread.startVirtualThread(() -> {
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        completion.completeExceptionally(new IllegalStateException(
                                "Timed out waiting for snapshot store executor shutdown"));
                    } else {
                        completion.complete(null);
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    completion.completeExceptionally(error);
                }
            });
        } catch (RuntimeException error) {
            completion.completeExceptionally(error);
        }
        return completion;
    }

    @Override
    public void close() {
        closeAsync();
    }

    private static byte[] encode(SnapshotData snapshot) {
        byte[] data = snapshot.data();
        int withoutCrc = Math.addExact(NEW_HEADER_SIZE, data.length);
        ByteBuffer output = ByteBuffer.allocate(Math.addExact(withoutCrc, CRC_SIZE));
        output.putInt(MAGIC);
        output.putShort(FILE_VERSION);
        output.putLong(snapshot.lastIncludedIndex());
        output.putLong(snapshot.lastIncludedTerm());
        output.putInt(data.length);
        output.put(data);
        CRC32C crc = new CRC32C();
        crc.update(output.array(), 0, withoutCrc);
        output.putInt((int) crc.getValue());
        return output.array();
    }

    private static SnapshotData decode(byte[] bytes) throws IOException {
        if (bytes.length < LEGACY_HEADER_SIZE + CRC_SIZE) {
            throw new IOException("Corrupt snapshot.dat: file too short");
        }
        ByteBuffer input = ByteBuffer.wrap(bytes);
        boolean currentFormat = input.getInt(0) == MAGIC;
        int headerSize;
        int formatVersion;
        long index;
        long term;
        int dataLength;
        if (currentFormat) {
            if (bytes.length < NEW_HEADER_SIZE + CRC_SIZE) {
                throw new IOException("Corrupt snapshot.dat: incomplete header");
            }
            input.getInt();
            short fileVersion = input.getShort();
            if (fileVersion != FILE_VERSION) {
                throw new IOException("Unsupported snapshot file version: " + fileVersion);
            }
            headerSize = NEW_HEADER_SIZE;
            formatVersion = fileVersion;
            index = input.getLong();
            term = input.getLong();
            dataLength = input.getInt();
        } else {
            headerSize = LEGACY_HEADER_SIZE;
            formatVersion = CURRENT_FORMAT_VERSION;
            index = input.getLong();
            term = input.getLong();
            dataLength = input.getInt();
        }
        long expectedLength = (long) headerSize + dataLength + CRC_SIZE;
        if (index < 0 || term < 0 || dataLength <= 0 || expectedLength != bytes.length) {
            throw new IOException("Corrupt snapshot.dat: invalid boundary or data length");
        }
        byte[] data = new byte[dataLength];
        input.get(data);
        int storedCrc = input.getInt();
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length - CRC_SIZE);
        if ((int) crc.getValue() != storedCrc) {
            throw new IOException("Corrupt snapshot.dat: CRC mismatch");
        }
        return new SnapshotData(data, index, term, formatVersion);
    }

    private void requireOpen() {
        if (!opened || closed || directory == null) {
            throw new IllegalStateException("Snapshot store is not open");
        }
    }

    private CompletableFuture<Void> run(IoRunnable operation) {
        return CompletableFuture.runAsync(() -> {
            try {
                operation.run();
            } catch (IOException error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }, executor);
    }

    private <T> CompletableFuture<T> supply(IoSupplier<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.get();
            } catch (IOException error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }, executor);
    }

    private static void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) channel.write(bytes);
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (AccessDeniedException denied) {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
                throw denied;
            }
        }
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run() throws IOException;
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    enum PersistenceCheckpoint {
        BEFORE_TEMPORARY_CREATE,
        AFTER_TEMPORARY_WRITE,
        AFTER_TEMPORARY_FORCE,
        AFTER_ATOMIC_PUBLICATION,
        AFTER_DIRECTORY_FORCE
    }

    @FunctionalInterface
    interface PersistenceObserver {
        void reached(PersistenceCheckpoint checkpoint);
    }
}

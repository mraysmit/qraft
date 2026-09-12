package dev.mars.qraft.controller.raft.storage.snapshot;

import dev.mars.qraft.raft.api.SnapshotStore;

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
import java.util.zip.CRC32C;

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

    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("qraft-snapshot-store").factory());
    private volatile Path directory;
    private volatile boolean opened;
    private volatile boolean closed;

    @Override
    public CompletableFuture<Void> open(Path directory) {
        return run(() -> {
            if (opened) return;
            if (closed) throw new IllegalStateException("Snapshot store is closed");
            this.directory = directory.toAbsolutePath().normalize();
            Files.createDirectories(this.directory);
            Path current = this.directory.resolve(SNAPSHOT_FILE);
            Path temporary = this.directory.resolve(TEMP_FILE);
            if (Files.exists(temporary)) {
                if (!Files.exists(current)) {
                    throw new IOException("Unpublished snapshot exists without snapshot.dat; preserve directory for recovery");
                }
                Files.delete(temporary);
            }
            opened = true;
        });
    }

    @Override
    public CompletableFuture<Void> saveAtomically(SnapshotData snapshot) {
        return run(() -> {
            requireOpen();
            byte[] encoded = encode(snapshot);
            Path temporary = directory.resolve(TEMP_FILE);
            Path published = directory.resolve(SNAPSHOT_FILE);
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                writeFully(channel, ByteBuffer.wrap(encoded));
                channel.force(true);
            }
            Files.move(temporary, published,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(directory);
        });
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
    public void close() {
        if (closed) return;
        closed = true;
        opened = false;
        executor.close();
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
}

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

package dev.mars.qraft.client;

import dev.mars.qraft.common.storage.IdentityFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A client's data directory, {@code client.dataDirectory}, held for as long as the client runs. Opening it takes an
 * exclusive lock on {@value #LOCK_FILE}, so a directory belongs to one client at a time, and then loads the node ID
 * kept in {@code node-id}, generating it at the first start.
 *
 * <p>The lock comes first, so two clients starting on one empty directory cannot both generate a node ID. A node ID
 * that cannot be read stops the open, and the lock is let go again. The node ID lives as long as the directory: a
 * client whose directory was wiped is a new node.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-27
 * @version 1.0
 */
public final class ClientDataDirectory implements AutoCloseable {
    static final String LOCK_FILE = "client.lock";
    private static final IdentityFile NODE_ID = new IdentityFile("node-id", "node ID", "this client");

    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;
    private final String nodeId;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ClientDataDirectory(Path path, FileChannel channel, FileLock lock, String nodeId) {
        this.path = path;
        this.channel = channel;
        this.lock = lock;
        this.nodeId = nodeId;
    }

    /**
     * Creates {@code directory} if it does not exist, locks it, and loads or creates its node ID.
     *
     * @throws IllegalStateException if another client holds the directory, or its node ID cannot be read
     */
    public static ClientDataDirectory open(Path directory) throws IOException {
        Files.createDirectories(directory);
        FileChannel channel = FileChannel.open(directory.resolve(LOCK_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException heldHere) {
            channel.close();
            throw new IllegalStateException(directory + " is in use by another client in this process; a data"
                    + " directory belongs to one client at a time", heldHere);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
        if (lock == null) {
            channel.close();
            throw new IllegalStateException(directory + " is in use by another client process; a data directory"
                    + " belongs to one client at a time");
        }
        try {
            return new ClientDataDirectory(directory, channel, lock, NODE_ID.loadOrCreate(directory));
        } catch (IOException | RuntimeException failure) {
            try {
                release(lock, channel);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** The directory, as configured. */
    public Path path() {
        return path;
    }

    /** The node ID kept in the directory. */
    public String nodeId() {
        return nodeId;
    }

    /** Lets the directory go. Later calls do nothing. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            release(lock, channel);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not release the client data directory " + path, failure);
        }
    }

    private static void release(FileLock lock, FileChannel channel) throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}

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

package dev.mars.qraft.server.recovery;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Holds the exclusive lock on a stopped data directory's {@code raft.lock}, the file RaftLog locks for as long as
 * a server uses the directory. While it is held, no server can open the directory, so a copy is never taken of a
 * directory in use. The file is opened, never created or written: the directory is left as it was.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-22
 * @version 1.0
 */
final class SourceLock implements AutoCloseable {
    static final String FILE = "raft.lock";

    private final FileChannel channel;
    private final FileLock lock;

    private SourceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Locks {@code directory}, or refuses it if it has no lock file or a server holds the lock. */
    static SourceLock acquire(Path directory) throws RecoveryRefusedException, IOException {
        Path file = directory.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            throw new RecoveryRefusedException(directory + " has no " + FILE
                    + "; it is not a complete Qraft data directory");
        }
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) return new SourceLock(channel, lock);
            channel.close();
            throw new RecoveryRefusedException(directory + " is locked by another process; stop the server that "
                    + "uses it before inspecting or recovering it");
        } catch (OverlappingFileLockException heldHere) {
            channel.close();
            throw new RecoveryRefusedException(directory + " is locked by storage open in this process; close it "
                    + "before inspecting or recovering the directory", heldHere);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}

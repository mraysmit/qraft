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

package dev.mars.qraft.common.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Durable, atomic writes of a small file: a reader finds the old content or the new, and the new is on disk before
 * the write returns.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-31
 * @version 1.0
 */
public final class DurableFile {
    private DurableFile() {
    }

    /**
     * Writes {@code content} through a temporary file beside {@code file}, forces it, moves it into place with
     * {@code moveOptions}, and forces the directory. When this fails, what {@code file} held is untouched.
     */
    public static void write(Path file, byte[] content, CopyOption... moveOptions) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(content);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        Files.move(temporary, file, moveOptions);
        forceDirectory(file.getParent());
    }

    /** Forces {@code directory}'s entries to disk, where the platform allows a directory to be opened for it. */
    /**
     * As {@link #write}, for a file that holds a secret: on a file system with POSIX permissions it is readable and
     * writable by its owner alone, {@code rw-------}, from the moment it is created (ACL list, proposal 3). Elsewhere,
     * as on Windows, it takes the permissions of its directory, which a deployment restricts.
     */
    public static void writePrivate(Path file, byte[] content, CopyOption... moveOptions) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        boolean posix = Files.getFileStore(file.getParent()).supportsFileAttributeView("posix");
        java.util.Set<java.nio.file.attribute.PosixFilePermission> ownerOnly =
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
        java.nio.file.attribute.FileAttribute<?>[] attributes = posix
                ? new java.nio.file.attribute.FileAttribute<?>[] {
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(ownerOnly)}
                : new java.nio.file.attribute.FileAttribute<?>[0];
        try (FileChannel channel = FileChannel.open(temporary, java.util.Set.of(StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), attributes)) {
            // A temporary file left by an earlier failure keeps its permissions, so they are set again.
            if (posix) Files.setPosixFilePermissions(temporary, ownerOnly);
            ByteBuffer bytes = ByteBuffer.wrap(content);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        Files.move(temporary, file, moveOptions);
        forceDirectory(file.getParent());
    }

    public static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (AccessDeniedException denied) {
            // Windows cannot open a directory for forcing; its rename is already durable there.
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
                throw denied;
            }
        }
    }
}

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

import dev.mars.qraft.raft.storage.ServerIdentity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Copies a locked data directory into a new one, byte for byte, and proves the copy (Step 7 contract, step 2).
 * Every file is copied, forced and compared by SHA-256 with its source, except RaftLog's {@code raft.lock}: it
 * holds no data, and the lock held on it blocks reading it through another handle on Windows, so it is recreated
 * empty. The source is only read.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-22
 * @version 1.0
 */
final class DirectoryCopy {
    private DirectoryCopy() { }

    /** Copies {@code source} into {@code target}, which must not exist, and verifies every copied file. */
    static void copy(Path source, Path target) throws RecoveryRefusedException, IOException {
        List<Path> files = new ArrayList<>();
        List<Path> directories = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path copy = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(copy);
                    directories.add(copy);
                } else if (Files.isRegularFile(path)) {
                    if (isLockFile(source, path)) {
                        Files.createFile(copy);
                    } else {
                        Files.copy(path, copy);
                        files.add(path);
                    }
                    force(copy);
                } else {
                    throw new RecoveryRefusedException(path + " is neither a file nor a directory; a Qraft data "
                            + "directory holds only files and directories");
                }
            }
        }
        for (Path file : files) {
            Path copy = target.resolve(source.relativize(file).toString());
            if (!Arrays.equals(sha256(file), sha256(copy))) {
                throw new RecoveryRefusedException("The copy of " + file + " does not match its source");
            }
        }
        for (Path directory : directories.reversed()) ServerIdentity.forceDirectory(directory);
    }

    /** Deletes {@code directory} and everything under it, if it exists. */
    static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static boolean isLockFile(Path source, Path path) {
        return path.getParent().equals(source) && path.getFileName().toString().equals(SourceLock.FILE);
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static byte[] sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", missing);
        }
    }
}

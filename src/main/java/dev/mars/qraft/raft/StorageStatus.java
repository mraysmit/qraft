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

package dev.mars.qraft.raft;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * The Raft storage's status (observability list, Phase 3, decision 6), read from its directory: the bytes every file
 * under it holds, the log's and the snapshot's together, and the age of the published snapshot. A file the log removes
 * while the directory is read is left out, as it no longer holds anything.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class StorageStatus {
    /** The published snapshot's file in the storage directory, as {@code FileSnapshotStore} writes it. */
    public static final String SNAPSHOT_FILE = "snapshot.dat";

    private final Path directory;
    private final Clock clock;

    public StorageStatus(Path directory, Clock clock) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The bytes the storage holds; 0 for a directory that does not exist. */
    public long bytes() {
        if (!Files.isDirectory(directory)) return 0;
        long[] total = {0};
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) total[0] += attributes.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException removed) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException unreadable) {
            return total[0];
        }
        return total[0];
    }

    /** Seconds since the published snapshot was written, or -1 when there is none. */
    public long snapshotAgeSeconds() {
        try {
            return Math.max(0, Duration.between(Files.getLastModifiedTime(directory.resolve(SNAPSHOT_FILE)).toInstant(),
                    clock.instant()).toSeconds());
        } catch (IOException none) {
            return -1;
        }
    }
}

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
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A durable identity: a UUID generated at the first start and kept in one file of a data directory. A server keeps
 * its Raft identity in {@code server-id}, a client its node ID in {@code node-id}.
 *
 * <p>A new ID is durable before it is returned. A file that exists but cannot be read stops the start: replacing it
 * would silently change the owner's identity.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-27
 * @version 1.0
 */
public final class IdentityFile {
    private final String fileName;
    private final String idName;
    private final String owner;

    /**
     * @param fileName the file in the data directory, for example {@code node-id}
     * @param idName   what the ID is called in a message, for example {@code node ID}
     * @param owner    whose identity it is in a message, for example {@code this client}
     */
    public IdentityFile(String fileName, String idName, String owner) {
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.idName = Objects.requireNonNull(idName, "idName");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /** The name of the file the ID is kept in. */
    public String fileName() {
        return fileName;
    }

    /**
     * Returns the ID kept in {@code directory}, creating the directory and the ID if there is none.
     *
     * @throws IllegalStateException if the file exists but does not hold an ID
     */
    public String loadOrCreate(Path directory) throws IOException {
        Path file = directory.resolve(fileName);
        if (Files.exists(file)) {
            return read(file);
        }
        Files.createDirectories(directory);
        String id = UUID.randomUUID().toString();
        writeDurably(directory, id, StandardCopyOption.ATOMIC_MOVE);
        return id;
    }

    /**
     * Returns the ID kept in {@code directory} without creating one, or empty if there is no ID file.
     *
     * @throws IllegalStateException if the file exists but does not hold an ID
     */
    public Optional<String> existing(Path directory) throws IOException {
        Path file = directory.resolve(fileName);
        return Files.exists(file) ? Optional.of(read(file)) : Optional.empty();
    }

    /** Gives {@code directory} the ID {@code id}, durably and atomically, replacing any it holds. */
    public void replace(Path directory, String id) throws IOException {
        if (!UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("A " + idName + " is a canonical UUID: " + id);
        }
        writeDurably(directory, id, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Forces {@code directory}'s entries to disk, where the platform allows a directory to be opened for it. */
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

    private void writeDurably(Path directory, String id, StandardCopyOption... moveOptions) throws IOException {
        Path temporary = directory.resolve(fileName + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        Files.move(temporary, directory.resolve(fileName), moveOptions);
        forceDirectory(directory);
    }

    private String read(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        try {
            if (UUID.fromString(content).toString().equals(content)) {
                return content;
            }
        } catch (IllegalArgumentException notAUuid) {
            // Reported below with the file named.
        }
        throw new IllegalStateException("The " + idName + " in " + file + " cannot be read. It is not replaced, "
                + "because that would change " + owner + "'s identity; restore the file, or remove the whole "
                + "data directory to start " + owner + " as a new one");
    }
}

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

package dev.mars.qraft.controller.raft.storage;

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
import java.util.UUID;

/**
 * A server's durable Raft identity: a UUID generated at its first start and kept in {@value #FILE} in the Raft
 * data directory, next to the WAL and snapshots.
 *
 * <p>The identity lives with the storage. A server that restarts with its storage keeps its ID; a server whose
 * storage was wiped gets a new one, so it can never pass for the server whose votes and log it lost. A data
 * directory from before server IDs gets one at its next start, since its storage makes it the same server. A
 * file that exists but cannot be read stops the start: replacing it would silently change the server's
 * identity.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
public final class ServerIdentity {
    static final String FILE = "server-id";
    private static final String TEMPORARY_FILE = FILE + ".tmp";

    private ServerIdentity() {
    }

    /**
     * Returns the server ID kept in {@code directory}, creating the directory and the ID if there is none. A new
     * ID is durable before this returns.
     *
     * @throws IllegalStateException if the ID file exists but does not hold a server ID
     */
    public static String loadOrCreate(Path directory) throws IOException {
        Path file = directory.resolve(FILE);
        if (Files.exists(file)) {
            return read(file);
        }
        Files.createDirectories(directory);
        String id = UUID.randomUUID().toString();
        Path temporary = directory.resolve(TEMPORARY_FILE);
        try (FileChannel channel = FileChannel.open(temporary,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(directory);
        return id;
    }

    private static String read(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        try {
            if (UUID.fromString(content).toString().equals(content)) {
                return content;
            }
        } catch (IllegalArgumentException notAUuid) {
            // Reported below with the file named.
        }
        throw new IllegalStateException("The server ID in " + file + " cannot be read. It is not replaced, "
                + "because that would change this server's identity; restore the file, or remove the whole "
                + "data directory to start this server as a new one");
    }

    private static void forceDirectory(Path directory) throws IOException {
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

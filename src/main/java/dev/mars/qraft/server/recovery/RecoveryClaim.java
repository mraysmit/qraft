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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.qraft.raft.storage.ServerIdentity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * The claim file kept beside a recovery's source, {@code <source>.qraft-recovery.json} (Step 7 contract, one-time
 * use). It is created new before the source is copied, so one source never seeds two clusters, and then records
 * the new server ID and the recovery entry once they exist, and finally that the output was published. It never
 * enters a data directory, and server startup never reads it.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-22
 * @version 1.0
 */
record RecoveryClaim(Path source, String sourceServerId, Path output, Optional<String> newServerId,
                     long recoveryIndex, long recoveryTerm, boolean published) {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int VERSION = 1;

    /** The claim file of {@code source}, beside it. */
    static Path fileOf(Path source) {
        return source.resolveSibling(source.getFileName() + ".qraft-recovery.json");
    }

    /** A claim made before copying: nothing is known yet but the source and the output. */
    static RecoveryClaim claimed(Path source, String sourceServerId, Path output) {
        return new RecoveryClaim(source, sourceServerId, output, Optional.empty(), 0, 0, false);
    }

    RecoveryClaim prepared(String serverId, long index, long term) {
        return new RecoveryClaim(source, sourceServerId, output, Optional.of(serverId), index, term, false);
    }

    RecoveryClaim publishedClaim() {
        return new RecoveryClaim(source, sourceServerId, output, newServerId, recoveryIndex, recoveryTerm, true);
    }

    /** Reads the claim beside {@code source}, if there is one; refused if it cannot be read. */
    static Optional<RecoveryClaim> read(Path source) throws RecoveryRefusedException, IOException {
        Path file = fileOf(source);
        if (!Files.exists(file)) return Optional.empty();
        try {
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (root == null || root.path("version").asInt() != VERSION) {
                throw new IllegalArgumentException("unsupported claim version");
            }
            JsonNode newServerId = root.path("newServerId");
            return Optional.of(new RecoveryClaim(Path.of(text(root, "source")), text(root, "sourceServerId"),
                    Path.of(text(root, "output")),
                    newServerId.isTextual() ? Optional.of(newServerId.asText()) : Optional.empty(),
                    root.path("recoveryIndex").asLong(), root.path("recoveryTerm").asLong(),
                    root.path("published").asBoolean()));
        } catch (IOException | IllegalArgumentException unreadable) {
            throw new RecoveryRefusedException("The recovery claim " + file + " cannot be read: "
                    + unreadable.getMessage() + ". Preserve it and the source; do not delete it to retry", unreadable);
        }
    }

    /** Creates the claim file, which must not exist, durably. */
    void create() throws IOException {
        Path file = fileOf(source);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            write(channel);
        }
        ServerIdentity.forceDirectory(file.getParent());
    }

    /** Replaces the claim file with this claim, durably and atomically. */
    void replace() throws IOException {
        Path file = fileOf(source);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            write(channel);
        }
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        ServerIdentity.forceDirectory(file.getParent());
    }

    /** Deletes the claim file, durably; only for a claim made by a run that refused before recording a new ID. */
    void release() throws IOException {
        Path file = fileOf(source);
        Files.deleteIfExists(file);
        ServerIdentity.forceDirectory(file.getParent());
    }

    private void write(FileChannel channel) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        root.put("source", source.toString());
        root.put("sourceServerId", sourceServerId);
        root.put("output", output.toString());
        if (newServerId.isPresent()) root.put("newServerId", newServerId.get());
        else root.putNull("newServerId");
        root.put("recoveryIndex", recoveryIndex);
        root.put("recoveryTerm", recoveryTerm);
        root.put("published", published);
        ByteBuffer bytes = ByteBuffer.wrap(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException(field + " is missing");
        return value.asText();
    }
}

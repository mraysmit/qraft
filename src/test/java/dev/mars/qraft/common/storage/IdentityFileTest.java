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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link IdentityFile}, the durable UUID that a server keeps in {@code server-id} and a client in
 * {@code node-id}: created once, reloaded after, never replaced when unreadable, and named in the owner's words.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-27
 * @version 1.0
 */
class IdentityFileTest {
    private static final IdentityFile NODE_ID = new IdentityFile("node-id", "node ID", "this client");

    @TempDir
    Path root;

    @Test
    void aFirstStartCreatesTheDirectoryAndAUuidInTheNamedFile() throws IOException {
        Path data = root.resolve("client");

        String id = NODE_ID.loadOrCreate(data);

        assertEquals(id, UUID.fromString(id).toString(), "a canonical UUID");
        assertEquals(id, Files.readString(data.resolve("node-id"), StandardCharsets.UTF_8));
        assertEquals(List.of("node-id"), fileNames(data), "no temporary file is left behind");
    }

    @Test
    void laterStartsReloadTheSameId() throws IOException {
        String first = NODE_ID.loadOrCreate(root);

        assertEquals(first, NODE_ID.loadOrCreate(root));
        assertEquals(Optional.of(first), NODE_ID.existing(root));
    }

    @Test
    void existingDoesNotCreateAnId() throws IOException {
        assertEquals(Optional.empty(), NODE_ID.existing(root));
        assertEquals(List.of(), fileNames(root));
    }

    @Test
    void anUnreadableIdIsRefusedInTheOwnersWordsAndNeverReplaced() throws IOException {
        Path file = root.resolve("node-id");
        Files.writeString(file, "not-a-uuid");

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> NODE_ID.loadOrCreate(root));

        assertEquals("The node ID in " + file + " cannot be read. It is not replaced, because that would change"
                + " this client's identity; restore the file, or remove the whole data directory to start this"
                + " client as a new one", refused.getMessage());
        assertEquals("not-a-uuid", Files.readString(file), "the file is left for the operator to inspect");
        assertThrows(IllegalStateException.class, () -> NODE_ID.existing(root));
    }

    @Test
    void replaceWritesACanonicalIdAndRefusesAnyOther() throws IOException {
        String id = UUID.randomUUID().toString();
        NODE_ID.loadOrCreate(root);

        NODE_ID.replace(root, id);

        assertEquals(id, NODE_ID.loadOrCreate(root));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> NODE_ID.replace(root, id.toUpperCase()));
        assertTrue(refused.getMessage().startsWith("A node ID is a canonical UUID: "), refused.getMessage());
        assertEquals(id, NODE_ID.loadOrCreate(root));
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }
}

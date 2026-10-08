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

package dev.mars.qraft.raft.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ServerIdentity}: a server's first start generates a UUID and keeps it in the Raft data directory;
 * later starts reload it; a wiped directory gives a new one; a data directory from before server IDs gets one
 * without its data being touched; and an ID file that cannot be read stops the start instead of being replaced.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
class ServerIdentityTest {

    @TempDir
    Path root;

    @Test
    void aFirstStartCreatesAUuidAndKeepsItInTheDataDirectory() throws IOException {
        Path data = root.resolve("raft");

        String id = ServerIdentity.loadOrCreate(data);

        assertEquals(id, UUID.fromString(id).toString(), "a canonical UUID");
        assertEquals(id, Files.readString(data.resolve("server-id"), StandardCharsets.UTF_8));
        assertEquals(List.of("server-id"), fileNames(data), "no temporary file is left behind");
    }

    @Test
    void laterStartsReloadTheSameId() throws IOException {
        String first = ServerIdentity.loadOrCreate(root);

        assertEquals(first, ServerIdentity.loadOrCreate(root));
        assertEquals(first, ServerIdentity.loadOrCreate(root));
    }

    @Test
    void aWipedDataDirectoryGivesANewId() throws IOException {
        Path data = root.resolve("raft");
        String before = ServerIdentity.loadOrCreate(data);

        deleteRecursively(data);

        assertNotEquals(before, ServerIdentity.loadOrCreate(data), "a wiped server is a new server");
    }

    @Test
    void aDataDirectoryFromBeforeServerIdsGetsOneAndKeepsItsData() throws IOException {
        Files.writeString(root.resolve("raft.wal"), "existing log");

        String id = ServerIdentity.loadOrCreate(root);

        assertEquals(id, ServerIdentity.loadOrCreate(root));
        assertEquals("existing log", Files.readString(root.resolve("raft.wal")));
    }

    @Test
    void anUnreadableIdStopsTheStartAndIsNeverReplaced() throws IOException {
        for (String unreadable : List.of("not-a-uuid", "", "6F9619FF-8B86-D011-B42D-00C04FC964FF")) {
            Path file = root.resolve("server-id");
            Files.writeString(file, unreadable);

            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> ServerIdentity.loadOrCreate(root));

            assertTrue(refused.getMessage().contains(file.toString()), refused.getMessage());
            assertEquals(unreadable, Files.readString(file), "the file is left for the operator to inspect");
        }
    }

    @Test
    void aTemporaryFileLeftByAnInterruptedFirstStartIsReplaced() throws IOException {
        Files.writeString(root.resolve("server-id.tmp"), "half-writ");

        String id = ServerIdentity.loadOrCreate(root);

        assertEquals(id, UUID.fromString(id).toString());
        assertEquals(List.of("server-id"), fileNames(root));
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
        assertFalse(Files.exists(directory));
    }
}

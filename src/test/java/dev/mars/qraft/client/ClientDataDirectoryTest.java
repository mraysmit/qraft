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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ClientDataDirectory} within one process: the first open creates the directory, its lock and a
 * node ID; the node ID lives as long as the directory; one client holds a directory at a time; and an unreadable
 * node ID stops the open without keeping the lock. A lock held by another process is tested in
 * {@link ClientDataDirectoryProcessLockTest}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-27
 * @version 1.0
 */
class ClientDataDirectoryTest {

    @TempDir
    Path root;

    @Test
    void aFirstOpenCreatesTheDirectoryItsLockAndANodeId() throws IOException {
        Path data = root.resolve("client");

        try (ClientDataDirectory directory = ClientDataDirectory.open(data)) {
            assertEquals(data, directory.path());
            assertEquals(directory.nodeId(), UUID.fromString(directory.nodeId()).toString(), "a canonical UUID");
            assertEquals(directory.nodeId(), Files.readString(data.resolve("node-id"), StandardCharsets.UTF_8));
            assertEquals(List.of("client.lock", "node-id"), fileNames(data));
        }
    }

    @Test
    void theNodeIdSurvivesClosingAndReopening() throws IOException {
        String first;
        try (ClientDataDirectory directory = ClientDataDirectory.open(root)) {
            first = directory.nodeId();
        }

        try (ClientDataDirectory directory = ClientDataDirectory.open(root)) {
            assertEquals(first, directory.nodeId());
        }
    }

    @Test
    void aWipedDirectoryGivesANewNodeId() throws IOException {
        Path data = root.resolve("client");
        String before;
        try (ClientDataDirectory directory = ClientDataDirectory.open(data)) {
            before = directory.nodeId();
        }

        deleteRecursively(data);

        try (ClientDataDirectory directory = ClientDataDirectory.open(data)) {
            assertNotEquals(before, directory.nodeId(), "a wiped client is a new node");
        }
    }

    @Test
    void aSecondClientInThisProcessIsRefusedUntilTheFirstClosesTheDirectory() throws IOException {
        String nodeId;
        try (ClientDataDirectory first = ClientDataDirectory.open(root)) {
            nodeId = first.nodeId();

            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> ClientDataDirectory.open(root));

            assertEquals(root + " is in use by another client in this process; a data directory belongs to one"
                    + " client at a time", refused.getMessage());
            assertEquals(nodeId, first.nodeId(), "the first client keeps the directory");
        }

        try (ClientDataDirectory reopened = ClientDataDirectory.open(root)) {
            assertEquals(nodeId, reopened.nodeId());
        }
    }

    @Test
    void anUnreadableNodeIdStopsTheOpenAndDoesNotKeepTheLock() throws IOException {
        Path file = root.resolve("node-id");
        Files.writeString(file, "not-a-uuid");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ClientDataDirectory.open(root));

        assertTrue(refused.getMessage().startsWith("The node ID in " + file + " cannot be read."),
                refused.getMessage());
        assertEquals("not-a-uuid", Files.readString(file), "the file is left for the operator to inspect");

        String restored = UUID.randomUUID().toString();
        Files.writeString(file, restored);
        try (ClientDataDirectory directory = ClientDataDirectory.open(root)) {
            assertEquals(restored, directory.nodeId(), "the refused open released its lock");
        }
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}

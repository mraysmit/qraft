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

package dev.mars.qraft.runtime;

import dev.mars.qraft.raft.storage.RaftStorageFactory;
import dev.mars.qraft.server.recovery.SurvivorDirectoryFixture;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.serverIdOf;
import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_STATE_WITHOUT_CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the offline {@code qraft operator raft} commands (membership Step 7), which act on stopped data directories
 * and contact no server:
 * <ul>
 *   <li>{@code inspect --dir} prints a directory's report and its configuration, and says why ordinary startup
 *       would refuse it, with exit code 0;</li>
 *   <li>{@code recover --input} publishes the seed and prints its new server ID and recovery entry;</li>
 *   <li>a refusal exits with 1, an invalid recovery input or command line with 2, and the source is never
 *       changed by either.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-23
 * @version 1.0
 */
class RaftOperatorRecoveryCommandTest {
    @TempDir
    Path directory;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void inspectPrintsTheReportAndTheConfiguration() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("a"));

        int exit = run("raft", "inspect", "--dir", source.toString());

        assertEquals(0, exit, err());
        Map<String, String> report = fields();
        assertEquals(serverIdOf("a"), report.get("Server ID"));
        assertEquals("1", report.get("Term"));
        assertEquals("a", report.get("Voted for"));
        assertEquals("3", report.get("Last log index"));
        assertEquals("1", report.get("Last log term"));
        assertEquals("0", report.get("Snapshot index"));
        assertEquals("0", report.get("Snapshot term"));
        assertEquals("0", report.get("Compacted through"));
        assertEquals("WAL", report.get("Configuration from"));
        assertEquals("accepted", report.get("Ordinary startup"));
        for (String name : List.of("a", "b", "c")) {
            assertTrue(out().lines().anyMatch(line -> List.of(line.split("\\s+")).equals(
                    List.of(name, serverIdOf(name), name, "true"))), name + " in:\n" + out());
        }
    }

    @Test
    void inspectSaysWhyOrdinaryStartupWouldRefuseADirectory() throws Exception {
        IntentionalErrorsHelper.expect(RAFT_STATE_WITHOUT_CONFIGURATION);
        Path source = directory.resolve("a");
        SurvivorDirectoryFixture.write(source, serverIdOf("a"), 1, Optional.empty(),
                List.of(SurvivorDirectoryFixture.put(1, 1, "k", "v")));

        int exit = run("raft", "inspect", "--dir", source.toString());

        assertEquals(0, exit, err());
        assertEquals("(none)", fields().get("Voted for"));
        assertEquals("NONE", fields().get("Configuration from"));
        assertTrue(fields().get("Ordinary startup").startsWith("refused: "), out());
        assertTrue(fields().get("Ordinary startup").contains("no cluster configuration"), out());
    }

    @Test
    void inspectOfALockedDirectoryIsRefused() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("a"));
        RaftStorageFactory.DurableStorage running = RaftStorageFactory.createDurable(source, true)
                .get(10, TimeUnit.SECONDS);
        try {
            assertEquals(1, run("raft", "inspect", "--dir", source.toString()));
        } finally {
            running.snapshots().closeAsync().get(10, TimeUnit.SECONDS);
            running.wal().closeAsync().get(10, TimeUnit.SECONDS);
        }
        assertTrue(err().contains("is locked"), err());
    }

    @Test
    void recoverPublishesTheSeedAndPrintsItsNewServerId() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));
        Path output = directory.resolve("data").resolve("a");
        Path input = input(source, serverIdOf("a"), output);

        int exit = run("raft", "recover", "--input", input.toString());

        assertEquals(0, exit, err());
        Map<String, String> result = fields();
        assertEquals("recovered", result.get("Outcome"));
        assertEquals(output.toAbsolutePath().normalize().toString(), result.get("Output"));
        assertEquals(Files.readString(output.resolve("server-id")), result.get("Server ID"));
        assertEquals("4", result.get("Recovery index"));
        assertEquals("2", result.get("Recovery term"));
    }

    @Test
    void aRefusedRecoveryExitsWithOneAndPublishesNothing() throws Exception {
        Path source = SurvivorDirectoryFixture.survivor(directory.resolve("survivors").resolve("a"));
        Path output = directory.resolve("data").resolve("a");
        Map<String, String> before = SurvivorDirectoryFixture.digest(source);

        int exit = run("raft", "recover", "--input", input(source, serverIdOf("b"), output).toString());

        assertEquals(1, exit);
        assertTrue(err().contains("does not match"), err());
        assertFalse(Files.exists(output));
        assertEquals(before, SurvivorDirectoryFixture.digest(source));
    }

    @Test
    void anInvalidRecoveryInputIsMisuse() throws Exception {
        Path input = Files.writeString(directory.resolve("recovery.json"), "{\"version\":1,\"force\":true}");

        assertEquals(2, run("raft", "recover", "--input", input.toString()));
        assertTrue(err().contains("Unknown recovery input setting: force"), err());
        assertEquals(2, run("raft", "recover", "--input", directory.resolve("missing.json").toString()));
    }

    @Test
    void misuseOfTheOfflineCommandsPrintsTheUsage() {
        for (String[] misuse : new String[][] {
                {"raft", "inspect"}, {"raft", "inspect", "--dir"}, {"raft", "recover"},
                {"raft", "recover", "--input"}, {"raft", "inspect", "--dir", "x", "--input", "y"},
                {"raft", "recover", "--input", "x", "--http-addr", "y"}}) {
            err.reset();
            assertEquals(2, run(misuse), String.join(" ", misuse));
            assertTrue(err().contains("qraft operator raft inspect --dir <path>"), String.join(" ", misuse) + ": " + err());
            assertTrue(err().contains("qraft operator raft recover --input <recovery.json>"), err());
        }
    }

    /** A recovery input for {@code source}, with a server JSON for server a whose storage path is {@code output}. */
    private Path input(Path source, String serverId, Path output) throws Exception {
        Path server = Files.writeString(directory.resolve("server.json"), "{\"version\":1,\"server\":{\"id\":\"a\","
                + "\"raft\":{\"nodes\":{\"a\":\"host-a:9090\"},\"storage\":{\"path\":" + quoted(output) + "}}}}");
        return Files.writeString(directory.resolve("recovery.json"), "{\"version\":1,\"source\":{\"path\":"
                + quoted(source) + ",\"serverId\":\"" + serverId + "\"},\"serverConfig\":" + quoted(server) + "}");
    }

    private static String quoted(Path path) {
        return "\"" + path.toString().replace('\\', '/') + "\"";
    }

    /** The printed {@code name  value} lines, by name, up to the first blank line. */
    private Map<String, String> fields() {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : out().lines().toList()) {
            if (line.isBlank()) break;
            String[] parts = line.split("\\s{2,}", 2);
            if (parts.length == 2) fields.put(parts[0], parts[1]);
        }
        return fields;
    }

    private int run(String... args) {
        out.reset();
        return RaftOperatorCommand.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }
}

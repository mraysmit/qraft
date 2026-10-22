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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.mars.qraft.raft.ManualRaftClusterFixture.serverIdOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how {@link RecoveryInput} reads a recovery input file (membership Step 7 contract): the source and its
 * server ID come from the input, and the output directory, name and Raft address from the server JSON it names.
 * Both files follow the server JSON rules, and each kind of invalid input is refused as misuse.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-22
 * @version 1.0
 */
class RecoveryInputTest {
    @TempDir
    Path directory;

    @Test
    void readsThePlanFromTheInputAndTheServerConfiguration() throws Exception {
        Path source = directory.resolve("backup").resolve("a");
        Path output = directory.resolve("data").resolve("a");
        Path server = serverConfig(output, "\"a\":\"host-a:9090\",\"b\":\"host-b:9090\"");

        RecoveryPlan plan = RecoveryInput.read(input(source, serverIdOf("a"), server));

        assertEquals(new RecoveryPlan(source, serverIdOf("a"), output, "a", "host-a:9090"), plan);
    }

    @Test
    void anUnsupportedVersionIsRefused() throws Exception {
        assertMisuse(write("{\"version\":2," + body(serverConfig()) + "}"), "version");
    }

    @Test
    void anUnknownSettingIsRefused() throws Exception {
        assertMisuse(write("{\"version\":1," + body(serverConfig()) + ",\"force\":true}"), "Unknown");
        assertMisuse(write("{\"version\":1,\"source\":{\"path\":\"x\",\"serverId\":\"" + serverIdOf("a")
                + "\",\"name\":\"a\"},\"serverConfig\":" + quoted(serverConfig()) + "}"), "Unknown");
    }

    @Test
    void aDuplicateKeyIsRefused() throws Exception {
        assertMisuse(write("{\"version\":1,\"version\":1," + body(serverConfig()) + "}"), "");
    }

    @Test
    void aPlaceholderIsRefused() throws Exception {
        assertMisuse(write("{\"version\":1,\"source\":{\"path\":\"${BACKUP}\",\"serverId\":\""
                + serverIdOf("a") + "\"},\"serverConfig\":" + quoted(serverConfig()) + "}"), "");
    }

    @Test
    void eachMissingValueIsRefused() throws Exception {
        Path server = serverConfig();
        String id = serverIdOf("a");
        assertMisuse(write("{\"version\":1,\"serverConfig\":" + quoted(server) + "}"), "source");
        assertMisuse(write("{\"version\":1,\"source\":{\"serverId\":\"" + id + "\"},\"serverConfig\":"
                + quoted(server) + "}"), "path");
        assertMisuse(write("{\"version\":1,\"source\":{\"path\":\"x\"},\"serverConfig\":" + quoted(server) + "}"),
                "serverId");
        assertMisuse(write("{\"version\":1,\"source\":{\"path\":\"x\",\"serverId\":\"" + id + "\"}}"),
                "serverConfig");
        assertMisuse(write("{\"version\":1,\"source\":{\"path\":\"\",\"serverId\":\"" + id
                + "\"},\"serverConfig\":" + quoted(server) + "}"), "path");
    }

    @Test
    void aServerConfigurationThatCannotBeReadIsRefused() throws Exception {
        assertMisuse(input(directory.resolve("a"), serverIdOf("a"), directory.resolve("missing.json")), "");
        Path invalid = Files.writeString(directory.resolve("invalid.json"),
                "{\"version\":1,\"server\":{\"id\":\"a\",\"unexpected\":true}}");
        assertMisuse(input(directory.resolve("a"), serverIdOf("a"), invalid), "Unknown");
    }

    @Test
    void aServerConfigurationThatDoesNotListItsOwnAddressIsRefused() throws Exception {
        Path server = serverConfig(directory.resolve("data").resolve("a"), "\"b\":\"host-b:9090\"");
        assertMisuse(input(directory.resolve("a"), serverIdOf("a"), server), "server.raft.nodes");
    }

    /** A server JSON for server a whose storage path is {@code output} and whose nodes are {@code nodes}. */
    private Path serverConfig(Path output, String nodes) throws Exception {
        return Files.writeString(directory.resolve("server.json"), "{\"version\":1,\"server\":{\"id\":\"a\","
                + "\"raft\":{\"nodes\":{" + nodes + "},\"storage\":{\"path\":" + quoted(output) + "}}}}");
    }

    private Path serverConfig() throws Exception {
        return serverConfig(directory.resolve("data").resolve("a"), "\"a\":\"host-a:9090\"");
    }

    private Path input(Path source, String serverId, Path server) throws Exception {
        return write("{\"version\":1,\"source\":{\"path\":" + quoted(source) + ",\"serverId\":\"" + serverId
                + "\"},\"serverConfig\":" + quoted(server) + "}");
    }

    /** A valid source and serverConfig pair, for a document that varies only its other keys. */
    private String body(Path server) {
        return "\"source\":{\"path\":" + quoted(directory.resolve("a")) + ",\"serverId\":\"" + serverIdOf("a")
                + "\"},\"serverConfig\":" + quoted(server);
    }

    private Path write(String document) throws Exception {
        return Files.writeString(directory.resolve("recovery.json"), document);
    }

    /** {@code path} as a JSON string, with forward slashes so a Windows path needs no escaping. */
    private static String quoted(Path path) {
        return "\"" + path.toString().replace('\\', '/') + "\"";
    }

    private static void assertMisuse(Path input, String reason) {
        IllegalArgumentException misuse = assertThrows(IllegalArgumentException.class,
                () -> RecoveryInput.read(input));
        assertTrue(misuse.getMessage().contains(reason), misuse.getMessage());
    }
}

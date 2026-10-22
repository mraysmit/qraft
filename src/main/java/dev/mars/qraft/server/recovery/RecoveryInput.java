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
import dev.mars.qraft.common.config.JsonSettings;
import dev.mars.qraft.server.config.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a versioned recovery input file into a {@link RecoveryPlan}. The file names the stopped source, the server
 * ID it must hold, and the server JSON the seed will start with; that JSON supplies the output directory
 * ({@code server.raft.storage.path}), the name ({@code server.id}) and the Raft address
 * ({@code server.raft.nodes[server.id]}). Both files are read with the server JSON rules: {@code version} 1, and
 * unknown or duplicate keys, placeholders and missing values refused.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-22
 * @version 1.0
 */
public final class RecoveryInput {
    private RecoveryInput() { }

    /** Reads {@code inputFile}; an invalid input or server JSON is refused with {@link IllegalArgumentException}. */
    public static RecoveryPlan read(Path inputFile) {
        String document;
        try {
            document = Files.readString(inputFile);
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("Could not read the recovery input " + inputFile, unreadable);
        }
        JsonNode root = JsonSettings.readDocument(document, "Recovery input");
        JsonSettings.rejectUnknown(root, "recovery input", "version", "source", "serverConfig");
        JsonSettings.requireFormatVersion(root);
        JsonNode source = JsonSettings.requiredObject(root, "source");
        JsonSettings.rejectUnknown(source, "source", "path", "serverId");
        Path sourcePath = Path.of(requiredText(source, "path", "source.path"));
        String sourceServerId = requiredText(source, "serverId", "source.serverId");
        Path serverConfigPath = Path.of(requiredText(root, "serverConfig", "serverConfig"));

        AppConfig server = AppConfig.fromFile(serverConfigPath);
        String name = server.getNodeId();
        String address = server.getClusterMembers().get(name);
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("The server configuration " + serverConfigPath + " does not list "
                    + "server " + name + " in server.raft.nodes, so the seed's Raft address is unknown");
        }
        return new RecoveryPlan(sourcePath, sourceServerId, Path.of(server.getRaftStoragePath()), name, address);
    }

    private static String requiredText(JsonNode parent, String field, String label) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(label + " must be a non-empty string");
        }
        return value.asText();
    }
}

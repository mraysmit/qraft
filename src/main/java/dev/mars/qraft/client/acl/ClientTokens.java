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

package dev.mars.qraft.client.acl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.qraft.common.storage.DurableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * A client's tokens (ACL list, proposal 13), each from its mounted file:
 *
 * <ul>
 *   <li>the {@code client} token, for the client's own calls for its node, and the {@code configFileRegistration}
 *       token, for the file's services and checks, each falling back to the {@code default} token;</li>
 *   <li>the {@code default} token, for a request with none and a sync with no other token, falling back to none,
 *       which is the anonymous token;</li>
 *   <li>the {@code recovery} token, for the client's own endpoints with no server, with no fallback.</li>
 * </ul>
 *
 * <p>A token set while the client runs replaces its file's, as Consul's {@code agent token} command does. With
 * {@code client.acl.enableTokenPersistence} it is kept in {@value #PERSISTED_FILE} in the data directory, readable only
 * by the client's user (proposal 3), and takes its file's place at the next start, as Consul's persisted tokens do.
 * A token is never logged, nor quoted in a refusal.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
public final class ClientTokens {
    /** The file in the data directory that holds tokens set through the local API, with persistence enabled. */
    public static final String PERSISTED_FILE = "acl-tokens.json";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int VERSION = 1;
    private static final Map<TokenKind, String> FILE_SETTINGS = Map.of(TokenKind.CLIENT, "clientFile",
            TokenKind.CONFIG_FILE_REGISTRATION, "configFileRegistrationFile", TokenKind.DEFAULT, "defaultFile",
            TokenKind.RECOVERY, "recoveryFile");

    private final Map<TokenKind, String> tokens;
    private final Map<TokenKind, String> setThroughApi;
    private final Path persistedFile;

    private ClientTokens(Map<TokenKind, String> tokens, Map<TokenKind, String> setThroughApi, Path persistedFile) {
        this.tokens = tokens;
        this.setThroughApi = setThroughApi;
        this.persistedFile = persistedFile;
    }

    /** No tokens: every request is anonymous. */
    public static ClientTokens none() {
        return new ClientTokens(new EnumMap<>(TokenKind.class), new EnumMap<>(TokenKind.class), null);
    }

    /**
     * The tokens in {@code settings}' files, with those persisted in {@code dataDirectory} in their place when
     * persistence is enabled.
     *
     * @throws IllegalArgumentException if a token file cannot be read or holds no token, or what is persisted cannot
     *                                  be read; neither is ever quoted
     */
    public static ClientTokens load(ClientAclSettings settings, Path dataDirectory) {
        Map<TokenKind, String> tokens = new EnumMap<>(TokenKind.class);
        settings.tokenFiles().forEach((kind, file) -> tokens.put(kind, readFile(kind, file)));
        Map<TokenKind, String> persisted = new EnumMap<>(TokenKind.class);
        Path persistedFile = settings.enableTokenPersistence() ? dataDirectory.resolve(PERSISTED_FILE) : null;
        if (persistedFile != null && Files.exists(persistedFile)) {
            persisted.putAll(readPersisted(persistedFile));
            tokens.putAll(persisted);
        }
        return new ClientTokens(tokens, persisted, persistedFile);
    }

    /** The token of {@code kind} itself, with no fallback. */
    public synchronized Optional<String> token(TokenKind kind) {
        return Optional.ofNullable(tokens.get(kind));
    }

    /** The token for the client's own calls for its node: the {@code client} token, else the default token. */
    public synchronized Optional<String> clientToken() {
        return token(TokenKind.CLIENT).or(this::defaultToken);
    }

    /** The token for the file's services and checks: the {@code configFileRegistration} token, else the default. */
    public synchronized Optional<String> configFileRegistrationToken() {
        return token(TokenKind.CONFIG_FILE_REGISTRATION).or(this::defaultToken);
    }

    /** The default token, for a request with none and a sync with no other token. */
    public synchronized Optional<String> defaultToken() {
        return token(TokenKind.DEFAULT);
    }

    /** Whether {@code secret} is the recovery token. */
    public synchronized boolean isRecovery(String secret) {
        String recovery = tokens.get(TokenKind.RECOVERY);
        return secret != null && recovery != null && java.security.MessageDigest.isEqual(
                recovery.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Replaces the token of {@code kind}, kept across a restart only with persistence enabled.
     *
     * @throws IllegalArgumentException if {@code secret} is blank or holds a character a header cannot carry
     * @throws IOException if persistence is enabled and the token cannot be kept; nothing has changed
     */
    public synchronized void set(TokenKind kind, String secret) throws IOException {
        String token = requireSendable(secret, "the " + kind.apiName() + " token");
        if (persistedFile != null) {
            Map<TokenKind, String> next = new EnumMap<>(setThroughApi);
            next.put(kind, token);
            writePersisted(persistedFile, next);
            setThroughApi.put(kind, token);
        }
        tokens.put(kind, token);
    }

    /**
     * The token each configured service names in its own {@code tokenFile}, by service ID.
     *
     * @throws IllegalArgumentException if a file cannot be read or holds no token; neither is ever quoted
     */
    public static Map<String, String> readServiceTokens(Map<String, Path> files) {
        Map<String, String> tokens = new java.util.LinkedHashMap<>();
        files.forEach((serviceId, file) -> {
            String setting = "catalog.services[" + serviceId + "].tokenFile";
            try {
                tokens.put(serviceId, requireSendable(Files.readString(file), setting + " (" + file + ")"));
            } catch (IOException unreadable) {
                throw new IllegalArgumentException(setting + " cannot be read: " + file + ": "
                        + unreadable.getClass().getSimpleName());
            }
        });
        return Map.copyOf(tokens);
    }

    private static String readFile(TokenKind kind, Path file) {
        String setting = "client.acl.tokens." + FILE_SETTINGS.get(kind);
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException unreadable) {
            throw new IllegalArgumentException(setting + " cannot be read: " + file + ": " + unreadable.getClass()
                    .getSimpleName());
        }
        return requireSendable(content, setting + " (" + file + ")");
    }

    /** {@code value} stripped, refused when blank or holding a character a header cannot carry; never quoted. */
    static String requireSendable(String value, String what) {
        String token = value == null ? "" : value.strip();
        if (token.isEmpty()) throw new IllegalArgumentException(what + " holds no token");
        if (!token.chars().allMatch(character -> character >= 0x20 && character < 0x7f)) {
            throw new IllegalArgumentException(what + " holds a character that a header cannot carry");
        }
        return token;
    }

    private static Map<TokenKind, String> readPersisted(Path file) {
        try {
            JsonNode root = JSON.readTree(Files.readAllBytes(file));
            if (root == null || root.path("version").asInt() != VERSION || !root.path("tokens").isObject()) {
                throw new IllegalArgumentException("it is not a version " + VERSION + " token file");
            }
            Map<TokenKind, String> tokens = new EnumMap<>(TokenKind.class);
            root.get("tokens").properties().forEach(entry -> {
                TokenKind kind = TokenKind.fromApiName(entry.getKey()).orElseThrow(() ->
                        new IllegalArgumentException("it names no such token: " + entry.getKey()));
                tokens.put(kind, requireSendable(entry.getValue().asText(), "the persisted " + kind.apiName()
                        + " token"));
            });
            return tokens;
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("The tokens persisted in " + file + " cannot be read: "
                    + (failure instanceof IOException ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
    }

    private static void writePersisted(Path file, Map<TokenKind, String> tokens) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        ObjectNode written = root.putObject("tokens");
        tokens.forEach((kind, token) -> written.put(kind.apiName(), token));
        DurableFile.writePrivate(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public String toString() {
        return "ClientTokens[" + tokens.keySet() + ", values=<hidden>]";
    }
}

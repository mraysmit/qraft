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

import dev.mars.qraft.common.acl.AclDefaultPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests a client's tokens (ACL list, proposal 13, Phase 6): each read from its mounted file; the fallbacks of the table,
 * {@code client} and {@code configFileRegistration} to {@code default}, and {@code default} to none, which is the
 * anonymous token; the recovery token, which has no fallback; and a token set while the client runs, kept across a
 * restart only with {@code enableTokenPersistence}, in place of its file's.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class ClientTokensTest {
    @TempDir
    Path directory;

    @Test
    void eachTokenIsReadFromItsFileAndTheTableSaysWhatFallsBack() throws Exception {
        ClientTokens tokens = ClientTokens.load(settings(false, Map.of(
                TokenKind.DEFAULT, file("default", "default-secret\n"),
                TokenKind.RECOVERY, file("recovery", "  recovery-secret  "))), directory);

        assertEquals(Optional.of("default-secret"), tokens.defaultToken());
        assertEquals(Optional.of("default-secret"), tokens.clientToken(), "client falls back to default");
        assertEquals(Optional.of("default-secret"), tokens.configFileRegistrationToken());
        assertEquals(Optional.empty(), tokens.token(TokenKind.CLIENT));
        assertTrue(tokens.isRecovery("recovery-secret"));
        assertFalse(tokens.isRecovery("default-secret"));
        assertFalse(tokens.isRecovery(null));

        ClientTokens own = ClientTokens.load(settings(false, Map.of(
                TokenKind.DEFAULT, file("default", "default-secret"),
                TokenKind.CLIENT, file("client", "client-secret"),
                TokenKind.CONFIG_FILE_REGISTRATION, file("registration", "registration-secret"))), directory);
        assertEquals(Optional.of("client-secret"), own.clientToken());
        assertEquals(Optional.of("registration-secret"), own.configFileRegistrationToken());

        ClientTokens none = ClientTokens.load(settings(false, Map.of()), directory);
        assertEquals(Optional.empty(), none.clientToken(), "with no default, a call is anonymous");
        assertFalse(none.isRecovery("anything"), "there is no recovery token without its file");
    }

    @Test
    void aTokenFileThatCannotBeUsedIsRefusedWithoutQuotingIt() throws Exception {
        Path blank = file("blank", "  \n");
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> ClientTokens.load(settings(false, Map.of(TokenKind.CLIENT, blank)), directory));
        assertTrue(empty.getMessage().contains("clientFile"), empty.getMessage());

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> ClientTokens.load(settings(false, Map.of(TokenKind.DEFAULT, directory.resolve("absent"))),
                        directory));
        assertTrue(missing.getMessage().contains("absent"), missing.getMessage());

        Path control = file("control", "bad\u0001secret");
        IllegalArgumentException unsendable = assertThrows(IllegalArgumentException.class,
                () -> ClientTokens.load(settings(false, Map.of(TokenKind.DEFAULT, control)), directory));
        assertFalse(unsendable.getMessage().contains("secret"), unsendable.getMessage());
    }

    @Test
    void aTokenSetWhileRunningIsKeptOnlyWithPersistence() throws Exception {
        ClientTokens transient_ = ClientTokens.load(settings(false, Map.of(TokenKind.DEFAULT, file("d", "file"))),
                directory);
        transient_.set(TokenKind.DEFAULT, "set-while-running");
        assertEquals(Optional.of("set-while-running"), transient_.defaultToken());
        assertFalse(Files.exists(directory.resolve(ClientTokens.PERSISTED_FILE)));
        assertEquals(Optional.of("file"), ClientTokens.load(settings(false, Map.of(TokenKind.DEFAULT,
                file("d", "file"))), directory).defaultToken(), "without persistence a restart reads the file");

        ClientTokens persisted = ClientTokens.load(settings(true, Map.of(TokenKind.DEFAULT, file("d", "file"))),
                directory);
        persisted.set(TokenKind.CLIENT, "kept-client");
        persisted.set(TokenKind.DEFAULT, "kept-default");
        assertTrue(Files.exists(directory.resolve(ClientTokens.PERSISTED_FILE)));

        ClientTokens restarted = ClientTokens.load(settings(true, Map.of(TokenKind.DEFAULT, file("d", "file"))),
                directory);
        assertEquals(Optional.of("kept-default"), restarted.defaultToken(), "a persisted token beats its file");
        assertEquals(Optional.of("kept-client"), restarted.clientToken());
    }

    private Path file(String name, String content) throws Exception {
        return Files.writeString(directory.resolve(name), content);
    }

    private static ClientAclSettings settings(boolean persistence, Map<TokenKind, Path> files) {
        return new ClientAclSettings(true, AclDefaultPolicy.DENY, DownPolicy.EXTEND_CACHE,
                ClientAclSettings.DEFAULT_TTL, ClientAclSettings.DEFAULT_TTL, ClientAclSettings.DEFAULT_TTL,
                persistence, files);
    }
}

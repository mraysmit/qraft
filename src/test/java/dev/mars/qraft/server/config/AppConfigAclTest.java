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

package dev.mars.qraft.server.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the server's ACL and security-profile settings of the ACL list's proposals 12 and 16 (Phase 3): ACLs are off
 * by default and {@code defaultPolicy} has no default; enabling ACLs needs a default policy and excludes the operator
 * token; the management secret is read from a file, must be a UUID outside the reserved range, and never appears in
 * a message or a log line; the {@code production} profile refuses to run open; and each combination logs the one
 * startup warning it should, or none.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
class AppConfigAclTest {
    private static final String SECRET = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    private static final String OPEN = "Security profile development with ACLs disabled: every call is allowed";
    private static final String CRITICAL =
            "CRITICAL ACL_DENY_NOT_ENFORCED: server.acl.defaultPolicy is deny, but ACLs are disabled";
    private static final String PLAINTEXT =
            "ACLs are enabled, but no listener uses TLS: tokens cross the network unencrypted";

    @TempDir
    Path directory;

    @Test
    void aclsAreOffByDefaultWithNoDefaultPolicyAndTheDevelopmentProfile() {
        AppConfig config = config("");

        assertFalse(config.isAclEnabled());
        assertEquals(Optional.empty(), config.getAclDefaultPolicy());
        assertEquals(Optional.empty(), config.getInitialManagementSecret());
        assertEquals(SecurityProfile.DEVELOPMENT, config.getSecurityProfile());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void readsTheAclSettingsAndTheManagementSecretFromItsFile() throws Exception {
        Path file = Files.writeString(directory.resolve("management"), "  " + SECRET + "\n");

        AppConfig config = config(acl(true, "deny", file) + ",\"security\":{\"profile\":\"production\"}");
        config.validate();

        assertTrue(config.isAclEnabled());
        assertEquals(Optional.of(AclDefaultPolicy.DENY), config.getAclDefaultPolicy());
        assertEquals(Optional.of(SECRET), config.getInitialManagementSecret(), "the file's text, trimmed");
        assertEquals(SecurityProfile.PRODUCTION, config.getSecurityProfile());
        assertEquals(Optional.of(AclDefaultPolicy.ALLOW), config("\"acl\":{\"enabled\":true,\"defaultPolicy\":\"allow\"}")
                .getAclDefaultPolicy());
    }

    @Test
    void theDocumentRefusesWhatItDoesNotKnow() {
        for (String server : List.of(
                "\"acl\":{\"defaultPolicy\":\"Deny\"}",
                "\"acl\":{\"defaultPolicy\":\"maybe\"}",
                "\"acl\":{\"enabled\":\"yes\"}",
                "\"acl\":{\"initialManagementToken\":\"" + SECRET + "\"}",
                "\"acl\":{\"tokens\":{}}",
                "\"security\":{\"profile\":\"prod\"}",
                "\"security\":{\"mode\":\"production\"}")) {
            assertThrows(IllegalArgumentException.class, () -> config(server), server);
        }
    }

    @Test
    void enabledAclsNeedADefaultPolicyAndExcludeTheOperatorToken() {
        rejected("\"acl\":{\"enabled\":true}", "server.acl.defaultPolicy must be set when ACLs are enabled");
        rejected("\"acl\":{\"enabled\":true,\"defaultPolicy\":\"deny\"},\"operator\":{\"token\":\"sixteen-chars-ok\"}",
                "server.operator.token cannot be used with server.acl.enabled");
        accepted("\"acl\":{\"defaultPolicy\":\"deny\"}");
        accepted("\"acl\":{\"defaultPolicy\":\"deny\"},\"operator\":{\"token\":\"sixteen-chars-ok\"}");
    }

    @Test
    void theManagementFileMustBeReadableAndHoldAUuidOutsideTheReservedRange() throws Exception {
        Path missing = directory.resolve("missing");
        Path notAUuid = Files.writeString(directory.resolve("not-a-uuid"), "correct-horse-battery-staple");
        Path reserved = Files.writeString(directory.resolve("reserved"), "00000000-0000-0000-0000-000000000009");
        Path good = Files.writeString(directory.resolve("good"), SECRET);

        rejected(acl(true, "deny", missing), "server.acl.initialManagementTokenFile cannot be read: " + missing);
        IllegalStateException wrong = rejected(acl(true, "deny", notAUuid),
                "server.acl.initialManagementTokenFile must hold a UUID");
        assertFalse(wrong.getMessage().contains("correct-horse"), "a refusal never shows the file's text");
        rejected(acl(true, "deny", reserved), "a UUID in the range kept for built-ins");
        rejected(acl(false, "deny", good), "server.acl.initialManagementTokenFile needs server.acl.enabled");
        accepted(acl(true, "deny", good));
    }

    @Test
    void theProductionProfileRefusesToRunOpen() {
        rejected("\"security\":{\"profile\":\"production\"}", "the production profile needs server.acl.enabled");
        rejected("\"security\":{\"profile\":\"production\"},\"acl\":{\"defaultPolicy\":\"deny\"}",
                "the production profile needs server.acl.enabled");
        rejected("\"security\":{\"profile\":\"production\"},\"acl\":{\"enabled\":true,\"defaultPolicy\":\"allow\"}",
                "the production profile needs server.acl.defaultPolicy deny");
        accepted("\"security\":{\"profile\":\"production\"},\"acl\":{\"enabled\":true,\"defaultPolicy\":\"deny\"}");
        accepted("\"security\":{\"profile\":\"development\"},\"acl\":{\"enabled\":true,\"defaultPolicy\":\"allow\"}");
    }

    @Test
    void eachCombinationLogsTheOneStartupWarningItShould() throws Exception {
        Path file = Files.writeString(directory.resolve("management"), SECRET);

        assertEquals(List.of(OPEN), warnings(config("")));
        assertEquals(List.of(OPEN), warnings(config("\"acl\":{\"defaultPolicy\":\"allow\"}")));
        assertEquals(List.of(CRITICAL), warnings(config("\"acl\":{\"defaultPolicy\":\"deny\"}")),
                "the critical warning takes the open warning's place");
        assertEquals(List.of(PLAINTEXT), warnings(config("\"acl\":{\"enabled\":true,\"defaultPolicy\":\"allow\"}")));
        assertEquals(List.of(PLAINTEXT), warnings(config(acl(true, "deny", file)
                + ",\"security\":{\"profile\":\"production\"}")));
    }

    @Test
    void theEffectiveSettingsAreLoggedWithoutTheSecret() throws Exception {
        Path file = Files.writeString(directory.resolve("management"), SECRET);
        AppConfig config = config(acl(true, "deny", file));
        config.validate();

        List<String> lines = logged(config).stream().map(ILoggingEvent::getFormattedMessage).toList();

        assertTrue(lines.stream().anyMatch(line -> line.contains("ACL configuration: enabled=true, defaultPolicy=deny,"
                        + " initialManagementTokenFile=" + file + ", securityProfile=development")), lines.toString());
        assertTrue(lines.stream().noneMatch(line -> line.contains(SECRET)), "no line holds the secret");
    }

    /** Each warning as its opening words, which name its condition, so that its advice may grow. */
    private static List<String> warnings(AppConfig config) {
        config.validate();
        return logged(config).stream().filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(event -> opening(event.getFormattedMessage())).toList();
    }

    private static String opening(String line) {
        return List.of(OPEN, CRITICAL, PLAINTEXT).stream().filter(line::startsWith).findFirst().orElse(line);
    }

    private static List<ILoggingEvent> logged(AppConfig config) {
        Logger logger = (Logger) LoggerFactory.getLogger(AppConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            config.logConfiguration();
        } finally {
            logger.detachAppender(appender);
        }
        return List.copyOf(appender.list);
    }

    private static String acl(boolean enabled, String defaultPolicy, Path file) {
        return "\"acl\":{\"enabled\":" + enabled + ",\"defaultPolicy\":\"" + defaultPolicy
                + "\",\"initialManagementTokenFile\":\"" + file.toString().replace('\\', '/') + "\"}";
    }

    private static void accepted(String server) {
        assertDoesNotThrow(() -> config(server).validate(), server);
    }

    private static IllegalStateException rejected(String server, String reason) {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> config(server).validate(),
                server);
        assertTrue(refused.getMessage().contains(reason), server + ": " + refused.getMessage());
        return refused;
    }

    private static AppConfig config(String server) {
        return AppConfig.fromJson("{\"version\":1,\"server\":{" + server + "}}");
    }
}

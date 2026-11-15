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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the effective configuration a server answers (UI-4 list, decision 3): every setting by its key in the
 * document, with the value in force and whether the document gave it or the default did; secrets never answered; and
 * the file the configuration came from.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-15
 * @version 1.0
 */
class AppConfigEffectiveTest {
    private static final String OPERATOR_SECRET = "operator-secret-0123456789";

    private static Map<String, EffectiveSetting> byKey(AppConfig config) {
        return config.effective().settings().stream()
                .collect(Collectors.toMap(EffectiveSetting::key, Function.identity()));
    }

    @Test
    void eachSettingHasItsValueInForceAndWhetherTheDocumentOrTheDefaultGaveIt() {
        AppConfig config = AppConfig.fromJson("""
                {"version":1,"server":{"id":"s-1","http":{"port":8500},"raft":{"electionTimeoutMs":250,
                "nodes":{"s-1":"127.0.0.1:9080"},"membership":{"cleanup":{"minVoters":1}}},
                "ui":{"enabled":false}},"logging":{"directory":"/var/log/qraft"}}""");
        Map<String, EffectiveSetting> settings = byKey(config);

        assertEquals(new EffectiveSetting("server.http.port", 8500, true, false), settings.get("server.http.port"));
        assertEquals(new EffectiveSetting("server.http.host", "0.0.0.0", false, false), settings.get("server.http.host"));
        assertEquals(250L, settings.get("server.raft.electionTimeoutMs").value());
        assertTrue(settings.get("server.raft.electionTimeoutMs").configured());
        assertEquals(Map.of("s-1", "127.0.0.1:9080"), settings.get("server.raft.nodes").value());
        assertTrue(settings.get("server.raft.nodes").configured(), "a map of members is one setting");
        assertEquals(1, settings.get("server.raft.membership.cleanup.minVoters").value());
        assertTrue(settings.get("server.raft.membership.cleanup.minVoters").configured());
        assertEquals(60_000L, settings.get("server.raft.membership.cleanup.failureTimeoutMs").value(),
                "a default worked out from other settings");
        assertFalse(settings.get("server.raft.membership.cleanup.failureTimeoutMs").configured());
        assertEquals(false, settings.get("server.ui.enabled").value());
        assertEquals("/var/log/qraft", settings.get("logging.directory").value());
        assertEquals("development", settings.get("server.security.profile").value());
        assertNull(settings.get("server.acl.defaultPolicy").value(), "a setting with no value has none");
        assertNull(settings.get("server.dns").value(), "DNS is not served");
        assertFalse(settings.containsKey("server.dns.port"));
        assertNull(settings.get("server.tls.caFile").value());

        Set<String> keys = new HashSet<>();
        config.effective().settings().forEach(setting -> assertTrue(keys.add(setting.key()), setting.key()));
        for (String key : Set.of("server.id", "server.apiGrpcPort", "server.raft.storage.path",
                "server.raft.snapshot.threshold", "server.raft.io.queueSize", "server.telemetry.prometheusPort",
                "server.shutdown.timeoutMs", "server.health.nodeTtlMs", "server.ui.path", "server.operator.token",
                "server.acl.enabled", "server.tls.https.enabled", "server.tls.raft.verifyServerHostname",
                "server.audit.path")) {
            assertTrue(keys.contains(key), key);
        }
    }

    @Test
    void aSecretIsNeverAnswered() throws Exception {
        AppConfig config = AppConfig.fromJson("""
                {"version":1,"server":{"id":"s-1","operator":{"token":"%s"},
                "telemetry":{"otlpEndpoint":"http://otel-user:otel-pass@collector:4317/v1?apiKey=k-123"},
                "dns":{"port":8600,"tokenFile":"/etc/qraft/dns-token"}}}""".formatted(OPERATOR_SECRET));
        Map<String, EffectiveSetting> settings = byKey(config);

        assertEquals(new EffectiveSetting("server.operator.token", null, true, true),
                settings.get("server.operator.token"));
        assertEquals(new EffectiveSetting("server.telemetry.otlpEndpoint", "http://collector:4317/v1", true, true),
                settings.get("server.telemetry.otlpEndpoint"));
        assertEquals(Path.of("/etc/qraft/dns-token").toString(), settings.get("server.dns.tokenFile").value(),
                "a file's path is given, never what it holds");
        assertEquals(8600, settings.get("server.dns.port").value());
        String answered = new ObjectMapper().writeValueAsString(config.effective().settings());
        for (String secret : new String[] {OPERATOR_SECRET, "otel-user", "otel-pass", "k-123"}) {
            assertFalse(answered.contains(secret), secret + " in " + answered);
        }

        EffectiveSetting unset = byKey(AppConfig.fromJson("{\"version\":1,\"server\":{\"id\":\"s-1\"}}"))
                .get("server.operator.token");
        assertEquals(new EffectiveSetting("server.operator.token", "", false, false), unset,
                "no token is not a secret");
    }

    @Test
    void theConfigurationNamesTheFileItWasReadFrom(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("server.json");
        Files.writeString(file, "{\"version\":1,\"server\":{\"id\":\"s-1\"}}");

        assertEquals(Optional.of(file), AppConfig.fromFile(file).effective().file());
        assertEquals(Optional.empty(), AppConfig.fromJson("{\"version\":1,\"server\":{\"id\":\"s-1\"}}").effective()
                .file());
    }
}

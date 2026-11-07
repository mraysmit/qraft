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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code client.acl} (ACL list, proposal 13, Phase 6): its settings and their defaults, the four token files,
 * proposal 12's rules as a client applies them (refused with ACLs enabled and no default policy, and the critical
 * warning with ACLs disabled and {@code deny}), and a configured service's own {@code tokenFile}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class ClientAclSettingsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;

    @Test
    void withoutClientAclTheyAreDisabled() {
        assertEquals(ClientAclSettings.DISABLED, ClientAclSettings.parse(null));
        assertFalse(ClientAclSettings.DISABLED.enabled());
    }

    @Test
    void everySettingIsRead() throws Exception {
        ClientAclSettings settings = ClientAclSettings.parse(JSON.readTree("""
                {"enabled":true,"defaultPolicy":"deny","downPolicy":"async-cache","tokenTtlMs":1000,
                 "policyTtlMs":2000,"roleTtlMs":3000,"enableTokenPersistence":true,
                 "tokens":{"clientFile":"/run/client","configFileRegistrationFile":"/run/registration",
                           "defaultFile":"/run/default","recoveryFile":"/run/recovery"}}"""));

        assertTrue(settings.enabled());
        assertEquals(AclDefaultPolicy.DENY, settings.defaultPolicy());
        assertEquals(DownPolicy.ASYNC_CACHE, settings.downPolicy());
        assertEquals(Duration.ofSeconds(1), settings.tokenTtl());
        assertEquals(Duration.ofSeconds(2), settings.policyTtl());
        assertEquals(Duration.ofSeconds(3), settings.roleTtl());
        assertTrue(settings.enableTokenPersistence());
        assertEquals(Map.of(TokenKind.CLIENT, Path.of("/run/client"),
                TokenKind.CONFIG_FILE_REGISTRATION, Path.of("/run/registration"),
                TokenKind.DEFAULT, Path.of("/run/default"), TokenKind.RECOVERY, Path.of("/run/recovery")),
                settings.tokenFiles());
    }

    @Test
    void theDefaultsAreConsuls() throws Exception {
        ClientAclSettings settings = ClientAclSettings.parse(JSON.readTree(
                "{\"enabled\":true,\"defaultPolicy\":\"allow\"}"));

        assertEquals(DownPolicy.EXTEND_CACHE, settings.downPolicy());
        assertEquals(Duration.ofSeconds(30), settings.tokenTtl());
        assertEquals(Duration.ofSeconds(30), settings.policyTtl());
        assertEquals(Duration.ofSeconds(30), settings.roleTtl());
        assertFalse(settings.enableTokenPersistence());
        assertEquals(Map.of(), settings.tokenFiles());
    }

    @Test
    void whatCannotBeUsedIsRefused() {
        for (String acl : List.of("{\"enabled\":true}", "{\"enabled\":true,\"defaultPolicy\":\"maybe\"}",
                "{\"enabled\":true,\"defaultPolicy\":\"deny\",\"downPolicy\":\"sometimes\"}",
                "{\"enabled\":true,\"defaultPolicy\":\"deny\",\"tokenTtlMs\":0}",
                "{\"enabled\":true,\"defaultPolicy\":\"deny\",\"roleTtlMs\":-5}",
                "{\"enabled\":true,\"defaultPolicy\":\"deny\",\"token\":\"inline\"}",
                "{\"enabled\":true,\"defaultPolicy\":\"deny\",\"tokens\":{\"agentFile\":\"/x\"}}",
                "{\"enabled\":true,\"defaultPolicy\":\"deny\",\"tokens\":[]}", "[]")) {
            assertThrows(IllegalArgumentException.class, () -> ClientAclSettings.parse(JSON.readTree(acl)), acl);
        }
        IllegalArgumentException noDefault = assertThrows(IllegalArgumentException.class,
                () -> ClientAclSettings.parse(JSON.readTree("{\"enabled\":true}")));
        assertTrue(noDefault.getMessage().contains("client.acl.defaultPolicy"), noDefault.getMessage());
    }

    @Test
    void denyWithAclsDisabledIsTheCriticalWarningAndNothingElseWarns() throws Exception {
        String warning = ClientAclSettings.parse(JSON.readTree("{\"enabled\":false,\"defaultPolicy\":\"deny\"}"))
                .startupWarning().orElseThrow();

        assertTrue(warning.startsWith("CRITICAL ACL_DENY_NOT_ENFORCED"), warning);
        assertTrue(ClientAclSettings.DISABLED.startupWarning().isEmpty());
        assertTrue(ClientAclSettings.parse(JSON.readTree("{\"enabled\":true,\"defaultPolicy\":\"deny\"}"))
                .startupWarning().isEmpty());
    }

    @Test
    void theClientConfigurationReadsClientAclAndAServicesTokenFile() {
        ClientConfiguration config = ClientConfiguration.fromJson("""
                {"version":1,
                 "client":{"nodeName":"client-1","dataDirectory":"%s",
                           "acl":{"enabled":true,"defaultPolicy":"deny","tokens":{"clientFile":"/run/client"}}},
                 "servers":{"urls":["http://localhost:8080"]},
                 "catalog":{"services":[{"id":"web-1","name":"web","address":"10.0.0.1","port":80,
                                         "tokenFile":"/run/web"}]}}""".formatted(
                directory.toString().replace("\\", "\\\\")));

        assertTrue(config.getAcl().enabled());
        assertEquals(Path.of("/run/client"), config.getAcl().tokenFiles().get(TokenKind.CLIENT));
        assertEquals(Map.of("web-1", Path.of("/run/web")), config.getServiceTokenFiles());
    }
}

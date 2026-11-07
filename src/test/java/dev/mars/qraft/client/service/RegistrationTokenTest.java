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

package dev.mars.qraft.client.service;

import dev.mars.qraft.client.acl.ClientAclSettings;
import dev.mars.qraft.client.acl.ClientTokens;
import dev.mars.qraft.client.acl.DownPolicy;
import dev.mars.qraft.client.acl.TokenKind;
import dev.mars.qraft.client.config.DefinitionJson;
import dev.mars.qraft.client.config.DefinitionJson.Registration;
import dev.mars.qraft.common.ServiceDefinition;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the token a local API registration keeps (ACL list, proposal 13, Phase 6): kept with the registration in
 * {@code registrations.json}, readable only by the client's user, restored at the next start and forgotten with the
 * registration; and the token each call to the servers carries: the client's own for its node, a registration's own
 * for its service, else the default token, and for a service from the file its own {@code tokenFile}, else the
 * {@code configFileRegistration} token.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class RegistrationTokenTest {
    private static final ServiceDefinition FILE = new ServiceDefinition("file", "file", "10.0.0.9", 7000, List.of(),
            Map.of(), true);

    @TempDir
    Path directory;

    @Test
    void aRegistrationKeepsItsTokenAcrossARestartAndForgetsItWithTheRegistration() throws Exception {
        LocalDefinitions definitions = load();
        definitions.register(registration("web-1", "web"), false, "web-token");
        definitions.register(registration("db-1", "db"), false, null);

        assertEquals(Optional.of("web-token"), definitions.registrationToken("web-1"));
        assertEquals(Optional.empty(), definitions.registrationToken("db-1"), "registered with none");
        assertTrue(definitions.registeredThroughApi("web-1"));
        assertFalse(definitions.registeredThroughApi("file"), "the file's service is not the API's");

        LocalDefinitions restarted = load();
        assertEquals(Optional.of("web-token"), restarted.registrationToken("web-1"), "kept in the data directory");
        Path kept = directory.resolve("registrations.json");
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(kept)),
                    "only the client's user may read a file holding tokens");
        }

        restarted.register(registration("web-1", "web"), false, null);
        assertEquals(Optional.empty(), restarted.registrationToken("web-1"), "a registration replaces its token");
        restarted.register(registration("web-1", "web"), false, "again");
        restarted.deregisterService("web-1");
        assertEquals(Optional.empty(), load().registrationToken("web-1"));
        assertFalse(Files.readString(kept).contains("again"), "a removed registration's token is not kept");
    }

    @Test
    void eachCallCarriesTheTokenOfWhatItIsFor() throws Exception {
        ClientTokens tokens = ClientTokens.load(new ClientAclSettings(true, AclDefaultPolicy.DENY,
                DownPolicy.EXTEND_CACHE, ClientAclSettings.DEFAULT_TTL, ClientAclSettings.DEFAULT_TTL,
                ClientAclSettings.DEFAULT_TTL, false, Map.of(
                        TokenKind.CLIENT, Files.writeString(directory.resolve("client"), "client-token"),
                        TokenKind.DEFAULT, Files.writeString(directory.resolve("default"), "default-token"))),
                directory);
        LocalDefinitions definitions = LocalDefinitions.load(List.of(FILE,
                new ServiceDefinition("own", "own", "10.0.0.9", 7001, List.of(), Map.of(), true)), List.of(),
                new RegistrationStore(directory));
        definitions.register(registration("web-1", "web"), false, "web-token");
        definitions.register(registration("db-1", "db"), false, null);
        ServiceTokens service = new ServiceTokens(tokens, definitions, Map.of("own", "own-token"));

        assertEquals(Optional.of("client-token"), service.node());
        assertEquals(Optional.of("web-token"), service.service("web-1"));
        assertEquals(Optional.of("default-token"), service.service("db-1"), "a registration with none uses default");
        assertEquals(Optional.of("own-token"), service.service("own"), "a file service's own tokenFile");
        assertEquals(Optional.of("default-token"), service.service("file"),
                "else configFileRegistration, which falls back to default");
    }

    private LocalDefinitions load() throws Exception {
        return LocalDefinitions.load(List.of(FILE), List.of(), new RegistrationStore(directory));
    }

    private static Registration registration(String id, String name) {
        return DefinitionJson.parseRegistration(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("id", id).put("name", name).put("port", 80), "10.0.0.5");
    }
}

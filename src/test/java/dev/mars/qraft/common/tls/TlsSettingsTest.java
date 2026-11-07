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

package dev.mars.qraft.common.tls;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the TLS material settings shared by {@code server.tls} and {@code client.tls} (transport security list,
 * decision 1): what is read, the defaults, and what is refused.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class TlsSettingsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void withoutATlsObjectThereIsNone() {
        assertEquals(Optional.empty(), TlsSettings.parse(null, "client.tls"));
    }

    @Test
    void everySettingIsRead() throws Exception {
        TlsSettings settings = TlsSettings.parse(JSON.readTree("""
                {"caFile":"/tls/ca.pem","certFile":"/tls/c.pem","keyFile":"/tls/c.key","minVersion":"TLSv1.3"}"""),
                "client.tls").orElseThrow();

        assertEquals(Path.of("/tls/ca.pem"), settings.caFile());
        assertEquals(Optional.of(Path.of("/tls/c.pem")), settings.certFile());
        assertEquals(Optional.of(Path.of("/tls/c.key")), settings.keyFile());
        assertEquals(List.of("TLSv1.3"), settings.protocols());
        assertEquals("client.tls", settings.location());
    }

    @Test
    void theMinimumVersionIsTls12ByDefaultAsInConsul() throws Exception {
        TlsSettings settings = TlsSettings.parse(JSON.readTree("{\"caFile\":\"/tls/ca.pem\"}"), "client.tls")
                .orElseThrow();

        assertEquals(List.of("TLSv1.3", "TLSv1.2"), settings.protocols());
        assertEquals(Optional.empty(), settings.certFile());
        assertEquals(Optional.empty(), settings.keyFile());
    }

    @Test
    void theCaFileIsRequired() throws Exception {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TlsSettings.parse(JSON.readTree("{\"minVersion\":\"TLSv1.2\"}"), "client.tls"));
        assertEquals("client.tls.caFile is required", refused.getMessage());
    }

    @Test
    void aCertificateNeedsItsKeyAndAKeyItsCertificate() throws Exception {
        IllegalArgumentException noKey = assertThrows(IllegalArgumentException.class, () -> TlsSettings.parse(
                JSON.readTree("{\"caFile\":\"/ca\",\"certFile\":\"/c.pem\"}"), "client.tls"));
        assertEquals("client.tls.certFile and client.tls.keyFile must be set together", noKey.getMessage());

        IllegalArgumentException noCertificate = assertThrows(IllegalArgumentException.class, () -> TlsSettings.parse(
                JSON.readTree("{\"caFile\":\"/ca\",\"keyFile\":\"/c.key\"}"), "server.tls"));
        assertEquals("server.tls.certFile and server.tls.keyFile must be set together", noCertificate.getMessage());
    }

    @Test
    void anUnknownVersionIsRefused() throws Exception {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> TlsSettings.parse(
                JSON.readTree("{\"caFile\":\"/ca\",\"minVersion\":\"TLSv1.1\"}"), "client.tls"));
        assertEquals("client.tls.minVersion must be TLSv1.2 or TLSv1.3", refused.getMessage());
    }

    @Test
    void anUnknownKeyIsRefusedButTheCallerCanAllowItsOwn() throws Exception {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> TlsSettings.parse(
                JSON.readTree("{\"caFile\":\"/ca\",\"verifyIncoming\":true}"), "client.tls"));
        assertTrue(refused.getMessage().contains("verifyIncoming"), refused.getMessage());

        TlsSettings settings = TlsSettings.parse(JSON.readTree("{\"caFile\":\"/ca\",\"https\":{}}"), "server.tls",
                "https").orElseThrow();
        assertEquals(Path.of("/ca"), settings.caFile());
    }

    @Test
    void aBlankFileIsRefused() throws Exception {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> TlsSettings.parse(
                JSON.readTree("{\"caFile\":\"  \"}"), "client.tls"));
        assertEquals("client.tls.caFile must not be blank", refused.getMessage());
    }
}

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

package dev.mars.qraft.common.dns;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.server.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the settings of the DNS interface, {@code server.dns} and {@code client.dns} (DNS discovery list, Phase 3,
 * decisions 1 and 3).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-09
 * @version 1.0
 */
class DnsSettingsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Optional<DnsSettings> parse(String json, boolean allowTokenFile) throws Exception {
        JsonNode node = json == null ? null : JSON.readTree(json);
        return DnsSettings.parse(node, "server.dns", "10.0.0.9", allowTokenFile);
    }

    private static String message(String json, boolean allowTokenFile) {
        return assertThrows(IllegalArgumentException.class, () -> parse(json, allowTokenFile)).getMessage();
    }

    @Test
    void isOffWithoutTheSection() throws Exception {
        assertEquals(Optional.empty(), parse(null, true));
        assertEquals(Optional.empty(), parse("null", true));
    }

    @Test
    void takesTheDefaultsForWhatTheSectionLeavesOut() throws Exception {
        assertEquals(Optional.of(new DnsSettings("10.0.0.9", 8600, "qraft", 0, false, Optional.empty())),
                parse("{\"port\":8600}", true));
    }

    @Test
    void readsEverySetting() throws Exception {
        assertEquals(Optional.of(new DnsSettings("127.0.0.1", 0, "consul.example", 30, true,
                        Optional.of(Path.of("dns.token")))),
                parse("{\"host\":\"127.0.0.1\",\"port\":0,\"domain\":\"Consul.Example.\",\"ttlSeconds\":30,"
                        + "\"onlyPassing\":true,\"tokenFile\":\"dns.token\"}", true));
    }

    @Test
    void refusesWhatItCannotUse() {
        assertTrue(message("{}", true).contains("server.dns.port is required"));
        assertTrue(message("[]", true).contains("server.dns must be an object"));
        assertTrue(message("{\"port\":65536}", true).contains("server.dns.port"));
        assertTrue(message("{\"port\":-1}", true).contains("server.dns.port"));
        assertTrue(message("{\"port\":53,\"domain\":\"bad_label\"}", true).contains("server.dns.domain"));
        assertTrue(message("{\"port\":53,\"ttlSeconds\":-1}", true).contains("server.dns.ttlSeconds"));
        assertTrue(message("{\"port\":53,\"host\":\"\"}", true).contains("server.dns.host"));
        assertTrue(message("{\"port\":53,\"recursors\":[]}", true).contains("recursors"));
        assertTrue(message("{\"port\":53,\"tokenFile\":\"t\"}", false).contains("tokenFile"),
                "a client reads under its default token");
        assertTrue(message("{\"port\":53,\"tokenFile\":\" \"}", true).contains("server.dns.tokenFile"));
    }

    @Test
    void aServerReadsServerDnsWithItsHttpHostAsTheDefaultHost() {
        AppConfig config = AppConfig.fromJson("{\"version\":1,\"server\":{\"http\":{\"host\":\"10.1.1.1\"},"
                + "\"dns\":{\"port\":8600,\"tokenFile\":\"/run/dns.token\"}}}");

        assertEquals(Optional.of(new DnsSettings("10.1.1.1", 8600, "qraft", 0, false,
                Optional.of(Path.of("/run/dns.token")))), config.getDns());
        assertEquals(Optional.empty(), AppConfig.fromJson("{\"version\":1,\"server\":{}}").getDns());
    }

    @Test
    void aClientReadsClientDnsWithItsBindAddressAsTheDefaultHost() {
        ClientConfiguration config = ClientConfiguration.fromJson("{\"version\":1,\"client\":{\"dataDirectory\":"
                + "\"data\",\"bindAddress\":\"0.0.0.0\",\"dns\":{\"port\":8600,\"onlyPassing\":true}},"
                + "\"servers\":{\"urls\":[\"http://127.0.0.1:8080\"]}}");

        assertEquals(Optional.of(new DnsSettings("0.0.0.0", 8600, "qraft", 0, true, Optional.empty())),
                config.getDns());
        assertThrows(IllegalArgumentException.class, () -> ClientConfiguration.fromJson("{\"version\":1,\"client\":"
                + "{\"dataDirectory\":\"data\",\"dns\":{\"port\":8600,\"tokenFile\":\"t\"}},"
                + "\"servers\":{\"urls\":[\"http://127.0.0.1:8080\"]}}"));
    }
}

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

package dev.mars.qraft.client.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@code client.tls} (transport security list, decision 1, Phase 3): absent by default, read when present, and
 * refused with a key it does not know.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class ClientTlsConfigurationTest {
    private static String document(String tls) {
        return "{\"version\":1,\"client\":{\"nodeName\":\"n\",\"dataDirectory\":\"/data\"" + tls + "},"
                + "\"servers\":{\"urls\":[\"https://server1:8080\"]}}";
    }

    @Test
    void withoutClientTlsThereIsNone() {
        assertEquals(Optional.empty(), ClientConfiguration.fromJson(document("")).getTls());
    }

    @Test
    void clientTlsIsRead() {
        ClientConfiguration configuration = ClientConfiguration.fromJson(document(
                ",\"tls\":{\"caFile\":\"/tls/ca.pem\",\"certFile\":\"/tls/c.pem\",\"keyFile\":\"/tls/c.key\"}"));

        assertEquals(Path.of("/tls/ca.pem"), configuration.getTls().orElseThrow().caFile());
        assertEquals(Optional.of(Path.of("/tls/c.pem")), configuration.getTls().orElseThrow().certFile());
        assertEquals("client.tls", configuration.getTls().orElseThrow().location());
    }

    @Test
    void anUnknownKeyIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ClientConfiguration.fromJson(document(
                ",\"tls\":{\"caFile\":\"/tls/ca.pem\",\"https\":{}}")));
    }
}

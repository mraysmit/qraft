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

package dev.mars.qraft.server.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the administrative interface's bootstrap object: the public settings it names, and JSON that is safe to
 * place inside the {@code <script>} element of {@code index.html} (design section 12.4.1).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-13
 * @version 1.0
 */
class UiBootstrapTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final char LINE_SEPARATOR = (char) 0x2028;
    private static final char PARAGRAPH_SEPARATOR = (char) 0x2029;

    @Test
    void itNamesThePublicSettingsAndTheRequestsNonce() throws Exception {
        JsonNode bootstrap = JSON.readTree(new UiBootstrap("/", "/ui/", Map.of("events", true), "token", "production")
                .toJson("bm9uY2U"));

        Set<String> fields = new TreeSet<>();
        bootstrap.fieldNames().forEachRemaining(fields::add);
        assertEquals(new TreeSet<>(List.of("apiBasePath", "uiPath", "features", "authMode", "securityProfile",
                "cspNonce")), fields);
        assertEquals("/", bootstrap.path("apiBasePath").asText());
        assertEquals("/ui/", bootstrap.path("uiPath").asText());
        assertTrue(bootstrap.path("features").path("events").asBoolean());
        assertEquals("token", bootstrap.path("authMode").asText());
        assertEquals("production", bootstrap.path("securityProfile").asText());
        assertEquals("bm9uY2U", bootstrap.path("cspNonce").asText());
    }

    @Test
    void theJsonCannotCloseTheElementThatCarriesIt() throws Exception {
        String hostile = "</script><script>alert(1)</script><!--&";
        String json = new UiBootstrap("/", "/ui/", Map.of(hostile, true), "none", "development").toJson("n");

        assertFalse(json.contains("<"), json);
        assertFalse(json.contains(">"), json);
        assertFalse(json.contains("&"), json);
        assertTrue(JSON.readTree(json).path("features").path(hostile).asBoolean(), "the value survives the escaping");
    }

    @Test
    void lineAndParagraphSeparatorsAreEscaped() throws Exception {
        String separators = "a" + LINE_SEPARATOR + "b" + PARAGRAPH_SEPARATOR + "c";
        String json = new UiBootstrap("/", "/ui/", Map.of(separators, false), "none", "development").toJson("n");

        assertFalse(json.indexOf(LINE_SEPARATOR) >= 0, json);
        assertFalse(json.indexOf(PARAGRAPH_SEPARATOR) >= 0, json);
        assertTrue(JSON.readTree(json).path("features").has(separators));
    }
}

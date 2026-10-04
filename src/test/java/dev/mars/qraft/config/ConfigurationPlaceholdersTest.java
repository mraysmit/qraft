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

package dev.mars.qraft.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that a configuration document containing an environment-style placeholder is rejected rather than
 * read literally or interpolated (design section 16). A {@code ${...}} anywhere in a string value, or in a
 * field name such as a free-form node ID, is rejected with its JSON path; a lone {@code $} is ordinary text.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-28
 * @version 1.0
 */
class ConfigurationPlaceholdersTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aPlaceholderInANestedValueIsRejectedWithItsPath() {
        assertRejected("{\"server\":{\"http\":{\"host\":\"${QRAFT_HOST}\"}}}",
                "server.http.host contains an environment-style placeholder, which configuration does not support: "
                        + "${QRAFT_HOST}");
    }

    @Test
    void aPlaceholderInsideLongerTextOrWithADefaultIsRejected() {
        assertRejected("{\"logging\":{\"directory\":\"/var/log/${USER:-qraft}/x\"}}", "logging.directory");
    }

    @Test
    void aPlaceholderInAnArrayElementIsRejectedWithItsIndex() {
        assertRejected("{\"controllers\":{\"urls\":[\"http://a:8080\",\"http://${CONTROLLER}:8080\"]}}",
                "controllers.urls[1]");
        assertRejected("{\"catalog\":{\"services\":[{\"checks\":[{\"url\":\"http://${HOST}/health\"}]}]}}",
                "catalog.services[0].checks[0].url");
    }

    @Test
    void aPlaceholderInAFieldNameIsRejected() {
        assertRejected("{\"server\":{\"raft\":{\"nodes\":{\"${NODE_ID}\":\"a:9080\"}}}}",
                "server.raft.nodes has a field name with an environment-style placeholder");
    }

    @Test
    void ordinaryTextNumbersAndALoneDollarAreAccepted() {
        for (String document : new String[] {
                "{}", "{\"a\":1,\"b\":true,\"c\":null,\"d\":[1,2]}", "{\"secret\":\"pa$$word\"}",
                "{\"url\":\"http://h/api?$filter=x\"}", "{\"text\":\"{braces} and $ alone\"}"}) {
            assertDoesNotThrow(() -> ConfigurationPlaceholders.reject(JSON.readTree(document)), document);
        }
    }

    @Test
    void aNullDocumentIsLeftToTheCallersOwnCheck() {
        assertDoesNotThrow(() -> ConfigurationPlaceholders.reject(null));
    }

    private static void assertRejected(String document, String expectedMessageStart) {
        JsonNode root;
        try {
            root = JSON.readTree(document);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> ConfigurationPlaceholders.reject(root), document);
        assertEquals(true, rejected.getMessage().startsWith(expectedMessageStart), rejected.getMessage());
    }
}

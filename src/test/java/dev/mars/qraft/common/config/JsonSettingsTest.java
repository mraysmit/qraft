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

package dev.mars.qraft.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link JsonSettings}: what it reads, and the exact message of each refusal. Both modes' configuration
 * rely on these messages, and their own tests check them through the documents they accept and refuse.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-10
 * @version 1.0
 */
class JsonSettingsTest {

    @Test
    void aDocumentIsReadIntoItsRootObject() {
        JsonNode root = JsonSettings.readDocument("{\"version\":1,\"server\":{\"id\":\"a\"}}", "Server");

        assertEquals("a", root.path("server").path("id").textValue());
    }

    @Test
    void aDocumentThatIsNotOneJsonObjectIsRefusedInTheOwnersName() {
        assertEquals("Server configuration is not valid JSON", refusal(() -> JsonSettings.readDocument("{", "Server")));
        assertEquals("Client configuration is not valid JSON",
                refusal(() -> JsonSettings.readDocument("{\"a\":1,\"a\":2}", "Client")),
                "a repeated key is not valid");
        assertEquals("Client configuration must be a JSON object",
                refusal(() -> JsonSettings.readDocument("[]", "Client")));
        assertEquals("Server configuration must be a JSON object",
                refusal(() -> JsonSettings.readDocument("", "Server")));
    }

    @Test
    void aPlaceholderIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> JsonSettings.readDocument("{\"server\":{\"id\":\"${NODE}\"}}", "Server"));
    }

    @Test
    void theOnlyFormatVersionIsOne() {
        JsonSettings.requireFormatVersion(object("{\"version\":1}"));

        assertEquals("Unsupported configuration version: 2",
                refusal(() -> JsonSettings.requireFormatVersion(object("{\"version\":2}"))));
        assertEquals("version must be an integer", refusal(() -> JsonSettings.requireFormatVersion(object("{}"))));
    }

    @Test
    void objectsAreRequiredOrDefaultToEmpty() {
        JsonNode parent = object("{\"raft\":{\"port\":9080},\"http\":null,\"io\":5}");

        assertEquals(9080, JsonSettings.requiredObject(parent, "raft").path("port").intValue());
        assertEquals("server must be an object", refusal(() -> JsonSettings.requiredObject(parent, "server")));
        assertEquals("io must be an object", refusal(() -> JsonSettings.requiredObject(parent, "io")));
        assertTrue(JsonSettings.optionalObject(parent, "telemetry").isEmpty(), "an absent object is an empty one");
        assertTrue(JsonSettings.optionalObject(parent, "http").isEmpty(), "and so is a null");
        assertEquals("io must be an object", refusal(() -> JsonSettings.optionalObject(parent, "io")));
    }

    @Test
    void numbersAndBooleansAreReadStrictly() {
        JsonNode parent = object("{\"port\":8080,\"big\":5000000000,\"text\":\"8080\",\"real\":1.5,\"on\":true}");

        assertEquals(8080, JsonSettings.requiredInt(parent, "port"));
        assertEquals(8080, JsonSettings.optionalInt(parent, "port", 1));
        assertEquals(1, JsonSettings.optionalInt(parent, "absent", 1));
        assertEquals(5_000_000_000L, JsonSettings.optionalLong(parent, "big", 1));
        assertEquals(7, JsonSettings.optionalLong(parent, "absent", 7));
        assertTrue(JsonSettings.optionalBoolean(parent, "on", false));
        assertFalse(JsonSettings.optionalBoolean(parent, "absent", false));

        assertEquals("absent must be an integer", refusal(() -> JsonSettings.requiredInt(parent, "absent")));
        assertEquals("text must be an integer", refusal(() -> JsonSettings.optionalInt(parent, "text", 1)));
        assertEquals("big must be an integer", refusal(() -> JsonSettings.requiredInt(parent, "big")),
                "a number too large for an int is not one");
        assertEquals("real must be an integer", refusal(() -> JsonSettings.optionalLong(parent, "real", 1)));
        assertEquals("text must be a boolean", refusal(() -> JsonSettings.optionalBoolean(parent, "text", true)));
    }

    @Test
    void aSettingThatIsNotAllowedIsRefusedByNameAndPlace() {
        JsonNode http = object("{\"host\":\"0.0.0.0\",\"prot\":8080}");

        JsonSettings.rejectUnknown(object("{\"host\":\"0.0.0.0\"}"), "server.http", "host", "port");
        assertEquals("Unknown server.http setting: prot",
                refusal(() -> JsonSettings.rejectUnknown(http, "server.http", "host", "port")));
    }

    private static JsonNode object(String json) {
        return JsonSettings.readDocument(json, "Test");
    }

    private static String refusal(Runnable reading) {
        return assertThrows(IllegalArgumentException.class, reading::run).getMessage();
    }
}

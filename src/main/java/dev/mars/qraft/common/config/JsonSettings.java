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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.Set;

/**
 * Reads the settings of a Qraft configuration document: the parsing and the checks that the server's and the
 * client's configuration share. Each refusal is an {@link IllegalArgumentException} that names the setting.
 *
 * <p>Reading a text setting is not here: the two modes differ on whether a blank one is allowed.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-10
 * @version 1.0
 */
public final class JsonSettings {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private JsonSettings() {
    }

    /**
     * Parses a configuration document into its root object. A document that is not JSON, that repeats a key,
     * that is not an object, or that holds a placeholder is refused. {@code owner} begins the message, as in
     * "Server configuration is not valid JSON".
     */
    public static JsonNode readDocument(String document, String owner) {
        final JsonNode root;
        try {
            root = JSON.readTree(document);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException(owner + " configuration is not valid JSON", error);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(owner + " configuration must be a JSON object");
        }
        ConfigurationPlaceholders.reject(root);
        return root;
    }

    /** Requires the document's {@code version} to be 1, the only format there is. */
    public static void requireFormatVersion(JsonNode root) {
        int version = requiredInt(root, "version");
        if (version != 1) {
            throw new IllegalArgumentException("Unsupported configuration version: " + version);
        }
    }

    public static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) throw new IllegalArgumentException(field + " must be an object");
        return value;
    }

    /** The object under {@code field}, or an empty object when the document has none. */
    public static JsonNode optionalObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return JSON.createObjectNode();
        if (!value.isObject()) throw new IllegalArgumentException(field + " must be an object");
        return value;
    }

    public static int requiredInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.intValue();
    }

    public static int optionalInt(JsonNode parent, String field, int fallback) {
        return parent.has(field) ? requiredInt(parent, field) : fallback;
    }

    public static long optionalLong(JsonNode parent, String field, long fallback) {
        JsonNode value = parent.get(field);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.longValue();
    }

    public static boolean optionalBoolean(JsonNode parent, String field, boolean fallback) {
        JsonNode value = parent.get(field);
        if (value == null) return fallback;
        if (!value.isBoolean()) throw new IllegalArgumentException(field + " must be a boolean");
        return value.booleanValue();
    }

    /** Refuses any setting of {@code object} that is not one of {@code allowedNames}. */
    public static void rejectUnknown(JsonNode object, String location, String... allowedNames) {
        Set<String> allowed = Set.of(allowedNames);
        object.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("Unknown " + location + " setting: " + name);
            }
        });
    }
}

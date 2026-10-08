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

import java.util.Iterator;
import java.util.Map;

/**
 * Refuses a configuration document that contains an environment-style placeholder. Qraft does not read
 * configuration from the environment (design section 16), so a {@code ${...}} in a document is a mistake:
 * read literally it would name a host, path, or ID that does not exist, and interpolated it would reintroduce
 * environment configuration. Any string value, or any field name, containing {@code ${} is refused with its
 * JSON path. A lone {@code $} is ordinary text.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-28
 * @version 1.0
 */
public final class ConfigurationPlaceholders {
    private static final String PLACEHOLDER = "${";

    private ConfigurationPlaceholders() {
    }

    /**
     * Refuses {@code document} if any string value or field name in it contains a placeholder. A {@code null}
     * document is left to the caller's own check.
     *
     * @throws IllegalArgumentException naming the JSON path of the first placeholder found
     */
    public static void reject(JsonNode document) {
        if (document != null) visit(document, "");
    }

    private static void visit(JsonNode node, String path) {
        if (node.isTextual()) {
            if (node.textValue().contains(PLACEHOLDER)) {
                throw new IllegalArgumentException(path + " contains an environment-style placeholder, which "
                        + "configuration does not support: " + node.textValue());
            }
        } else if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getKey().contains(PLACEHOLDER)) {
                    throw new IllegalArgumentException((path.isEmpty() ? "the document" : path)
                            + " has a field name with an environment-style placeholder, which configuration "
                            + "does not support: " + field.getKey());
                }
                visit(field.getValue(), path.isEmpty() ? field.getKey() : path + "." + field.getKey());
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                visit(node.get(index), path + "[" + index + "]");
            }
        }
    }
}

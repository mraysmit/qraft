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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The public runtime settings the administrative interface's {@code index.html} carries (design section 12.4.1): the
 * base path of the API it calls, its own path, the feature flags it may show, how it authenticates ({@code none}, or
 * {@code token} with ACLs enabled), and the server's security profile. It never holds a secret or a credential.
 *
 * <p>{@link #toJson} adds the request's content security policy nonce, which the interface hands to Ant Design so
 * that its runtime styles are allowed, and escapes the JSON so that it can sit inside a {@code <script>} element.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-13
 * @version 1.0
 */
public record UiBootstrap(String apiBasePath, String uiPath, Map<String, Boolean> features, String authMode,
                          String securityProfile) {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final char LINE_SEPARATOR = (char) 0x2028;
    private static final char PARAGRAPH_SEPARATOR = (char) 0x2029;

    public UiBootstrap {
        Objects.requireNonNull(apiBasePath, "apiBasePath");
        Objects.requireNonNull(uiPath, "uiPath");
        features = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(features, "features")));
        Objects.requireNonNull(authMode, "authMode");
        Objects.requireNonNull(securityProfile, "securityProfile");
    }

    /** The settings as JSON for a request whose content security policy nonce is {@code nonce}. */
    public String toJson(String nonce) {
        ObjectNode node = JSON.createObjectNode();
        node.put("apiBasePath", apiBasePath);
        node.put("uiPath", uiPath);
        ObjectNode flags = node.putObject("features");
        features.forEach(flags::put);
        node.put("authMode", authMode);
        node.put("securityProfile", securityProfile);
        node.put("cspNonce", Objects.requireNonNull(nonce, "nonce"));
        try {
            return escapeForScriptElement(JSON.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("the bootstrap object could not be written", e);
        }
    }

    /**
     * Escapes the characters that could end the {@code <script>} element or open a comment in it, and the line and
     * paragraph separators that older JavaScript parsers take for line ends. In JSON they can only occur inside a
     * string, where a JSON unicode escape reads back as the same character.
     */
    private static String escapeForScriptElement(String json) {
        StringBuilder escaped = new StringBuilder(json.length() + 16);
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '<' || c == '>' || c == '&' || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR) {
                escaped.append(String.format("\\u%04x", (int) c));
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }
}

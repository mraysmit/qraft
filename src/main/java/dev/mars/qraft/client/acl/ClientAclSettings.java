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

package dev.mars.qraft.client.acl;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.config.JsonSettings;

import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code client.acl} (ACL list, proposal 13).
 *
 * @param tokenFiles each token's mounted file, by kind; a kind without one has no token from a file
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
public record ClientAclSettings(boolean enabled, AclDefaultPolicy defaultPolicy, DownPolicy downPolicy,
                                Duration tokenTtl, Duration policyTtl, Duration roleTtl,
                                boolean enableTokenPersistence, Map<TokenKind, Path> tokenFiles) {
    /** The cache's lifetime for each kind of entry, 30 s by default, as Consul's. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);
    /** ACLs disabled: the settings of a configuration without {@code client.acl}. */
    public static final ClientAclSettings DISABLED = new ClientAclSettings(false, null, DownPolicy.EXTEND_CACHE,
            DEFAULT_TTL, DEFAULT_TTL, DEFAULT_TTL, false, Map.of());

    private static final Map<String, TokenKind> TOKEN_FILES = Map.of("clientFile", TokenKind.CLIENT,
            "configFileRegistrationFile", TokenKind.CONFIG_FILE_REGISTRATION, "defaultFile", TokenKind.DEFAULT,
            "recoveryFile", TokenKind.RECOVERY);

    public ClientAclSettings {
        tokenFiles = Map.copyOf(tokenFiles);
    }

    /**
     * The settings {@code acl} holds; {@code null} is {@link #DISABLED}. As the servers' (proposal 12), enabled ACLs
     * need a default policy, which must be the servers'; anything unknown is refused.
     */
    public static ClientAclSettings parse(JsonNode acl) {
        if (acl == null || acl.isNull()) return DISABLED;
        if (!acl.isObject()) throw new IllegalArgumentException("client.acl must be an object");
        JsonSettings.rejectUnknown(acl, "client.acl", "enabled", "defaultPolicy", "downPolicy", "tokenTtlMs",
                "policyTtlMs", "roleTtlMs", "enableTokenPersistence", "tokens");
        boolean enabled = JsonSettings.optionalBoolean(acl, "enabled", false);
        AclDefaultPolicy defaultPolicy = defaultPolicy(acl.get("defaultPolicy"));
        if (enabled && defaultPolicy == null) {
            throw new IllegalArgumentException("client.acl.defaultPolicy must be set when ACLs are enabled: allow or "
                    + "deny, as the servers' is");
        }
        JsonNode down = acl.get("downPolicy");
        DownPolicy downPolicy = down == null ? DownPolicy.EXTEND_CACHE
                : DownPolicy.of(down.isTextual() ? down.textValue() : down.toString());
        return new ClientAclSettings(enabled, defaultPolicy, downPolicy, ttl(acl, "tokenTtlMs"),
                ttl(acl, "policyTtlMs"), ttl(acl, "roleTtlMs"),
                JsonSettings.optionalBoolean(acl, "enableTokenPersistence", false), tokenFiles(acl.get("tokens")));
    }

    /** The one warning a start logs for these settings: {@code deny} with ACLs disabled, as on a server. */
    public Optional<String> startupWarning() {
        if (enabled || defaultPolicy != AclDefaultPolicy.DENY) return Optional.empty();
        return Optional.of("CRITICAL ACL_DENY_NOT_ENFORCED: client.acl.defaultPolicy is deny, but ACLs are disabled, "
                + "so nothing is denied and every call is allowed. Set client.acl.enabled to enforce it");
    }

    private static AclDefaultPolicy defaultPolicy(JsonNode value) {
        if (value == null || value.isNull()) return null;
        return switch (value.isTextual() ? value.textValue() : "") {
            case "allow" -> AclDefaultPolicy.ALLOW;
            case "deny" -> AclDefaultPolicy.DENY;
            default -> throw new IllegalArgumentException("client.acl.defaultPolicy is allow or deny, not " + value);
        };
    }

    private static Duration ttl(JsonNode acl, String field) {
        long millis = JsonSettings.optionalLong(acl, field, DEFAULT_TTL.toMillis());
        if (millis < 1) throw new IllegalArgumentException("client.acl." + field + " must be positive");
        return Duration.ofMillis(millis);
    }

    private static Map<TokenKind, Path> tokenFiles(JsonNode tokens) {
        if (tokens == null || tokens.isNull()) return Map.of();
        if (!tokens.isObject()) throw new IllegalArgumentException("client.acl.tokens must be an object");
        JsonSettings.rejectUnknown(tokens, "client.acl.tokens", TOKEN_FILES.keySet().toArray(String[]::new));
        Map<TokenKind, Path> files = new EnumMap<>(TokenKind.class);
        tokens.properties().forEach(entry -> {
            if (!entry.getValue().isTextual() || entry.getValue().textValue().isBlank()) {
                throw new IllegalArgumentException("client.acl.tokens." + entry.getKey() + " must be a path");
            }
            files.put(TOKEN_FILES.get(entry.getKey()), Path.of(entry.getValue().textValue()));
        });
        return files;
    }
}

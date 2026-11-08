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

package dev.mars.qraft.server.acl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.state.acl.AuthMethod;
import dev.mars.qraft.state.acl.JwtConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a bearer JWT for a {@code jwt} auth method (authentication list, Phase 2, decision 1), as Consul's method
 * does: an RS or ES signature by one of the method's keys, never {@code none} or HMAC; the bound issuer and one of the
 * bound audiences; the expiry and not-before times with their leeways and the clock skew; and then the claims mapped to
 * the names that selectors and bind names read. Anything else is refused with {@link IllegalArgumentException}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class JwtValidator {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A login's mapped claims: single values, and lists. */
    public record Mapped(Map<String, String> values, Map<String, List<String>> lists) {
        public Mapped {
            values = Map.copyOf(values);
            lists = Map.copyOf(lists);
        }
    }

    private JwtValidator() { }

    public static Mapped validate(String jwt, AuthMethod method, Clock clock) {
        if (jwt == null || jwt.isBlank()) throw new IllegalArgumentException("A bearer token is required");
        String[] parts = jwt.split("\\.", -1);
        if (parts.length != 3) throw new IllegalArgumentException("A JWT has three parts");
        JsonNode header = json(parts[0]);
        JsonNode claims = json(parts[1]);
        String alg = header.path("alg").asText("");
        String algorithm = switch (alg) {
            case "ES256" -> "SHA256withECDSAinP1363Format";
            case "ES384" -> "SHA384withECDSAinP1363Format";
            case "ES512" -> "SHA512withECDSAinP1363Format";
            case "RS256" -> "SHA256withRSA";
            case "RS384" -> "SHA384withRSA";
            case "RS512" -> "SHA512withRSA";
            default -> throw new IllegalArgumentException("A JWT signed with " + alg + " is not accepted");
        };
        if (!(method.config() instanceof JwtConfig config)) {
            throw new IllegalArgumentException("The auth method " + method.name() + " is not a jwt method");
        }
        verify(parts, algorithm, alg.startsWith("ES"), JwtKeys.parse(config.publicKeysPem()));
        checkClaims(claims, config, clock.instant());
        return mapped(claims, config);
    }

    private static void verify(String[] parts, String algorithm, boolean ec, List<PublicKey> keys) {
        byte[] signature;
        try {
            signature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException("A JWT's signature is not base64url");
        }
        byte[] input = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        for (PublicKey key : keys) {
            if (ec != key instanceof ECPublicKey) continue;
            try {
                Signature verifier = Signature.getInstance(algorithm);
                verifier.initVerify(key);
                verifier.update(input);
                if (verifier.verify(signature)) return;
            } catch (GeneralSecurityException notThisKey) {
                // The next key is tried.
            }
        }
        throw new IllegalArgumentException("The JWT is not signed by a key of the auth method");
    }

    private static void checkClaims(JsonNode claims, JwtConfig config, Instant now) {
        long skew = config.clockSkewLeeway().getSeconds();
        long at = now.getEpochSecond();
        if (claims.has("exp") && at > claims.path("exp").asLong() + config.expirationLeeway().getSeconds() + skew) {
            throw new IllegalArgumentException("The JWT has expired");
        }
        if (claims.has("nbf") && at < claims.path("nbf").asLong() - config.notBeforeLeeway().getSeconds() - skew) {
            throw new IllegalArgumentException("The JWT is not valid yet");
        }
        if (!config.boundIssuer().isEmpty() && !config.boundIssuer().equals(claims.path("iss").asText(null))) {
            throw new IllegalArgumentException("The JWT's issuer is not the auth method's");
        }
        if (!config.boundAudiences().isEmpty()) {
            Set<String> audiences = new LinkedHashSet<>();
            JsonNode aud = claims.path("aud");
            if (aud.isArray()) aud.forEach(item -> audiences.add(item.asText()));
            else if (aud.isTextual()) audiences.add(aud.asText());
            if (audiences.stream().noneMatch(config.boundAudiences()::contains)) {
                throw new IllegalArgumentException("The JWT is not for an audience of the auth method");
            }
        }
    }

    private static Mapped mapped(JsonNode claims, JwtConfig config) {
        Map<String, String> values = new LinkedHashMap<>();
        config.claimMappings().forEach((claim, name) -> {
            JsonNode value = claims.get(claim);
            if (value != null && value.isValueNode() && !value.isNull()) values.put(name, value.asText());
        });
        Map<String, List<String>> lists = new LinkedHashMap<>();
        config.listClaimMappings().forEach((claim, name) -> {
            JsonNode value = claims.get(claim);
            if (value == null || value.isNull()) return;
            List<String> items = new ArrayList<>();
            if (value.isArray()) value.forEach(item -> items.add(item.asText()));
            else if (value.isValueNode()) items.add(value.asText());
            lists.put(name, List.copyOf(items));
        });
        return new Mapped(values, lists);
    }

    private static JsonNode json(String part) {
        try {
            JsonNode node = JSON.readTree(Base64.getUrlDecoder().decode(part));
            if (node == null || !node.isObject()) throw new IllegalArgumentException("A JWT part is not a JSON object");
            return node;
        } catch (IOException | IllegalArgumentException notJson) {
            throw new IllegalArgumentException("A JWT part is not base64url JSON");
        }
    }
}

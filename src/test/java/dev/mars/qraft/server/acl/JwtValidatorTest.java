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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.state.acl.AuthMethod;
import dev.mars.qraft.state.acl.JwtConfig;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests JWT validation for the {@code jwt} auth method (authentication list, Phase 2, decision 1): an ES256 or RS256 token
 * signed by a configured key, from the bound issuer, for a bound audience, within its times and their leeways, is
 * accepted and its claims mapped; a token signed by another key, with {@code none} or HMAC, expired, not yet valid, from
 * another issuer, for another audience, or malformed, is refused.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class JwtValidatorTest {
    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    static KeyPair ecKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static KeyPair rsaKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    static String pem(KeyPair keys) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(
                keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
    }

    /** A JWT with {@code claims}, signed with {@code key} under {@code alg}, or unsigned for {@code none}. */
    static String jwt(String alg, PrivateKey key, Map<String, Object> claims) throws Exception {
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String signingInput = url.encodeToString(JSON.writeValueAsBytes(Map.of("alg", alg, "typ", "JWT"))) + "."
                + url.encodeToString(JSON.writeValueAsBytes(claims));
        if (alg.equals("none")) return signingInput + ".";
        Signature signer = Signature.getInstance(alg.equals("ES256") ? "SHA256withECDSAinP1363Format" : "SHA256withRSA");
        signer.initSign(key);
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + url.encodeToString(signer.sign());
    }

    static Map<String, Object> claims(Instant expires) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "https://issuer.example");
        claims.put("aud", List.of("qraft", "other"));
        claims.put("sub", "web");
        claims.put("groups", List.of("admins", "dev"));
        claims.put("exp", expires.getEpochSecond());
        claims.put("nbf", NOW.minusSeconds(60).getEpochSecond());
        return claims;
    }

    private static AuthMethod method(String... pems) {
        return new AuthMethod("workloads", "jwt", "", Duration.ofMinutes(30), new JwtConfig(List.of(pems),
                "https://issuer.example", List.of("qraft"), Map.of("sub", "name"), Map.of("groups", "groups"),
                Duration.ofSeconds(30), Duration.ZERO, Duration.ofSeconds(5)), 0, 0);
    }

    @Test
    void aTokenSignedByAConfiguredKeyIsAcceptedAndItsClaimsMapped() throws Exception {
        KeyPair ec = ecKeys();
        KeyPair rsa = rsaKeys();
        AuthMethod method = method(pem(rsa), pem(ec));

        JwtValidator.Mapped mapped = JwtValidator.validate(jwt("ES256", ec.getPrivate(), claims(NOW.plusSeconds(300))),
                method, CLOCK);
        assertEquals(Map.of("name", "web"), mapped.values());
        assertEquals(Map.of("groups", List.of("admins", "dev")), mapped.lists());
        assertEquals("web", JwtValidator.validate(jwt("RS256", rsa.getPrivate(), claims(NOW.plusSeconds(300))), method,
                CLOCK).values().get("name"));
        assertEquals("web", JwtValidator.validate(jwt("ES256", ec.getPrivate(), claims(NOW.minusSeconds(20))), method,
                CLOCK).values().get("name"), "expired within the expiration leeway");
    }

    @Test
    void anythingElseIsRefused() throws Exception {
        KeyPair ec = ecKeys();
        AuthMethod method = method(pem(ec));
        Map<String, Object> wrongIssuer = claims(NOW.plusSeconds(300));
        wrongIssuer.put("iss", "https://elsewhere.example");
        Map<String, Object> wrongAudience = claims(NOW.plusSeconds(300));
        wrongAudience.put("aud", "elsewhere");
        Map<String, Object> notYet = claims(NOW.plusSeconds(300));
        notYet.put("nbf", NOW.plusSeconds(60).getEpochSecond());

        for (String refused : List.of(
                jwt("ES256", ecKeys().getPrivate(), claims(NOW.plusSeconds(300))),
                jwt("none", null, claims(NOW.plusSeconds(300))),
                jwt("ES256", ec.getPrivate(), claims(NOW.minusSeconds(60))),
                jwt("ES256", ec.getPrivate(), wrongIssuer),
                jwt("ES256", ec.getPrivate(), wrongAudience),
                jwt("ES256", ec.getPrivate(), notYet),
                hmac(claims(NOW.plusSeconds(300))),
                "not.a.jwt", "two.parts", "")) {
            assertThrows(IllegalArgumentException.class, () -> JwtValidator.validate(refused, method, CLOCK), refused);
        }
    }

    private static String hmac(Map<String, Object> claims) throws Exception {
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        return url.encodeToString(JSON.writeValueAsBytes(Map.of("alg", "HS256"))) + "."
                + url.encodeToString(JSON.writeValueAsBytes(claims)) + "." + url.encodeToString(new byte[32]);
    }
}

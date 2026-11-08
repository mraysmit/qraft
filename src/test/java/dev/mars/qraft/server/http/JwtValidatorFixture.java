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

package dev.mars.qraft.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Map;

/**
 * Test fixture that makes EC key pairs and the ES256 JWTs a workload would present at login (authentication list).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
final class JwtValidatorFixture {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JwtValidatorFixture() { }

    static KeyPair ecKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static String pem(KeyPair keys) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(
                keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
    }

    static String jwt(KeyPair keys, Map<String, Object> claims) throws Exception {
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String signingInput = url.encodeToString(JSON.writeValueAsBytes(Map.of("alg", "ES256", "typ", "JWT"))) + "."
                + url.encodeToString(JSON.writeValueAsBytes(claims));
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(keys.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + url.encodeToString(signer.sign());
    }
}

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

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The public keys a {@code jwt} auth method verifies with (authentication list, decision 1): PEM {@code PUBLIC KEY}
 * blocks of RSA or EC keys, as Consul's {@code JWTValidationPubKeys}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class JwtKeys {
    private static final String BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String END = "-----END PUBLIC KEY-----";

    private JwtKeys() { }

    /** The keys {@code pems} hold; refused with {@link IllegalArgumentException} for one that is not an RSA or EC key. */
    public static List<PublicKey> parse(List<String> pems) {
        List<PublicKey> keys = new ArrayList<>();
        for (String pem : pems) keys.add(parse(pem));
        return keys;
    }

    public static PublicKey parse(String pem) {
        String text = pem == null ? "" : pem.strip();
        if (!text.startsWith(BEGIN) || !text.endsWith(END)) {
            throw new IllegalArgumentException("A JWT validation key is a PEM PUBLIC KEY block");
        }
        byte[] der;
        try {
            der = Base64.getMimeDecoder().decode(text.substring(BEGIN.length(), text.length() - END.length()));
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException("A JWT validation key's PEM block is not base64");
        }
        for (String algorithm : List.of("RSA", "EC")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(der));
            } catch (GeneralSecurityException notThisAlgorithm) {
                // The next algorithm is tried.
            }
        }
        throw new IllegalArgumentException("A JWT validation key is neither an RSA nor an EC public key");
    }
}

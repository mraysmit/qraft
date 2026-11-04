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

package dev.mars.qraft.state.acl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How a token's secret is held, and what an ACL ID is (ACL list, proposal 3, decided 2026-11-01).
 *
 * <p>Following ZooKeeper's digest scheme rather than Consul, a server keeps only the SHA-256 hash of a secret, taken
 * before the command that carries it is proposed, so replicas, the WAL, snapshots and backups never hold a working
 * secret. A presented secret is hashed and looked up by its hash. A generated secret has about 122 random bits, too
 * many to guess back from a fast hash, so no slow password hash is needed. The hash is of the secret exactly as
 * written: Consul compares secrets case-sensitively ({@code indexFromStringCaseSensitive} in
 * {@code agent/consul/state/acl_schema.go}).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
public final class AclSecrets {
    /**
     * IDs whose first 120 bits are zero are kept for built-ins, as Consul's {@code ACLReservedIDPrefix} in
     * {@code agent/structs/acl.go} keeps them.
     */
    public static final String RESERVED_ID_PREFIX = "00000000-0000-0000-0000-0000000000";
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");

    private AclSecrets() {
    }

    /** The lower-case hex SHA-256 of {@code secret}'s UTF-8 bytes. */
    public static String hash(String secret) {
        if (secret == null) throw new IllegalArgumentException("a secret is needed to hash");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform provides SHA-256", e);
        }
    }

    /**
     * Whether {@code value} is a UUID in its canonical form of 36 characters, in either case. Java's
     * {@link java.util.UUID#fromString} also accepts shortened forms such as {@code 1-1-1-1-1}; Consul does not.
     */
    public static boolean isUuid(String value) {
        return value != null && UUID.matcher(value).matches();
    }

    /** Whether {@code id} lies in the range kept for built-ins. */
    public static boolean isReserved(String id) {
        return id != null && id.toLowerCase(Locale.ROOT).startsWith(RESERVED_ID_PREFIX);
    }

    /** {@code id} in lower case, refused unless it is a UUID: Consul indexes an ID as a UUID's bytes. */
    public static String canonicalId(String id, String what) {
        if (!isUuid(id)) throw new IllegalArgumentException(what + " must be a UUID: " + id);
        return id.toLowerCase(Locale.ROOT);
    }

    /** {@code hash}, refused unless it is a lower-case hex SHA-256, so that no token can hold a secret itself. */
    static String secretHash(String hash) {
        if (hash == null || !SHA_256_HEX.matcher(hash).matches()) {
            throw new IllegalArgumentException("a token holds the lower-case hex SHA-256 of its secret, not the secret");
        }
        return hash;
    }
}

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

import dev.mars.qraft.common.acl.AclRules;
import dev.mars.qraft.common.acl.NodeIdentity;
import dev.mars.qraft.common.acl.ServiceIdentity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the token, policy and role values of the ACL list's proposals 2, 3 and 8: a secret is held only as its
 * SHA-256 hash; IDs are UUIDs; names follow Consul's rules; links and identities are held in one order; times are
 * kept in milliseconds; an expiry lies between one minute and 24 hours after the token's creation; and the built-in
 * policies and anonymous token carry Consul's IDs.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
class AclValuesTest {
    private static final String ACCESSOR = "6ba7b810-9dad-41d1-80b4-00c04fd430c8";
    private static final String SECRET = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    private static final String SECRET_HASH = "16362f566387b3cf5a6e92fb0a986c76ca20eb3a0c12cbdfbd0b29501e0c18df";
    private static final String POLICY_A = "1b4e28ba-2fa1-4d2a-883f-0016d3cca427";
    private static final String POLICY_B = "9c5b94b1-35ad-49bb-b118-8e8fc24abf80";
    private static final Instant CREATED = Instant.parse("2026-11-04T09:00:00.123456Z");

    @Test
    void aSecretIsHeldAsTheLowerCaseHexOfItsSha256() {
        assertEquals(SECRET_HASH, AclSecrets.hash(SECRET));
        assertNotEquals(AclSecrets.hash(SECRET), AclSecrets.hash(SECRET.toUpperCase()),
                "a secret is compared as written, as Consul's indexFromStringCaseSensitive compares it");
        assertEquals("2f183a4e64493af3f377f745eda502363cd3e7ef6e4d266d444758de0a85fcc8",
                AclSecrets.hash(AclBuiltins.ANONYMOUS_SECRET), "the anonymous secret is Consul's word, not a UUID");

        assertTrue(AclSecrets.isUuid(SECRET));
        assertTrue(AclSecrets.isUuid(SECRET.toUpperCase()));
        assertFalse(AclSecrets.isUuid("1-1-1-1-1"), "Java's UUID parser accepts this; a secret must be canonical");
        assertFalse(AclSecrets.isUuid(SECRET + "0"));
        assertFalse(AclSecrets.isUuid(null));
        assertTrue(AclSecrets.isReserved("00000000-0000-0000-0000-000000000099"),
                "Consul reserves IDs whose first 120 bits are zero for its built-ins");
        assertFalse(AclSecrets.isReserved(ACCESSOR));
    }

    @Test
    void aTokenHoldsItsLinksAndIdentitiesInOneOrderAndItsTimesInMilliseconds() {
        AclToken token = new AclToken(ACCESSOR.toUpperCase(), SECRET_HASH, null, List.of(POLICY_B, POLICY_A, POLICY_B),
                null, List.of(new NodeIdentity("web-2"), new NodeIdentity("web-1")),
                List.of(new ServiceIdentity("web", null, null), new ServiceIdentity("api", null, null)),
                CREATED, CREATED.plus(Duration.ofHours(1)), false);

        assertEquals(ACCESSOR, token.accessorId(),
                "an accessor ID is held in lower case: Consul indexes it as a UUID's bytes (indexFromUUIDString)");
        assertEquals("", token.description());
        assertEquals(List.of(POLICY_A, POLICY_B), token.policyIds());
        assertEquals(List.of(), token.roleIds());
        assertEquals(List.of(new NodeIdentity("web-1"), new NodeIdentity("web-2")), token.nodeIdentities());
        assertEquals(List.of("api", "web"), token.serviceIdentities().stream().map(ServiceIdentity::serviceName).toList());
        assertEquals(Instant.parse("2026-11-04T09:00:00.123Z"), token.createTime());
        assertEquals(Instant.parse("2026-11-04T10:00:00.123Z"), token.expirationTime());
        assertThrows(UnsupportedOperationException.class, () -> token.policyIds().add(POLICY_A));
    }

    @Test
    void aTokenNeedsAUuidAccessorAHashACreationTimeAndAnExpiryWithinConsulsBounds() {
        assertThrows(IllegalArgumentException.class, () -> token("not-a-uuid", SECRET_HASH, CREATED, null));
        assertThrows(IllegalArgumentException.class, () -> token(ACCESSOR, SECRET, CREATED, null),
                "a token holds the hash of its secret, never the secret");
        assertThrows(IllegalArgumentException.class, () -> token(ACCESSOR, SECRET_HASH.toUpperCase(), CREATED, null));
        assertThrows(NullPointerException.class, () -> token(ACCESSOR, SECRET_HASH, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> token(ACCESSOR, SECRET_HASH, CREATED, CREATED.plusSeconds(59)));
        assertThrows(IllegalArgumentException.class,
                () -> token(ACCESSOR, SECRET_HASH, CREATED, CREATED.plus(Duration.ofHours(24)).plusMillis(1)));

        assertEquals(Duration.ofMinutes(1), Duration.between(
                token(ACCESSOR, SECRET_HASH, CREATED, CREATED.plusSeconds(60)).createTime(),
                token(ACCESSOR, SECRET_HASH, CREATED, CREATED.plusSeconds(60)).expirationTime()));
        assertNull(token(ACCESSOR, SECRET_HASH, CREATED, null).expirationTime());
        assertThrows(IllegalArgumentException.class, () -> new AclToken(ACCESSOR, SECRET_HASH, "", List.of("p"),
                List.of(), List.of(), List.of(), CREATED, null, false), "a link names a policy by its UUID");
    }

    @Test
    void aPolicyNameFollowsConsulsRule() {
        for (String name : List.of("billing-services", "team_a", "acme/billing", "A1")) {
            assertEquals(name, policy(POLICY_A, name).name());
        }
        for (String name : List.of("", "a b", "/billing", "a/b/c", "builtin/mine", "billing!", "p".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> policy(POLICY_A, name), name);
        }
        assertEquals(AclRules.EMPTY, new AclPolicy(POLICY_A, "empty", null, null).rules());
        assertEquals("", new AclPolicy(POLICY_A, "empty", null, null).description());
        assertThrows(IllegalArgumentException.class, () -> policy("policy-a", "billing"));
    }

    @Test
    void aBuiltInPolicyMayKeepItsReservedName() {
        assertEquals("builtin/global-read-only",
                policy(AclBuiltins.GLOBAL_READ_ONLY_ID, "builtin/global-read-only").name());
        assertThrows(IllegalArgumentException.class, () -> policy(POLICY_A, "builtin/global-read-only"));
        assertThrows(IllegalArgumentException.class,
                () -> policy(AclBuiltins.GLOBAL_MANAGEMENT_ID, "builtin/global-read-only"),
                "only its own reserved name");
    }

    @Test
    void aRoleNameFollowsConsulsRuleAndItsLinksAreHeldInOneOrder() {
        AclRole role = new AclRole(POLICY_A, "billing-team", null, List.of(POLICY_B, POLICY_A), null,
                List.of(new ServiceIdentity("b", null, null), new ServiceIdentity("a", null, null)));

        assertEquals(List.of(POLICY_A, POLICY_B), role.policyIds());
        assertEquals(List.of(), role.nodeIdentities());
        assertEquals(List.of("a", "b"), role.serviceIdentities().stream().map(ServiceIdentity::serviceName).toList());
        for (String name : List.of("", "a/b", "a b", "r".repeat(257))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AclRole(POLICY_A, name, null, null, null, null), name);
        }
        assertEquals("r".repeat(256), new AclRole(POLICY_A, "r".repeat(256), null, null, null, null).name());
    }

    @Test
    void theBuiltInsCarryConsulsIdsAndRules() {
        assertEquals("00000000-0000-0000-0000-000000000001", AclBuiltins.GLOBAL_MANAGEMENT_ID);
        assertEquals("00000000-0000-0000-0000-000000000002", AclBuiltins.GLOBAL_READ_ONLY_ID);
        assertEquals("00000000-0000-0000-0000-000000000002", AclBuiltins.ANONYMOUS_TOKEN_ID);
        assertEquals(List.of(AclBuiltins.GLOBAL_MANAGEMENT_ID, AclBuiltins.GLOBAL_READ_ONLY_ID),
                AclBuiltins.policies().stream().map(AclPolicy::id).toList());
        assertEquals(List.of("global-management", "builtin/global-read-only"),
                AclBuiltins.policies().stream().map(AclPolicy::name).toList());
        assertEquals(List.of(AclRules.GLOBAL_MANAGEMENT, AclRules.GLOBAL_READ_ONLY),
                AclBuiltins.policies().stream().map(AclPolicy::rules).toList());

        AclToken anonymous = AclBuiltins.anonymousToken();
        assertEquals(AclBuiltins.ANONYMOUS_TOKEN_ID, anonymous.accessorId());
        assertEquals(AclSecrets.hash("anonymous"), anonymous.secretHash());
        assertEquals("Anonymous Token", anonymous.description());
        assertEquals(List.of(), anonymous.policyIds(), "the anonymous token grants nothing until policies are attached");
        assertEquals(Instant.EPOCH, anonymous.createTime(), "the same on every server, so no server's clock");
        assertNull(anonymous.expirationTime());
        assertTrue(AclBuiltins.isBuiltInPolicy(AclBuiltins.GLOBAL_READ_ONLY_ID));
        assertFalse(AclBuiltins.isBuiltInPolicy(POLICY_A));
    }

    private static AclToken token(String accessor, String secretHash, Instant created, Instant expires) {
        return new AclToken(accessor, secretHash, "", List.of(), List.of(), List.of(), List.of(), created, expires,
                false);
    }

    private static AclPolicy policy(String id, String name) {
        return new AclPolicy(id, name, "", AclRules.EMPTY);
    }
}

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

package dev.mars.qraft.state;

import dev.mars.qraft.common.acl.AclAccess;
import dev.mars.qraft.common.acl.AclResource;
import dev.mars.qraft.common.acl.AclRule;
import dev.mars.qraft.common.acl.AclRules;
import dev.mars.qraft.common.acl.AclScope;
import dev.mars.qraft.common.acl.NodeIdentity;
import dev.mars.qraft.common.acl.ServiceIdentity;
import dev.mars.qraft.raft.grpc.AclAccessProto;
import dev.mars.qraft.raft.grpc.AclCommandProto;
import dev.mars.qraft.raft.grpc.AclPolicyProto;
import dev.mars.qraft.raft.grpc.AclResourceProto;
import dev.mars.qraft.raft.grpc.AclRuleProto;
import dev.mars.qraft.raft.grpc.RaftCommandMessage;
import dev.mars.qraft.state.acl.AclPolicy;
import dev.mars.qraft.state.acl.AclRole;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the encoding of ACL commands in the Raft log: every command round-trips with its values, a token's expiry and
 * an empty description stay distinct from their absence, an entry holds the hash of a secret and never the secret, the
 * field numbers that are now part of the WAL format do not change, and an incomplete or unknown entry is refused
 * rather than read as something else.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
class AclCommandCodecTest {
    private static final Instant CREATED = Instant.parse("2026-11-04T09:00:00.250Z");
    private static final String SECRET = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    private static final String ACCESSOR = "6ba7b810-9dad-41d1-80b4-00c04fd430c8";
    private static final String POLICY = "1b4e28ba-2fa1-4d2a-883f-0016d3cca427";
    private static final String ROLE = "9c5b94b1-35ad-49bb-b118-8e8fc24abf80";

    private final ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();

    @Test
    void everyAclCommandRoundTripsWithItsValues() {
        AclToken expiring = new AclToken(ACCESSOR, AclSecrets.hash(SECRET), "billing deploys", List.of(POLICY),
                List.of(ROLE), List.of(new NodeIdentity("web-1"), new NodeIdentity("Web.2/a")),
                List.of(new ServiceIdentity("billing", "acme", "prod"), new ServiceIdentity("api", null, null)),
                CREATED, CREATED.plus(Duration.ofHours(8)), true);
        AclToken lasting = new AclToken(ACCESSOR, AclSecrets.hash(SECRET), "", List.of(), List.of(), List.of(),
                List.of(), CREATED, null, false);
        AclPolicy policy = new AclPolicy(POLICY, "acme/billing", "", rules());
        AclRole role = new AclRole(ROLE, "billing-team", "the billing team", List.of(POLICY),
                List.of(new NodeIdentity("web-1")), List.of(new ServiceIdentity("billing", "acme", "prod")));

        for (AclCommand command : List.of(
                AclCommand.createToken(expiring), AclCommand.createToken(lasting), AclCommand.updateToken(expiring),
                AclCommand.deleteToken(ACCESSOR), AclCommand.createPolicy(policy), AclCommand.updatePolicy(
                        new AclPolicy(POLICY, "empty", "none", AclRules.EMPTY)), AclCommand.deletePolicy(POLICY),
                AclCommand.createRole(role), AclCommand.updateRole(new AclRole(ROLE, "r", null, null, null, null)),
                AclCommand.deleteRole(ROLE), AclCommand.bootstrap(lasting, 0), AclCommand.bootstrap(expiring, 7),
                AclCommand.deleteExpiredTokens(CREATED, List.of(ACCESSOR, POLICY)),
                AclCommand.deleteExpiredTokens(CREATED, List.of()))) {
            assertEquals(command, codec.deserialize(codec.serialize(command)), command.toString());
        }
        AclToken decoded = ((AclCommand.CreateToken) codec.deserialize(codec.serialize(
                AclCommand.createToken(lasting)))).token();
        assertNull(decoded.expirationTime(), "a token without an expiry decodes without one");
        assertEquals(CREATED, decoded.createTime());
    }

    @Test
    void anEntryHoldsTheHashOfASecretAndNeverTheSecret() {
        AclToken token = new AclToken(ACCESSOR, AclSecrets.hash(SECRET), "", List.of(), List.of(), List.of(),
                List.of(), CREATED, null, false);

        for (AclCommand command : List.of(AclCommand.createToken(token), AclCommand.bootstrap(token, 0))) {
            String entry = new String(codec.serialize(command), StandardCharsets.ISO_8859_1);
            assertFalse(entry.contains(SECRET), "the entry holds the secret");
            assertTrue(entry.contains(AclSecrets.hash(SECRET)), "the entry holds the secret's hash");
        }
    }

    @Test
    void theFieldNumbersOfTheWalFormatDoNotChange() {
        // These numbers are written to every WAL holding an ACL command; renumbering them would make those
        // entries decode as other commands.
        assertEquals(5, RaftCommandMessage.ACL_COMMAND_FIELD_NUMBER);
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), List.of(
                AclCommandProto.CREATE_TOKEN_FIELD_NUMBER, AclCommandProto.UPDATE_TOKEN_FIELD_NUMBER,
                AclCommandProto.DELETE_TOKEN_FIELD_NUMBER, AclCommandProto.CREATE_POLICY_FIELD_NUMBER,
                AclCommandProto.UPDATE_POLICY_FIELD_NUMBER, AclCommandProto.DELETE_POLICY_FIELD_NUMBER,
                AclCommandProto.CREATE_ROLE_FIELD_NUMBER, AclCommandProto.UPDATE_ROLE_FIELD_NUMBER,
                AclCommandProto.DELETE_ROLE_FIELD_NUMBER, AclCommandProto.BOOTSTRAP_FIELD_NUMBER,
                AclCommandProto.DELETE_EXPIRED_TOKENS_FIELD_NUMBER));
        assertEquals(List.of(1, 2, 3, 4, 5), List.of(AclResourceProto.ACL_RESOURCE_NODE_VALUE,
                AclResourceProto.ACL_RESOURCE_SERVICE_VALUE, AclResourceProto.ACL_RESOURCE_CLIENT_VALUE,
                AclResourceProto.ACL_RESOURCE_OPERATOR_VALUE, AclResourceProto.ACL_RESOURCE_ACL_VALUE));
        assertEquals(List.of(1, 2, 3), List.of(AclAccessProto.ACL_ACCESS_READ_VALUE,
                AclAccessProto.ACL_ACCESS_WRITE_VALUE, AclAccessProto.ACL_ACCESS_DENY_VALUE));
    }

    @Test
    void anIncompleteOrUnknownEntryIsRefused() {
        byte[] empty = RaftCommandMessage.newBuilder().setAclCommand(AclCommandProto.getDefaultInstance())
                .build().toByteArray();
        byte[] tokenless = RaftCommandMessage.newBuilder().setAclCommand(AclCommandProto.newBuilder()
                .setDeleteToken("")).build().toByteArray();
        AclPolicyProto unknownResource = AclPolicyProto.newBuilder().setId(POLICY).setName("p")
                .addRules(AclRuleProto.newBuilder().setResourceValue(99).setAccess(AclAccessProto.ACL_ACCESS_READ)
                        .setPrefix(true).setName(""))
                .build();
        AclPolicyProto unspecifiedAccess = AclPolicyProto.newBuilder().setId(POLICY).setName("p")
                .addRules(AclRuleProto.newBuilder().setResource(AclResourceProto.ACL_RESOURCE_OPERATOR)).build();

        assertThrows(IllegalArgumentException.class, () -> codec.deserialize(empty), "no command is set");
        assertThrows(IllegalArgumentException.class, () -> codec.deserialize(tokenless));
        for (AclPolicyProto policy : List.of(unknownResource, unspecifiedAccess)) {
            byte[] entry = RaftCommandMessage.newBuilder().setAclCommand(AclCommandProto.newBuilder()
                    .setCreatePolicy(policy)).build().toByteArray();
            assertThrows(IllegalArgumentException.class, () -> codec.deserialize(entry), policy.toString());
        }
    }

    private static AclRules rules() {
        return new AclRules(List.of(
                AclRule.exact(AclResource.SERVICE, "billing", AclAccess.WRITE)
                        .in(new AclScope("acme", false), new AclScope("prod", false)),
                AclRule.prefix(AclResource.SERVICE, "", AclAccess.READ).in(new AclScope("acme", false), AclScope.ANY),
                AclRule.prefix(AclResource.NODE, "", AclAccess.READ),
                AclRule.exact(AclResource.NODE, "web-1", AclAccess.DENY),
                AclRule.exact(AclResource.CLIENT, "web-1", AclAccess.WRITE),
                AclRule.single(AclResource.OPERATOR, AclAccess.READ),
                AclRule.single(AclResource.ACL, AclAccess.DENY)));
    }
}

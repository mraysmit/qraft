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
import dev.mars.qraft.common.acl.NodeIdentity;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.state.acl.AclBuiltins;
import dev.mars.qraft.state.acl.AclPolicy;
import dev.mars.qraft.state.acl.AclRefusal;
import dev.mars.qraft.state.acl.AclRole;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the replicated ACL state of the ACL list's Phase 1, applied through the state store: the built-in policies
 * and anonymous token in the initial state; a token found by its secret's hash; the uniqueness of accessors, secrets,
 * IDs and names; links that must exist when written; a secret and an expiry that cannot change; built-ins that
 * cannot be deleted or have their rules changed; the deletion of a linked policy or role, whose links are dropped
 * when read (proposal 18); the lock-out guard (proposal 17); the one-time bootstrap and its reset; the deletion of
 * expired tokens; the snapshot; and a read-only view.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
class AclStateStoreTest {
    private static final Instant CREATED = Instant.parse("2026-11-04T09:00:00Z");
    private static final String ALICE = "6ba7b810-9dad-41d1-80b4-00c04fd430c8";
    private static final String BOB = "6ba7b811-9dad-41d1-80b4-00c04fd430c8";
    private static final String CAROL = "6ba7b812-9dad-41d1-80b4-00c04fd430c8";
    private static final String ALICE_SECRET = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    private static final String BOB_SECRET = "3f2504e1-4f89-41d3-9a0c-0305e82c3301";
    private static final String CAROL_SECRET = "3f2504e2-4f89-41d3-9a0c-0305e82c3301";
    private static final String OPERATORS = "1b4e28ba-2fa1-4d2a-883f-0016d3cca427";
    private static final String READERS = "9c5b94b1-35ad-49bb-b118-8e8fc24abf80";
    private static final String ADMINS = "c56a4180-65aa-42ec-a945-5fd21dec0538";
    private static final String MANAGEMENT = AclBuiltins.GLOBAL_MANAGEMENT_ID;

    private final QraftStateStore store = new QraftStateStore();

    @Test
    void theInitialStateHoldsTheBuiltInPoliciesAndTheAnonymousTokenOnly() {
        assertEquals(AclBuiltins.policies(), store.getAcl().policies());
        assertEquals(List.of(AclBuiltins.anonymousToken()), store.getAcl().tokens());
        assertEquals(List.of(), store.getAcl().roles());
        assertEquals(0, store.getAcl().bootstrapIndex());
        assertEquals(Optional.of(AclBuiltins.anonymousToken()), store.getAcl().tokenBySecret("anonymous"));
    }

    @Test
    void aTokenIsFoundByItsSecretWhichItHoldsOnlyAsAHash() {
        AclToken alice = token(ALICE, ALICE_SECRET, MANAGEMENT);

        assertSuccess(store.apply(AclCommand.createToken(alice)));

        assertEquals(Optional.of(alice), store.getAcl().token(ALICE));
        assertEquals(Optional.of(alice), store.getAcl().tokenBySecret(ALICE_SECRET));
        assertEquals(Optional.empty(), store.getAcl().tokenBySecret(AclSecrets.hash(ALICE_SECRET)),
                "the hash is not a secret: presenting it finds nothing");
        assertEquals(Optional.empty(), store.getAcl().tokenBySecret(ALICE_SECRET.toUpperCase()));
        assertEquals(Optional.of(alice), store.getAcl().token(ALICE.toUpperCase()));
    }

    @Test
    void aCreatedTokenNeedsAFreeAccessorAFreeSecretAndLinksThatExist() {
        assertSuccess(store.apply(AclCommand.createToken(token(ALICE, ALICE_SECRET, MANAGEMENT))));

        assertRefused(AclRefusal.ACCESSOR_IN_USE, store.apply(AclCommand.createToken(token(ALICE, BOB_SECRET))));
        assertRefused(AclRefusal.SECRET_IN_USE, store.apply(AclCommand.createToken(token(BOB, ALICE_SECRET))));
        assertRefused(AclRefusal.SECRET_IN_USE,
                store.apply(AclCommand.createToken(token(BOB, AclBuiltins.ANONYMOUS_SECRET))));
        assertRefused(AclRefusal.RESERVED_ID,
                store.apply(AclCommand.createToken(token("00000000-0000-0000-0000-000000000077", BOB_SECRET))));
        assertRefused(AclRefusal.POLICY_NOT_FOUND, store.apply(AclCommand.createToken(token(BOB, BOB_SECRET, READERS))));
        assertRefused(AclRefusal.ROLE_NOT_FOUND, store.apply(AclCommand.createToken(withRoles(token(BOB, BOB_SECRET),
                ADMINS))));
        assertEquals(List.of(AclBuiltins.ANONYMOUS_TOKEN_ID, ALICE),
                store.getAcl().tokens().stream().map(AclToken::accessorId).toList(), "nothing refused was stored");
    }

    @Test
    void anUpdateCannotChangeASecretOrAnExpiryAndKeepsWhatTheServerOwns() {
        AclToken expiring = new AclToken(BOB, AclSecrets.hash(BOB_SECRET), "", List.of(), List.of(), List.of(),
                List.of(), CREATED, CREATED.plus(Duration.ofHours(1)), true);
        assertSuccess(store.apply(AclCommand.createToken(expiring)));
        assertSuccess(store.apply(AclCommand.createPolicy(policy(READERS, "readers", AclRules.GLOBAL_READ_ONLY))));

        assertRefused(AclRefusal.SECRET_IMMUTABLE, store.apply(AclCommand.updateToken(new AclToken(BOB,
                AclSecrets.hash(CAROL_SECRET), "", List.of(), List.of(), List.of(), List.of(), CREATED,
                CREATED.plus(Duration.ofHours(1)), true))));
        for (Instant expiry : new Instant[] {null, CREATED.plus(Duration.ofHours(2))}) {
            assertRefused(AclRefusal.EXPIRY_IMMUTABLE, store.apply(AclCommand.updateToken(new AclToken(BOB,
                    AclSecrets.hash(BOB_SECRET), "", List.of(), List.of(), List.of(), List.of(), CREATED, expiry,
                    true))));
        }
        AclToken changed = new AclToken(BOB, AclSecrets.hash(BOB_SECRET), "reader", List.of(READERS), List.of(),
                List.of(new NodeIdentity("web-1")), List.of(), CREATED.plusSeconds(30), CREATED.plus(Duration.ofHours(1)),
                false);

        assertSuccess(store.apply(AclCommand.updateToken(changed)));

        AclToken stored = store.getAcl().token(BOB).orElseThrow();
        assertEquals("reader", stored.description());
        assertEquals(List.of(READERS), stored.policyIds());
        assertEquals(List.of(new NodeIdentity("web-1")), stored.nodeIdentities());
        assertEquals(CREATED, stored.createTime(), "the creation time is the server's");
        assertEquals(true, stored.installedFromFile(), "and so is the mark of a token installed from the file");
        assertInstanceOf(RaftCommandResult.NotFound.class, store.apply(AclCommand.updateToken(token(CAROL, CAROL_SECRET))));
    }

    @Test
    void theAnonymousTokenCanBeGivenPoliciesButNotDeleted() {
        assertSuccess(store.apply(AclCommand.createPolicy(policy(READERS, "readers", AclRules.GLOBAL_READ_ONLY))));
        AclToken anonymous = AclBuiltins.anonymousToken();

        assertSuccess(store.apply(AclCommand.updateToken(new AclToken(anonymous.accessorId(), anonymous.secretHash(),
                anonymous.description(), List.of(READERS), List.of(), List.of(), List.of(), anonymous.createTime(),
                null, false))));

        assertEquals(List.of(READERS), store.getAcl().token(AclBuiltins.ANONYMOUS_TOKEN_ID).orElseThrow().policyIds());
        assertRefused(AclRefusal.BUILT_IN, store.apply(AclCommand.deleteToken(AclBuiltins.ANONYMOUS_TOKEN_ID)));
        assertInstanceOf(RaftCommandResult.NotFound.class, store.apply(AclCommand.deleteToken(CAROL)));
    }

    @Test
    void policyAndRoleNamesAreUniqueRegardlessOfCaseAndTheirIdsAreUniqueAndNotReserved() {
        assertSuccess(store.apply(AclCommand.createPolicy(policy(READERS, "Readers", AclRules.GLOBAL_READ_ONLY))));
        assertSuccess(store.apply(AclCommand.createPolicy(policy(OPERATORS, "operators", AclRules.EMPTY))));

        assertRefused(AclRefusal.NAME_IN_USE, store.apply(AclCommand.createPolicy(policy(ADMINS, "readers", AclRules.EMPTY))));
        assertRefused(AclRefusal.NAME_IN_USE,
                store.apply(AclCommand.createPolicy(policy(ADMINS, "GLOBAL-management", AclRules.EMPTY))));
        assertRefused(AclRefusal.ID_IN_USE, store.apply(AclCommand.createPolicy(policy(READERS, "other", AclRules.EMPTY))));
        assertRefused(AclRefusal.RESERVED_ID, store.apply(AclCommand.createPolicy(
                policy("00000000-0000-0000-0000-000000000003", "other", AclRules.EMPTY))));
        assertRefused(AclRefusal.NAME_IN_USE,
                store.apply(AclCommand.updatePolicy(policy(OPERATORS, "READERS", AclRules.EMPTY))));
        assertSuccess(store.apply(AclCommand.updatePolicy(policy(READERS, "readers", AclRules.GLOBAL_READ_ONLY))));
        assertEquals("readers", store.getAcl().policy(READERS).orElseThrow().name(), "a policy may change its own case");
        assertEquals(Optional.of(READERS), store.getAcl().policyByName("READERS").map(AclPolicy::id));
        assertInstanceOf(RaftCommandResult.NotFound.class,
                store.apply(AclCommand.updatePolicy(policy(ADMINS, "admins", AclRules.EMPTY))));

        assertSuccess(store.apply(AclCommand.createRole(role(ADMINS, "admins", READERS))));
        assertRefused(AclRefusal.NAME_IN_USE, store.apply(AclCommand.createRole(role(OPERATORS, "ADMINS"))));
        assertRefused(AclRefusal.ID_IN_USE, store.apply(AclCommand.createRole(role(ADMINS, "other"))));
        assertRefused(AclRefusal.POLICY_NOT_FOUND, store.apply(AclCommand.createRole(role(OPERATORS, "ops", CAROL))));
        assertRefused(AclRefusal.RESERVED_ID,
                store.apply(AclCommand.createRole(role("00000000-0000-0000-0000-000000000004", "ops"))));
        assertEquals(Optional.of(ADMINS), store.getAcl().roleByName("Admins").map(AclRole::id));
    }

    @Test
    void aBuiltInPolicyCanBeRenamedAndDescribedButKeepsItsRulesAndCannotBeDeleted() {
        AclPolicy renamed = new AclPolicy(MANAGEMENT, "superuser", "everything", AclRules.GLOBAL_MANAGEMENT);

        assertSuccess(store.apply(AclCommand.updatePolicy(renamed)));

        assertEquals(Optional.of(renamed), store.getAcl().policy(MANAGEMENT));
        assertRefused(AclRefusal.BUILT_IN, store.apply(AclCommand.updatePolicy(
                new AclPolicy(MANAGEMENT, "superuser", "everything", AclRules.GLOBAL_READ_ONLY))));
        assertRefused(AclRefusal.BUILT_IN, store.apply(AclCommand.deletePolicy(MANAGEMENT)));
        assertRefused(AclRefusal.BUILT_IN, store.apply(AclCommand.deletePolicy(AclBuiltins.GLOBAL_READ_ONLY_ID)));
        assertEquals(2, store.getAcl().policies().size());
    }

    @Test
    void aDeletedPolicyOrRoleThatTokensLinkToIsDroppedFromThemWhenTheyAreRead() {
        assertSuccess(store.apply(AclCommand.createPolicy(policy(READERS, "readers", AclRules.GLOBAL_READ_ONLY))));
        assertSuccess(store.apply(AclCommand.createRole(role(ADMINS, "admins", READERS))));
        assertSuccess(store.apply(AclCommand.createToken(withRoles(token(BOB, BOB_SECRET, READERS), ADMINS))));

        assertSuccess(store.apply(AclCommand.deletePolicy(READERS)));

        assertEquals(List.of(), store.getAcl().token(BOB).orElseThrow().policyIds());
        assertEquals(List.of(ADMINS), store.getAcl().token(BOB).orElseThrow().roleIds());
        assertEquals(List.of(), store.getAcl().role(ADMINS).orElseThrow().policyIds());

        assertSuccess(store.apply(AclCommand.deleteRole(ADMINS)));

        assertEquals(List.of(), store.getAcl().tokenBySecret(BOB_SECRET).orElseThrow().roleIds());
        assertEquals(List.of(), store.getAcl().tokens().get(1).roleIds());
    }

    @Test
    void aDeletedPolicyNoLongerGrantsAndARefusedDeletionLeavesTheLinksInPlace() {
        AclRules aclWrite = new AclRules(List.of(AclRule.single(AclResource.ACL, AclAccess.WRITE)));
        assertSuccess(store.apply(AclCommand.createPolicy(policy(OPERATORS, "operators", aclWrite))));
        assertSuccess(store.apply(AclCommand.createToken(token(ALICE, ALICE_SECRET, OPERATORS))));
        assertSuccess(store.apply(AclCommand.createToken(token(BOB, BOB_SECRET, MANAGEMENT))));

        // Bob still holds acl:write, so the policy that gives it to Alice can go, and with it what it granted her.
        assertSuccess(store.apply(AclCommand.deletePolicy(OPERATORS)));
        assertRefused(AclRefusal.ACL_LOCKOUT, store.apply(AclCommand.deleteToken(BOB)),
                "Alice no longer holds acl:write, so Bob is the last holder");

        assertSuccess(store.apply(AclCommand.createPolicy(policy(READERS, "acl-writers", aclWrite))));
        assertSuccess(store.apply(AclCommand.updateToken(token(BOB, BOB_SECRET, READERS))));
        assertRefused(AclRefusal.ACL_LOCKOUT, store.apply(AclCommand.deletePolicy(READERS)));
        assertEquals(List.of(READERS), store.getAcl().token(BOB).orElseThrow().policyIds(),
                "a refused deletion leaves the policy and the link to it");
    }

    @Test
    void noCommandMayRemoveTheLastTokenHoldingAclWriteWithoutAnExpiry() {
        AclRules aclWrite = new AclRules(List.of(AclRule.single(AclResource.ACL, AclAccess.WRITE)));
        AclRules aclDeny = new AclRules(List.of(AclRule.single(AclResource.ACL, AclAccess.DENY)));
        assertSuccess(store.apply(AclCommand.createPolicy(policy(OPERATORS, "operators", aclWrite))));
        assertSuccess(store.apply(AclCommand.createPolicy(policy(READERS, "deniers", aclDeny))));
        assertSuccess(store.apply(AclCommand.createRole(role(ADMINS, "admins", OPERATORS))));
        assertSuccess(store.apply(AclCommand.createToken(withRoles(token(ALICE, ALICE_SECRET), ADMINS))));
        AclToken expiring = new AclToken(BOB, AclSecrets.hash(BOB_SECRET), "", List.of(MANAGEMENT), List.of(),
                List.of(), List.of(), CREATED, CREATED.plus(Duration.ofHours(1)), false);
        assertSuccess(store.apply(AclCommand.createToken(expiring)));

        // Alice holds acl:write through her role; Bob's expires, so he does not count.
        assertRefused(AclRefusal.ACL_LOCKOUT, store.apply(AclCommand.deleteToken(ALICE)));
        assertRefused(AclRefusal.ACL_LOCKOUT, store.apply(AclCommand.updateToken(token(ALICE, ALICE_SECRET))));
        assertRefused(AclRefusal.ACL_LOCKOUT, store.apply(AclCommand.updateToken(
                withRoles(token(ALICE, ALICE_SECRET, READERS), ADMINS))), "deny wins at the same specificity");
        assertRefused(AclRefusal.ACL_LOCKOUT, store.apply(AclCommand.deleteRole(ADMINS)));
        assertRefused(AclRefusal.ACL_LOCKOUT, store.apply(AclCommand.updateRole(role(ADMINS, "admins"))));
        assertRefused(AclRefusal.ACL_LOCKOUT, store.apply(AclCommand.deletePolicy(OPERATORS)));
        assertRefused(AclRefusal.ACL_LOCKOUT, store.apply(AclCommand.updatePolicy(policy(OPERATORS, "operators",
                new AclRules(List.of(AclRule.single(AclResource.ACL, AclAccess.READ)))))));

        assertSuccess(store.apply(AclCommand.createToken(token(CAROL, CAROL_SECRET, MANAGEMENT))));
        assertSuccess(store.apply(AclCommand.deleteToken(ALICE)), "Carol holds it now");
        assertSuccess(store.apply(AclCommand.deleteToken(BOB)), "an expiring token never counts");
        assertRefused(AclRefusal.ACL_LOCKOUT, store.apply(AclCommand.deleteToken(CAROL)));
    }

    @Test
    void untilATokenHoldsAclWriteNothingIsALockOut() {
        AclRules aclRead = new AclRules(List.of(AclRule.single(AclResource.ACL, AclAccess.READ)));

        assertSuccess(store.apply(AclCommand.createPolicy(policy(READERS, "readers", aclRead))));
        assertSuccess(store.apply(AclCommand.createToken(token(ALICE, ALICE_SECRET, READERS))));
        assertSuccess(store.apply(AclCommand.deleteToken(ALICE)));
        assertSuccess(store.apply(AclCommand.deletePolicy(READERS)));
    }

    @Test
    void theBootstrapWorksOnceUntilItIsResetWithItsIndex() {
        AclToken first = token(ALICE, ALICE_SECRET, MANAGEMENT);

        assertSuccess(store.apply(AclCommand.bootstrap(first, 0)));
        assertEquals(1, store.getAcl().bootstrapIndex());
        assertRefused(AclRefusal.ACL_BOOTSTRAPPED, store.apply(AclCommand.bootstrap(token(BOB, BOB_SECRET, MANAGEMENT), 0)));
        assertRefused(AclRefusal.INVALID_RESET_INDEX,
                store.apply(AclCommand.bootstrap(token(BOB, BOB_SECRET, MANAGEMENT), 2)));
        assertRefused(AclRefusal.SECRET_IN_USE,
                store.apply(AclCommand.bootstrap(token(BOB, ALICE_SECRET, MANAGEMENT), 1)));
        assertEquals(1, store.getAcl().bootstrapIndex(), "a refused bootstrap does not use up the reset");

        assertSuccess(store.apply(AclCommand.bootstrap(token(BOB, BOB_SECRET, MANAGEMENT), 1)));

        assertEquals(2, store.getAcl().bootstrapIndex());
        assertEquals(List.of(AclBuiltins.ANONYMOUS_TOKEN_ID, ALICE, BOB),
                store.getAcl().tokens().stream().map(AclToken::accessorId).toList());
    }

    @Test
    void theDeletionOfExpiredTokensRemovesOnlyTheNamedTokensThatHaveExpired() {
        Instant deadline = CREATED.plus(Duration.ofHours(1));
        for (String[] spec : new String[][] {{ALICE, ALICE_SECRET}, {BOB, BOB_SECRET}}) {
            assertSuccess(store.apply(AclCommand.createToken(new AclToken(spec[0], AclSecrets.hash(spec[1]), "",
                    List.of(), List.of(), List.of(), List.of(), CREATED, deadline, false))));
        }
        assertSuccess(store.apply(AclCommand.createToken(token(CAROL, CAROL_SECRET))));

        RaftCommandResult<?> early = store.apply(AclCommand.deleteExpiredTokens(deadline.minusMillis(1),
                List.of(ALICE, BOB, CAROL)));
        RaftCommandResult<?> due = store.apply(AclCommand.deleteExpiredTokens(deadline, List.of(ALICE, CAROL)));

        assertEquals(new RaftCommandResult.Success<>(List.of()), early, "the replicated time decides, not a clock");
        assertEquals(new RaftCommandResult.Success<>(List.of(ALICE)), due, "a token without an expiry never expires");
        assertEquals(List.of(AclBuiltins.ANONYMOUS_TOKEN_ID, BOB, CAROL),
                store.getAcl().tokens().stream().map(AclToken::accessorId).toList());
    }

    @Test
    void theAclStateRoundTripsThroughTheSnapshotAndAnOlderSnapshotRestoresTheInitialState() {
        assertSuccess(store.apply(AclCommand.createPolicy(policy(READERS, "readers", AclRules.GLOBAL_READ_ONLY))));
        assertSuccess(store.apply(AclCommand.createRole(role(ADMINS, "admins", READERS))));
        assertSuccess(store.apply(AclCommand.bootstrap(withRoles(token(ALICE, ALICE_SECRET, MANAGEMENT), ADMINS), 0)));
        assertSuccess(store.apply(AclCommand.deletePolicy(READERS)));

        QraftStateStore restored = new QraftStateStore();
        restored.restoreSnapshot(store.takeSnapshot());

        assertEquals(store.getAcl().tokens(), restored.getAcl().tokens());
        assertEquals(store.getAcl().policies(), restored.getAcl().policies());
        assertEquals(store.getAcl().roles(), restored.getAcl().roles());
        assertEquals(1, restored.getAcl().bootstrapIndex());
        assertEquals(Optional.of(ALICE), restored.getAcl().tokenBySecret(ALICE_SECRET).map(AclToken::accessorId));
        String snapshot = new String(store.takeSnapshot(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(snapshot.contains(AclSecrets.hash(ALICE_SECRET)));
        assertEquals(false, snapshot.contains(ALICE_SECRET), "a snapshot holds no secret");

        restored.reset();
        assertEquals(List.of(AclBuiltins.anonymousToken()), restored.getAcl().tokens());
        assertEquals(0, restored.getAcl().bootstrapIndex());
        restored.restoreSnapshot("{\"nodes\":{},\"metadata\":{},\"lastAppliedIndex\":3}".getBytes());
        assertEquals(AclBuiltins.policies(), restored.getAcl().policies(), "a snapshot from before ACLs");
        assertEquals(List.of(AclBuiltins.anonymousToken()), restored.getAcl().tokens());
    }

    @Test
    void theAclStateIsReadOnlyThroughTheStore() {
        Object view = store.getAcl();

        assertEquals(false, view instanceof dev.mars.qraft.state.acl.AclState,
                "callers cannot reach the mutable ACL state, even by casting");
        java.util.Set<String> mutators = java.util.Set.of("createToken", "updateToken", "deleteToken", "createPolicy",
                "updatePolicy", "deletePolicy", "createRole", "updateRole", "deleteRole", "bootstrap",
                "deleteExpiredTokens", "restore", "reset");
        for (java.lang.reflect.Method method : view.getClass().getMethods()) {
            assertEquals(false, mutators.contains(method.getName()), "the view exposes " + method.getName());
        }
        assertThrows(UnsupportedOperationException.class, () -> store.getAcl().tokens().clear());
        assertThrows(UnsupportedOperationException.class, () -> store.getAcl().policies().clear());
        assertThrows(UnsupportedOperationException.class, () -> store.getAcl().roles().clear());
    }

    private static AclToken token(String accessor, String secret, String... policies) {
        return new AclToken(accessor, AclSecrets.hash(secret), "", List.of(policies), List.of(), List.of(), List.of(),
                CREATED, null, false);
    }

    private static AclToken withRoles(AclToken token, String... roles) {
        return new AclToken(token.accessorId(), token.secretHash(), token.description(), token.policyIds(),
                List.of(roles), token.nodeIdentities(), token.serviceIdentities(), token.createTime(),
                token.expirationTime(), token.installedFromFile());
    }

    private static AclPolicy policy(String id, String name, AclRules rules) {
        return new AclPolicy(id, name, "", rules);
    }

    private static AclRole role(String id, String name, String... policies) {
        return new AclRole(id, name, "", List.of(policies), List.of(), List.of());
    }

    private static void assertSuccess(RaftCommandResult<?> result) {
        assertInstanceOf(RaftCommandResult.Success.class, result, String.valueOf(result));
    }

    private static void assertSuccess(RaftCommandResult<?> result, String message) {
        assertInstanceOf(RaftCommandResult.Success.class, result, message + ": " + result);
    }

    private static void assertRefused(AclRefusal refusal, RaftCommandResult<?> result) {
        assertRefused(refusal, result, refusal.code());
    }

    private static void assertRefused(AclRefusal refusal, RaftCommandResult<?> result, String message) {
        RaftCommandResult.Refused<?> refused = assertInstanceOf(RaftCommandResult.Refused.class, result,
                message + ": " + result);
        assertEquals(refusal.code(), refused.code(), message);
    }
}

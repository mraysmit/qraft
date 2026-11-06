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

import dev.mars.qraft.common.acl.AclAccess;
import dev.mars.qraft.common.acl.AclDecision;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.acl.AclRequest;
import dev.mars.qraft.common.acl.AclResource;
import dev.mars.qraft.common.acl.AclRulesJson;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RemovalAuthorizer;
import dev.mars.qraft.state.AclCommand;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.acl.AclBuiltins;
import dev.mars.qraft.state.acl.AclPolicy;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how a server resolves a presented secret to the principal a request acts as (ACL list, Phase 4), and the
 * removal authorizer that asks it for {@code operator:write}: with ACLs disabled every request is unrestricted and a
 * secret is ignored; with them enabled, no secret is the anonymous token, an unknown secret resolves to nothing, and
 * a known one decides by its grants under the default policy, refused in Consul's words.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-06
 * @version 1.0
 */
class AclResolverTest {
    private static final String WEB_SECRET = "6a1f3c2e-8b4d-4e5f-9a6b-7c8d9e0f1a2b";
    private static final String WEB_ACCESSOR = "1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9";
    private static final String OPERATOR_SECRET = "7b2e4d3f-9c5e-4f60-8b7c-8d9e0f1a2b3c";
    private static final String UNKNOWN_SECRET = "8c3f5e40-ad6f-4071-9c8d-9e0f1a2b3c4d";
    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");

    private final QraftStateStore store = new QraftStateStore();

    @Test
    void withAclsDisabledEveryRequestIsUnrestrictedAndASecretIsIgnored() {
        AclResolver resolver = AclResolver.disabled();

        AclPrincipal principal = resolver.resolve(UNKNOWN_SECRET).orElseThrow();

        assertFalse(resolver.enabled());
        assertFalse(principal.enforced());
        assertTrue(principal.allows(AclRequest.single(AclResource.ACL, AclAccess.WRITE)));
        assertTrue(resolver.resolve(null).orElseThrow().allows(AclRequest.named(AclResource.NODE, "web-1",
                AclAccess.WRITE)));
    }

    @Test
    void withoutASecretARequestActsAsTheAnonymousToken() {
        AclResolver resolver = AclResolver.enabled(AclDefaultPolicy.DENY, store.getAcl());

        for (String none : new String[]{null, ""}) {
            AclPrincipal anonymous = resolver.resolve(none).orElseThrow();
            assertTrue(anonymous.enforced());
            assertEquals(AclBuiltins.ANONYMOUS_TOKEN_ID, anonymous.accessorId());
            assertFalse(anonymous.allows(AclRequest.named(AclResource.NODE, "web-1", AclAccess.READ)));
        }
    }

    @Test
    void aSecretThatNamesNoTokenResolvesToNothing() {
        AclResolver resolver = AclResolver.enabled(AclDefaultPolicy.ALLOW, store.getAcl());

        assertEquals(Optional.empty(), resolver.resolve(UNKNOWN_SECRET));
        assertEquals("ACL not found", AclResolver.TOKEN_NOT_FOUND_MESSAGE);
    }

    @Test
    void aKnownSecretDecidesByItsGrantsUnderTheDefaultPolicy() {
        createToken(WEB_ACCESSOR, WEB_SECRET, "{\"node\":{\"web-1\":{\"policy\":\"write\"}}}");

        AclPrincipal underDeny = AclResolver.enabled(AclDefaultPolicy.DENY, store.getAcl())
                .resolve(WEB_SECRET).orElseThrow();
        AclPrincipal underAllow = AclResolver.enabled(AclDefaultPolicy.ALLOW, store.getAcl())
                .resolve(WEB_SECRET).orElseThrow();

        assertEquals(WEB_ACCESSOR, underDeny.accessorId());
        AclRequest writeWeb = AclRequest.named(AclResource.NODE, "web-1", AclAccess.WRITE);
        assertInstanceOf(AclDecision.ByRule.class, underDeny.decide(writeWeb));
        assertTrue(underDeny.allows(writeWeb));
        assertFalse(underDeny.allows(AclRequest.named(AclResource.NODE, "db-1", AclAccess.WRITE)));
        assertTrue(underAllow.allows(AclRequest.named(AclResource.NODE, "db-1", AclAccess.WRITE)));
        assertFalse(underAllow.allows(AclRequest.single(AclResource.ACL, AclAccess.READ)),
                "the default policy never grants acl");
    }

    @Test
    void aRefusalNamesTheAccessorThePermissionAndWhatItIsOnInConsulsWords() {
        createToken(WEB_ACCESSOR, WEB_SECRET, "{\"node\":{\"web-1\":{\"policy\":\"read\"}}}");
        AclPrincipal principal = AclResolver.enabled(AclDefaultPolicy.DENY, store.getAcl())
                .resolve(WEB_SECRET).orElseThrow();

        assertEquals("Permission denied: token with AccessorID '" + WEB_ACCESSOR
                        + "' lacks permission 'node:write' on \"web-1\"",
                principal.permissionDenied(AclRequest.named(AclResource.NODE, "web-1", AclAccess.WRITE)));
        assertEquals("Permission denied: token with AccessorID '" + WEB_ACCESSOR
                        + "' lacks permission 'service:read' on \"api\" in tenant \"acme\" namespace \"prod\"",
                principal.permissionDenied(AclRequest.service("api", "acme", "prod", AclAccess.READ)));
        assertEquals("Permission denied: token with AccessorID '" + WEB_ACCESSOR
                        + "' lacks permission 'operator:write'",
                principal.permissionDenied(AclRequest.single(AclResource.OPERATOR, AclAccess.WRITE)));
    }

    @Test
    void aRemovalNeedsOperatorWriteAndAnUnknownSecretIsNotFound() {
        createToken(WEB_ACCESSOR, WEB_SECRET, "{\"operator\":\"read\"}");
        createToken("2c3d4e5f-6071-4829-93a4-b5c6d7e8f9a0", OPERATOR_SECRET, "{\"operator\":\"write\"}");
        RemovalAuthorizer removals = AclRemovalAuthorizer.of(AclResolver.enabled(AclDefaultPolicy.DENY,
                store.getAcl()));

        assertEquals(Optional.empty(), removals.refusal(OPERATOR_SECRET));
        assertEquals(Optional.of("Permission denied: token with AccessorID '" + WEB_ACCESSOR
                + "' lacks permission 'operator:write'"), removals.refusal(WEB_SECRET));
        assertEquals(Optional.of("ACL not found"), removals.refusal(UNKNOWN_SECRET));
        assertEquals(Optional.of("Permission denied: token with AccessorID '" + AclBuiltins.ANONYMOUS_TOKEN_ID
                + "' lacks permission 'operator:write'"), removals.refusal(""));
    }

    private void createToken(String accessor, String secret, String rules) {
        String policyId = java.util.UUID.nameUUIDFromBytes(accessor.getBytes()).toString();
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(AclCommand.createPolicy(
                new AclPolicy(policyId, "policy-" + accessor, "", AclRulesJson.parse(rules)))));
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(AclCommand.createToken(new AclToken(accessor,
                AclSecrets.hash(secret), "", List.of(policyId), List.of(), List.of(), List.of(), NOW, null, false))));
    }
}

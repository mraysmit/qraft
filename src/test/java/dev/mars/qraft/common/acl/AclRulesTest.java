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

package dev.mars.qraft.common.acl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the rule model of the ACL list's proposals 6 and 7: which resources take a name and a scope, that an
 * exact name cannot be blank while a prefix may be empty, that a rule outside a tenant or namespace applies to
 * {@code default}, that a policy's rules are held in one canonical order and refuse a repeated rule, how equally
 * specific access merges, and the rules of the two built-in policies.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
class AclRulesTest {

    @Test
    void onlyNodeServiceAndClientTakeANameAndOnlyServiceTakesAScope() {
        assertEquals(List.of(AclResource.NODE, AclResource.SERVICE, AclResource.CLIENT),
                List.of(AclResource.values()).stream().filter(AclResource::named).toList());
        assertEquals(List.of(AclResource.SERVICE),
                List.of(AclResource.values()).stream().filter(AclResource::scoped).toList());

        assertThrows(IllegalArgumentException.class,
                () -> new AclRule(AclResource.OPERATOR, "raft", false, AclAccess.READ, null, null),
                "operator is a single value, as in Consul");
        assertThrows(IllegalArgumentException.class,
                () -> new AclRule(AclResource.ACL, "", true, AclAccess.READ, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AclRule(AclResource.NODE, null, false, AclAccess.READ, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AclRule(AclResource.NODE, "web-1", false, AclAccess.READ, AclScope.DEFAULT, AclScope.DEFAULT),
                "nodes have no scope (proposal 7)");
    }

    @Test
    void anExactNameCannotBeBlankWhileAPrefixMayBeEmpty() {
        assertThrows(IllegalArgumentException.class, () -> AclRule.exact(AclResource.NODE, " ", AclAccess.READ));
        assertThrows(IllegalArgumentException.class, () -> AclRule.exact(AclResource.CLIENT, "", AclAccess.READ));

        assertEquals("", AclRule.prefix(AclResource.NODE, "", AclAccess.READ).name());
    }

    @Test
    void aServiceRuleOutsideATenantAndNamespaceAppliesToDefault() {
        AclRule rule = AclRule.exact(AclResource.SERVICE, "billing", AclAccess.WRITE);

        assertEquals(AclScope.DEFAULT, rule.tenant());
        assertEquals(AclScope.DEFAULT, rule.namespace());
        assertEquals(new AclScope("default", false), AclScope.DEFAULT);
        assertEquals(new AclScope("", true), AclScope.ANY);
        assertThrows(IllegalArgumentException.class, () -> new AclScope(" ", false),
                "an exact scope names a tenant or namespace");
        assertNull(AclRule.exact(AclResource.NODE, "web-1", AclAccess.READ).tenant());
    }

    @Test
    void rulesAreHeldInOneOrderWhateverOrderTheyAreGivenIn() {
        List<AclRule> rules = List.of(
                AclRule.single(AclResource.ACL, AclAccess.READ),
                AclRule.prefix(AclResource.SERVICE, "", AclAccess.READ).in(AclScope.ANY, AclScope.ANY),
                AclRule.exact(AclResource.SERVICE, "billing", AclAccess.WRITE)
                        .in(new AclScope("acme", false), new AclScope("prod", false)),
                AclRule.prefix(AclResource.NODE, "web-", AclAccess.WRITE),
                AclRule.exact(AclResource.NODE, "web-1", AclAccess.DENY),
                AclRule.single(AclResource.OPERATOR, AclAccess.WRITE));
        List<AclRule> reversed = new ArrayList<>(rules);
        java.util.Collections.reverse(reversed);

        AclRules held = new AclRules(rules);

        assertEquals(held, new AclRules(reversed), "every replica encodes a policy's rules the same");
        assertEquals(List.of(AclResource.NODE, AclResource.NODE, AclResource.SERVICE, AclResource.SERVICE,
                        AclResource.OPERATOR, AclResource.ACL),
                held.rules().stream().map(AclRule::resource).toList());
        assertThrows(UnsupportedOperationException.class, () -> held.rules().clear());
    }

    @Test
    void aRuleCannotAppearTwiceInOnePolicyWhateverItsAccess() {
        assertThrows(IllegalArgumentException.class, () -> new AclRules(List.of(
                AclRule.exact(AclResource.NODE, "web-1", AclAccess.READ),
                AclRule.exact(AclResource.NODE, "web-1", AclAccess.WRITE))));
        assertThrows(IllegalArgumentException.class, () -> new AclRules(List.of(
                AclRule.single(AclResource.ACL, AclAccess.READ),
                AclRule.single(AclResource.ACL, AclAccess.READ))));

        // An exact name and a prefix of the same text, or the same name in another scope, are different rules.
        assertEquals(3, new AclRules(List.of(
                AclRule.exact(AclResource.SERVICE, "web", AclAccess.READ),
                AclRule.prefix(AclResource.SERVICE, "web", AclAccess.READ),
                AclRule.exact(AclResource.SERVICE, "web", AclAccess.READ)
                        .in(new AclScope("acme", false), AclScope.DEFAULT))).rules().size());
    }

    @Test
    void equallySpecificAccessMergesDenyThenWriteThenRead() {
        assertEquals(AclAccess.WRITE, AclAccess.READ.merge(AclAccess.WRITE));
        assertEquals(AclAccess.WRITE, AclAccess.WRITE.merge(AclAccess.READ));
        assertEquals(AclAccess.DENY, AclAccess.WRITE.merge(AclAccess.DENY));
        assertEquals(AclAccess.DENY, AclAccess.DENY.merge(AclAccess.READ));
        assertEquals(AclAccess.READ, AclAccess.READ.merge(AclAccess.READ));
    }

    @Test
    void theBuiltInPoliciesGrantEveryResourceInEveryScope() {
        assertEquals(new AclRules(List.of(
                AclRule.prefix(AclResource.NODE, "", AclAccess.WRITE),
                AclRule.prefix(AclResource.SERVICE, "", AclAccess.WRITE).in(AclScope.ANY, AclScope.ANY),
                AclRule.prefix(AclResource.CLIENT, "", AclAccess.WRITE),
                AclRule.single(AclResource.OPERATOR, AclAccess.WRITE),
                AclRule.single(AclResource.ACL, AclAccess.WRITE))), AclRules.GLOBAL_MANAGEMENT);
        // Consul's global-read-only includes acl = "read" (agent/structs/acl.go, aclPolicyGlobalRulesTemplate).
        assertEquals(new AclRules(List.of(
                AclRule.prefix(AclResource.NODE, "", AclAccess.READ),
                AclRule.prefix(AclResource.SERVICE, "", AclAccess.READ).in(AclScope.ANY, AclScope.ANY),
                AclRule.prefix(AclResource.CLIENT, "", AclAccess.READ),
                AclRule.single(AclResource.OPERATOR, AclAccess.READ),
                AclRule.single(AclResource.ACL, AclAccess.READ))), AclRules.GLOBAL_READ_ONLY);
    }
}

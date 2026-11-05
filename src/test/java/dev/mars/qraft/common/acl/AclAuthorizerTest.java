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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the authorizer of the ACL list's Phase 2, against Consul's {@code acl/policy_authorizer.go},
 * {@code policy_merger.go}, {@code static_authorizer.go} and {@code agent/structs/acl_ce.go}, and the owner's
 * decisions of 2026-11-05 on scope:
 *
 * <ul>
 *   <li>an exact name beats a prefix, and a longer prefix a shorter one;</li>
 *   <li>for an equally specific name, the more specific scope wins: an exact tenant before a prefix, a longer prefix
 *       before a shorter, the tenant before the namespace;</li>
 *   <li>rules equal in both merge, {@code deny} before {@code write} before {@code read}, across a token's policies;</li>
 *   <li>a matched {@code read} rule denies a write rather than leaving it to the default policy;</li>
 *   <li>the default policy decides only where no rule matches, and grants {@code acl} under neither;</li>
 *   <li>a rule in one tenant or namespace grants nothing in another, and the empty prefix matches any;</li>
 *   <li>a node identity writes its node and reads every service; a service identity writes its service and reads
 *       the services of its own tenant and namespace, and every node;</li>
 *   <li>a role's grants decide like a policy's, and the decision names the policy, role or identity and the rule.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
class AclAuthorizerTest {
    private static final AclScope ACME = new AclScope("acme", false);
    private static final AclScope PROD = new AclScope("prod", false);

    /** One row: the grants, the default policy, the request, and whether it is allowed. */
    private record Row(String name, List<AclGrant> grants, AclDefaultPolicy defaultPolicy, AclRequest request,
                       boolean allowed) {
    }

    @Test
    void namePrecedenceFollowsConsul() {
        List<Row> rows = List.of(
                row("an exact deny beats a prefix write", policies(rules(
                        AclRule.prefix(AclResource.NODE, "", AclAccess.WRITE),
                        AclRule.exact(AclResource.NODE, "web-1", AclAccess.DENY))), write(AclResource.NODE, "web-1"),
                        false),
                row("an exact write beats a prefix deny", policies(rules(
                        AclRule.prefix(AclResource.NODE, "web", AclAccess.DENY),
                        AclRule.exact(AclResource.NODE, "web-1", AclAccess.WRITE))), write(AclResource.NODE, "web-1"),
                        true),
                row("the exact rule is for that name only", policies(rules(
                        AclRule.prefix(AclResource.NODE, "web", AclAccess.DENY),
                        AclRule.exact(AclResource.NODE, "web-1", AclAccess.WRITE))), write(AclResource.NODE, "web-10"),
                        false),
                row("a longer prefix beats a shorter", policies(rules(
                        AclRule.prefix(AclResource.CLIENT, "", AclAccess.DENY),
                        AclRule.prefix(AclResource.CLIENT, "web-", AclAccess.WRITE))),
                        write(AclResource.CLIENT, "web-1"), true),
                row("a shorter prefix decides where the longer does not match", policies(rules(
                        AclRule.prefix(AclResource.CLIENT, "", AclAccess.DENY),
                        AclRule.prefix(AclResource.CLIENT, "web-", AclAccess.WRITE))),
                        write(AclResource.CLIENT, "api-1"), false),
                row("a prefix as long as the name is still a prefix, and an exact rule beats it", policies(rules(
                        AclRule.prefix(AclResource.NODE, "web-1", AclAccess.WRITE),
                        AclRule.exact(AclResource.NODE, "web-1", AclAccess.READ))), write(AclResource.NODE, "web-1"),
                        false),
                row("names are compared as written, as Consul's radix tree compares them", policies(rules(
                        AclRule.exact(AclResource.NODE, "web-1", AclAccess.WRITE))), write(AclResource.NODE, "WEB-1"),
                        false));

        assertRows(rows, AclDefaultPolicy.DENY);
    }

    @Test
    void equallySpecificRulesMergeDenyThenWriteThenReadAcrossPolicies() {
        AclGrant reads = policy("readers", rules(AclRule.prefix(AclResource.SERVICE, "", AclAccess.READ)));
        AclGrant writes = policy("writers", rules(AclRule.prefix(AclResource.SERVICE, "", AclAccess.WRITE)));
        AclGrant denies = policy("deniers", rules(AclRule.prefix(AclResource.SERVICE, "", AclAccess.DENY)));

        AclRequest writeApi = service("api", "default", "default", AclAccess.WRITE);
        AclRequest readApi = service("api", "default", "default", AclAccess.READ);
        assertRows(List.of(
                row("read and write merge to write", List.of(reads, writes), writeApi, true),
                row("deny wins over write", List.of(writes, denies), writeApi, false),
                row("deny wins over read", List.of(denies, reads), readApi, false)),
                AclDefaultPolicy.ALLOW);

        AclDecision decision = AclAuthorizer.decide(List.of(reads, writes, denies), AclDefaultPolicy.ALLOW, readApi);
        assertEquals(new AclDecision.ByRule(false, denies.origin(),
                AclRule.prefix(AclResource.SERVICE, "", AclAccess.DENY)), decision, "the deny decided, from its policy");
    }

    @Test
    void aMatchedReadRuleDeniesAWriteUnderEitherDefault() {
        AclGrant reads = policy("readers", rules(AclRule.exact(AclResource.NODE, "web-1", AclAccess.READ)));

        for (AclDefaultPolicy defaultPolicy : AclDefaultPolicy.values()) {
            assertEquals(new AclDecision.ByRule(false, reads.origin(),
                            AclRule.exact(AclResource.NODE, "web-1", AclAccess.READ)),
                    AclAuthorizer.decide(List.of(reads), defaultPolicy, write(AclResource.NODE, "web-1")));
            assertEquals(true, AclAuthorizer.decide(List.of(reads), defaultPolicy, read(AclResource.NODE, "web-1"))
                    .allowed(), "write grants read too; read grants only read");
        }
    }

    @Test
    void theDefaultPolicyDecidesOnlyWhereNoRuleMatchesAndNeverGrantsAcl() {
        AclGrant nodes = policy("nodes", rules(AclRule.prefix(AclResource.NODE, "web-", AclAccess.WRITE)));

        assertEquals(new AclDecision.ByDefault(true, AclDefaultPolicy.ALLOW),
                AclAuthorizer.decide(List.of(nodes), AclDefaultPolicy.ALLOW, write(AclResource.NODE, "api-1")));
        assertEquals(new AclDecision.ByDefault(false, AclDefaultPolicy.DENY),
                AclAuthorizer.decide(List.of(nodes), AclDefaultPolicy.DENY, read(AclResource.NODE, "api-1")));
        for (AclDefaultPolicy defaultPolicy : AclDefaultPolicy.values()) {
            for (AclAccess access : List.of(AclAccess.READ, AclAccess.WRITE)) {
                assertEquals(new AclDecision.ByDefault(false, defaultPolicy), AclAuthorizer.decide(List.of(nodes),
                                defaultPolicy, AclRequest.single(AclResource.ACL, access)),
                        "Consul's allow-all authorizer denies acl: " + defaultPolicy + " " + access);
            }
        }
        assertEquals(true, AclAuthorizer.decide(List.of(), AclDefaultPolicy.ALLOW,
                AclRequest.single(AclResource.OPERATOR, AclAccess.WRITE)).allowed(), "operator follows the default");
        assertEquals(true, AclAuthorizer.decide(List.of(policy("global", AclRules.GLOBAL_MANAGEMENT)),
                AclDefaultPolicy.DENY, AclRequest.single(AclResource.ACL, AclAccess.WRITE)).allowed());
    }

    @Test
    void aRuleAppliesOnlyInItsTenantAndNamespaceAndTheEmptyPrefixMatchesAny() {
        List<AclGrant> acmeProd = policies(rules(AclRule.exact(AclResource.SERVICE, "api", AclAccess.WRITE)
                .in(ACME, PROD)));
        List<AclGrant> unnested = policies(rules(AclRule.exact(AclResource.SERVICE, "api", AclAccess.WRITE)));
        List<AclGrant> anywhere = policies(rules(AclRule.exact(AclResource.SERVICE, "api", AclAccess.WRITE)
                .in(AclScope.ANY, AclScope.ANY)));
        List<AclGrant> acmePrefix = policies(rules(AclRule.exact(AclResource.SERVICE, "api", AclAccess.WRITE)
                .in(new AclScope("ac", true), AclScope.ANY)));

        assertRows(List.of(
                row("in its tenant and namespace", acmeProd, service("api", "acme", "prod", AclAccess.WRITE), true),
                row("not in another namespace", acmeProd, service("api", "acme", "dev", AclAccess.WRITE), false),
                row("not in another tenant", acmeProd, service("api", "other", "prod", AclAccess.WRITE), false),
                row("a rule outside the nesting is in default", unnested,
                        service("api", "default", "default", AclAccess.WRITE), true),
                row("and only there", unnested, service("api", "acme", "default", AclAccess.WRITE), false),
                row("the empty prefix matches any tenant and namespace", anywhere,
                        service("api", "other", "x", AclAccess.WRITE), true),
                row("a tenant prefix matches by prefix", acmePrefix, service("api", "acme", "x", AclAccess.WRITE),
                        true),
                row("and not otherwise", acmePrefix, service("api", "bcme", "x", AclAccess.WRITE), false)),
                AclDefaultPolicy.DENY);
    }

    @Test
    void forAnEquallySpecificNameTheMoreSpecificScopeWins() {
        // The owner's decision of 2026-11-05: name first, as Consul, then scope.
        AclRule broadDeny = AclRule.exact(AclResource.SERVICE, "web", AclAccess.DENY).in(AclScope.ANY, AclScope.ANY);
        AclRule acmeProdWrite = AclRule.exact(AclResource.SERVICE, "web", AclAccess.WRITE).in(ACME, PROD);
        AclRule tenantFirst = AclRule.exact(AclResource.SERVICE, "web", AclAccess.WRITE).in(ACME, AclScope.ANY);
        AclRule namespaceOnly = AclRule.exact(AclResource.SERVICE, "web", AclAccess.DENY).in(AclScope.ANY, PROD);
        AclRule longerTenant = AclRule.exact(AclResource.SERVICE, "web", AclAccess.WRITE)
                .in(new AclScope("acm", true), AclScope.ANY);
        AclRule shorterTenant = AclRule.exact(AclResource.SERVICE, "web", AclAccess.DENY)
                .in(new AclScope("a", true), AclScope.ANY);
        AclRule prefixInScope = AclRule.prefix(AclResource.SERVICE, "", AclAccess.WRITE).in(ACME, PROD);

        assertRows(List.of(
                row("an exact scope beats the empty prefix", List.of(policy("a", rules(broadDeny)),
                        policy("b", rules(acmeProdWrite))), service("web", "acme", "prod", AclAccess.WRITE), true),
                row("the broad rule still decides elsewhere", List.of(policy("a", rules(broadDeny)),
                        policy("b", rules(acmeProdWrite))), service("web", "acme", "dev", AclAccess.WRITE), false),
                row("the tenant ranks before the namespace", policies(rules(tenantFirst, namespaceOnly)),
                        service("web", "acme", "prod", AclAccess.WRITE), true),
                row("a longer tenant prefix beats a shorter", policies(rules(longerTenant, shorterTenant)),
                        service("web", "acme", "prod", AclAccess.WRITE), true),
                row("the name ranks before the scope", policies(rules(broadDeny, prefixInScope)),
                        service("web", "acme", "prod", AclAccess.WRITE), false)),
                AclDefaultPolicy.ALLOW);
    }

    @Test
    void aNodeIdentityWritesItsNodeAndReadsEveryService() {
        List<AclGrant> identity = List.of(AclGrant.of(new NodeIdentity("web-1"), null));

        assertRows(List.of(
                row("its node", identity, write(AclResource.NODE, "web-1"), true),
                row("not another node", identity, write(AclResource.NODE, "web-2"), false),
                row("not another node's reads either", identity, read(AclResource.NODE, "web-2"), false),
                row("every service, in every tenant and namespace", identity,
                        service("api", "acme", "prod", AclAccess.READ), true),
                row("but writes none", identity, service("api", "default", "default", AclAccess.WRITE), false),
                row("nor its client", identity, write(AclResource.CLIENT, "web-1"), false),
                row("nor acl", identity, AclRequest.single(AclResource.ACL, AclAccess.READ), false)),
                AclDefaultPolicy.DENY);
    }

    @Test
    void aServiceIdentityWritesItsServiceAndReadsItsOwnScopeAndEveryNode() {
        // The owner's decision of 2026-11-05: a service identity reads the services of its own tenant and namespace.
        List<AclGrant> identity = List.of(AclGrant.of(new ServiceIdentity("billing", "acme", "prod"), null));

        assertRows(List.of(
                row("its service in its scope", identity, service("billing", "acme", "prod", AclAccess.WRITE), true),
                row("not its name in another scope", identity, service("billing", "acme", "dev", AclAccess.WRITE),
                        false),
                row("not another service", identity, service("api", "acme", "prod", AclAccess.WRITE), false),
                row("reads its own scope's services", identity, service("api", "acme", "prod", AclAccess.READ), true),
                row("and no other tenant's", identity, service("api", "other", "prod", AclAccess.READ), false),
                row("nor another namespace's", identity, service("api", "acme", "dev", AclAccess.READ), false),
                row("reads every node", identity, read(AclResource.NODE, "web-9"), true),
                row("and writes none", identity, write(AclResource.NODE, "web-9"), false),
                row("no sidecar, since Qraft has no mesh", identity,
                        service("billing-sidecar-proxy", "acme", "prod", AclAccess.WRITE), false)),
                AclDefaultPolicy.DENY);
    }

    @Test
    void aRolesGrantsDecideLikeAPolicysAndTheDecisionNamesTheRole() {
        AclGrant viaRole = AclGrant.policy("1b4e28ba-2fa1-4d2a-883f-0016d3cca427", "operators", "admins",
                rules(AclRule.single(AclResource.OPERATOR, AclAccess.WRITE)));
        AclGrant nodeViaRole = AclGrant.of(new NodeIdentity("web-1"), "admins");

        AclDecision operator = AclAuthorizer.decide(List.of(viaRole), AclDefaultPolicy.DENY,
                AclRequest.single(AclResource.OPERATOR, AclAccess.WRITE));
        AclDecision node = AclAuthorizer.decide(List.of(viaRole, nodeViaRole), AclDefaultPolicy.DENY,
                write(AclResource.NODE, "web-1"));

        assertEquals(new AclDecision.ByRule(true, new AclOrigin.Policy("1b4e28ba-2fa1-4d2a-883f-0016d3cca427",
                "operators", "admins"), AclRule.single(AclResource.OPERATOR, AclAccess.WRITE)), operator);
        assertEquals(new AclDecision.ByRule(true, new AclOrigin.Node(new NodeIdentity("web-1"), "admins"),
                AclRule.exact(AclResource.NODE, "web-1", AclAccess.WRITE)), node);
    }

    @Test
    void aRequestNamesWhatItsResourceTakes() {
        assertThrows(IllegalArgumentException.class, () -> AclRequest.single(AclResource.ACL, AclAccess.DENY),
                "deny is not something a request asks for");
        assertThrows(IllegalArgumentException.class, () -> AclRequest.single(AclResource.NODE, AclAccess.READ));
        assertThrows(IllegalArgumentException.class, () -> AclRequest.named(AclResource.SERVICE, "api", AclAccess.READ),
                "a service is asked for in a tenant and namespace");
        assertThrows(IllegalArgumentException.class, () -> AclRequest.named(AclResource.ACL, "x", AclAccess.READ));
        assertThrows(IllegalArgumentException.class, () -> AclRequest.service("api", " ", "prod", AclAccess.READ));
        assertThrows(NullPointerException.class, () -> AclRequest.named(AclResource.NODE, null, AclAccess.READ));
    }

    private static void assertRows(List<Row> rows, AclDefaultPolicy defaultPolicy) {
        List<String> wrong = new ArrayList<>();
        for (Row row : rows) {
            AclDefaultPolicy policy = row.defaultPolicy() == null ? defaultPolicy : row.defaultPolicy();
            AclDecision decision = AclAuthorizer.decide(row.grants(), policy, row.request());
            if (decision.allowed() != row.allowed()) wrong.add(row.name() + ": " + decision);
        }
        assertEquals(List.of(), wrong);
    }

    private static Row row(String name, List<AclGrant> grants, AclRequest request, boolean allowed) {
        return new Row(name, grants, null, request, allowed);
    }

    private static AclRules rules(AclRule... rules) {
        return new AclRules(List.of(rules));
    }

    private static List<AclGrant> policies(AclRules rules) {
        return List.of(policy("policy", rules));
    }

    private static AclGrant policy(String name, AclRules rules) {
        return AclGrant.policy("9c5b94b1-35ad-49bb-b118-8e8fc24abf80", name, null, rules);
    }

    private static AclRequest read(AclResource resource, String name) {
        return AclRequest.named(resource, name, AclAccess.READ);
    }

    private static AclRequest write(AclResource resource, String name) {
        return AclRequest.named(resource, name, AclAccess.WRITE);
    }

    private static AclRequest service(String name, String tenant, String namespace, AclAccess access) {
        return AclRequest.service(name, tenant, namespace, access);
    }
}

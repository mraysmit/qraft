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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the parsing of a policy's rules from the JSON form of Consul's rules (ACL list, proposals 6 to 8, and Phase 2):
 * each resource exactly and by prefix, the single values, the {@code tenant} and {@code namespace} nesting with their
 * {@code _prefix} forms and the {@code default} outside them, and the refusal of anything this list does not know or
 * that is written twice.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
class AclRulesJsonTest {
    private static final AclScope ACME = new AclScope("acme", false);
    private static final AclScope PROD = new AclScope("prod", false);

    @Test
    void parsesTheExampleOfProposal8() {
        AclRules rules = AclRulesJson.parse("""
                {
                  "tenant": {
                    "acme": {
                      "namespace": {
                        "prod": { "service_prefix": { "billing-": { "policy": "write" } } }
                      },
                      "namespace_prefix": {
                        "": { "service_prefix": { "": { "policy": "read" } } }
                      }
                    }
                  },
                  "node_prefix": { "": { "policy": "read" } }
                }
                """);

        assertEquals(new AclRules(List.of(
                AclRule.prefix(AclResource.SERVICE, "billing-", AclAccess.WRITE).in(ACME, PROD),
                AclRule.prefix(AclResource.SERVICE, "", AclAccess.READ).in(ACME, AclScope.ANY),
                AclRule.prefix(AclResource.NODE, "", AclAccess.READ))), rules);
    }

    @Test
    void parsesEveryResourceExactlyAndByPrefixAndTheSingleValues() {
        AclRules rules = AclRulesJson.parse("""
                {"node": {"web-1": {"policy": "write"}}, "node_prefix": {"web-": {"policy": "read"}},
                 "service": {"api": {"policy": "deny"}}, "service_prefix": {"": {"policy": "read"}},
                 "client": {"web-1": {"policy": "write"}}, "client_prefix": {"": {"policy": "read"}},
                 "operator": "read", "acl": "write"}
                """);

        assertEquals(new AclRules(List.of(
                AclRule.exact(AclResource.NODE, "web-1", AclAccess.WRITE),
                AclRule.prefix(AclResource.NODE, "web-", AclAccess.READ),
                AclRule.exact(AclResource.SERVICE, "api", AclAccess.DENY),
                AclRule.prefix(AclResource.SERVICE, "", AclAccess.READ),
                AclRule.exact(AclResource.CLIENT, "web-1", AclAccess.WRITE),
                AclRule.prefix(AclResource.CLIENT, "", AclAccess.READ),
                AclRule.single(AclResource.OPERATOR, AclAccess.READ),
                AclRule.single(AclResource.ACL, AclAccess.WRITE))), rules);
        assertEquals(AclRules.EMPTY, AclRulesJson.parse("{}"));
    }

    @Test
    void aServiceRuleOutsideATenantOrNamespaceIsInDefault() {
        AclRules rules = AclRulesJson.parse("""
                {"service": {"a": {"policy": "read"}},
                 "tenant": {"acme": {"service": {"b": {"policy": "read"}}}},
                 "namespace": {"prod": {"service": {"c": {"policy": "read"}}}},
                 "tenant_prefix": {"": {"namespace_prefix": {"p": {"service": {"d": {"policy": "read"}}}}}}}
                """);

        assertEquals(Map.of(
                        "a", List.of(AclScope.DEFAULT, AclScope.DEFAULT),
                        "b", List.of(ACME, AclScope.DEFAULT),
                        "c", List.of(AclScope.DEFAULT, PROD),
                        "d", List.of(AclScope.ANY, new AclScope("p", true))),
                rules.rules().stream().collect(java.util.stream.Collectors.toMap(AclRule::name,
                        rule -> List.of(rule.tenant(), rule.namespace()))));
    }

    @Test
    void refusesWhatThisListDoesNotKnowOrDoesNotAllow() {
        Map<String, String> refused = new java.util.LinkedHashMap<>();
        refused.put("an unknown resource", "{\"keyring\": \"read\"}");
        refused.put("list access on keys, left out (key/value list, decision 5)",
                "{\"key_prefix\": {\"\": {\"policy\": \"list\"}}}");
        refused.put("a session rule in a tenant",
                "{\"tenant\": {\"acme\": {\"session\": {\"a\": {\"policy\": \"read\"}}}}}");
        refused.put("Consul's intentions, which need a mesh",
                "{\"service\": {\"a\": {\"policy\": \"read\", \"intentions\": \"read\"}}}");
        refused.put("list, which only keys take", "{\"service\": {\"a\": {\"policy\": \"list\"}}}");
        refused.put("an access in capitals", "{\"acl\": \"Write\"}");
        refused.put("a rule without a policy", "{\"service\": {\"a\": {}}}");
        refused.put("a rule that is not an object", "{\"service\": {\"a\": \"read\"}}");
        refused.put("a single value given as an object", "{\"operator\": {\"policy\": \"read\"}}");
        refused.put("a node rule in a tenant", "{\"tenant\": {\"acme\": {\"node\": {\"a\": {\"policy\": \"read\"}}}}}");
        refused.put("an acl rule in a namespace", "{\"namespace\": {\"prod\": {\"acl\": \"read\"}}}");
        refused.put("a tenant in a tenant", "{\"tenant\": {\"acme\": {\"tenant\": {}}}}");
        refused.put("a namespace in a namespace", "{\"namespace\": {\"a\": {\"namespace_prefix\": {}}}}");
        refused.put("a blank exact name", "{\"node\": {\" \": {\"policy\": \"read\"}}}");
        refused.put("a blank exact tenant", "{\"tenant\": {\"\": {}}}");
        refused.put("a key written twice", "{\"acl\": \"read\", \"acl\": \"write\"}");
        refused.put("the same rule twice, through the default",
                "{\"service\": {\"a\": {\"policy\": \"read\"}}, \"tenant\": {\"default\": {\"namespace\": "
                        + "{\"default\": {\"service\": {\"a\": {\"policy\": \"write\"}}}}}}}");
        refused.put("rules that are not an object", "[]");
        refused.put("text that is not JSON", "service \"a\" { policy = \"read\" }");
        refused.put("nothing", "");

        for (Map.Entry<String, String> entry : refused.entrySet()) {
            assertThrows(IllegalArgumentException.class, () -> AclRulesJson.parse(entry.getValue()), entry.getKey());
        }
    }

    @Test
    void writesRulesInTheFormItReads() throws Exception {
        String written = AclRulesJson.write(AclRules.GLOBAL_READ_ONLY);

        assertEquals(new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"node_prefix": {"": {"policy": "read"}},
                 "client_prefix": {"": {"policy": "read"}},
                 "session_prefix": {"": {"policy": "read"}},
                 "operator": "read", "acl": "read",
                 "tenant_prefix": {"": {"namespace_prefix": {"": {"service_prefix": {"": {"policy": "read"}},
                                                                   "key_prefix": {"": {"policy": "read"}}}}}}}
                """), new com.fasterxml.jackson.databind.ObjectMapper().readTree(written));
    }

    @Test
    void whatItWritesReadsBackAsTheSameRules() {
        List<AclRules> cases = List.of(AclRules.EMPTY, AclRules.GLOBAL_MANAGEMENT, AclRules.GLOBAL_READ_ONLY,
                new AclRules(List.of(
                        AclRule.exact(AclResource.SERVICE, "web", AclAccess.WRITE),
                        AclRule.exact(AclResource.SERVICE, "web", AclAccess.DENY).in(ACME, AclScope.DEFAULT),
                        AclRule.prefix(AclResource.SERVICE, "billing-", AclAccess.WRITE).in(ACME, PROD),
                        AclRule.prefix(AclResource.SERVICE, "", AclAccess.READ).in(AclScope.DEFAULT, PROD),
                        AclRule.prefix(AclResource.SERVICE, "", AclAccess.READ).in(new AclScope("ac", true),
                                new AclScope("p", true)),
                        AclRule.exact(AclResource.NODE, "web-1", AclAccess.WRITE),
                        AclRule.prefix(AclResource.NODE, "", AclAccess.READ),
                        AclRule.exact(AclResource.CLIENT, "web-1", AclAccess.WRITE),
                        AclRule.single(AclResource.OPERATOR, AclAccess.DENY))));

        for (AclRules rules : cases) {
            assertEquals(rules, AclRulesJson.parse(AclRulesJson.write(rules)), rules.toString());
        }
    }

    @Test
    void aRefusalNamesWhereTheRulesWentWrong() {
        IllegalArgumentException nested = assertThrows(IllegalArgumentException.class, () -> AclRulesJson.parse(
                "{\"tenant\": {\"acme\": {\"namespace\": {\"prod\": {\"servcie\": {}}}}}}"));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> AclRulesJson.parse("{\"keyring\": \"read\"}"));

        assertTrue(nested.getMessage().contains("tenant.acme.namespace.prod.servcie"), nested.getMessage());
        assertTrue(unknown.getMessage().contains("keyring"), unknown.getMessage());
    }
}

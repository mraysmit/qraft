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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code key} and {@code key_prefix} rules (key/value list, Phase 2): they are read and written as Consul writes
 * them, scoped by tenant and namespace as {@code service} rules are; an exact rule beats a prefix, and a longer prefix
 * a shorter; and the built-in management and read-only policies cover keys.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class AclKeyRulesTest {
    private static final String RULES = """
            {"key_prefix":{"app/":{"policy":"read"},"app/config/":{"policy":"write"}},
             "key":{"app/secret":{"policy":"deny"}},
             "tenant":{"acme":{"namespace":{"prod":{"key_prefix":{"":{"policy":"write"}}}}}}}""";

    private static boolean allowed(AclRules rules, String key, String tenant, String namespace, AclAccess access) {
        return AclAuthorizer.decide(List.of(AclGrant.policy("9c5b94b1-35ad-49bb-b118-8e8fc24abf80", "keys", null, rules)),
                AclDefaultPolicy.DENY, AclRequest.key(key, tenant, namespace, access)).allowed();
    }

    @Test
    void keyRulesAreReadAndWrittenBack() {
        AclRules rules = AclRulesJson.parse(RULES);

        assertEquals(4, rules.rules().stream().filter(rule -> rule.resource() == AclResource.KEY).count());
        assertEquals(rules, AclRulesJson.parse(AclRulesJson.write(rules)), "written as they are read");
    }

    @Test
    void anExactRuleBeatsAPrefixAndALongerPrefixAShorter() {
        AclRules rules = AclRulesJson.parse(RULES);

        assertTrue(allowed(rules, "app/name", "default", "default", AclAccess.READ));
        assertFalse(allowed(rules, "app/name", "default", "default", AclAccess.WRITE), "read does not grant write");
        assertFalse(allowed(rules, "app/secret", "default", "default", AclAccess.READ), "the exact deny");
        assertTrue(allowed(rules, "app/config/db", "default", "default", AclAccess.WRITE), "the longer prefix");
        assertFalse(allowed(rules, "other", "default", "default", AclAccess.READ), "no rule, and the default denies");
    }

    @Test
    void aScopedRuleAppliesInItsTenantAndNamespaceOnly() {
        AclRules rules = AclRulesJson.parse(RULES);

        assertTrue(allowed(rules, "anything", "acme", "prod", AclAccess.WRITE));
        assertFalse(allowed(rules, "anything", "acme", "dev", AclAccess.WRITE));
        assertFalse(allowed(rules, "anything", "default", "default", AclAccess.WRITE));
    }

    private static boolean writesPrefix(String rulesJson, String prefix, AclDefaultPolicy defaultPolicy) {
        return AclAuthorizer.decideWritePrefix(List.of(AclGrant.policy("9c5b94b1-35ad-49bb-b118-8e8fc24abf80", "keys",
                null, AclRulesJson.parse(rulesJson))), defaultPolicy,
                AclRequest.key(prefix, "default", "default", AclAccess.WRITE)).allowed();
    }

    @Test
    void writeOnAPrefixNeedsWriteThereAndNoLesserRuleUnderItAsConsulsKeyWritePrefix() {
        String rules = """
                {"key_prefix":{"app/":{"policy":"write"},"app/locked/":{"policy":"read"}},
                 "key":{"app/secret":{"policy":"deny"}}}""";

        assertTrue(writesPrefix(rules, "app/config/", AclDefaultPolicy.DENY), "write, and nothing lesser under it");
        assertFalse(writesPrefix(rules, "app/", AclDefaultPolicy.DENY), "a read prefix and a deny lie under it");
        assertFalse(writesPrefix(rules, "app/locked/", AclDefaultPolicy.DENY), "the prefix itself is only read");
        assertFalse(writesPrefix(rules, "other/", AclDefaultPolicy.DENY), "no rule: the default, deny");
        assertTrue(writesPrefix(rules, "other/", AclDefaultPolicy.ALLOW), "no rule: the default, allow");
        assertFalse(writesPrefix(rules, "", AclDefaultPolicy.ALLOW),
                "under allow, a lesser rule anywhere under the prefix still refuses it");
        assertTrue(writesPrefix("{\"key_prefix\":{\"\":{\"policy\":\"write\"}}}", "", AclDefaultPolicy.DENY));
    }

    @Test
    void theBuiltInPoliciesCoverKeys() {
        assertTrue(allowed(AclRules.GLOBAL_MANAGEMENT, "any/key", "acme", "prod", AclAccess.WRITE));
        assertTrue(allowed(AclRules.GLOBAL_READ_ONLY, "any/key", "acme", "prod", AclAccess.READ));
        assertFalse(allowed(AclRules.GLOBAL_READ_ONLY, "any/key", "acme", "prod", AclAccess.WRITE));
    }
}

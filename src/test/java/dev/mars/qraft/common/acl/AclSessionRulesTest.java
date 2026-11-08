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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code session} and {@code session_prefix} rules (sessions list, Phase 3): they name a node, as Consul's do, and
 * have no tenant or namespace; they are read and written as Consul writes them; an exact rule beats a prefix; and the
 * built-in management and read-only policies cover sessions.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class AclSessionRulesTest {
    private static final String RULES = """
            {"session_prefix":{"":{"policy":"read"},"web-":{"policy":"write"}},
             "session":{"web-db":{"policy":"deny"}}}""";

    private static boolean allowed(AclRules rules, String node, AclAccess access) {
        return AclAuthorizer.decide(List.of(AclGrant.policy("6f7cf0c2-4f39-4c55-9f67-3d6a3c8a0d11", "sessions", null,
                rules)), AclDefaultPolicy.DENY, AclRequest.named(AclResource.SESSION, node, access)).allowed();
    }

    @Test
    void sessionRulesNameANodeAndAreReadAndWrittenBack() {
        AclRules rules = AclRulesJson.parse(RULES);

        assertEquals(3, rules.rules().stream().filter(rule -> rule.resource() == AclResource.SESSION).count());
        assertEquals(rules, AclRulesJson.parse(AclRulesJson.write(rules)), "written as they are read");
        assertTrue(AclResource.SESSION.named());
        assertFalse(AclResource.SESSION.scoped(), "a session rule names a node, and nodes have no scope");
        assertThrows(IllegalArgumentException.class, () -> AclRulesJson.parse(
                "{\"tenant\":{\"acme\":{\"session\":{\"a\":{\"policy\":\"read\"}}}}}"));
    }

    @Test
    void anExactRuleBeatsAPrefixAndALongerPrefixAShorter() {
        AclRules rules = AclRulesJson.parse(RULES);

        assertTrue(allowed(rules, "host-a", AclAccess.READ));
        assertFalse(allowed(rules, "host-a", AclAccess.WRITE), "read does not grant write");
        assertTrue(allowed(rules, "web-1", AclAccess.WRITE), "the longer prefix");
        assertFalse(allowed(rules, "web-db", AclAccess.READ), "the exact deny");
    }

    @Test
    void theBuiltInPoliciesCoverSessions() {
        assertTrue(AclRules.GLOBAL_MANAGEMENT.rules().contains(AclRule.prefix(AclResource.SESSION, "",
                AclAccess.WRITE)));
        assertTrue(AclRules.GLOBAL_READ_ONLY.rules().contains(AclRule.prefix(AclResource.SESSION, "",
                AclAccess.READ)));
    }
}

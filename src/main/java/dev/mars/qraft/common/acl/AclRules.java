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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The rules of one policy, held in one order whatever order they were given in, so that every replica encodes a
 * policy the same. A rule cannot appear twice for the same names, whatever access each would grant (ACL list,
 * proposal 8).
 *
 * <p>The two built-in policies grant every resource of this list in every scope, as Consul's
 * {@code aclPolicyGlobalRulesTemplate} in {@code agent/structs/acl.go} does: {@code write} for
 * {@code global-management} and {@code read} for {@code builtin/global-read-only}, each including {@code acl}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
public record AclRules(List<AclRule> rules) {
    public static final AclRules EMPTY = new AclRules(List.of());
    public static final AclRules GLOBAL_MANAGEMENT = global(AclAccess.WRITE);
    public static final AclRules GLOBAL_READ_ONLY = global(AclAccess.READ);

    public AclRules {
        List<AclRule> ordered = new ArrayList<>(rules == null ? List.of() : rules);
        ordered.forEach(rule -> Objects.requireNonNull(rule, "rule"));
        ordered.sort(AclRule.ORDER);
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).sameTarget(ordered.get(i - 1))) {
                throw new IllegalArgumentException("a policy holds the same rule twice: " + ordered.get(i));
            }
        }
        rules = List.copyOf(ordered);
    }

    /**
     * The access that {@code policies} together grant to a single-valued resource, {@code operator} or {@code acl},
     * or {@code null} when none has a rule for it. Every rule for such a resource is equally specific, so
     * {@link AclAccess#merge} decides.
     */
    public static AclAccess mergedAccess(AclResource resource, List<AclRules> policies) {
        if (resource.named()) {
            throw new IllegalArgumentException(resource.ruleName() + " is named, so it has no single access");
        }
        AclAccess merged = null;
        for (AclRules policy : policies) {
            for (AclRule rule : policy.rules()) {
                if (rule.resource() == resource) merged = merged == null ? rule.access() : merged.merge(rule.access());
            }
        }
        return merged;
    }

    private static AclRules global(AclAccess access) {
        return new AclRules(List.of(
                AclRule.prefix(AclResource.NODE, "", access),
                AclRule.prefix(AclResource.SERVICE, "", access).in(AclScope.ANY, AclScope.ANY),
                AclRule.prefix(AclResource.CLIENT, "", access),
                AclRule.single(AclResource.OPERATOR, access),
                AclRule.single(AclResource.ACL, access)));
    }
}

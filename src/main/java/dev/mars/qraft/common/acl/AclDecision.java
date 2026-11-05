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

import java.util.Objects;

/**
 * An authorizer's answer, with what decided it, so that enforcement and an explanation cannot differ (ACL list,
 * proposal 19): a rule, from a policy or an identity, or the default policy where no rule matched. For {@code acl},
 * the default policy always refuses.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
public sealed interface AclDecision permits AclDecision.ByRule, AclDecision.ByDefault {

    boolean allowed();

    /** A rule decided: the one that ranked first for the request and, among equals, won the merge. */
    record ByRule(boolean allowed, AclOrigin origin, AclRule rule) implements AclDecision {
        public ByRule {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(rule, "rule");
        }
    }

    /** No rule matched, and the default policy decided. */
    record ByDefault(boolean allowed, AclDefaultPolicy policy) implements AclDecision {
        public ByDefault {
            Objects.requireNonNull(policy, "policy");
        }
    }
}

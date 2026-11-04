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

import java.util.Comparator;
import java.util.Objects;

/**
 * One rule of a policy: a resource, the name or name prefix it applies to, and the access it grants, with the tenant
 * and namespace of a {@code service} rule (ACL list, proposals 6 and 7).
 *
 * <ul>
 *   <li>A {@code node}, {@code service} or {@code client} rule has a name. An exact name cannot be blank; a prefix
 *       may be empty, matching every name.</li>
 *   <li>An {@code operator} or {@code acl} rule has neither a name nor a prefix.</li>
 *   <li>A {@code service} rule has a tenant and a namespace, {@code default} unless the rule is nested under them;
 *       no other rule has either.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
public record AclRule(AclResource resource, String name, boolean prefix, AclAccess access, AclScope tenant,
                      AclScope namespace) {
    /** The order of a policy's rules: by resource, scope, exact before prefix, then name. Access is not part of it. */
    static final Comparator<AclRule> ORDER = Comparator.comparing(AclRule::resource)
            .thenComparing(AclRule::tenant, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(AclRule::namespace, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(AclRule::prefix)
            .thenComparing(AclRule::name, Comparator.nullsFirst(Comparator.naturalOrder()));

    public AclRule {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(access, "access");
        if (resource.named()) {
            AclNames.requireMatch(name, prefix, "a " + resource.ruleName() + " rule");
        } else if (name != null || prefix) {
            throw new IllegalArgumentException("a " + resource.ruleName() + " rule names nothing: it is a single value");
        }
        if (resource.scoped()) {
            tenant = tenant == null ? AclScope.DEFAULT : tenant;
            namespace = namespace == null ? AclScope.DEFAULT : namespace;
        } else if (tenant != null || namespace != null) {
            throw new IllegalArgumentException("a " + resource.ruleName() + " rule has no tenant or namespace");
        }
    }

    /** A rule for the resource named exactly {@code name}. */
    public static AclRule exact(AclResource resource, String name, AclAccess access) {
        return new AclRule(resource, name, false, access, null, null);
    }

    /** A rule for every resource whose name starts with {@code prefix}. */
    public static AclRule prefix(AclResource resource, String prefix, AclAccess access) {
        return new AclRule(resource, prefix, true, access, null, null);
    }

    /** A rule for a single-valued resource, {@code operator} or {@code acl}. */
    public static AclRule single(AclResource resource, AclAccess access) {
        return new AclRule(resource, null, false, access, null, null);
    }

    /** This rule in {@code tenant} and {@code namespace}; only a {@code service} rule has them. */
    public AclRule in(AclScope tenant, AclScope namespace) {
        return new AclRule(resource, name, prefix, access, tenant, namespace);
    }

    /** Whether {@code other} is a rule for the same names as this one, whatever access each grants. */
    boolean sameTarget(AclRule other) {
        return ORDER.compare(this, other) == 0;
    }
}

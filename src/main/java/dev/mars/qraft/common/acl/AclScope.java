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

/**
 * The tenants or namespaces a {@code service} rule applies in: one by its exact name, or every one whose name starts
 * with a prefix, which may be empty to match any (ACL list, proposal 7). The counterpart of Consul Enterprise's
 * {@code partition} and {@code namespace} nesting, with its {@code _prefix} forms.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
public record AclScope(String name, boolean prefix) implements Comparable<AclScope> {
    /** Where a rule outside a {@code tenant} or {@code namespace} nesting applies. */
    public static final AclScope DEFAULT = new AclScope("default", false);
    /** Every tenant or namespace: the empty prefix. */
    public static final AclScope ANY = new AclScope("", true);

    public AclScope {
        AclNames.requireMatch(name, prefix, "a scope");
    }

    /** Exact scopes before prefixes, then by name, so that a policy's rules have one order. */
    @Override
    public int compareTo(AclScope other) {
        int byForm = Boolean.compare(prefix, other.prefix);
        return byForm != 0 ? byForm : name.compareTo(other.name);
    }
}

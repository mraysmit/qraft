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

package dev.mars.qraft.state.acl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A read-only view of the replicated ACL state, so that only committed commands change it. Tokens and roles are
 * read with their links to deleted policies and roles dropped, as Consul's {@code fixupTokenPolicyLinks},
 * {@code fixupTokenRoleLinks} and {@code fixupRolePolicyLinks} drop them (proposal 18).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
public final class AclView {
    private final AclState state;

    private AclView(AclState state) {
        this.state = state;
    }

    /** A view of {@code state}. */
    public static AclView of(AclState state) {
        return new AclView(Objects.requireNonNull(state, "state"));
    }

    /** The token with {@code accessorId}, in either case. */
    public Optional<AclToken> token(String accessorId) {
        return state.token(accessorId);
    }

    /** The token whose secret is {@code secret}, found by the secret's hash. */
    public Optional<AclToken> tokenBySecret(String secret) {
        return state.tokenBySecret(secret);
    }

    /** Every token, in accessor ID order. */
    public List<AclToken> tokens() {
        return state.tokens();
    }

    public Optional<AclPolicy> policy(String id) {
        return state.policy(id);
    }

    /** The policy named {@code name}, regardless of case. */
    public Optional<AclPolicy> policyByName(String name) {
        return state.policyByName(name);
    }

    /** Every policy, in ID order. */
    public List<AclPolicy> policies() {
        return state.policies();
    }

    public Optional<AclRole> role(String id) {
        return state.role(id);
    }

    /** The role named {@code name}, regardless of case. */
    public Optional<AclRole> roleByName(String name) {
        return state.roleByName(name);
    }

    /** Every role, in ID order. */
    public List<AclRole> roles() {
        return state.roles();
    }

    /** How many bootstraps have been applied: zero before the first, and the reset index after it. */
    public long bootstrapIndex() {
        return state.bootstrapIndex();
    }
}

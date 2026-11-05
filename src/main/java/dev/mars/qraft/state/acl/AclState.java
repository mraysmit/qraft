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

import dev.mars.qraft.common.acl.AclAccess;
import dev.mars.qraft.common.acl.AclAuthorizer;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.acl.AclGrant;
import dev.mars.qraft.common.acl.AclRequest;
import dev.mars.qraft.common.acl.AclResource;
import dev.mars.qraft.common.acl.NodeIdentity;
import dev.mars.qraft.common.acl.ServiceIdentity;
import dev.mars.qraft.raft.RaftCommandResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The replicated ACL state: tokens, policies, roles, and the bootstrap index (ACL list, Phase 1). Each command is
 * decided from what this state holds, so every replica decides the same, and a refused command changes nothing.
 *
 * <ul>
 *   <li><b>Initial state.</b> The built-in policies and the anonymous token ({@link AclBuiltins}), and no bootstrap.
 *       A snapshot written before ACLs restores this state.</li>
 *   <li><b>Uniqueness.</b> Accessor IDs, secret hashes, and policy and role IDs are unique; policy and role names are
 *       unique regardless of case, as Consul's name indexes lower-case them. A created token, policy or role cannot
 *       take an ID in the range kept for built-ins.</li>
 *   <li><b>Links.</b> A token's and a role's links must name policies and roles that exist when they are written, as
 *       Consul's token writer requires. A policy or role that something links to can be deleted (proposal 18): its
 *       links stay stored and are dropped whenever a token or role is read, and grant nothing.</li>
 *   <li><b>What cannot change.</b> A token's secret and expiry; its creation time and installed-from-file mark, which
 *       an update keeps; a built-in policy's rules; and the existence of the built-ins.</li>
 *   <li><b>The lock-out guard</b> (proposal 17). While some token holds {@code acl:write} without an expiry, a command
 *       after which none would is refused with {@link AclRefusal#ACL_LOCKOUT}. Holding it is decided by
 *       {@link AclAuthorizer} over the token's grants, directly and through its roles; identities never grant it. The guard is applied here, as each command applies, so that two commands in flight cannot together
 *       remove the last holder. Consul has no such guard.</li>
 *   <li><b>The bootstrap index</b> counts the bootstraps applied, and is the reset index an operator writes to
 *       {@code acl-bootstrap-reset}. Consul uses the bootstrap's Raft index; the state machine is not given an
 *       entry's index, so a count kept in the replicated state takes its place.</li>
 * </ul>
 *
 * <p>Commands are applied on the state machine's thread. Each builds the next state and publishes it whole, so a
 * reader on another thread sees one state or the next, never a mixture.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
public final class AclState {
    private static final String TOKEN = "AclToken";
    private static final String POLICY = "AclPolicy";
    private static final String ROLE = "AclRole";
    private static final AclRequest ACL_WRITE = AclRequest.single(AclResource.ACL, AclAccess.WRITE);

    private volatile Tables tables = Tables.initial();

    public RaftCommandResult<?> createToken(AclToken token) {
        Tables current = tables;
        RaftCommandResult<?> refused = refusalToCreate(current, token);
        if (refused != null) return refused;
        Draft draft = new Draft(current);
        draft.tokens.put(token.accessorId(), token);
        return commit(draft, token);
    }

    public RaftCommandResult<?> updateToken(AclToken token) {
        Tables current = tables;
        AclToken stored = current.tokens.get(token.accessorId());
        if (stored == null) return new RaftCommandResult.NotFound<>(token.accessorId(), TOKEN);
        if (!stored.secretHash().equals(token.secretHash())) {
            return refused(AclRefusal.SECRET_IMMUTABLE, "Changing a token's secret is not permitted");
        }
        if (!Objects.equals(stored.expirationTime(), token.expirationTime())) {
            return refused(AclRefusal.EXPIRY_IMMUTABLE, "Cannot change expiration time of " + token.accessorId());
        }
        RaftCommandResult<?> refused = refusalForLinks(current, token.policyIds(), token.roleIds());
        if (refused != null) return refused;
        AclToken updated = stored.withChangesOf(token);
        Draft draft = new Draft(current);
        draft.tokens.put(updated.accessorId(), updated);
        return commit(draft, updated);
    }

    public RaftCommandResult<?> deleteToken(String accessorId) {
        Tables current = tables;
        if (AclBuiltins.ANONYMOUS_TOKEN_ID.equals(accessorId)) {
            return refused(AclRefusal.BUILT_IN, "Deletion of the builtin anonymous token is not permitted");
        }
        if (!current.tokens.containsKey(accessorId)) return new RaftCommandResult.NotFound<>(accessorId, TOKEN);
        Draft draft = new Draft(current);
        AclToken removed = draft.tokens.remove(accessorId);
        return commit(draft, removed);
    }

    public RaftCommandResult<?> createPolicy(AclPolicy policy) {
        Tables current = tables;
        if (AclSecrets.isReserved(policy.id())) return reservedId(policy.id());
        if (current.policies.containsKey(policy.id())) {
            return refused(AclRefusal.ID_IN_USE, "A policy with ID " + policy.id() + " already exists");
        }
        if (namedPolicy(current, policy.name()).isPresent()) return nameInUse("policy", policy.name());
        Draft draft = new Draft(current);
        draft.policies.put(policy.id(), policy);
        return commit(draft, policy);
    }

    public RaftCommandResult<?> updatePolicy(AclPolicy policy) {
        Tables current = tables;
        AclPolicy stored = current.policies.get(policy.id());
        if (stored == null) return new RaftCommandResult.NotFound<>(policy.id(), POLICY);
        if (namedPolicy(current, policy.name()).filter(named -> !named.id().equals(policy.id())).isPresent()) {
            return nameInUse("policy", policy.name());
        }
        if (AclBuiltins.isBuiltInPolicy(policy.id()) && !stored.rules().equals(policy.rules())) {
            return refused(AclRefusal.BUILT_IN, "Changing the rules for the builtin " + stored.name()
                    + " policy is not permitted");
        }
        Draft draft = new Draft(current);
        draft.policies.put(policy.id(), policy);
        return commit(draft, policy);
    }

    public RaftCommandResult<?> deletePolicy(String id) {
        Tables current = tables;
        if (AclBuiltins.isBuiltInPolicy(id)) {
            return refused(AclRefusal.BUILT_IN, "Deletion of the builtin " + current.policies.get(id).name()
                    + " policy is not permitted");
        }
        if (!current.policies.containsKey(id)) return new RaftCommandResult.NotFound<>(id, POLICY);
        Draft draft = new Draft(current);
        AclPolicy removed = draft.policies.remove(id);
        return commit(draft, removed);
    }

    public RaftCommandResult<?> createRole(AclRole role) {
        Tables current = tables;
        if (AclSecrets.isReserved(role.id())) return reservedId(role.id());
        if (current.roles.containsKey(role.id())) {
            return refused(AclRefusal.ID_IN_USE, "A role with ID " + role.id() + " already exists");
        }
        if (namedRole(current, role.name()).isPresent()) return nameInUse("role", role.name());
        RaftCommandResult<?> refused = refusalForLinks(current, role.policyIds(), List.of());
        if (refused != null) return refused;
        Draft draft = new Draft(current);
        draft.roles.put(role.id(), role);
        return commit(draft, role);
    }

    public RaftCommandResult<?> updateRole(AclRole role) {
        Tables current = tables;
        if (!current.roles.containsKey(role.id())) return new RaftCommandResult.NotFound<>(role.id(), ROLE);
        if (namedRole(current, role.name()).filter(named -> !named.id().equals(role.id())).isPresent()) {
            return nameInUse("role", role.name());
        }
        RaftCommandResult<?> refused = refusalForLinks(current, role.policyIds(), List.of());
        if (refused != null) return refused;
        Draft draft = new Draft(current);
        draft.roles.put(role.id(), role);
        return commit(draft, role);
    }

    public RaftCommandResult<?> deleteRole(String id) {
        Tables current = tables;
        if (!current.roles.containsKey(id)) return new RaftCommandResult.NotFound<>(id, ROLE);
        Draft draft = new Draft(current);
        AclRole removed = draft.roles.remove(id);
        return commit(draft, removed);
    }

    /**
     * Creates the bootstrap token, as Consul's {@code ACLBootstrap} in {@code agent/consul/state/acl.go} does: once,
     * and again only with the current bootstrap index as {@code resetIndex}. The token is created under the same
     * rules as any other, and a refused bootstrap leaves the index as it was.
     */
    public RaftCommandResult<?> bootstrap(AclToken token, long resetIndex) {
        Tables current = tables;
        if (current.bootstrapIndex > 0) {
            if (resetIndex == 0) {
                return refused(AclRefusal.ACL_BOOTSTRAPPED,
                        "ACL bootstrap no longer allowed (reset index: " + current.bootstrapIndex + ")");
            }
            if (resetIndex != current.bootstrapIndex) {
                return refused(AclRefusal.INVALID_RESET_INDEX, "Invalid ACL bootstrap reset index " + resetIndex
                        + " (reset index: " + current.bootstrapIndex + ")");
            }
        }
        RaftCommandResult<?> refused = refusalToCreate(current, token);
        if (refused != null) return refused;
        Draft draft = new Draft(current);
        draft.tokens.put(token.accessorId(), token);
        draft.bootstrapIndex = current.bootstrapIndex + 1;
        return commit(draft, token);
    }

    /** Deletes those of {@code accessorIds} whose expiry is not after {@code asOf}, answering the IDs it deleted. */
    public RaftCommandResult<?> deleteExpiredTokens(Instant asOf, List<String> accessorIds) {
        Draft draft = new Draft(tables);
        List<String> deleted = new ArrayList<>();
        for (String accessorId : accessorIds) {
            AclToken token = draft.tokens.get(accessorId);
            if (token != null && token.expirationTime() != null && !token.expirationTime().isAfter(asOf)) {
                draft.tokens.remove(accessorId);
                deleted.add(accessorId);
            }
        }
        // A token with an expiry never holds acl:write for the lock-out guard, so this cannot be a lock-out.
        return commit(draft, List.copyOf(deleted));
    }

    Optional<AclToken> token(String accessorId) {
        Tables current = tables;
        if (!AclSecrets.isUuid(accessorId)) return Optional.empty();
        return Optional.ofNullable(current.tokens.get(accessorId.toLowerCase(Locale.ROOT)))
                .map(token -> current.readable(token));
    }

    Optional<AclToken> tokenBySecret(String secret) {
        Tables current = tables;
        if (secret == null) return Optional.empty();
        return Optional.ofNullable(current.accessorsBySecretHash.get(AclSecrets.hash(secret)))
                .map(current.tokens::get).map(token -> current.readable(token));
    }

    List<AclToken> tokens() {
        Tables current = tables;
        return current.tokens.values().stream().map(token -> current.readable(token)).toList();
    }

    Optional<AclPolicy> policy(String id) {
        return AclSecrets.isUuid(id) ? Optional.ofNullable(tables.policies.get(id.toLowerCase(Locale.ROOT)))
                : Optional.empty();
    }

    Optional<AclPolicy> policyByName(String name) {
        return namedPolicy(tables, name);
    }

    List<AclPolicy> policies() {
        return List.copyOf(tables.policies.values());
    }

    Optional<AclRole> role(String id) {
        Tables current = tables;
        if (!AclSecrets.isUuid(id)) return Optional.empty();
        return Optional.ofNullable(current.roles.get(id.toLowerCase(Locale.ROOT))).map(role -> current.readable(role));
    }

    Optional<AclRole> roleByName(String name) {
        Tables current = tables;
        return namedRole(current, name).map(role -> current.readable(role));
    }

    List<AclRole> roles() {
        Tables current = tables;
        return current.roles.values().stream().map(role -> current.readable(role)).toList();
    }

    long bootstrapIndex() {
        return tables.bootstrapIndex;
    }

    List<AclGrant> grants(AclToken token) {
        return tables.grants(token);
    }

    /** The state for a snapshot, with links as they are stored, each list in ID order. */
    public Snapshot snapshot() {
        Tables current = tables;
        return new Snapshot(List.copyOf(current.tokens.values()), List.copyOf(current.policies.values()),
                List.copyOf(current.roles.values()), current.bootstrapIndex);
    }

    /** Restores {@code snapshot}, or the initial state when it is {@code null}, as a snapshot from before ACLs is. */
    public void restore(Snapshot snapshot) {
        tables = snapshot == null ? Tables.initial()
                : Tables.of(snapshot.tokens(), snapshot.policies(), snapshot.roles(), snapshot.bootstrapIndex());
    }

    public void reset() {
        tables = Tables.initial();
    }

    /** The ACL section of a snapshot. Every list is in ID order, so that replicas write the same bytes. */
    public record Snapshot(List<AclToken> tokens, List<AclPolicy> policies, List<AclRole> roles, long bootstrapIndex) {
        public Snapshot {
            tokens = List.copyOf(tokens == null ? List.of() : tokens);
            policies = List.copyOf(policies == null ? List.of() : policies);
            roles = List.copyOf(roles == null ? List.of() : roles);
        }
    }

    private RaftCommandResult<?> commit(Draft draft, Object entity) {
        Tables candidate = draft.freeze();
        if (holdsAclWrite(tables) && !holdsAclWrite(candidate)) {
            return refused(AclRefusal.ACL_LOCKOUT,
                    "No token would hold acl:write without an expiry after this change, so it is refused");
        }
        tables = candidate;
        return new RaftCommandResult.Success<>(entity);
    }

    /**
     * Whether some token in {@code state} holds {@code acl:write} without an expiry, decided by the same authorizer
     * that enforcement uses. No default policy grants {@code acl}, so the one given here changes nothing.
     */
    private static boolean holdsAclWrite(Tables state) {
        for (AclToken token : state.tokens.values()) {
            if (token.expirationTime() == null && AclAuthorizer.decide(state.grants(token), AclDefaultPolicy.DENY,
                    ACL_WRITE).allowed()) {
                return true;
            }
        }
        return false;
    }

    private static RaftCommandResult<?> refusalToCreate(Tables current, AclToken token) {
        if (AclSecrets.isReserved(token.accessorId())) return reservedId(token.accessorId());
        if (current.tokens.containsKey(token.accessorId())) {
            return refused(AclRefusal.ACCESSOR_IN_USE, "A token with accessor ID " + token.accessorId()
                    + " already exists");
        }
        if (current.accessorsBySecretHash.containsKey(token.secretHash())) {
            return refused(AclRefusal.SECRET_IN_USE, "The token's secret is already in use");
        }
        return refusalForLinks(current, token.policyIds(), token.roleIds());
    }

    private static RaftCommandResult<?> refusalForLinks(Tables current, List<String> policyIds, List<String> roleIds) {
        for (String id : policyIds) {
            if (!current.policies.containsKey(id)) {
                return refused(AclRefusal.POLICY_NOT_FOUND, "No such ACL policy with ID " + id);
            }
        }
        for (String id : roleIds) {
            if (!current.roles.containsKey(id)) return refused(AclRefusal.ROLE_NOT_FOUND, "No such ACL role with ID " + id);
        }
        return null;
    }

    private static Optional<AclPolicy> namedPolicy(Tables current, String name) {
        if (name == null) return Optional.empty();
        String key = name.toLowerCase(Locale.ROOT);
        return current.policies.values().stream()
                .filter(policy -> policy.name().toLowerCase(Locale.ROOT).equals(key)).findFirst();
    }

    private static Optional<AclRole> namedRole(Tables current, String name) {
        if (name == null) return Optional.empty();
        String key = name.toLowerCase(Locale.ROOT);
        return current.roles.values().stream().filter(role -> role.name().toLowerCase(Locale.ROOT).equals(key))
                .findFirst();
    }

    private static RaftCommandResult<?> reservedId(String id) {
        return refused(AclRefusal.RESERVED_ID, "UUIDs with the prefix " + AclSecrets.RESERVED_ID_PREFIX
                + " are reserved: " + id);
    }

    private static RaftCommandResult<?> nameInUse(String kind, String name) {
        return refused(AclRefusal.NAME_IN_USE, "A " + kind + " with name \"" + name + "\" already exists");
    }

    private static RaftCommandResult<?> refused(AclRefusal refusal, String message) {
        return new RaftCommandResult.Refused<>(refusal.code(), message);
    }

    /** One whole ACL state, never changed once built, with an index of tokens by their secret's hash. */
    private record Tables(SortedMap<String, AclToken> tokens, SortedMap<String, AclPolicy> policies,
                          SortedMap<String, AclRole> roles, Map<String, String> accessorsBySecretHash,
                          long bootstrapIndex) {
        static Tables initial() {
            return of(List.of(AclBuiltins.anonymousToken()), AclBuiltins.policies(), List.of(), 0);
        }

        static Tables of(Collection<AclToken> tokens, Collection<AclPolicy> policies, Collection<AclRole> roles,
                         long bootstrapIndex) {
            TreeMap<String, AclToken> tokenMap = new TreeMap<>();
            tokens.forEach(token -> tokenMap.put(token.accessorId(), token));
            TreeMap<String, AclPolicy> policyMap = new TreeMap<>();
            policies.forEach(policy -> policyMap.put(policy.id(), policy));
            TreeMap<String, AclRole> roleMap = new TreeMap<>();
            roles.forEach(role -> roleMap.put(role.id(), role));
            return frozen(tokenMap, policyMap, roleMap, bootstrapIndex);
        }

        static Tables frozen(TreeMap<String, AclToken> tokens, TreeMap<String, AclPolicy> policies,
                             TreeMap<String, AclRole> roles, long bootstrapIndex) {
            Map<String, String> bySecretHash = new HashMap<>();
            tokens.values().forEach(token -> bySecretHash.put(token.secretHash(), token.accessorId()));
            return new Tables(Collections.unmodifiableSortedMap(tokens), Collections.unmodifiableSortedMap(policies),
                    Collections.unmodifiableSortedMap(roles), Map.copyOf(bySecretHash), bootstrapIndex);
        }

        /**
         * What {@code token} is granted: its own policies, node identities and service identities, then those of each
         * of its roles, in ID order, leaving out policies and roles that no longer exist.
         */
        List<AclGrant> grants(AclToken token) {
            List<AclGrant> grants = new ArrayList<>();
            addGrants(grants, token.policyIds(), token.nodeIdentities(), token.serviceIdentities(), null);
            for (String roleId : token.roleIds()) {
                AclRole role = roles.get(roleId);
                if (role != null) {
                    addGrants(grants, role.policyIds(), role.nodeIdentities(), role.serviceIdentities(), role.id());
                }
            }
            return List.copyOf(grants);
        }

        private void addGrants(List<AclGrant> grants, List<String> policyIds, List<NodeIdentity> nodeIdentities,
                               List<ServiceIdentity> serviceIdentities, String roleId) {
            for (String policyId : policyIds) {
                AclPolicy policy = policies.get(policyId);
                if (policy != null) grants.add(AclGrant.policy(policy.id(), policy.name(), roleId, policy.rules()));
            }
            nodeIdentities.forEach(identity -> grants.add(AclGrant.of(identity, roleId)));
            serviceIdentities.forEach(identity -> grants.add(AclGrant.of(identity, roleId)));
        }

        AclToken readable(AclToken token) {
            boolean dangling = token.policyIds().stream().anyMatch(id -> !policies.containsKey(id))
                    || token.roleIds().stream().anyMatch(id -> !roles.containsKey(id));
            return dangling ? token.withLinksIn(policies.keySet(), roles.keySet()) : token;
        }

        AclRole readable(AclRole role) {
            boolean dangling = role.policyIds().stream().anyMatch(id -> !policies.containsKey(id));
            return dangling ? role.withLinksIn(policies.keySet()) : role;
        }
    }

    /** The next state while a command builds it. */
    private static final class Draft {
        final TreeMap<String, AclToken> tokens;
        final TreeMap<String, AclPolicy> policies;
        final TreeMap<String, AclRole> roles;
        long bootstrapIndex;

        Draft(Tables from) {
            tokens = new TreeMap<>(from.tokens);
            policies = new TreeMap<>(from.policies);
            roles = new TreeMap<>(from.roles);
            bootstrapIndex = from.bootstrapIndex;
        }

        Tables freeze() {
            return Tables.frozen(tokens, policies, roles, bootstrapIndex);
        }
    }
}

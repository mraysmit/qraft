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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Decides whether a token's grants allow a request (ACL list, Phase 2). It is one pure function, which the server and
 * the client both use, and it answers with what decided, so that an explanation cannot differ from what is enforced
 * (proposal 19).
 *
 * <ol>
 *   <li><b>Which rules match.</b> A rule for the request's resource whose name is the request's exactly, or a prefix
 *       of it; and, for a service, whose tenant and namespace match the request's in the same way.</li>
 *   <li><b>Which ranks first.</b> The name decides first, as in Consul's {@code getPolicy}
 *       ({@code acl/policy_authorizer.go}): an exact name before any prefix, a longer prefix before a shorter. For an
 *       equally specific name, the scope decides, as the owner decided on 2026-11-05: an exact tenant before a prefix,
 *       a longer prefix before a shorter, then the namespace the same way.</li>
 *   <li><b>Equal rules merge</b> across the token's grants, {@code deny} before {@code write} before {@code read}, as
 *       Consul's {@code takesPrecedenceOver} ({@code acl/policy.go}) merges them. The decision names the first grant,
 *       in the order given, whose rule has the merged access.</li>
 *   <li><b>The rule's access decides</b>, as Consul's {@code enforce}: {@code write} allows reading and writing,
 *       {@code read} allows reading and refuses writing, and {@code deny} refuses both. A matched rule never leaves the
 *       request to the default policy.</li>
 *   <li><b>No rule matched:</b> the default policy decides, except that {@code acl} is refused under either, as
 *       Consul's static allow-all authorizer refuses it ({@code acl/static_authorizer.go}).</li>
 * </ol>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
public final class AclAuthorizer {
    private static final int EXACT = Integer.MAX_VALUE;
    private static final Comparator<Rank> RANK = Comparator.comparingInt(Rank::name)
            .thenComparingInt(Rank::tenant).thenComparingInt(Rank::namespace);

    private AclAuthorizer() {
    }

    /** Decides {@code request} for a token holding {@code grants}, under {@code defaultPolicy}. */
    public static AclDecision decide(List<AclGrant> grants, AclDefaultPolicy defaultPolicy, AclRequest request) {
        Objects.requireNonNull(grants, "grants");
        Objects.requireNonNull(defaultPolicy, "defaultPolicy");
        Objects.requireNonNull(request, "request");
        Rank best = null;
        List<Candidate> first = new ArrayList<>();
        for (AclGrant grant : grants) {
            for (AclRule rule : grant.rules().rules()) {
                Rank rank = rank(rule, request);
                if (rank == null) continue;
                int order = best == null ? 1 : RANK.compare(rank, best);
                if (order > 0) {
                    best = rank;
                    first.clear();
                }
                if (order >= 0) first.add(new Candidate(grant.origin(), rule));
            }
        }
        if (first.isEmpty()) {
            boolean allowed = request.resource() != AclResource.ACL && defaultPolicy == AclDefaultPolicy.ALLOW;
            return new AclDecision.ByDefault(allowed, defaultPolicy);
        }
        AclAccess merged = first.getFirst().rule().access();
        for (Candidate candidate : first) merged = merged.merge(candidate.rule().access());
        AclAccess decidingAccess = merged;
        Candidate decided = first.stream().filter(candidate -> candidate.rule().access() == decidingAccess)
                .findFirst().orElseThrow();
        return new AclDecision.ByRule(allows(merged, request.access()), decided.origin(), decided.rule());
    }

    /**
     * Decides write on every name under {@code prefix}'s name, as Consul's {@code KeyWritePrefix} does for a recursive
     * key delete (key/value list, Phase 3): refused when any rule of the grants, exact or prefix, whose name starts
     * with the prefix and whose scope covers the request's, grants less than write; otherwise decided as a write on
     * the prefix itself, by the longest rule that covers it or, with none, by the default policy. The decision rests
     * on the rules, not on the names that exist, so a name created under the prefix later is covered as well.
     */
    public static AclDecision decideWritePrefix(List<AclGrant> grants, AclDefaultPolicy defaultPolicy,
                                                AclRequest prefix) {
        Objects.requireNonNull(grants, "grants");
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.access() != AclAccess.WRITE) throw new IllegalArgumentException("a prefix is decided for write");
        for (AclGrant grant : grants) {
            for (AclRule rule : grant.rules().rules()) {
                if (rule.resource() != prefix.resource() || !rule.name().startsWith(prefix.name())) continue;
                if (rule.resource().scoped() && (match(rule.tenant().name(), rule.tenant().prefix(), prefix.tenant()) < 0
                        || match(rule.namespace().name(), rule.namespace().prefix(), prefix.namespace()) < 0)) {
                    continue;
                }
                if (!allows(rule.access(), AclAccess.WRITE)) return new AclDecision.ByRule(false, grant.origin(), rule);
            }
        }
        return decide(grants, defaultPolicy, prefix);
    }

    /** How specifically {@code rule} matches {@code request}, or {@code null} when it does not match it. */
    private static Rank rank(AclRule rule, AclRequest request) {
        if (rule.resource() != request.resource()) return null;
        int name = rule.resource().named() ? match(rule.name(), rule.prefix(), request.name()) : 0;
        if (name < 0) return null;
        if (!rule.resource().scoped()) return new Rank(name, 0, 0);
        int tenant = match(rule.tenant().name(), rule.tenant().prefix(), request.tenant());
        int namespace = match(rule.namespace().name(), rule.namespace().prefix(), request.namespace());
        return tenant < 0 || namespace < 0 ? null : new Rank(name, tenant, namespace);
    }

    /** {@link #EXACT} for an exact match, the prefix's length for a prefix match, and -1 for no match. */
    private static int match(String pattern, boolean prefix, String value) {
        if (prefix) return value.startsWith(pattern) ? pattern.length() : -1;
        return pattern.equals(value) ? EXACT : -1;
    }

    private static boolean allows(AclAccess granted, AclAccess requested) {
        return switch (granted) {
            case WRITE -> true;
            case READ -> requested == AclAccess.READ;
            case DENY -> false;
        };
    }

    private record Rank(int name, int tenant, int namespace) {
    }

    private record Candidate(AclOrigin origin, AclRule rule) {
    }
}

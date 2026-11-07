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

package dev.mars.qraft.server.acl;

import dev.mars.qraft.common.acl.AclAuthorizer;
import dev.mars.qraft.common.acl.AclDecision;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.acl.AclDenial;
import dev.mars.qraft.common.acl.AclGrant;
import dev.mars.qraft.common.acl.AclRequest;
import dev.mars.qraft.state.acl.AclToken;

import java.util.List;
import java.util.Objects;

/**
 * Who a request acts as (ACL list, Phase 4): a resolved token with what it was granted and the default policy, or,
 * with ACLs disabled, no one in particular, allowed everything.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-06
 * @version 1.0
 */
public final class AclPrincipal {
    private static final AclPrincipal UNRESTRICTED = new AclPrincipal(null, List.of(), null);

    private final String accessorId;
    private final List<AclGrant> grants;
    private final AclDefaultPolicy defaultPolicy;

    private AclPrincipal(String accessorId, List<AclGrant> grants, AclDefaultPolicy defaultPolicy) {
        this.accessorId = accessorId;
        this.grants = List.copyOf(grants);
        this.defaultPolicy = defaultPolicy;
    }

    /** With ACLs disabled: every request is allowed. */
    public static AclPrincipal unrestricted() {
        return UNRESTRICTED;
    }

    /** {@code token}, holding {@code grants}, under {@code defaultPolicy}. */
    public static AclPrincipal of(AclToken token, List<AclGrant> grants, AclDefaultPolicy defaultPolicy) {
        return new AclPrincipal(Objects.requireNonNull(token, "token").accessorId(),
                Objects.requireNonNull(grants, "grants"), Objects.requireNonNull(defaultPolicy, "defaultPolicy"));
    }

    /** Whether ACLs decide this principal's requests; false with ACLs disabled. */
    public boolean enforced() {
        return defaultPolicy != null;
    }

    /** The token's accessor ID, which names it in answers and logs, or {@code null} with ACLs disabled. */
    public String accessorId() {
        return accessorId;
    }

    /** Decides {@code request}, with what decided it; with ACLs disabled, everything is allowed by default. */
    public AclDecision decide(AclRequest request) {
        Objects.requireNonNull(request, "request");
        if (!enforced()) return new AclDecision.ByDefault(true, AclDefaultPolicy.ALLOW);
        return AclAuthorizer.decide(grants, defaultPolicy, request);
    }

    /** Whether {@code request} is allowed. */
    public boolean allows(AclRequest request) {
        return decide(request).allowed();
    }

    /**
     * The refusal of {@code request}, in the words of Consul's {@code PermissionDeniedError}: the accessor ID, the
     * permission it lacks, and what it lacks it on. It never holds the secret.
     */
    public String permissionDenied(AclRequest request) {
        return AclDenial.message(accessorId, request);
    }
}

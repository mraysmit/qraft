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

package dev.mars.qraft.client.acl;

import dev.mars.qraft.common.acl.AclAuthorizer;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.acl.AclDenial;
import dev.mars.qraft.common.acl.AclGrant;
import dev.mars.qraft.common.acl.AclRequest;

import java.util.List;
import java.util.Objects;

/**
 * Whom a request to a client's local API acts as: a token's grants under the default policy, decided by the same
 * {@link AclAuthorizer} the servers use (ACL list, proposal 10), or, with no server to ask, what the down policy says:
 * everything allowed, or everything refused.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
public final class ClientPrincipal {
    private enum Mode { UNRESTRICTED, DENY_ALL, GRANTS }

    private static final ClientPrincipal UNRESTRICTED = new ClientPrincipal(Mode.UNRESTRICTED, null, List.of(), null);
    private static final ClientPrincipal DENY_ALL = new ClientPrincipal(Mode.DENY_ALL, null, List.of(), null);

    private final Mode mode;
    private final String accessorId;
    private final List<AclGrant> grants;
    private final AclDefaultPolicy defaultPolicy;

    private ClientPrincipal(Mode mode, String accessorId, List<AclGrant> grants, AclDefaultPolicy defaultPolicy) {
        this.mode = mode;
        this.accessorId = accessorId;
        this.grants = List.copyOf(grants);
        this.defaultPolicy = defaultPolicy;
    }

    /** With ACLs disabled, or under the {@code allow} down policy with no server: everything is allowed. */
    public static ClientPrincipal unrestricted() {
        return UNRESTRICTED;
    }

    /** Under the {@code deny} down policy with no server: everything is refused. */
    public static ClientPrincipal deniedEverything() {
        return DENY_ALL;
    }

    /** The token {@code accessorId}, holding {@code grants}, under {@code defaultPolicy}. */
    public static ClientPrincipal of(String accessorId, List<AclGrant> grants, AclDefaultPolicy defaultPolicy) {
        return new ClientPrincipal(Mode.GRANTS, accessorId, Objects.requireNonNull(grants, "grants"),
                Objects.requireNonNull(defaultPolicy, "defaultPolicy"));
    }

    /** The token's accessor ID, or {@code null} for a principal no token stands for. */
    public String accessorId() {
        return accessorId;
    }

    /** The grants the token holds. */
    public List<AclGrant> grants() {
        return grants;
    }

    /** Whether {@code request} is allowed. */
    public boolean allows(AclRequest request) {
        Objects.requireNonNull(request, "request");
        return switch (mode) {
            case UNRESTRICTED -> true;
            case DENY_ALL -> false;
            case GRANTS -> AclAuthorizer.decide(grants, defaultPolicy, request).allowed();
        };
    }

    /** The refusal of {@code request}, in Consul's words; a principal no token stands for is named so. */
    public String permissionDenied(AclRequest request) {
        return mode == Mode.DENY_ALL ? AclDenial.message("(no server answered; the down policy is deny)", request)
                : AclDenial.message(accessorId, request);
    }
}

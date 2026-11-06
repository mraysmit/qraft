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

import dev.mars.qraft.common.acl.AclAccess;
import dev.mars.qraft.common.acl.AclRequest;
import dev.mars.qraft.common.acl.AclResource;
import dev.mars.qraft.raft.RemovalAuthorizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * With ACLs enabled, a server removal needs {@code operator:write}, as Consul's {@code RaftRemovePeerByID} does (ACL
 * list, proposal 11). The token is resolved in the deciding server's own applied state, so the leader decides a
 * forwarded removal from what it holds. A refusal is logged at INFO with the accessor ID, never the secret.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-06
 * @version 1.0
 */
public final class AclRemovalAuthorizer {
    private static final Logger LOG = LoggerFactory.getLogger(AclRemovalAuthorizer.class);
    private static final AclRequest OPERATOR_WRITE = AclRequest.single(AclResource.OPERATOR, AclAccess.WRITE);

    private AclRemovalAuthorizer() {
    }

    /** A removal authorizer that asks {@code resolver} for {@code operator:write}. */
    public static RemovalAuthorizer of(AclResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        return token -> {
            Optional<AclPrincipal> principal = resolver.resolve(token);
            if (principal.isEmpty()) {
                LOG.info("ACL refused a server removal: the token was not found");
                return Optional.of(AclResolver.TOKEN_NOT_FOUND_MESSAGE);
            }
            if (principal.get().allows(OPERATOR_WRITE)) return Optional.empty();
            LOG.info("ACL refused a server removal: accessor={} lacks operator:write", principal.get().accessorId());
            return Optional.of(principal.get().permissionDenied(OPERATOR_WRITE));
        };
    }
}

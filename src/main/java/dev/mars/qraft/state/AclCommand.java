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

package dev.mars.qraft.state;

import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.state.acl.AclPolicy;
import dev.mars.qraft.state.acl.AclRole;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Mutations of the replicated ACL state (ACL list, Phase 1). A token carries its secret only as a hash, taken before
 * the command is proposed, and the times the leader stamped into it. The state machine decides each command from what
 * the replicated state holds, so every replica decides the same; {@code QraftStateStore} applies them.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
public sealed interface AclCommand extends RaftCommand
        permits AclCommand.CreateToken, AclCommand.UpdateToken, AclCommand.DeleteToken,
                AclCommand.CreatePolicy, AclCommand.UpdatePolicy, AclCommand.DeletePolicy,
                AclCommand.CreateRole, AclCommand.UpdateRole, AclCommand.DeleteRole,
                AclCommand.Bootstrap, AclCommand.DeleteExpiredTokens {

    record CreateToken(AclToken token) implements AclCommand {
        public CreateToken {
            Objects.requireNonNull(token, "token");
        }
    }

    /** Replaces a token's description, links and identities; its secret and expiry must be the ones it has. */
    record UpdateToken(AclToken token) implements AclCommand {
        public UpdateToken {
            Objects.requireNonNull(token, "token");
        }
    }

    record DeleteToken(String accessorId) implements AclCommand {
        public DeleteToken {
            accessorId = AclSecrets.canonicalId(accessorId, "a token's accessor ID");
        }
    }

    record CreatePolicy(AclPolicy policy) implements AclCommand {
        public CreatePolicy {
            Objects.requireNonNull(policy, "policy");
        }
    }

    record UpdatePolicy(AclPolicy policy) implements AclCommand {
        public UpdatePolicy {
            Objects.requireNonNull(policy, "policy");
        }
    }

    record DeletePolicy(String id) implements AclCommand {
        public DeletePolicy {
            id = AclSecrets.canonicalId(id, "a policy's ID");
        }
    }

    record CreateRole(AclRole role) implements AclCommand {
        public CreateRole {
            Objects.requireNonNull(role, "role");
        }
    }

    record UpdateRole(AclRole role) implements AclCommand {
        public UpdateRole {
            Objects.requireNonNull(role, "role");
        }
    }

    record DeleteRole(String id) implements AclCommand {
        public DeleteRole {
            id = AclSecrets.canonicalId(id, "a role's ID");
        }
    }

    /**
     * Creates the first management token, once, as Consul's {@code ACLBootstrap} does. Both the bootstrap endpoint and
     * a server installing its management file use it (proposal 12). A {@code resetIndex} of zero asks for the first
     * bootstrap; after one, only the current bootstrap index, written by an operator to {@code acl-bootstrap-reset},
     * allows another.
     */
    record Bootstrap(AclToken token, long resetIndex) implements AclCommand {
        public Bootstrap {
            Objects.requireNonNull(token, "token");
            if (resetIndex < 0) throw new IllegalArgumentException("a reset index cannot be negative");
        }
    }

    /**
     * Deletes the named tokens that have expired at {@code asOf}, the time the leader chose, so that no replica reads
     * its own clock. A named token that is gone, has no expiry, or expires later is left as it is.
     */
    record DeleteExpiredTokens(Instant asOf, List<String> accessorIds) implements AclCommand {
        public DeleteExpiredTokens {
            asOf = Instant.ofEpochMilli(Objects.requireNonNull(asOf, "asOf").toEpochMilli());
            TreeSet<String> canonical = new TreeSet<>();
            for (String accessorId : Objects.requireNonNull(accessorIds, "accessorIds")) {
                canonical.add(AclSecrets.canonicalId(accessorId, "a token's accessor ID"));
            }
            accessorIds = List.copyOf(canonical);
        }
    }

    static AclCommand createToken(AclToken token) {
        return new CreateToken(token);
    }

    static AclCommand updateToken(AclToken token) {
        return new UpdateToken(token);
    }

    static AclCommand deleteToken(String accessorId) {
        return new DeleteToken(accessorId);
    }

    static AclCommand createPolicy(AclPolicy policy) {
        return new CreatePolicy(policy);
    }

    static AclCommand updatePolicy(AclPolicy policy) {
        return new UpdatePolicy(policy);
    }

    static AclCommand deletePolicy(String id) {
        return new DeletePolicy(id);
    }

    static AclCommand createRole(AclRole role) {
        return new CreateRole(role);
    }

    static AclCommand updateRole(AclRole role) {
        return new UpdateRole(role);
    }

    static AclCommand deleteRole(String id) {
        return new DeleteRole(id);
    }

    static AclCommand bootstrap(AclToken token, long resetIndex) {
        return new Bootstrap(token, resetIndex);
    }

    static AclCommand deleteExpiredTokens(Instant asOf, List<String> accessorIds) {
        return new DeleteExpiredTokens(asOf, accessorIds);
    }
}

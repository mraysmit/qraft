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
 * Where a token's grant comes from, so that a decision can say what decided it (ACL list, proposal 19): a policy, a
 * node identity or a service identity, each held by the token itself or, when {@code roleId} is set, through that
 * role.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
public sealed interface AclOrigin permits AclOrigin.Policy, AclOrigin.Node, AclOrigin.Service {

    /** The role the grant comes through, or {@code null} when the token holds it itself. */
    String roleId();

    record Policy(String policyId, String policyName, String roleId) implements AclOrigin {
        public Policy {
            Objects.requireNonNull(policyId, "policyId");
            Objects.requireNonNull(policyName, "policyName");
        }
    }

    record Node(NodeIdentity identity, String roleId) implements AclOrigin {
        public Node {
            Objects.requireNonNull(identity, "identity");
        }
    }

    record Service(ServiceIdentity identity, String roleId) implements AclOrigin {
        public Service {
            Objects.requireNonNull(identity, "identity");
        }
    }
}

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

package dev.mars.qraft.server.http;

import dev.mars.qraft.state.acl.AclToken;
import dev.mars.qraft.state.acl.AclView;

import java.util.List;

/**
 * A token as the ACL API answers it, after Consul's token answer in camel case: its policies and roles with their
 * names, its identities, and its times. The secret is shown only in the answer that made it (ACL list, proposal 3);
 * the hash never is.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
record AclTokenAnswer(String accessorId, String secretId, String description, List<Link> policies, List<Link> roles,
                      List<NodeIdentityAnswer> nodeIdentities, List<ServiceIdentityAnswer> serviceIdentities,
                      String createTime, String expirationTime, boolean installedFromFile) {

    record Link(String id, String name) {
    }

    record NodeIdentityAnswer(String nodeName) {
    }

    record ServiceIdentityAnswer(String serviceName, String tenant, String namespace) {
    }

    /** {@code token} with the secret that was just made for it; names come from {@code acl}. */
    static AclTokenAnswer of(AclToken token, String secret, AclView acl) {
        return new AclTokenAnswer(token.accessorId(), secret, token.description(),
                token.policyIds().stream().map(id -> new Link(id, acl.policy(id).map(p -> p.name()).orElse(null)))
                        .toList(),
                token.roleIds().stream().map(id -> new Link(id, acl.role(id).map(r -> r.name()).orElse(null)))
                        .toList(),
                token.nodeIdentities().stream().map(identity -> new NodeIdentityAnswer(identity.nodeName())).toList(),
                token.serviceIdentities().stream().map(identity -> new ServiceIdentityAnswer(identity.serviceName(),
                        identity.tenant(), identity.namespace())).toList(),
                token.createTime().toString(),
                token.expirationTime() == null ? null : token.expirationTime().toString(), token.installedFromFile());
    }

    @Override
    public String toString() {
        return "AclTokenAnswer[accessorId=" + accessorId + ", secretId=<hidden>]";
    }
}

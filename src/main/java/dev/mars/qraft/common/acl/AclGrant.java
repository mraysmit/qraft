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

import java.util.List;
import java.util.Objects;

/**
 * Rules a token holds, with where they come from: a policy's rules, or the rules an identity stands for (ACL list,
 * proposal 9). The authorizer takes a token's grants as they are, so the server and the client decide alike.
 *
 * <p>An identity's rules follow Consul's {@code aclPolicyTemplateNodeIdentity} and
 * {@code aclPolicyTemplateServiceIdentity} in {@code agent/structs/acl_ce.go}, without the sidecar rule, since Qraft
 * has no mesh, and with the scope the owner decided on 2026-11-05:
 *
 * <ul>
 *   <li>a node identity: {@code write} on its node, and {@code read} on every service in every tenant and namespace,
 *       because a node has no scope;</li>
 *   <li>a service identity: {@code write} on its service and {@code read} on every service, both in its own tenant
 *       and namespace, and {@code read} on every node.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
public record AclGrant(AclOrigin origin, AclRules rules) {
    public AclGrant {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(rules, "rules");
    }

    /** A policy's rules, held directly or, with a {@code roleId}, through that role. */
    public static AclGrant policy(String policyId, String policyName, String roleId, AclRules rules) {
        return new AclGrant(new AclOrigin.Policy(policyId, policyName, roleId), rules);
    }

    /** The rules {@code identity} stands for, held directly or, with a {@code roleId}, through that role. */
    public static AclGrant of(NodeIdentity identity, String roleId) {
        return new AclGrant(new AclOrigin.Node(identity, roleId), new AclRules(List.of(
                AclRule.exact(AclResource.NODE, identity.nodeName(), AclAccess.WRITE),
                AclRule.prefix(AclResource.SERVICE, "", AclAccess.READ).in(AclScope.ANY, AclScope.ANY))));
    }

    /** The rules {@code identity} stands for, held directly or, with a {@code roleId}, through that role. */
    public static AclGrant of(ServiceIdentity identity, String roleId) {
        AclScope tenant = new AclScope(identity.tenant(), false);
        AclScope namespace = new AclScope(identity.namespace(), false);
        return new AclGrant(new AclOrigin.Service(identity, roleId), new AclRules(List.of(
                AclRule.exact(AclResource.SERVICE, identity.serviceName(), AclAccess.WRITE).in(tenant, namespace),
                AclRule.prefix(AclResource.SERVICE, "", AclAccess.READ).in(tenant, namespace),
                AclRule.prefix(AclResource.NODE, "", AclAccess.READ))));
    }
}

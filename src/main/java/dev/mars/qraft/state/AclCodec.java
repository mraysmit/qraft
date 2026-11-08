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

import dev.mars.qraft.common.acl.AclAccess;
import dev.mars.qraft.common.acl.AclResource;
import dev.mars.qraft.common.acl.AclRule;
import dev.mars.qraft.common.acl.AclRules;
import dev.mars.qraft.common.acl.AclScope;
import dev.mars.qraft.common.acl.NodeIdentity;
import dev.mars.qraft.common.acl.ServiceIdentity;
import dev.mars.qraft.raft.grpc.AclAccessProto;
import dev.mars.qraft.raft.grpc.AclBootstrapProto;
import dev.mars.qraft.raft.grpc.AclCommandProto;
import dev.mars.qraft.raft.grpc.AclDeleteExpiredTokensProto;
import dev.mars.qraft.raft.grpc.AclNodeIdentityProto;
import dev.mars.qraft.raft.grpc.AclPolicyProto;
import dev.mars.qraft.raft.grpc.AclResourceProto;
import dev.mars.qraft.raft.grpc.AclRoleProto;
import dev.mars.qraft.raft.grpc.AclRuleProto;
import dev.mars.qraft.raft.grpc.AclScopeProto;
import dev.mars.qraft.raft.grpc.AclServiceIdentityProto;
import dev.mars.qraft.raft.grpc.AclTokenProto;
import dev.mars.qraft.state.acl.AclPolicy;
import dev.mars.qraft.state.acl.AclRole;
import dev.mars.qraft.state.acl.AclToken;

import java.time.Instant;

/**
 * Converts {@link AclCommand} to and from its protobuf form in the Raft log. Times are written in milliseconds, a
 * token's expiry and a rule's name keep their absence, and decoding builds the values through their own validation, so
 * an entry that is incomplete, or that holds a resource or access level this server does not know, is refused with
 * {@link IllegalArgumentException} rather than read as something else.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
final class AclCodec {
    private AclCodec() {
    }

    static AclCommandProto toProto(AclCommand command) {
        AclCommandProto.Builder builder = AclCommandProto.newBuilder();
        return switch (command) {
            case AclCommand.CreateToken create -> builder.setCreateToken(toProto(create.token())).build();
            case AclCommand.UpdateToken update -> builder.setUpdateToken(toProto(update.token())).build();
            case AclCommand.DeleteToken delete -> builder.setDeleteToken(delete.accessorId()).build();
            case AclCommand.CreatePolicy create -> builder.setCreatePolicy(toProto(create.policy())).build();
            case AclCommand.UpdatePolicy update -> builder.setUpdatePolicy(toProto(update.policy())).build();
            case AclCommand.DeletePolicy delete -> builder.setDeletePolicy(delete.id()).build();
            case AclCommand.CreateRole create -> builder.setCreateRole(toProto(create.role())).build();
            case AclCommand.UpdateRole update -> builder.setUpdateRole(toProto(update.role())).build();
            case AclCommand.DeleteRole delete -> builder.setDeleteRole(delete.id()).build();
            case AclCommand.Bootstrap bootstrap -> builder.setBootstrap(AclBootstrapProto.newBuilder()
                    .setToken(toProto(bootstrap.token())).setResetIndex(bootstrap.resetIndex())).build();
            case AclCommand.DeleteExpiredTokens expired -> builder.setDeleteExpiredTokens(
                    AclDeleteExpiredTokensProto.newBuilder().setAsOfEpochMs(expired.asOf().toEpochMilli())
                            .addAllAccessorIds(expired.accessorIds())).build();
        };
    }

    static AclCommand fromProto(AclCommandProto proto) {
        return switch (proto.getCommandCase()) {
            case CREATE_TOKEN -> AclCommand.createToken(fromProto(proto.getCreateToken()));
            case UPDATE_TOKEN -> AclCommand.updateToken(fromProto(proto.getUpdateToken()));
            case DELETE_TOKEN -> AclCommand.deleteToken(proto.getDeleteToken());
            case CREATE_POLICY -> AclCommand.createPolicy(fromProto(proto.getCreatePolicy()));
            case UPDATE_POLICY -> AclCommand.updatePolicy(fromProto(proto.getUpdatePolicy()));
            case DELETE_POLICY -> AclCommand.deletePolicy(proto.getDeletePolicy());
            case CREATE_ROLE -> AclCommand.createRole(fromProto(proto.getCreateRole()));
            case UPDATE_ROLE -> AclCommand.updateRole(fromProto(proto.getUpdateRole()));
            case DELETE_ROLE -> AclCommand.deleteRole(proto.getDeleteRole());
            case BOOTSTRAP -> AclCommand.bootstrap(fromProto(proto.getBootstrap().getToken()),
                    proto.getBootstrap().getResetIndex());
            case DELETE_EXPIRED_TOKENS -> AclCommand.deleteExpiredTokens(
                    Instant.ofEpochMilli(proto.getDeleteExpiredTokens().getAsOfEpochMs()),
                    proto.getDeleteExpiredTokens().getAccessorIdsList());
            case COMMAND_NOT_SET -> throw new IllegalArgumentException("An ACL entry holds no command");
        };
    }

    static AclTokenProto toProto(AclToken token) {
        AclTokenProto.Builder builder = AclTokenProto.newBuilder()
                .setAccessorId(token.accessorId())
                .setSecretHash(token.secretHash())
                .setDescription(token.description())
                .addAllPolicyIds(token.policyIds())
                .addAllRoleIds(token.roleIds())
                .addAllNodeIdentities(token.nodeIdentities().stream().map(AclCodec::toProto).toList())
                .addAllServiceIdentities(token.serviceIdentities().stream().map(AclCodec::toProto).toList())
                .setCreateTimeEpochMs(token.createTime().toEpochMilli())
                .setInstalledFromFile(token.installedFromFile());
        if (token.expirationTime() != null) builder.setExpirationTimeEpochMs(token.expirationTime().toEpochMilli());
        return builder.build();
    }

    static AclToken fromProto(AclTokenProto proto) {
        return new AclToken(proto.getAccessorId(), proto.getSecretHash(), proto.getDescription(),
                proto.getPolicyIdsList(), proto.getRoleIdsList(),
                proto.getNodeIdentitiesList().stream().map(AclCodec::fromProto).toList(),
                proto.getServiceIdentitiesList().stream().map(AclCodec::fromProto).toList(),
                Instant.ofEpochMilli(proto.getCreateTimeEpochMs()),
                proto.hasExpirationTimeEpochMs() ? Instant.ofEpochMilli(proto.getExpirationTimeEpochMs()) : null,
                proto.getInstalledFromFile());
    }

    private static AclPolicyProto toProto(AclPolicy policy) {
        return AclPolicyProto.newBuilder()
                .setId(policy.id())
                .setName(policy.name())
                .setDescription(policy.description())
                .addAllRules(policy.rules().rules().stream().map(AclCodec::toProto).toList())
                .build();
    }

    private static AclPolicy fromProto(AclPolicyProto proto) {
        return new AclPolicy(proto.getId(), proto.getName(), proto.getDescription(),
                new AclRules(proto.getRulesList().stream().map(AclCodec::fromProto).toList()));
    }

    private static AclRoleProto toProto(AclRole role) {
        return AclRoleProto.newBuilder()
                .setId(role.id())
                .setName(role.name())
                .setDescription(role.description())
                .addAllPolicyIds(role.policyIds())
                .addAllNodeIdentities(role.nodeIdentities().stream().map(AclCodec::toProto).toList())
                .addAllServiceIdentities(role.serviceIdentities().stream().map(AclCodec::toProto).toList())
                .build();
    }

    private static AclRole fromProto(AclRoleProto proto) {
        return new AclRole(proto.getId(), proto.getName(), proto.getDescription(), proto.getPolicyIdsList(),
                proto.getNodeIdentitiesList().stream().map(AclCodec::fromProto).toList(),
                proto.getServiceIdentitiesList().stream().map(AclCodec::fromProto).toList());
    }

    private static AclRuleProto toProto(AclRule rule) {
        AclRuleProto.Builder builder = AclRuleProto.newBuilder()
                .setResource(toProto(rule.resource()))
                .setPrefix(rule.prefix())
                .setAccess(toProto(rule.access()));
        if (rule.name() != null) builder.setName(rule.name());
        if (rule.tenant() != null) builder.setTenant(toProto(rule.tenant()));
        if (rule.namespace() != null) builder.setNamespace(toProto(rule.namespace()));
        return builder.build();
    }

    private static AclRule fromProto(AclRuleProto proto) {
        return new AclRule(fromProto(proto.getResource()), proto.hasName() ? proto.getName() : null,
                proto.getPrefix(), fromProto(proto.getAccess()),
                proto.hasTenant() ? fromProto(proto.getTenant()) : null,
                proto.hasNamespace() ? fromProto(proto.getNamespace()) : null);
    }

    private static AclScopeProto toProto(AclScope scope) {
        return AclScopeProto.newBuilder().setName(scope.name()).setPrefix(scope.prefix()).build();
    }

    private static AclScope fromProto(AclScopeProto proto) {
        return new AclScope(proto.getName(), proto.getPrefix());
    }

    private static AclNodeIdentityProto toProto(NodeIdentity identity) {
        return AclNodeIdentityProto.newBuilder().setNodeName(identity.nodeName()).build();
    }

    private static NodeIdentity fromProto(AclNodeIdentityProto proto) {
        return new NodeIdentity(proto.getNodeName());
    }

    private static AclServiceIdentityProto toProto(ServiceIdentity identity) {
        return AclServiceIdentityProto.newBuilder().setServiceName(identity.serviceName())
                .setTenant(identity.tenant()).setNamespace(identity.namespace()).build();
    }

    private static ServiceIdentity fromProto(AclServiceIdentityProto proto) {
        return new ServiceIdentity(proto.getServiceName(), proto.getTenant(), proto.getNamespace());
    }

    private static AclResourceProto toProto(AclResource resource) {
        return switch (resource) {
            case NODE -> AclResourceProto.ACL_RESOURCE_NODE;
            case SERVICE -> AclResourceProto.ACL_RESOURCE_SERVICE;
            case CLIENT -> AclResourceProto.ACL_RESOURCE_CLIENT;
            case OPERATOR -> AclResourceProto.ACL_RESOURCE_OPERATOR;
            case ACL -> AclResourceProto.ACL_RESOURCE_ACL;
            case KEY -> AclResourceProto.ACL_RESOURCE_KEY;
            case SESSION -> AclResourceProto.ACL_RESOURCE_SESSION;
        };
    }

    private static AclResource fromProto(AclResourceProto proto) {
        return switch (proto) {
            case ACL_RESOURCE_NODE -> AclResource.NODE;
            case ACL_RESOURCE_SERVICE -> AclResource.SERVICE;
            case ACL_RESOURCE_CLIENT -> AclResource.CLIENT;
            case ACL_RESOURCE_OPERATOR -> AclResource.OPERATOR;
            case ACL_RESOURCE_ACL -> AclResource.ACL;
            case ACL_RESOURCE_KEY -> AclResource.KEY;
            case ACL_RESOURCE_SESSION -> AclResource.SESSION;
            case ACL_RESOURCE_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalArgumentException("An ACL rule names an unknown resource: " + proto);
        };
    }

    private static AclAccessProto toProto(AclAccess access) {
        return switch (access) {
            case READ -> AclAccessProto.ACL_ACCESS_READ;
            case WRITE -> AclAccessProto.ACL_ACCESS_WRITE;
            case DENY -> AclAccessProto.ACL_ACCESS_DENY;
        };
    }

    private static AclAccess fromProto(AclAccessProto proto) {
        return switch (proto) {
            case ACL_ACCESS_READ -> AclAccess.READ;
            case ACL_ACCESS_WRITE -> AclAccess.WRITE;
            case ACL_ACCESS_DENY -> AclAccess.DENY;
            case ACL_ACCESS_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalArgumentException("An ACL rule grants an unknown access: " + proto);
        };
    }
}

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

import dev.mars.qraft.raft.grpc.AuthCommandProto;
import dev.mars.qraft.raft.grpc.AuthMethodProto;
import dev.mars.qraft.raft.grpc.BindingRuleProto;
import dev.mars.qraft.raft.grpc.JwtConfigProto;
import dev.mars.qraft.raft.grpc.LoginProto;
import dev.mars.qraft.state.acl.AuthCommand;
import dev.mars.qraft.state.acl.AuthMethod;
import dev.mars.qraft.state.acl.BindType;
import dev.mars.qraft.state.acl.BindingRule;
import dev.mars.qraft.state.acl.JwtConfig;

import java.time.Duration;

/**
 * The protobuf form of {@link AuthCommand} (authentication list, Phase 1); durations in milliseconds, and a login's
 * token in the ACL commands' own form.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
final class AuthCodec {
    private AuthCodec() { }

    static AuthCommandProto toProto(AuthCommand command) {
        AuthCommandProto.Builder proto = AuthCommandProto.newBuilder();
        switch (command) {
            case AuthCommand.CreateMethod create -> proto.setCreateMethod(method(create.method()));
            case AuthCommand.UpdateMethod update -> proto.setUpdateMethod(method(update.method()));
            case AuthCommand.DeleteMethod delete -> proto.setDeleteMethod(delete.name());
            case AuthCommand.CreateBindingRule create -> proto.setCreateBindingRule(rule(create.rule()));
            case AuthCommand.UpdateBindingRule update -> proto.setUpdateBindingRule(rule(update.rule()));
            case AuthCommand.DeleteBindingRule delete -> proto.setDeleteBindingRule(delete.id());
            case AuthCommand.Login login -> proto.setLogin(LoginProto.newBuilder()
                    .setToken(AclCodec.toProto(login.token())).setMethod(login.method()));
            case AuthCommand.Logout logout -> proto.setLogout(logout.accessorId());
        }
        return proto.build();
    }

    static AuthCommand fromProto(AuthCommandProto proto) {
        return switch (proto.getCommandCase()) {
            case CREATE_METHOD -> AuthCommand.createMethod(method(proto.getCreateMethod()));
            case UPDATE_METHOD -> AuthCommand.updateMethod(method(proto.getUpdateMethod()));
            case DELETE_METHOD -> AuthCommand.deleteMethod(proto.getDeleteMethod());
            case CREATE_BINDING_RULE -> AuthCommand.createBindingRule(rule(proto.getCreateBindingRule()));
            case UPDATE_BINDING_RULE -> AuthCommand.updateBindingRule(rule(proto.getUpdateBindingRule()));
            case DELETE_BINDING_RULE -> AuthCommand.deleteBindingRule(proto.getDeleteBindingRule());
            case LOGIN -> AuthCommand.login(AclCodec.fromProto(proto.getLogin().getToken()), proto.getLogin().getMethod());
            case LOGOUT -> AuthCommand.logout(proto.getLogout());
            case COMMAND_NOT_SET -> throw new IllegalArgumentException("an auth command holds no change");
        };
    }

    private static AuthMethodProto method(AuthMethod method) {
        AuthMethodProto.Builder proto = AuthMethodProto.newBuilder().setName(method.name()).setType(method.type())
                .setDescription(method.description()).setMaxTokenTtlMs(method.maxTokenTtl().toMillis());
        switch (method.config()) {
            case JwtConfig config -> proto.setConfig(JwtConfigProto.newBuilder()
                    .addAllPublicKeysPem(config.publicKeysPem())
                    .setBoundIssuer(config.boundIssuer()).addAllBoundAudiences(config.boundAudiences())
                    .putAllClaimMappings(config.claimMappings()).putAllListClaimMappings(config.listClaimMappings())
                    .setExpirationLeewayMs(config.expirationLeeway().toMillis())
                    .setNotBeforeLeewayMs(config.notBeforeLeeway().toMillis())
                    .setClockSkewLeewayMs(config.clockSkewLeeway().toMillis()));
            case dev.mars.qraft.state.acl.KubernetesConfig config -> proto.setKubernetesConfig(
                    dev.mars.qraft.raft.grpc.KubernetesConfigProto.newBuilder().setHost(config.host())
                            .setCaCertPem(config.caCertPem()).setServiceAccountJwt(config.serviceAccountJwt()));
        }
        return proto.build();
    }

    private static AuthMethod method(AuthMethodProto proto) {
        dev.mars.qraft.state.acl.AuthMethodConfig decoded;
        if (proto.hasKubernetesConfig()) {
            var kubernetes = proto.getKubernetesConfig();
            decoded = new dev.mars.qraft.state.acl.KubernetesConfig(kubernetes.getHost(), kubernetes.getCaCertPem(),
                    kubernetes.getServiceAccountJwt());
        } else {
            JwtConfigProto config = proto.getConfig();
            decoded = new JwtConfig(config.getPublicKeysPemList(), config.getBoundIssuer(),
                    config.getBoundAudiencesList(), config.getClaimMappingsMap(), config.getListClaimMappingsMap(),
                    Duration.ofMillis(config.getExpirationLeewayMs()), Duration.ofMillis(config.getNotBeforeLeewayMs()),
                    Duration.ofMillis(config.getClockSkewLeewayMs()));
        }
        return new AuthMethod(proto.getName(), proto.getType(), proto.getDescription(),
                Duration.ofMillis(proto.getMaxTokenTtlMs()), decoded, 0, 0);
    }

    private static BindingRuleProto rule(BindingRule rule) {
        return BindingRuleProto.newBuilder().setId(rule.id()).setDescription(rule.description())
                .setAuthMethod(rule.authMethod()).setSelector(rule.selector()).setBindType(rule.bindType().apiName())
                .setBindName(rule.bindName()).build();
    }

    private static BindingRule rule(BindingRuleProto proto) {
        return new BindingRule(proto.getId(), proto.getDescription(), proto.getAuthMethod(), proto.getSelector(),
                BindType.ofApiName(proto.getBindType()), proto.getBindName(), 0, 0);
    }
}

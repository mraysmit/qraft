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

package dev.mars.qraft.state.tenancy;

import dev.mars.qraft.raft.grpc.NamespaceProto;
import dev.mars.qraft.raft.grpc.TenancyCommandProto;
import dev.mars.qraft.raft.grpc.TenantProto;

/**
 * The protobuf form of {@link TenancyCommand} (tenancy list, Phase 1).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class TenancyCodec {
    private TenancyCodec() { }

    public static TenancyCommandProto toProto(TenancyCommand command) {
        TenancyCommandProto.Builder proto = TenancyCommandProto.newBuilder();
        switch (command) {
            case TenancyCommand.CreateTenant create -> proto.setCreateTenant(tenant(create.name(), create.description()));
            case TenancyCommand.UpdateTenant update -> proto.setUpdateTenant(tenant(update.name(), update.description()));
            case TenancyCommand.DeleteTenant delete -> proto.setDeleteTenant(tenant(delete.name(), ""));
            case TenancyCommand.CreateNamespace create ->
                    proto.setCreateNamespace(namespace(create.tenant(), create.name(), create.description()));
            case TenancyCommand.UpdateNamespace update ->
                    proto.setUpdateNamespace(namespace(update.tenant(), update.name(), update.description()));
            case TenancyCommand.DeleteNamespace delete ->
                    proto.setDeleteNamespace(namespace(delete.tenant(), delete.name(), ""));
        }
        return proto.build();
    }

    public static TenancyCommand fromProto(TenancyCommandProto proto) {
        return switch (proto.getCommandCase()) {
            case CREATE_TENANT -> new TenancyCommand.CreateTenant(proto.getCreateTenant().getName(),
                    proto.getCreateTenant().getDescription());
            case UPDATE_TENANT -> new TenancyCommand.UpdateTenant(proto.getUpdateTenant().getName(),
                    proto.getUpdateTenant().getDescription());
            case DELETE_TENANT -> new TenancyCommand.DeleteTenant(proto.getDeleteTenant().getName());
            case CREATE_NAMESPACE -> new TenancyCommand.CreateNamespace(proto.getCreateNamespace().getTenant(),
                    proto.getCreateNamespace().getName(), proto.getCreateNamespace().getDescription());
            case UPDATE_NAMESPACE -> new TenancyCommand.UpdateNamespace(proto.getUpdateNamespace().getTenant(),
                    proto.getUpdateNamespace().getName(), proto.getUpdateNamespace().getDescription());
            case DELETE_NAMESPACE -> new TenancyCommand.DeleteNamespace(proto.getDeleteNamespace().getTenant(),
                    proto.getDeleteNamespace().getName());
            case COMMAND_NOT_SET -> throw new IllegalArgumentException("a tenancy command holds no change");
        };
    }

    private static TenantProto tenant(String name, String description) {
        return TenantProto.newBuilder().setName(name).setDescription(description).build();
    }

    private static NamespaceProto namespace(String tenant, String name, String description) {
        return NamespaceProto.newBuilder().setTenant(tenant).setName(name).setDescription(description).build();
    }
}

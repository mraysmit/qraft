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

package dev.mars.qraft.controller.state;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstance;
import dev.mars.qraft.controller.raft.grpc.*;

/**
 * Facade for converting between Java command objects and Protobuf-encoded bytes.
 * Replaces Java serialization (ObjectOutputStream/ObjectInputStream) with
 * version-safe Protobuf encoding for Raft log entry command payloads.
 *
 * <p>Delegates to domain-specific codecs for supported {@link RaftCommand} subtypes:
 * <ul>
 *   <li>{@link AgentCodec} — {@link AgentCommand}, AgentInfo, AgentCapabilities, AgentStatus</li>
 *   <li>Inline mapping — {@link DistributedStateRaftCommand} via SystemMetadata protobuf envelope</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-02-15
 */
public final class ProtobufCommandCodec {

    private ProtobufCommandCodec() {
        // Utility class — not instantiable
    }

    /**
     * Serialize a state machine command to Protobuf-encoded bytes.
     *
    * @param command the command object (one of the supported command types, or null for no-op)
     * @return Protobuf-encoded bytes as ByteString
     */
    public static ByteString serialize(RaftCommand command) {
        if (command == null) {
            // No-op entry: encode as empty RaftCommandMessage (no oneof field set)
            return RaftCommandMessage.getDefaultInstance().toByteString();
        }
        RaftCommandMessage raftMessage = switch (command) {
            case AgentCommand cmd -> RaftCommandMessage.newBuilder()
                    .setAgentCommand(AgentCodec.toProto(cmd)).build();
            case DistributedStateRaftCommand cmd -> RaftCommandMessage.newBuilder()
                .setSystemMetadataCommand(toSystemMetadataProto(cmd.delegate())).build();
            case CatalogCommand cmd -> RaftCommandMessage.newBuilder()
                    .setCatalogCommand(toCatalogProto(cmd)).build();
                default -> throw new IllegalArgumentException(
                    "Unsupported RaftCommand type for protobuf codec: " + command.getClass().getName());
        };
        return raftMessage.toByteString();
    }

    /**
     * Deserialize Protobuf-encoded bytes back to a state machine command object.
     *
     * @param data Protobuf-encoded bytes
     * @return the deserialized command object, or null for no-op entries
     * @throws RuntimeException if the data cannot be parsed
     */
    public static RaftCommand deserialize(ByteString data) {
        try {
            RaftCommandMessage raftMessage = RaftCommandMessage.parseFrom(data);
            return switch (raftMessage.getCommandCase()) {
                case AGENT_COMMAND -> AgentCodec.fromProto(raftMessage.getAgentCommand());
                case SYSTEM_METADATA_COMMAND -> fromSystemMetadataProto(raftMessage.getSystemMetadataCommand());
                case CATALOG_COMMAND -> fromCatalogProto(raftMessage.getCatalogCommand());
                case COMMAND_NOT_SET -> null; // No-op entry
                default -> throw new IllegalArgumentException(
                        "Unsupported protobuf command case: " + raftMessage.getCommandCase());
            };
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException("Failed to deserialize Protobuf command", e);
        }
    }

    private static SystemMetadataCommandProto toSystemMetadataProto(DistributedStateCommand cmd) {
        var builder = SystemMetadataCommandProto.newBuilder().setKey(cmd.key());
        return switch (cmd) {
            case DistributedStateCommand.Put put -> builder
                    .setType(SystemMetadataCommandType.SYSTEM_METADATA_CMD_SET)
                    .setValue(put.value())
                    .build();
            case DistributedStateCommand.Delete ignored -> builder
                    .setType(SystemMetadataCommandType.SYSTEM_METADATA_CMD_DELETE)
                    .build();
        };
    }

    private static DistributedStateRaftCommand fromSystemMetadataProto(SystemMetadataCommandProto proto) {
        DistributedStateCommand delegate = switch (proto.getType()) {
            case SYSTEM_METADATA_CMD_SET -> DistributedStateCommand.put(proto.getKey(), proto.getValue());
            case SYSTEM_METADATA_CMD_DELETE -> DistributedStateCommand.delete(proto.getKey());
            default -> throw new IllegalArgumentException("Unknown SystemMetadataCommandType: " + proto.getType());
        };
        return new DistributedStateRaftCommand(delegate);
    }

    private static CatalogCommandProto toCatalogProto(CatalogCommand command) {
        var builder = CatalogCommandProto.newBuilder();
        return switch (command) {
            case CatalogCommand.Register register -> builder
                    .setType(CatalogCommandType.CATALOG_CMD_REGISTER)
                    .setServiceId(register.instance().serviceId())
                    .setInstance(toServiceInstanceProto(register.instance()))
                    .build();
            case CatalogCommand.Deregister deregister -> builder
                    .setType(CatalogCommandType.CATALOG_CMD_DEREGISTER)
                    .setServiceId(deregister.serviceId())
                    .setNodeId(deregister.nodeId())
                    .setTenantId(deregister.tenantId())
                    .setNamespace(deregister.namespace())
                    .build();
        };
    }

    private static CatalogCommand fromCatalogProto(CatalogCommandProto proto) {
        return switch (proto.getType()) {
            case CATALOG_CMD_REGISTER -> CatalogCommand.register(fromServiceInstanceProto(proto.getInstance()));
            case CATALOG_CMD_DEREGISTER -> proto.getNodeId().isBlank()
                    ? CatalogCommand.deregister(proto.getServiceId())
                    : CatalogCommand.deregister(new dev.mars.qraft.catalog.ServiceInstanceId(
                            scopeOrDefault(proto.getTenantId()), scopeOrDefault(proto.getNamespace()),
                            proto.getNodeId(), proto.getServiceId()));
            default -> throw new IllegalArgumentException("Unknown CatalogCommandType: " + proto.getType());
        };
    }

    private static ServiceInstanceProto toServiceInstanceProto(ServiceInstance instance) {
        return ServiceInstanceProto.newBuilder()
                .setServiceId(instance.serviceId())
                .setServiceName(instance.serviceName())
                .setNodeId(instance.nodeId())
                .setAddress(instance.address())
                .setPort(instance.port())
                .addAllTags(instance.tags())
                .putAllMetadata(instance.metadata())
                .setHealth(instance.health().name())
                .setTenantId(instance.tenantId())
                .setNamespace(instance.namespace())
                .setDatacenter(instance.datacenter())
                .setRegion(instance.region())
                .setEnabled(instance.enabled())
                .build();
    }

    private static ServiceInstance fromServiceInstanceProto(ServiceInstanceProto proto) {
        return new ServiceInstance(proto.getServiceId(), proto.getServiceName(), proto.getNodeId(),
                proto.getAddress(), proto.getPort(), proto.getTagsList(), proto.getMetadataMap(),
                ServiceHealth.valueOf(proto.getHealth()),
                scopeOrDefault(proto.getTenantId()), scopeOrDefault(proto.getNamespace()),
                proto.getDatacenter(), proto.getRegion(),
                !proto.hasEnabled() || proto.getEnabled());
    }

    private static String scopeOrDefault(String value) {
        return value == null || value.isBlank() ? ServiceInstance.DEFAULT_SCOPE : value;
    }
}

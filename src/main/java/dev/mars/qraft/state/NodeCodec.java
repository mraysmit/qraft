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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.ClientCapabilities;
import dev.mars.qraft.common.ClientInfo;
import dev.mars.qraft.common.ClientNetworkInfo;
import dev.mars.qraft.common.ClientStatus;
import dev.mars.qraft.common.ClientSystemInfo;
import dev.mars.qraft.raft.grpc.ClientCapabilitiesProto;
import dev.mars.qraft.raft.grpc.ClientCommandProto;
import dev.mars.qraft.raft.grpc.ClientCommandType;
import dev.mars.qraft.raft.grpc.ClientInfoProto;
import dev.mars.qraft.raft.grpc.ClientNetworkInfoProto;
import dev.mars.qraft.raft.grpc.ClientStatusProto;
import dev.mars.qraft.raft.grpc.ClientSystemInfoProto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;

/**
 * Protobuf codec for client-related types: {@link ClientCommand},
 * {@link ClientInfo}, {@link ClientCapabilities}, {@link ClientSystemInfo},
 * {@link ClientNetworkInfo}, and {@link ClientStatus}.
 *
 * <p>Package-private utility class used by {@link ProtobufCommandCodec}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025
 */
final class ClientCodec {

    private ClientCodec() {
    }

    // ── Command ─────────────────────────────────────────────────

    static ClientCommandProto toProto(ClientCommand cmd) {
        ClientCommandProto.Builder builder = ClientCommandProto.newBuilder()
                .setClientId(cmd.clientId())
                .setTimestampEpochMs(cmd.timestamp().toEpochMilli());

        switch (cmd) {
            case ClientCommand.Register r -> {
                builder.setType(ClientCommandType.CLIENT_CMD_REGISTER);
                builder.setClientInfo(toProto(r.clientInfo()));
            }
            case ClientCommand.Deregister ignored -> {
                builder.setType(ClientCommandType.CLIENT_CMD_DEREGISTER);
            }
            case ClientCommand.UpdateStatus u -> {
                builder.setType(ClientCommandType.CLIENT_CMD_UPDATE_STATUS);
                builder.setNewStatus(toProto(u.newStatus()));
                builder.setExpectedStatus(toProto(u.expectedStatus()));
            }
            case ClientCommand.UpdateCapabilities c -> {
                builder.setType(ClientCommandType.CLIENT_CMD_UPDATE_CAPABILITIES);
                builder.setNewCapabilities(toProto(c.newCapabilities()));
            }
            case ClientCommand.Expire e -> {
                builder.setType(ClientCommandType.CLIENT_CMD_EXPIRE);
                builder.setExpectedLastContactEpochMs(e.expectedLastContact().toEpochMilli());
                builder.setReap(e.reap());
            }
            case ClientCommand.Heartbeat h -> {
                builder.setType(ClientCommandType.CLIENT_CMD_HEARTBEAT);
                builder.setSequenceNumber(h.sequenceNumber());
                if (h.registrationId() != null) builder.setRegistrationId(h.registrationId());
                if (h.status() != null) {
                    builder.setNewStatus(toProto(h.status()));
                }
            }
        }

        return builder.build();
    }

    static ClientCommand fromProto(ClientCommandProto proto) {
        // Decoding must never read the local clock: every replica and every replay must see one value.
        Instant timestamp = Instant.ofEpochMilli(proto.getTimestampEpochMs());
        ClientStatus newStatus = proto.getNewStatus() != ClientStatusProto.CLIENT_STATUS_UNSPECIFIED
                ? fromProto(proto.getNewStatus()) : null;
        return switch (proto.getType()) {
            case CLIENT_CMD_REGISTER -> new ClientCommand.Register(
                    proto.getClientId(), fromProto(proto.getClientInfo()), timestamp);
            case CLIENT_CMD_DEREGISTER -> new ClientCommand.Deregister(
                    proto.getClientId(), timestamp);
            case CLIENT_CMD_UPDATE_STATUS -> {
                yield new ClientCommand.UpdateStatus(
                        proto.getClientId(), fromProto(proto.getExpectedStatus()), newStatus, timestamp);
            }
            case CLIENT_CMD_UPDATE_CAPABILITIES -> new ClientCommand.UpdateCapabilities(
                    proto.getClientId(), fromProto(proto.getNewCapabilities()), timestamp);
            case CLIENT_CMD_HEARTBEAT -> new ClientCommand.Heartbeat(
                    proto.getClientId(), newStatus, timestamp, proto.getSequenceNumber(),
                    proto.getRegistrationId().isEmpty() ? null : proto.getRegistrationId());
            case CLIENT_CMD_EXPIRE -> new ClientCommand.Expire(proto.getClientId(),
                    Instant.ofEpochMilli(proto.getExpectedLastContactEpochMs()), proto.getReap(), timestamp);
            default -> throw new IllegalArgumentException("Unknown ClientCommandType: " + proto.getType());
        };
    }

    private static final ObjectMapper CAPABILITY_JSON = new ObjectMapper();

    private static String toJson(Object value) {
        try {
            return CAPABILITY_JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Custom capability value is not JSON-serializable", error);
        }
    }

    private static Object fromJson(String json) {
        try {
            return CAPABILITY_JSON.readValue(json, Object.class);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Malformed custom capability value", error);
        }
    }

    // ── Domain models ───────────────────────────────────────────

    private static ClientInfoProto toProto(ClientInfo info) {
        ClientInfoProto.Builder builder = ClientInfoProto.newBuilder()
                .setPort(info.getPort());
        Optional.ofNullable(info.getClientId()).ifPresent(builder::setClientId);
        Optional.ofNullable(info.getHostname()).ifPresent(builder::setHostname);
        Optional.ofNullable(info.getAddress()).ifPresent(builder::setAddress);
        Optional.ofNullable(info.getCapabilities()).ifPresent(c -> builder.setCapabilities(toProto(c)));
        Optional.ofNullable(info.getStatus()).ifPresent(s -> builder.setStatus(toProto(s)));
        Optional.ofNullable(info.getRegistrationTime()).ifPresent(t -> builder.setRegistrationTimeEpochMs(t.toEpochMilli()));
        Optional.ofNullable(info.getLastHeartbeat()).ifPresent(t -> builder.setLastHeartbeatEpochMs(t.toEpochMilli()));
        Optional.ofNullable(info.getVersion()).ifPresent(builder::setVersion);
        Optional.ofNullable(info.getRegion()).ifPresent(builder::setRegion);
        Optional.ofNullable(info.getDatacenter()).ifPresent(builder::setDatacenter);
        Optional.ofNullable(info.getMetadata()).ifPresent(builder::putAllMetadata);
        return builder.build();
    }

    private static ClientInfo fromProto(ClientInfoProto proto) {
        ClientInfo info = new ClientInfo(
                proto.getClientId(),
                proto.hasHostname() ? proto.getHostname() : null,
                proto.hasAddress() ? proto.getAddress() : null,
                proto.getPort());
        if (proto.hasCapabilities()) {
            info.setCapabilities(fromProto(proto.getCapabilities()));
        }
        if (proto.getStatus() != ClientStatusProto.CLIENT_STATUS_UNSPECIFIED) {
            info.setStatus(fromProto(proto.getStatus()));
        }
        // The ClientInfo constructor stamps the local clock; a decoded value must come from the entry only.
        info.setRegistrationTime(proto.getRegistrationTimeEpochMs() > 0
                ? Instant.ofEpochMilli(proto.getRegistrationTimeEpochMs()) : null);
        if (proto.getLastHeartbeatEpochMs() > 0) {
            info.setLastHeartbeat(Instant.ofEpochMilli(proto.getLastHeartbeatEpochMs()));
        }
        info.setVersion(proto.hasVersion() ? proto.getVersion() : null);
        info.setRegion(proto.hasRegion() ? proto.getRegion() : null);
        info.setDatacenter(proto.hasDatacenter() ? proto.getDatacenter() : null);
        if (proto.getMetadataCount() > 0) {
            info.setMetadata(new HashMap<>(proto.getMetadataMap()));
        }
        return info;
    }

    private static ClientCapabilitiesProto toProto(ClientCapabilities caps) {
        ClientCapabilitiesProto.Builder builder = ClientCapabilitiesProto.newBuilder()
                .addAllSupportedServices(caps.getSupportedServices());
        Optional.ofNullable(caps.getAvailableRegions()).ifPresent(builder::addAllAvailableRegions);
        Optional.ofNullable(caps.getCustomCapabilities()).ifPresent(cc ->
                cc.forEach((k, v) -> builder.putCustomCapabilitiesJson(k, toJson(v))));
        Optional.ofNullable(caps.getSystemInfo()).ifPresent(si -> builder.setSystemInfo(toProto(si)));
        Optional.ofNullable(caps.getNetworkInfo()).ifPresent(ni -> builder.setNetworkInfo(toProto(ni)));
        return builder.build();
    }

    private static ClientCapabilities fromProto(ClientCapabilitiesProto proto) {
        ClientCapabilities caps = new ClientCapabilities();
        caps.setSupportedServices(new HashSet<>(proto.getSupportedServicesList()));
        caps.setAvailableRegions(new HashSet<>(proto.getAvailableRegionsList()));
        if (proto.getCustomCapabilitiesJsonCount() > 0) {
            Map<String, Object> values = new HashMap<>();
            proto.getCustomCapabilitiesJsonMap().forEach((key, json) -> values.put(key, fromJson(json)));
            caps.setCustomCapabilities(values);
        } else if (proto.getCustomCapabilitiesCount() > 0) {
            caps.setCustomCapabilities(new HashMap<>(proto.getCustomCapabilitiesMap()));
        }
        if (proto.hasSystemInfo()) {
            caps.setSystemInfo(fromProto(proto.getSystemInfo()));
        }
        if (proto.hasNetworkInfo()) {
            caps.setNetworkInfo(fromProto(proto.getNetworkInfo()));
        }
        return caps;
    }

    private static ClientSystemInfoProto toProto(ClientSystemInfo info) {
        ClientSystemInfoProto.Builder builder = ClientSystemInfoProto.newBuilder()
                .setTotalMemory(info.getTotalMemory())
                .setAvailableMemory(info.getAvailableMemory())
                .setTotalDiskSpace(info.getTotalDiskSpace())
                .setAvailableDiskSpace(info.getAvailableDiskSpace())
                .setCpuCores(info.getCpuCores())
                .setCpuUsage(info.getCpuUsage())
                .setLoadAverage(info.getLoadAverage());
        Optional.ofNullable(info.getOperatingSystem()).ifPresent(builder::setOperatingSystem);
        Optional.ofNullable(info.getArchitecture()).ifPresent(builder::setArchitecture);
        Optional.ofNullable(info.getJavaVersion()).ifPresent(builder::setJavaVersion);
        return builder.build();
    }

    private static ClientSystemInfo fromProto(ClientSystemInfoProto proto) {
        ClientSystemInfo info = new ClientSystemInfo();
        info.setOperatingSystem(proto.hasOperatingSystem() ? proto.getOperatingSystem() : null);
        info.setArchitecture(proto.hasArchitecture() ? proto.getArchitecture() : null);
        info.setJavaVersion(proto.hasJavaVersion() ? proto.getJavaVersion() : null);
        info.setTotalMemory(proto.getTotalMemory());
        info.setAvailableMemory(proto.getAvailableMemory());
        info.setTotalDiskSpace(proto.getTotalDiskSpace());
        info.setAvailableDiskSpace(proto.getAvailableDiskSpace());
        info.setCpuCores(proto.getCpuCores());
        info.setCpuUsage(proto.getCpuUsage());
        info.setLoadAverage(proto.getLoadAverage());
        return info;
    }

    private static ClientNetworkInfoProto toProto(ClientNetworkInfo info) {
        ClientNetworkInfoProto.Builder builder = ClientNetworkInfoProto.newBuilder()
                .setBandwidthCapacity(info.getBandwidthCapacity())
                .setCurrentBandwidthUsage(info.getCurrentBandwidthUsage())
                .setLatencyMs(info.getLatencyMs())
                .setPacketLossPercentage(info.getPacketLossPercentage())
                .setIsNatTraversal(info.isNatTraversal());
        Optional.ofNullable(info.getPublicIpAddress()).ifPresent(builder::setPublicIpAddress);
        Optional.ofNullable(info.getPrivateIpAddress()).ifPresent(builder::setPrivateIpAddress);
        Optional.ofNullable(info.getNetworkInterfaces()).ifPresent(builder::addAllNetworkInterfaces);
        Optional.ofNullable(info.getConnectionType()).ifPresent(builder::setConnectionType);
        Optional.ofNullable(info.getFirewallPorts()).ifPresent(builder::addAllFirewallPorts);
        return builder.build();
    }

    private static ClientNetworkInfo fromProto(ClientNetworkInfoProto proto) {
        ClientNetworkInfo info = new ClientNetworkInfo();
        info.setPublicIpAddress(proto.hasPublicIpAddress() ? proto.getPublicIpAddress() : null);
        info.setPrivateIpAddress(proto.hasPrivateIpAddress() ? proto.getPrivateIpAddress() : null);
        info.setNetworkInterfaces(new ArrayList<>(proto.getNetworkInterfacesList()));
        info.setBandwidthCapacity(proto.getBandwidthCapacity());
        info.setCurrentBandwidthUsage(proto.getCurrentBandwidthUsage());
        info.setLatencyMs(proto.getLatencyMs());
        info.setPacketLossPercentage(proto.getPacketLossPercentage());
        info.setConnectionType(proto.hasConnectionType() ? proto.getConnectionType() : null);
        info.setNatTraversal(proto.getIsNatTraversal());
        info.setFirewallPorts(new ArrayList<>(proto.getFirewallPortsList()));
        return info;
    }

    // ── Enums ───────────────────────────────────────────────────

    private static ClientStatusProto toProto(ClientStatus status) {
        return switch (status) {
            case REGISTERING -> ClientStatusProto.CLIENT_STATUS_REGISTERING;
            case HEALTHY -> ClientStatusProto.CLIENT_STATUS_HEALTHY;
            case DEGRADED -> ClientStatusProto.CLIENT_STATUS_DEGRADED;
            case MAINTENANCE -> ClientStatusProto.CLIENT_STATUS_MAINTENANCE;
            case UNREACHABLE -> ClientStatusProto.CLIENT_STATUS_UNREACHABLE;
            case FAILED -> ClientStatusProto.CLIENT_STATUS_FAILED;
            case DEREGISTERED -> ClientStatusProto.CLIENT_STATUS_DEREGISTERED;
        };
    }

    private static ClientStatus fromProto(ClientStatusProto status) {
        return switch (status) {
            case CLIENT_STATUS_REGISTERING -> ClientStatus.REGISTERING;
            case CLIENT_STATUS_HEALTHY -> ClientStatus.HEALTHY;
            // Statuses of the job system: replicated history written earlier may hold them.
            case CLIENT_STATUS_ACTIVE, CLIENT_STATUS_IDLE -> ClientStatus.HEALTHY;
            case CLIENT_STATUS_DEGRADED -> ClientStatus.DEGRADED;
            case CLIENT_STATUS_OVERLOADED -> ClientStatus.DEGRADED;
            case CLIENT_STATUS_MAINTENANCE -> ClientStatus.MAINTENANCE;
            case CLIENT_STATUS_DRAINING -> ClientStatus.MAINTENANCE;
            case CLIENT_STATUS_UNREACHABLE -> ClientStatus.UNREACHABLE;
            case CLIENT_STATUS_FAILED -> ClientStatus.FAILED;
            case CLIENT_STATUS_DEREGISTERED -> ClientStatus.DEREGISTERED;
            default -> throw new IllegalArgumentException("Unknown ClientStatusProto: " + status);
        };
    }
}

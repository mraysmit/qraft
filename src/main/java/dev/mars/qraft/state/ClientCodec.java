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
import dev.mars.qraft.common.AgentCapabilities;
import dev.mars.qraft.common.AgentInfo;
import dev.mars.qraft.common.AgentNetworkInfo;
import dev.mars.qraft.common.AgentStatus;
import dev.mars.qraft.common.AgentSystemInfo;
import dev.mars.qraft.raft.grpc.AgentCapabilitiesProto;
import dev.mars.qraft.raft.grpc.AgentCommandProto;
import dev.mars.qraft.raft.grpc.AgentCommandType;
import dev.mars.qraft.raft.grpc.AgentInfoProto;
import dev.mars.qraft.raft.grpc.AgentNetworkInfoProto;
import dev.mars.qraft.raft.grpc.AgentStatusProto;
import dev.mars.qraft.raft.grpc.AgentSystemInfoProto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;

/**
 * Protobuf codec for agent-related types: {@link AgentCommand},
 * {@link AgentInfo}, {@link AgentCapabilities}, {@link AgentSystemInfo},
 * {@link AgentNetworkInfo}, and {@link AgentStatus}.
 *
 * <p>Package-private utility class used by {@link ProtobufCommandCodec}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025
 */
final class AgentCodec {

    private AgentCodec() {
    }

    // ── Command ─────────────────────────────────────────────────

    static AgentCommandProto toProto(AgentCommand cmd) {
        AgentCommandProto.Builder builder = AgentCommandProto.newBuilder()
                .setAgentId(cmd.agentId())
                .setTimestampEpochMs(cmd.timestamp().toEpochMilli());

        switch (cmd) {
            case AgentCommand.Register r -> {
                builder.setType(AgentCommandType.AGENT_CMD_REGISTER);
                builder.setAgentInfo(toProto(r.agentInfo()));
            }
            case AgentCommand.Deregister ignored -> {
                builder.setType(AgentCommandType.AGENT_CMD_DEREGISTER);
            }
            case AgentCommand.UpdateStatus u -> {
                builder.setType(AgentCommandType.AGENT_CMD_UPDATE_STATUS);
                builder.setNewStatus(toProto(u.newStatus()));
                builder.setExpectedStatus(toProto(u.expectedStatus()));
            }
            case AgentCommand.UpdateCapabilities c -> {
                builder.setType(AgentCommandType.AGENT_CMD_UPDATE_CAPABILITIES);
                builder.setNewCapabilities(toProto(c.newCapabilities()));
            }
            case AgentCommand.Expire e -> {
                builder.setType(AgentCommandType.AGENT_CMD_EXPIRE);
                builder.setExpectedLastContactEpochMs(e.expectedLastContact().toEpochMilli());
                builder.setReap(e.reap());
            }
            case AgentCommand.Heartbeat h -> {
                builder.setType(AgentCommandType.AGENT_CMD_HEARTBEAT);
                builder.setSequenceNumber(h.sequenceNumber());
                if (h.registrationId() != null) builder.setRegistrationId(h.registrationId());
                if (h.status() != null) {
                    builder.setNewStatus(toProto(h.status()));
                }
            }
        }

        return builder.build();
    }

    static AgentCommand fromProto(AgentCommandProto proto) {
        // Decoding must never read the local clock: every replica and every replay must see one value.
        Instant timestamp = Instant.ofEpochMilli(proto.getTimestampEpochMs());
        AgentStatus newStatus = proto.getNewStatus() != AgentStatusProto.AGENT_STATUS_UNSPECIFIED
                ? fromProto(proto.getNewStatus()) : null;
        return switch (proto.getType()) {
            case AGENT_CMD_REGISTER -> new AgentCommand.Register(
                    proto.getAgentId(), fromProto(proto.getAgentInfo()), timestamp);
            case AGENT_CMD_DEREGISTER -> new AgentCommand.Deregister(
                    proto.getAgentId(), timestamp);
            case AGENT_CMD_UPDATE_STATUS -> {
                yield new AgentCommand.UpdateStatus(
                        proto.getAgentId(), fromProto(proto.getExpectedStatus()), newStatus, timestamp);
            }
            case AGENT_CMD_UPDATE_CAPABILITIES -> new AgentCommand.UpdateCapabilities(
                    proto.getAgentId(), fromProto(proto.getNewCapabilities()), timestamp);
            case AGENT_CMD_HEARTBEAT -> new AgentCommand.Heartbeat(
                    proto.getAgentId(), newStatus, timestamp, proto.getSequenceNumber(),
                    proto.getRegistrationId().isEmpty() ? null : proto.getRegistrationId());
            case AGENT_CMD_EXPIRE -> new AgentCommand.Expire(proto.getAgentId(),
                    Instant.ofEpochMilli(proto.getExpectedLastContactEpochMs()), proto.getReap(), timestamp);
            default -> throw new IllegalArgumentException("Unknown AgentCommandType: " + proto.getType());
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

    private static AgentInfoProto toProto(AgentInfo info) {
        AgentInfoProto.Builder builder = AgentInfoProto.newBuilder()
                .setPort(info.getPort());
        Optional.ofNullable(info.getAgentId()).ifPresent(builder::setAgentId);
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

    private static AgentInfo fromProto(AgentInfoProto proto) {
        AgentInfo info = new AgentInfo(
                proto.getAgentId(),
                proto.hasHostname() ? proto.getHostname() : null,
                proto.hasAddress() ? proto.getAddress() : null,
                proto.getPort());
        if (proto.hasCapabilities()) {
            info.setCapabilities(fromProto(proto.getCapabilities()));
        }
        if (proto.getStatus() != AgentStatusProto.AGENT_STATUS_UNSPECIFIED) {
            info.setStatus(fromProto(proto.getStatus()));
        }
        // The AgentInfo constructor stamps the local clock; a decoded value must come from the entry only.
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

    private static AgentCapabilitiesProto toProto(AgentCapabilities caps) {
        AgentCapabilitiesProto.Builder builder = AgentCapabilitiesProto.newBuilder()
                .addAllSupportedServices(caps.getSupportedServices());
        Optional.ofNullable(caps.getAvailableRegions()).ifPresent(builder::addAllAvailableRegions);
        Optional.ofNullable(caps.getCustomCapabilities()).ifPresent(cc ->
                cc.forEach((k, v) -> builder.putCustomCapabilitiesJson(k, toJson(v))));
        Optional.ofNullable(caps.getSystemInfo()).ifPresent(si -> builder.setSystemInfo(toProto(si)));
        Optional.ofNullable(caps.getNetworkInfo()).ifPresent(ni -> builder.setNetworkInfo(toProto(ni)));
        return builder.build();
    }

    private static AgentCapabilities fromProto(AgentCapabilitiesProto proto) {
        AgentCapabilities caps = new AgentCapabilities();
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

    private static AgentSystemInfoProto toProto(AgentSystemInfo info) {
        AgentSystemInfoProto.Builder builder = AgentSystemInfoProto.newBuilder()
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

    private static AgentSystemInfo fromProto(AgentSystemInfoProto proto) {
        AgentSystemInfo info = new AgentSystemInfo();
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

    private static AgentNetworkInfoProto toProto(AgentNetworkInfo info) {
        AgentNetworkInfoProto.Builder builder = AgentNetworkInfoProto.newBuilder()
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

    private static AgentNetworkInfo fromProto(AgentNetworkInfoProto proto) {
        AgentNetworkInfo info = new AgentNetworkInfo();
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

    private static AgentStatusProto toProto(AgentStatus status) {
        return switch (status) {
            case REGISTERING -> AgentStatusProto.AGENT_STATUS_REGISTERING;
            case HEALTHY -> AgentStatusProto.AGENT_STATUS_HEALTHY;
            case DEGRADED -> AgentStatusProto.AGENT_STATUS_DEGRADED;
            case MAINTENANCE -> AgentStatusProto.AGENT_STATUS_MAINTENANCE;
            case UNREACHABLE -> AgentStatusProto.AGENT_STATUS_UNREACHABLE;
            case FAILED -> AgentStatusProto.AGENT_STATUS_FAILED;
            case DEREGISTERED -> AgentStatusProto.AGENT_STATUS_DEREGISTERED;
        };
    }

    private static AgentStatus fromProto(AgentStatusProto status) {
        return switch (status) {
            case AGENT_STATUS_REGISTERING -> AgentStatus.REGISTERING;
            case AGENT_STATUS_HEALTHY -> AgentStatus.HEALTHY;
            // Statuses of the job system: replicated history written earlier may hold them.
            case AGENT_STATUS_ACTIVE, AGENT_STATUS_IDLE -> AgentStatus.HEALTHY;
            case AGENT_STATUS_DEGRADED -> AgentStatus.DEGRADED;
            case AGENT_STATUS_OVERLOADED -> AgentStatus.DEGRADED;
            case AGENT_STATUS_MAINTENANCE -> AgentStatus.MAINTENANCE;
            case AGENT_STATUS_DRAINING -> AgentStatus.MAINTENANCE;
            case AGENT_STATUS_UNREACHABLE -> AgentStatus.UNREACHABLE;
            case AGENT_STATUS_FAILED -> AgentStatus.FAILED;
            case AGENT_STATUS_DEREGISTERED -> AgentStatus.DEREGISTERED;
            default -> throw new IllegalArgumentException("Unknown AgentStatusProto: " + status);
        };
    }
}

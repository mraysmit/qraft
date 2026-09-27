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

import dev.mars.qraft.agent.AgentCapabilities;
import dev.mars.qraft.agent.AgentInfo;
import dev.mars.qraft.agent.AgentStatus;
import dev.mars.qraft.controller.raft.grpc.AgentCommandProto;
import dev.mars.qraft.controller.raft.grpc.AgentStatusProto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link AgentCodec} round trips for every agent command variant, rejection of unspecified
 * types, and typed command factories.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
class AgentCodecTest {

    @Test
    void roundTripsEveryAgentCommandVariant() {
        Instant timestamp = Instant.parse("2026-02-03T04:05:06Z");
        AgentCapabilities capabilities = new AgentCapabilities();
        capabilities.setSupportedServices(Set.of("kv", "health"));
        capabilities.setAvailableRegions(Set.of("eu-west"));

        AgentInfo info = new AgentInfo("agent-1", "host", "10.0.0.1", 8080);
        info.setStatus(AgentStatus.HEALTHY);
        info.setCapabilities(capabilities);
        info.setVersion("1.2.3");
        info.setRegion("eu-west");
        info.setDatacenter("dc-1");

        List<AgentCommand> commands = List.of(
                new AgentCommand.Register("agent-1", info, timestamp),
                new AgentCommand.Deregister("agent-1", timestamp),
                new AgentCommand.UpdateStatus("agent-1", AgentStatus.HEALTHY, AgentStatus.DEGRADED, timestamp),
                new AgentCommand.UpdateCapabilities("agent-1", capabilities, timestamp),
                new AgentCommand.Heartbeat("agent-1", AgentStatus.DEGRADED, timestamp, 42));

        for (AgentCommand command : commands) {
            AgentCommand decoded = AgentCodec.fromProto(AgentCodec.toProto(command));
            assertEquals(command.getClass(), decoded.getClass());
            assertEquals(command.agentId(), decoded.agentId());
            assertEquals(timestamp, decoded.timestamp());
        }

        AgentCommand.Register register = (AgentCommand.Register) AgentCodec.fromProto(AgentCodec.toProto(commands.getFirst()));
        assertEquals("host", register.agentInfo().getHostname());
        assertEquals(Set.of("kv", "health"), register.agentInfo().getCapabilities().getSupportedServices());
        assertEquals(AgentStatus.HEALTHY, register.agentInfo().getStatus());
        AgentCommand.Heartbeat heartbeat = (AgentCommand.Heartbeat)
                AgentCodec.fromProto(AgentCodec.toProto(commands.getLast()));
        assertEquals(42, heartbeat.sequenceNumber());
    }

    @Test
    void rejectsUnspecifiedCommandType() {
        AgentCommandProto proto = AgentCommandProto.newBuilder().setAgentId("agent").build();
        assertThrows(IllegalArgumentException.class, () -> AgentCodec.fromProto(proto));
    }

    @Test
    void factoriesProduceTypedCommands() {
        AgentInfo info = new AgentInfo("agent", "host", "address", 1);
        assertInstanceOf(AgentCommand.Register.class, AgentCommand.register(info));
        assertInstanceOf(AgentCommand.Deregister.class, AgentCommand.deregister("agent"));
        assertInstanceOf(AgentCommand.Heartbeat.class, AgentCommand.heartbeat("agent"));
        assertInstanceOf(AgentCommand.Heartbeat.class, AgentCommand.heartbeat("agent", null, null));
    }

    /** Statuses inherited from the job system, which replicated history written earlier may still hold. */
    private static final Map<AgentStatusProto, AgentStatus> LEGACY_STATUSES = Map.of(
            AgentStatusProto.AGENT_STATUS_ACTIVE, AgentStatus.HEALTHY,
            AgentStatusProto.AGENT_STATUS_IDLE, AgentStatus.HEALTHY,
            AgentStatusProto.AGENT_STATUS_OVERLOADED, AgentStatus.DEGRADED,
            AgentStatusProto.AGENT_STATUS_DRAINING, AgentStatus.MAINTENANCE);

    @Test
    void legacyStatusesInReplicatedHistoryDecodeToTheirCurrentMeaning() {
        Instant timestamp = Instant.parse("2026-02-03T04:05:06Z");
        AgentCommandProto heartbeat = AgentCodec.toProto(
                new AgentCommand.Heartbeat("agent-1", AgentStatus.HEALTHY, timestamp, 1));
        AgentCommandProto register = AgentCodec.toProto(new AgentCommand.Register("agent-1",
                new AgentInfo("agent-1", "host", "10.0.0.1", 8080), timestamp));

        LEGACY_STATUSES.forEach((legacy, current) -> {
            AgentCommand.Heartbeat decodedHeartbeat = (AgentCommand.Heartbeat) AgentCodec.fromProto(
                    heartbeat.toBuilder().setNewStatus(legacy).build());
            AgentCommand.Register decodedRegister = (AgentCommand.Register) AgentCodec.fromProto(register.toBuilder()
                    .setAgentInfo(register.getAgentInfo().toBuilder().setStatus(legacy)).build());
            assertEquals(current, decodedHeartbeat.status(), legacy.name());
            assertEquals(current, decodedRegister.agentInfo().getStatus(), legacy.name());
        });
    }

    @Test
    void noCurrentStatusIsEncodedAsALegacyValue() {
        Instant timestamp = Instant.parse("2026-02-03T04:05:06Z");
        for (AgentStatus status : EnumSet.allOf(AgentStatus.class)) {
            AgentStatusProto encoded = AgentCodec.toProto(
                    new AgentCommand.Heartbeat("agent-1", status, timestamp, 1)).getNewStatus();
            assertFalse(LEGACY_STATUSES.containsKey(encoded), status + " encodes as " + encoded);
        }
    }
}

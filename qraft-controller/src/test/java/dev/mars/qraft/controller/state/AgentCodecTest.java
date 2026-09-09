package dev.mars.qraft.controller.state;

import dev.mars.qraft.agent.AgentCapabilities;
import dev.mars.qraft.agent.AgentInfo;
import dev.mars.qraft.agent.AgentStatus;
import dev.mars.qraft.controller.raft.grpc.AgentCommandProto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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
                new AgentCommand.UpdateStatus("agent-1", AgentStatus.HEALTHY, AgentStatus.ACTIVE, timestamp),
                new AgentCommand.UpdateCapabilities("agent-1", capabilities, timestamp),
                new AgentCommand.Heartbeat("agent-1", AgentStatus.DEGRADED, timestamp));

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
}

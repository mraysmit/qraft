package dev.mars.qraft.controller.state;

import dev.mars.qraft.agent.AgentCapabilities;
import dev.mars.qraft.agent.AgentInfo;
import dev.mars.qraft.agent.AgentStatus;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ControllerStateStoreTest {

    @Test
    void genericStoreAppliesCommandsAndRestoresSnapshots() {
        GenericStateStore store = new GenericStateStore(Map.of("seed", "value"));
        assertInstanceOf(CommandResult.NoOp.class, store.apply(null));
        assertEquals("value", store.getMetadata().get("seed"));

        assertInstanceOf(CommandResult.Success.class, store.apply(command(DistributedStateCommand.put("key", "one"))));
        assertInstanceOf(CommandResult.Success.class, store.apply(command(DistributedStateCommand.delete("key"))));
        assertInstanceOf(CommandResult.NotFound.class, store.apply(command(DistributedStateCommand.delete("missing"))));
        assertThrows(IllegalArgumentException.class, () -> store.apply(AgentCommand.deregister("agent")));

        store.apply(command(DistributedStateCommand.put("snap", "saved")));
        store.setLastAppliedIndex(12);
        byte[] snapshot = store.takeSnapshot();
        store.reset();
        assertEquals(0, store.getLastAppliedIndex());
        assertTrue(store.getMetadata().isEmpty());
        store.restoreSnapshot(snapshot);
        assertEquals("saved", store.getMetadata().get("snap"));
        assertEquals(12, store.getLastAppliedIndex());
        assertThrows(RuntimeException.class, () -> store.restoreSnapshot(new byte[]{1, 2, 3}));
    }

    @Test
    void controllerStoreAppliesAgentAndMetadataLifecycle() {
        QraftStateStore store = new QraftStateStore(Map.of("environment", "test"));
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        AgentInfo agent = new AgentInfo("agent-1", "host", "127.0.0.1", 9000);
        agent.setStatus(AgentStatus.REGISTERING);

        assertInstanceOf(CommandResult.NoOp.class, store.apply(null));
        assertInstanceOf(CommandResult.Success.class,
                store.apply(new AgentCommand.Register("agent-1", agent, now)));
        assertTrue(store.findAgent("agent-1").isPresent());

        assertInstanceOf(CommandResult.CasMismatch.class, store.apply(new AgentCommand.UpdateStatus(
                "agent-1", AgentStatus.HEALTHY, AgentStatus.ACTIVE, now)));
        assertInstanceOf(CommandResult.Success.class, store.apply(new AgentCommand.UpdateStatus(
                "agent-1", AgentStatus.REGISTERING, AgentStatus.ACTIVE, now)));

        AgentCapabilities capabilities = new AgentCapabilities();
        capabilities.setSupportedServices(java.util.Set.of("kv"));
        assertInstanceOf(CommandResult.Success.class, store.apply(new AgentCommand.UpdateCapabilities(
                "agent-1", capabilities, now.plusSeconds(1))));
        assertInstanceOf(CommandResult.Success.class, store.apply(new AgentCommand.Heartbeat(
                "agent-1", AgentStatus.DEGRADED, now.plusSeconds(2))));
        assertEquals(AgentStatus.DEGRADED, store.findAgent("agent-1").orElseThrow().getStatus());

        assertInstanceOf(CommandResult.NotFound.class, store.apply(AgentCommand.heartbeat("missing")));
        assertInstanceOf(CommandResult.NotFound.class, store.apply(AgentCommand.updateCapabilities("missing", capabilities)));
        assertInstanceOf(CommandResult.NotFound.class, store.apply(AgentCommand.updateStatus(
                "missing", AgentStatus.HEALTHY, AgentStatus.ACTIVE)));

        store.apply(command(DistributedStateCommand.put("feature", "enabled")));
        assertEquals("enabled", store.getMetadata("feature"));
        assertEquals("enabled", store.findMetadata("feature").orElseThrow());
        assertInstanceOf(CommandResult.Success.class, store.apply(command(DistributedStateCommand.delete("feature"))));
        assertInstanceOf(CommandResult.NotFound.class, store.apply(command(DistributedStateCommand.delete("feature"))));

        store.setLastAppliedIndex(21);
        byte[] snapshot = store.takeSnapshot();
        assertInstanceOf(CommandResult.Success.class, store.apply(AgentCommand.deregister("agent-1")));
        assertInstanceOf(CommandResult.NotFound.class, store.apply(AgentCommand.deregister("agent-1")));
        store.restoreSnapshot(snapshot);
        assertEquals(21, store.getLastAppliedIndex());
        assertEquals(1, store.getAgents().size());
        assertThrows(IllegalStateException.class, () -> store.restoreSnapshot(new byte[]{9}));

        store.reset();
        assertEquals("3.0", store.getMetadata("version"));
        assertEquals(0, store.getLastAppliedIndex());
    }

    private static DistributedStateRaftCommand command(DistributedStateCommand command) {
        return new DistributedStateRaftCommand(command);
    }
}

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
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link GenericStateStore} and {@link QraftStateStore} command application, agent and
 * catalog lifecycle, heartbeat epochs, and snapshot restore.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
class ControllerStateStoreTest {

    @Test
    void genericStoreAppliesCommandsAndRestoresSnapshots() {
        GenericStateStore store = new GenericStateStore(Map.of("seed", "value"));
        assertInstanceOf(RaftCommandResult.NoOp.class, store.apply(null));
        assertEquals("value", store.getMetadata().get("seed"));

        assertInstanceOf(RaftCommandResult.Success.class, store.apply(command(DistributedStateCommand.put("key", "one"))));
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(command(DistributedStateCommand.delete("key"))));
        assertInstanceOf(RaftCommandResult.NotFound.class, store.apply(command(DistributedStateCommand.delete("missing"))));
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

        assertInstanceOf(RaftCommandResult.NoOp.class, store.apply(null));
        assertInstanceOf(RaftCommandResult.Success.class,
                store.apply(new AgentCommand.Register("agent-1", agent, now)));
        assertTrue(store.findAgent("agent-1").isPresent());

        assertInstanceOf(RaftCommandResult.CasMismatch.class, store.apply(new AgentCommand.UpdateStatus(
                "agent-1", AgentStatus.HEALTHY, AgentStatus.ACTIVE, now)));
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(new AgentCommand.UpdateStatus(
                "agent-1", AgentStatus.REGISTERING, AgentStatus.ACTIVE, now)));

        AgentCapabilities capabilities = new AgentCapabilities();
        capabilities.setSupportedServices(java.util.Set.of("kv"));
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(new AgentCommand.UpdateCapabilities(
                "agent-1", capabilities, now.plusSeconds(1))));
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(new AgentCommand.Heartbeat(
                "agent-1", AgentStatus.DEGRADED, now.plusSeconds(2))));
        assertEquals(AgentStatus.DEGRADED, store.findAgent("agent-1").orElseThrow().getStatus());

        assertInstanceOf(RaftCommandResult.Success.class, store.apply(new AgentCommand.Heartbeat(
                "agent-1", AgentStatus.HEALTHY, now.plusSeconds(3), 2)));
        byte[] sequencedSnapshot = store.takeSnapshot();
        assertInstanceOf(RaftCommandResult.CasMismatch.class, store.apply(new AgentCommand.Heartbeat(
                "agent-1", AgentStatus.DEGRADED, now.plusSeconds(2), 1)));
        store.restoreSnapshot(sequencedSnapshot);
        assertInstanceOf(RaftCommandResult.CasMismatch.class, store.apply(new AgentCommand.Heartbeat(
                "agent-1", AgentStatus.DEGRADED, now.plusSeconds(2), 1)));
        assertEquals(AgentStatus.HEALTHY, store.findAgent("agent-1").orElseThrow().getStatus());

        assertInstanceOf(RaftCommandResult.NotFound.class, store.apply(AgentCommand.heartbeat("missing")));
        assertInstanceOf(RaftCommandResult.NotFound.class, store.apply(AgentCommand.updateCapabilities("missing", capabilities)));
        assertInstanceOf(RaftCommandResult.NotFound.class, store.apply(AgentCommand.updateStatus(
                "missing", AgentStatus.HEALTHY, AgentStatus.ACTIVE)));

        store.apply(command(DistributedStateCommand.put("feature", "enabled")));
        assertEquals("enabled", store.getMetadata("feature"));
        assertEquals("enabled", store.findMetadata("feature").orElseThrow());
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(command(DistributedStateCommand.delete("feature"))));
        assertInstanceOf(RaftCommandResult.NotFound.class, store.apply(command(DistributedStateCommand.delete("feature"))));

        store.setLastAppliedIndex(21);
        byte[] snapshot = store.takeSnapshot();
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(AgentCommand.deregister("agent-1")));
        assertInstanceOf(RaftCommandResult.NotFound.class, store.apply(AgentCommand.deregister("agent-1")));
        store.restoreSnapshot(snapshot);
        assertEquals(21, store.getLastAppliedIndex());
        assertEquals(1, store.getAgents().size());
        assertThrows(IllegalStateException.class, () -> store.restoreSnapshot(new byte[]{9}));

        store.reset();
        assertEquals("3.0", store.getMetadata("version"));
        assertEquals(0, store.getLastAppliedIndex());
    }

    @Test
    void controllerStoreReplicatesCatalogAndIncludesItInSnapshots() {
        QraftStateStore store = new QraftStateStore();
        ServiceInstance instance = new ServiceInstance("payments-1", "payments", "node-1",
                "127.0.0.1", 8080, List.of("v1"), Map.of("team", "platform"), ServiceHealth.PASSING);

        assertInstanceOf(RaftCommandResult.Success.class, store.apply(CatalogCommand.register(instance)));
        assertEquals(List.of(instance), store.getServiceCatalog().instances("payments"));
        byte[] snapshot = store.takeSnapshot();
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(CatalogCommand.deregister("payments-1")));
        assertInstanceOf(RaftCommandResult.NotFound.class, store.apply(CatalogCommand.deregister("payments-1")));

        store.restoreSnapshot(snapshot);
        assertEquals(List.of(instance), store.getServiceCatalog().instances("payments"));
        store.reset();
        assertTrue(store.getServiceCatalog().instances().isEmpty());
    }

    @Test
    void lateHeartbeatFromPreviousRegistrationCannotPoisonNewSequenceEpoch() {
        QraftStateStore store = new QraftStateStore();
        AgentInfo agent = new AgentInfo("agent-1", "host", "127.0.0.1", 9000);
        Instant firstRegistration = Instant.parse("2026-09-21T10:00:00Z");
        Instant secondRegistration = firstRegistration.plusSeconds(10);

        agent.addMetadata(AgentInfo.REGISTRATION_ID_METADATA_KEY, "first");
        assertInstanceOf(RaftCommandResult.Success.class,
                store.apply(new AgentCommand.Register("agent-1", agent, firstRegistration)));
        agent.addMetadata(AgentInfo.REGISTRATION_ID_METADATA_KEY, "second");
        assertInstanceOf(RaftCommandResult.Success.class,
                store.apply(new AgentCommand.Register("agent-1", agent, secondRegistration)));

        assertInstanceOf(RaftCommandResult.CasMismatch.class,
                store.apply(new AgentCommand.Heartbeat("agent-1", AgentStatus.DEGRADED,
                        firstRegistration.plusSeconds(5), 50, "first")));
        assertInstanceOf(RaftCommandResult.Success.class,
                store.apply(new AgentCommand.Heartbeat("agent-1", AgentStatus.HEALTHY,
                        secondRegistration.plusSeconds(1), 1, "second")));
        assertEquals(AgentStatus.HEALTHY, store.findAgent("agent-1").orElseThrow().getStatus());
        assertEquals(secondRegistration.plusSeconds(1),
                store.findAgent("agent-1").orElseThrow().getLastHeartbeat());
    }

    @Test
    void restoresSnapshotsWrittenBeforeCatalogStateWasAdded() {
        QraftStateStore store = new QraftStateStore();
        byte[] legacySnapshot = """
                {"agents":{},"metadata":{"version":"2.0","feature":"enabled"},"lastAppliedIndex":17}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        store.restoreSnapshot(legacySnapshot);

        assertEquals("enabled", store.getMetadata("feature"));
        assertEquals(17, store.getLastAppliedIndex());
        assertTrue(store.getServiceCatalog().instances().isEmpty());
    }

    private static DistributedStateRaftCommand command(DistributedStateCommand command) {
        return new DistributedStateRaftCommand(command);
    }
}

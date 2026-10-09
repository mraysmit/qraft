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

import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.state.catalog.ServiceKey;
import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.NodeStatus;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link QraftStateStore} command application, client and
 * catalog lifecycle, heartbeat epochs, and snapshot restore.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
class ServerStateStoreTest {

    @Test
    void serverStoreAppliesClientAndMetadataLifecycle() {
        QraftStateStore store = new QraftStateStore(Map.of("environment", "test"));
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Node client = Node.of("client-1", "127.0.0.1", null, null, null);

        assertInstanceOf(RaftCommandResult.NoOp.class, store.apply(null));
        assertInstanceOf(RaftCommandResult.Success.class,
                store.apply(new NodeCommand.Register("client-1", client, now)));
        assertTrue(store.findNode("client-1").isPresent());

        assertInstanceOf(RaftCommandResult.CasMismatch.class, store.apply(new NodeCommand.UpdateStatus(
                "client-1", NodeStatus.HEALTHY, NodeStatus.HEALTHY, now)));
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(new NodeCommand.UpdateStatus(
                "client-1", NodeStatus.REGISTERING, NodeStatus.HEALTHY, now)));

        assertInstanceOf(RaftCommandResult.Success.class, store.apply(new NodeCommand.Heartbeat(
                "client-1", NodeStatus.UNREACHABLE, now.plusSeconds(2))));
        assertEquals(NodeStatus.UNREACHABLE, store.findNode("client-1").orElseThrow().status());

        assertInstanceOf(RaftCommandResult.Success.class, store.apply(new NodeCommand.Heartbeat(
                "client-1", NodeStatus.HEALTHY, now.plusSeconds(3), 2)));
        byte[] sequencedSnapshot = store.takeSnapshot();
        assertInstanceOf(RaftCommandResult.CasMismatch.class, store.apply(new NodeCommand.Heartbeat(
                "client-1", NodeStatus.UNREACHABLE, now.plusSeconds(2), 1)));
        store.restoreSnapshot(sequencedSnapshot);
        assertInstanceOf(RaftCommandResult.CasMismatch.class, store.apply(new NodeCommand.Heartbeat(
                "client-1", NodeStatus.UNREACHABLE, now.plusSeconds(2), 1)));
        assertEquals(NodeStatus.HEALTHY, store.findNode("client-1").orElseThrow().status());

        assertInstanceOf(RaftCommandResult.NotFound.class, store.apply(NodeCommand.heartbeat("missing")));
        assertInstanceOf(RaftCommandResult.NotFound.class, store.apply(NodeCommand.updateStatus(
                "missing", NodeStatus.HEALTHY, NodeStatus.HEALTHY)));

        store.apply(command(DistributedStateCommand.put("feature", "enabled")));
        assertEquals("enabled", store.getMetadata("feature"));
        assertEquals("enabled", store.findMetadata("feature").orElseThrow());
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(command(DistributedStateCommand.delete("feature"))));
        assertInstanceOf(RaftCommandResult.NotFound.class, store.apply(command(DistributedStateCommand.delete("feature"))));

        store.setLastAppliedIndex(21);
        byte[] snapshot = store.takeSnapshot();
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(NodeCommand.deregister("client-1")));
        assertInstanceOf(RaftCommandResult.NotFound.class, store.apply(NodeCommand.deregister("client-1")));
        store.restoreSnapshot(snapshot);
        assertEquals(21, store.getLastAppliedIndex());
        assertEquals(1, store.getNodes().size());
        IllegalStateException corrupt = assertThrows(IllegalStateException.class,
                () -> store.restoreSnapshot(new byte[]{9}));
        assertEquals("Failed to restore server snapshot", corrupt.getMessage());
        assertInstanceOf(IOException.class, corrupt.getCause(), "the unreadable bytes are the cause");
        assertEquals(21, store.getLastAppliedIndex(), "a snapshot that cannot be read changes nothing");
        assertEquals(1, store.getNodes().size());

        store.reset();
        assertEquals("3.0", store.getMetadata("version"));
        assertEquals(0, store.getLastAppliedIndex());
    }

    @Test
    void serverStoreReplicatesCatalogAndIncludesItInSnapshots() {
        QraftStateStore store = new QraftStateStore();
        ServiceInstance instance = new ServiceInstance("payments-1", "payments", "node-1",
                "127.0.0.1", 8080, List.of("v1"), Map.of("team", "platform"), ServiceHealth.PASSING);

        assertInstanceOf(RaftCommandResult.Success.class, store.apply(CatalogCommand.register(instance)));
        assertEquals(List.of(instance), store.getServiceCatalog().instances(ServiceKey.inDefaultScope("payments")));
        byte[] snapshot = store.takeSnapshot();
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(CatalogCommand.deregister("payments-1")));
        assertInstanceOf(RaftCommandResult.NotFound.class, store.apply(CatalogCommand.deregister("payments-1")));

        store.restoreSnapshot(snapshot);
        assertEquals(List.of(instance), store.getServiceCatalog().instances(ServiceKey.inDefaultScope("payments")));
        store.reset();
        assertTrue(store.getServiceCatalog().instances().isEmpty());
    }

    @Test
    void lateHeartbeatFromPreviousRegistrationCannotPoisonNewSequenceEpoch() {
        QraftStateStore store = new QraftStateStore();
        Node client = Node.of("client-1", "127.0.0.1", null, null, null);
        Instant firstRegistration = Instant.parse("2026-09-21T10:00:00Z");
        Instant secondRegistration = firstRegistration.plusSeconds(10);

        assertInstanceOf(RaftCommandResult.Success.class, store.apply(new NodeCommand.Register("client-1",
                client.withMetadata(Node.REGISTRATION_ID_METADATA_KEY, "first"), firstRegistration)));
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(new NodeCommand.Register("client-1",
                client.withMetadata(Node.REGISTRATION_ID_METADATA_KEY, "second"), secondRegistration)));

        assertInstanceOf(RaftCommandResult.CasMismatch.class,
                store.apply(new NodeCommand.Heartbeat("client-1", NodeStatus.UNREACHABLE,
                        firstRegistration.plusSeconds(5), 50, "first")));
        assertInstanceOf(RaftCommandResult.Success.class,
                store.apply(new NodeCommand.Heartbeat("client-1", NodeStatus.HEALTHY,
                        secondRegistration.plusSeconds(1), 1, "second")));
        assertEquals(NodeStatus.HEALTHY, store.findNode("client-1").orElseThrow().status());
        assertEquals(secondRegistration.plusSeconds(1),
                store.findNode("client-1").orElseThrow().lastHeartbeat());
    }

    @Test
    void restoresSnapshotsWrittenBeforeCatalogStateWasAdded() {
        QraftStateStore store = new QraftStateStore();
        byte[] legacySnapshot = """
                {"clients":{},"metadata":{"version":"2.0","feature":"enabled"},"lastAppliedIndex":17}
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

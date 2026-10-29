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

import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.NodeStatus;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.state.catalog.ServiceCheckId;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a node registration, and a snapshot holding nodes, a service and its check, written before a node
 * had an ID still decode and restore, and that their bytes stay unchanged. The manifest beside the fixtures
 * lists what each one holds.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-29
 * @version 1.0
 */
class NodeIdentityFixtureTest {
    private static final String FIXTURE_ROOT = "/fixtures/node-identity/";
    private static final Instant REGISTERED = Instant.parse("2026-10-29T08:00:00Z");
    private static final Map<String, String> WEB_METADATA = Map.of(
            Node.REGISTRATION_ID_METADATA_KEY, "reg-7", Node.VERSION_METADATA_KEY, "1.0-SNAPSHOT", "rack", "r3");
    private static final Node WEB = Node.of("node-web", "10.0.1.5", "dc-1", "eu-west", WEB_METADATA);
    private static final ServiceInstance WEB_1 = new ServiceInstance("web-1", "web", "node-web", "10.0.1.5", 8080,
            List.of("v1"), Map.of("owner", "team-a"), ServiceHealth.PASSING, "platform", "payments", "dc-1",
            "eu-west", true);
    private static final Map<String, String> SHA_256 = Map.of(
            "node-register-before-node-id.bin", "a648698ccce36ca35624764b3fc1153bbb98f0aa3d44d4ca6a6879bcfd523030",
            "node-snapshot-before-node-id.json", "4372e806054859ec2a7ec2df24156818c73f9946c21f8e9e091488426c7bd4f1");

    private final ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();

    @Test
    void decodesARegistrationWrittenBeforeTheNodeId() throws Exception {
        RaftCommand decoded = codec.deserialize(fixture("node-register-before-node-id.bin"));

        assertEquals(NodeCommand.register(WEB, REGISTERED), decoded);
        assertNull(((NodeCommand.Register) decoded).node().id(), "an entry written without a node ID has none");
    }

    @Test
    void restoresASnapshotWrittenBeforeTheNodeIdWithItsNodesServicesAndChecks() throws Exception {
        QraftStateStore store = new QraftStateStore();

        store.restoreSnapshot(fixture("node-snapshot-before-node-id.json"));

        assertEquals(5, store.getLastAppliedIndex());
        assertEquals(Set.of("node-web", "node-idle"), store.getNodes().keySet());
        Node web = store.findNode("node-web").orElseThrow();
        assertEquals(WEB.registeredAt(REGISTERED).withStatus(NodeStatus.HEALTHY)
                .withLastHeartbeat(REGISTERED.plusSeconds(5)), web);
        assertNull(web.id(), "a node from a snapshot written without node IDs has none");
        assertEquals(NodeStatus.REGISTERING, store.findNode("node-idle").orElseThrow().status());
        assertEquals(List.of(WEB_1), store.getServiceCatalog().instances());
        assertTrue(store.findHealthCheck(new ServiceCheckId(WEB_1.identity(), "http")).isPresent());

        byte[] again = store.takeSnapshot();
        QraftStateStore reopened = new QraftStateStore();
        reopened.restoreSnapshot(again);
        assertArrayEquals(again, reopened.takeSnapshot(), "the restored state writes the same snapshot again");
    }

    @Test
    void fixtureBytesRemainImmutable() throws Exception {
        for (var expected : SHA_256.entrySet()) {
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(fixture(expected.getKey())));
            assertEquals(expected.getValue(), actual, expected.getKey());
        }
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream input = NodeIdentityFixtureTest.class.getResourceAsStream(FIXTURE_ROOT + name)) {
            assertNotNull(input, "missing immutable fixture " + FIXTURE_ROOT + name);
            return input.readAllBytes();
        }
    }
}

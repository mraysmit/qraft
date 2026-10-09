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
import dev.mars.qraft.raft.RaftCommandResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests that the legacy node fixtures still decode and restore, and that their bytes stay unchanged. The
 * fixtures hold the node commands and a snapshot of nodes as the code wrote them on 2026-10-09, when a node
 * also had a host name, a port, a version, and capabilities, when a command could replace the capabilities,
 * and when there were more statuses. The manifest beside the fixtures lists what each one holds.
 *
 * <p>What a node no longer has is ignored. A removed status reads as the status that says whether the node
 * was in contact. An entry that holds the capabilities update applies nothing.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-09
 * @version 2.0
 */
class LegacyNodeFixtureTest {
    private static final String FIXTURE_ROOT = "/fixtures/node/";
    static final Instant REGISTERED = Instant.parse("2026-10-09T08:00:00Z");
    static final Instant CONTACTED = Instant.parse("2026-10-09T08:00:30Z");
    static final Instant COMMANDED = Instant.parse("2026-10-09T08:01:00Z");
    static final String REGISTRATION_ID = "reg-1";
    static final long HEARTBEAT_SEQUENCE = 7;
    static final long SNAPSHOT_INDEX = 9;
    /** The fully described node of the fixtures, as far as a node is still described. */
    private static final Node RICH = new Node("node-rich", "192.0.2.21", "dc-1", "eu-west",
            Map.of("rack", "r7", Node.REGISTRATION_ID_METADATA_KEY, REGISTRATION_ID),
            NodeStatus.HEALTHY, REGISTERED, CONTACTED);
    /** The node of the fixtures that was written with a name, a status, and a registration time only. */
    private static final Node MINIMAL = new Node("node-minimal", null, null, null, Map.of(),
            NodeStatus.REGISTERING, REGISTERED, null);
    private static final Map<String, String> SHA_256 = Map.ofEntries(
            Map.entry("node-register-rich.bin", "09c7072673ccde332f74977a2e21b0a2062fdb877aeccb6e55197c80486fc870"),
            Map.entry("node-register-minimal.bin", "f2608b63decac97d09e8b976505fb51f0d8431b79bd5214323fdb731b9b794dd"),
            Map.entry("node-deregister.bin", "f9d94e6d1be9fb243583bfbfe6e063e877a55c84e4379f65fb7e9005af91baa3"),
            Map.entry("node-update-status.bin", "cbc9bf6135d6d6688b36ccc065d38b2cbf5de3662242b3cdef61895935322cdf"),
            Map.entry("node-update-capabilities.bin",
                    "8fc258865b87a326b2b63cf108a4b86a59f52266382c50c7bc17c0abeb49cb01"),
            Map.entry("node-heartbeat.bin", "7da00f0eed4149123bb4c65461c42b56ac1de5cd3e46401ebf8dbce4f7188d65"),
            Map.entry("node-heartbeat-plain.bin", "499cf4a1ed012fb0d8fa5df2473fb8b627e218217dca35e221a171bceb2843e5"),
            Map.entry("node-expire.bin", "a42b133cd8578ab193c7020101530e5f78ea0c25056cf79bd5fc648d8ea0c088"),
            Map.entry("node-update-status-job-a.bin",
                    "d86c2f311647a4ef20adbacb4cebd7c8038c6c2e4242ccbc5e8eebc0a0313f1e"),
            Map.entry("node-update-status-job-b.bin",
                    "032b600465b939a500987b98af126f43089301e8b84a2b7135cdee70892b9437"),
            Map.entry("node-snapshot.json", "182245ec349f387d9284bddffdd0bde7924995871f63b72e307f70dbc1a411f0"));

    private final ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();

    @Test
    void decodesARichNodeRegistrationWithoutTheFieldsANodeNoLongerHas() throws Exception {
        NodeCommand.Register register = assertInstanceOf(NodeCommand.Register.class,
                codec.deserialize(fixture("node-register-rich.bin")));

        assertEquals("node-rich", register.name());
        assertEquals(COMMANDED, register.timestamp());
        assertEquals(RICH, register.node(),
                "the host name, the port, the version, and the capabilities in the entry are ignored");
    }

    @Test
    void decodesAMinimalNodeRegistrationWithItsAbsentFieldsAbsent() throws Exception {
        NodeCommand.Register register = assertInstanceOf(NodeCommand.Register.class,
                codec.deserialize(fixture("node-register-minimal.bin")));

        assertEquals("node-minimal", register.name());
        assertEquals(COMMANDED, register.timestamp());
        assertEquals(MINIMAL, register.node());
    }

    @Test
    void decodesTheOtherNodeCommandsWithExactFields() throws Exception {
        assertEquals(new NodeCommand.Deregister("node-rich", COMMANDED),
                codec.deserialize(fixture("node-deregister.bin")));
        assertEquals(new NodeCommand.UpdateStatus("node-rich", NodeStatus.HEALTHY, NodeStatus.HEALTHY,
                COMMANDED), codec.deserialize(fixture("node-update-status.bin")),
                "the removed degraded status decodes as healthy: the node was in contact");
        assertEquals(new NodeCommand.Heartbeat("node-rich", NodeStatus.HEALTHY, CONTACTED, HEARTBEAT_SEQUENCE,
                REGISTRATION_ID), codec.deserialize(fixture("node-heartbeat.bin")));
        assertEquals(new NodeCommand.Heartbeat("node-minimal", null, CONTACTED),
                codec.deserialize(fixture("node-heartbeat-plain.bin")));
        assertEquals(new NodeCommand.Expire("node-rich", CONTACTED, true, COMMANDED),
                codec.deserialize(fixture("node-expire.bin")));
    }

    @Test
    void anEntryHoldingTheRemovedCapabilitiesUpdateIsSkipped() throws Exception {
        assertNull(codec.deserialize(fixture("node-update-capabilities.bin")),
                "Qraft never issued a capabilities update, so an entry that holds one applies nothing");
    }

    @Test
    void decodesTheJobSystemsStatusesAsTheStatusesQraftSets() throws Exception {
        assertEquals(new NodeCommand.UpdateStatus("node-job", NodeStatus.HEALTHY, NodeStatus.HEALTHY,
                COMMANDED), codec.deserialize(fixture("node-update-status-job-a.bin")),
                "the job system's active and idle statuses decode as healthy");
        assertEquals(new NodeCommand.UpdateStatus("node-job", NodeStatus.HEALTHY, NodeStatus.HEALTHY,
                COMMANDED), codec.deserialize(fixture("node-update-status-job-b.bin")),
                "the job system's overloaded and draining statuses decode as healthy too");
    }

    @Test
    void restoresANodeSnapshotWithExactState() throws Exception {
        QraftStateStore store = new QraftStateStore();

        store.restoreSnapshot(fixture("node-snapshot.json"));

        assertEquals(SNAPSHOT_INDEX, store.getLastAppliedIndex());
        assertEquals(Set.of("node-rich", "node-minimal"), store.getNodes().keySet(),
                "the snapshot's nodes are read under the key they had when it was written");
        assertEquals(RICH, store.findNode("node-rich").orElseThrow(),
                "the heartbeat made the registering node healthy");
        assertEquals(MINIMAL, store.findNode("node-minimal").orElseThrow());

        // The snapshot carries the heartbeat sequence: the same sequence is stale, the next one is accepted.
        assertInstanceOf(RaftCommandResult.CasMismatch.class, store.apply(new NodeCommand.Heartbeat(
                "node-rich", null, COMMANDED, HEARTBEAT_SEQUENCE, REGISTRATION_ID)));
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(new NodeCommand.Heartbeat(
                "node-rich", null, COMMANDED, HEARTBEAT_SEQUENCE + 1, REGISTRATION_ID)));
    }

    @Test
    void fixtureBytesRemainImmutable() throws Exception {
        assertEquals(11, SHA_256.size(), "every node fixture has a recorded digest");
        for (var expected : SHA_256.entrySet()) {
            byte[] bytes = fixture(expected.getKey());
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
            assertEquals(expected.getValue(), actual, expected.getKey());
        }
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream input = LegacyNodeFixtureTest.class.getResourceAsStream(FIXTURE_ROOT + name)) {
            assertNotNull(input, "missing immutable legacy fixture " + FIXTURE_ROOT + name);
            return input.readAllBytes();
        }
    }
}

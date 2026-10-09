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

import dev.mars.qraft.common.ClientCapabilities;
import dev.mars.qraft.common.ClientInfo;
import dev.mars.qraft.common.ClientNetworkInfo;
import dev.mars.qraft.common.ClientStatus;
import dev.mars.qraft.common.ClientSystemInfo;
import dev.mars.qraft.raft.RaftCommandResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the legacy node fixtures decode and restore with exact fields, and that their bytes stay unchanged.
 * The fixtures freeze the node commands and a snapshot holding nodes as the code wrote them on 2026-10-09, before
 * the node model was reduced: every node command, the capabilities command, the job system's statuses, and a
 * snapshot with a fully described node.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-09
 * @version 1.0
 */
class LegacyNodeFixtureTest {
    private static final String FIXTURE_ROOT = "/fixtures/node/";
    static final Instant REGISTERED = Instant.parse("2026-10-09T08:00:00Z");
    static final Instant CONTACTED = Instant.parse("2026-10-09T08:00:30Z");
    static final Instant COMMANDED = Instant.parse("2026-10-09T08:01:00Z");
    static final String REGISTRATION_ID = "reg-1";
    static final long HEARTBEAT_SEQUENCE = 7;
    static final long SNAPSHOT_INDEX = 9;
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
    void decodesARichNodeRegistrationWithEveryField() throws Exception {
        ClientCommand.Register register = assertInstanceOf(ClientCommand.Register.class,
                codec.deserialize(fixture("node-register-rich.bin")));

        assertEquals("node-rich", register.clientId());
        assertEquals(COMMANDED, register.timestamp());
        ClientInfo node = register.clientInfo();
        assertRichNode(node);
        assertEquals(ClientStatus.HEALTHY, node.getStatus());
        assertEquals(REGISTERED, node.getRegistrationTime());
        assertEquals(CONTACTED, node.getLastHeartbeat());
    }

    @Test
    void decodesAMinimalNodeRegistrationWithItsAbsentFieldsAbsent() throws Exception {
        ClientCommand.Register register = assertInstanceOf(ClientCommand.Register.class,
                codec.deserialize(fixture("node-register-minimal.bin")));

        assertEquals("node-minimal", register.clientId());
        assertEquals(COMMANDED, register.timestamp());
        assertMinimalNode(register.clientInfo());
        assertEquals(ClientStatus.REGISTERING, register.clientInfo().getStatus());
        assertEquals(REGISTERED, register.clientInfo().getRegistrationTime());
    }

    @Test
    void decodesTheOtherNodeCommandsWithExactFields() throws Exception {
        assertEquals(new ClientCommand.Deregister("node-rich", COMMANDED),
                codec.deserialize(fixture("node-deregister.bin")));
        assertEquals(new ClientCommand.UpdateStatus("node-rich", ClientStatus.HEALTHY, ClientStatus.DEGRADED,
                COMMANDED), codec.deserialize(fixture("node-update-status.bin")));
        assertEquals(new ClientCommand.Heartbeat("node-rich", ClientStatus.HEALTHY, CONTACTED, HEARTBEAT_SEQUENCE,
                REGISTRATION_ID), codec.deserialize(fixture("node-heartbeat.bin")));
        assertEquals(new ClientCommand.Heartbeat("node-minimal", null, CONTACTED),
                codec.deserialize(fixture("node-heartbeat-plain.bin")));
        assertEquals(new ClientCommand.Expire("node-rich", CONTACTED, true, COMMANDED),
                codec.deserialize(fixture("node-expire.bin")));
    }

    @Test
    void decodesACapabilitiesUpdateWithEveryField() throws Exception {
        ClientCommand.UpdateCapabilities update = assertInstanceOf(ClientCommand.UpdateCapabilities.class,
                codec.deserialize(fixture("node-update-capabilities.bin")));

        assertEquals("node-rich", update.clientId());
        assertEquals(COMMANDED, update.timestamp());
        assertRichCapabilities(update.newCapabilities());
    }

    @Test
    void decodesTheJobSystemsStatusesAsTheStatusesQraftSets() throws Exception {
        assertEquals(new ClientCommand.UpdateStatus("node-job", ClientStatus.HEALTHY, ClientStatus.HEALTHY,
                COMMANDED), codec.deserialize(fixture("node-update-status-job-a.bin")),
                "the job system's active and idle statuses decode as healthy");
        assertEquals(new ClientCommand.UpdateStatus("node-job", ClientStatus.DEGRADED, ClientStatus.MAINTENANCE,
                COMMANDED), codec.deserialize(fixture("node-update-status-job-b.bin")),
                "the job system's overloaded and draining statuses decode as degraded and maintenance");
    }

    @Test
    void restoresANodeSnapshotWithExactState() throws Exception {
        QraftStateStore store = new QraftStateStore();

        store.restoreSnapshot(fixture("node-snapshot.json"));

        assertEquals(SNAPSHOT_INDEX, store.getLastAppliedIndex());
        assertEquals(Set.of("node-rich", "node-minimal"), store.getClients().keySet());
        ClientInfo rich = store.findClient("node-rich").orElseThrow();
        assertRichNode(rich);
        assertEquals(ClientStatus.HEALTHY, rich.getStatus(), "the heartbeat made the registering node healthy");
        assertEquals(REGISTERED, rich.getRegistrationTime());
        assertEquals(CONTACTED, rich.getLastHeartbeat());
        ClientInfo minimal = store.findClient("node-minimal").orElseThrow();
        assertMinimalNode(minimal);
        assertEquals(ClientStatus.REGISTERING, minimal.getStatus());
        assertEquals(REGISTERED, minimal.getRegistrationTime());

        // The snapshot carries the heartbeat sequence: the same sequence is stale, the next one is accepted.
        assertInstanceOf(RaftCommandResult.CasMismatch.class, store.apply(new ClientCommand.Heartbeat(
                "node-rich", null, COMMANDED, HEARTBEAT_SEQUENCE, REGISTRATION_ID)));
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(new ClientCommand.Heartbeat(
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

    // ── The values the fixtures were written from ──────────────────────────────────────────────────────

    static ClientInfo richNode() {
        ClientInfo node = new ClientInfo("node-rich", "rich-host", "192.0.2.21", 8501);
        node.setStatus(ClientStatus.HEALTHY);
        node.setRegistrationTime(REGISTERED);
        node.setLastHeartbeat(CONTACTED);
        node.setVersion("3.1.0");
        node.setRegion("eu-west");
        node.setDatacenter("dc-1");
        node.addMetadata("rack", "r7");
        node.addMetadata(ClientInfo.REGISTRATION_ID_METADATA_KEY, REGISTRATION_ID);
        node.setCapabilities(richCapabilities());
        return node;
    }

    static ClientInfo minimalNode() {
        ClientInfo node = new ClientInfo("node-minimal", null, null, 0);
        node.setRegistrationTime(REGISTERED);
        return node;
    }

    static ClientCapabilities richCapabilities() {
        ClientSystemInfo system = new ClientSystemInfo();
        system.setOperatingSystem("Linux");
        system.setArchitecture("amd64");
        system.setJavaVersion("27");
        system.setTotalMemory(17_179_869_184L);
        system.setAvailableMemory(8_589_934_592L);
        system.setTotalDiskSpace(500_000_000_000L);
        system.setAvailableDiskSpace(250_000_000_000L);
        system.setCpuCores(8);
        system.setCpuUsage(0.25);
        system.setLoadAverage(1.5);
        ClientNetworkInfo network = new ClientNetworkInfo();
        network.setPublicIpAddress("203.0.113.21");
        network.setPrivateIpAddress("192.0.2.21");
        network.setNetworkInterfaces(List.of("eth0", "lo"));
        network.setBandwidthCapacity(1_000_000_000L);
        network.setCurrentBandwidthUsage(250_000_000L);
        network.setLatencyMs(2.5);
        network.setPacketLossPercentage(0.01);
        network.setConnectionType("ethernet");
        network.setNatTraversal(true);
        network.setFirewallPorts(List.of(8501, 8502));
        ClientCapabilities capabilities = new ClientCapabilities();
        capabilities.addSupportedService("http");
        capabilities.addSupportedService("grpc");
        capabilities.addAvailableRegion("eu-west");
        capabilities.addAvailableRegion("eu-north");
        capabilities.addCustomCapability("tier", "gold");
        capabilities.addCustomCapability("slots", 4);
        capabilities.addCustomCapability("spot", false);
        capabilities.setSystemInfo(system);
        capabilities.setNetworkInfo(network);
        return capabilities;
    }

    private static void assertRichNode(ClientInfo node) {
        assertEquals("node-rich", node.getClientId());
        assertEquals("rich-host", node.getHostname());
        assertEquals("192.0.2.21", node.getAddress());
        assertEquals(8501, node.getPort());
        assertEquals("3.1.0", node.getVersion());
        assertEquals("eu-west", node.getRegion());
        assertEquals("dc-1", node.getDatacenter());
        assertEquals(Map.of("rack", "r7", ClientInfo.REGISTRATION_ID_METADATA_KEY, REGISTRATION_ID),
                node.getMetadata());
        assertRichCapabilities(node.getCapabilities());
    }

    private static void assertMinimalNode(ClientInfo node) {
        assertEquals("node-minimal", node.getClientId());
        assertNull(node.getHostname());
        assertNull(node.getAddress());
        assertEquals(0, node.getPort());
        assertNull(node.getCapabilities());
        assertNull(node.getLastHeartbeat());
        assertNull(node.getVersion());
        assertNull(node.getRegion());
        assertNull(node.getDatacenter());
        assertEquals(Map.of(), node.getMetadata());
    }

    private static void assertRichCapabilities(ClientCapabilities capabilities) {
        assertNotNull(capabilities, "the node's capabilities");
        assertEquals(Set.of("http", "grpc"), capabilities.getSupportedServices());
        assertEquals(Set.of("eu-west", "eu-north"), capabilities.getAvailableRegions());
        assertEquals(Map.of("tier", "gold", "slots", 4, "spot", false), capabilities.getCustomCapabilities());
        ClientSystemInfo system = capabilities.getSystemInfo();
        assertEquals("Linux", system.getOperatingSystem());
        assertEquals("amd64", system.getArchitecture());
        assertEquals("27", system.getJavaVersion());
        assertEquals(17_179_869_184L, system.getTotalMemory());
        assertEquals(8_589_934_592L, system.getAvailableMemory());
        assertEquals(500_000_000_000L, system.getTotalDiskSpace());
        assertEquals(250_000_000_000L, system.getAvailableDiskSpace());
        assertEquals(8, system.getCpuCores());
        assertEquals(0.25, system.getCpuUsage());
        assertEquals(1.5, system.getLoadAverage());
        ClientNetworkInfo network = capabilities.getNetworkInfo();
        assertEquals("203.0.113.21", network.getPublicIpAddress());
        assertEquals("192.0.2.21", network.getPrivateIpAddress());
        assertEquals(List.of("eth0", "lo"), network.getNetworkInterfaces());
        assertEquals(1_000_000_000L, network.getBandwidthCapacity());
        assertEquals(250_000_000L, network.getCurrentBandwidthUsage());
        assertEquals(2.5, network.getLatencyMs());
        assertEquals(0.01, network.getPacketLossPercentage());
        assertEquals("ethernet", network.getConnectionType());
        assertTrue(network.isNatTraversal());
        assertEquals(List.of(8501, 8502), network.getFirewallPorts());
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream input = LegacyNodeFixtureTest.class.getResourceAsStream(FIXTURE_ROOT + name)) {
            assertNotNull(input, "missing immutable legacy fixture " + FIXTURE_ROOT + name);
            return input.readAllBytes();
        }
    }
}
